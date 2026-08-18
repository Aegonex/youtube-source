package dev.lavalink.youtube.http;

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextRetryCounter;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static dev.lavalink.youtube.http.YoutubeOauth2Handler.OAUTH_INJECT_CONTEXT_ATTRIBUTE;

public class YoutubeHttpContextFilter extends BaseYoutubeHttpContextFilter {
  private static final Logger log = LoggerFactory.getLogger(YoutubeHttpContextFilter.class);

  private static final String ATTRIBUTE_RESET_RETRY = "isResetRetry";
  private static final String ATTRIBUTE_MEDIA_VIA_RELAY = "mediaViaRelay";
  public static final String ATTRIBUTE_USER_AGENT_SPECIFIED = "clientUserAgent";
  public static final String ATTRIBUTE_VISITOR_DATA_SPECIFIED = "clientVisitorData";
  public static final String ATTRIBUTE_CIPHER_REQUEST_SPECIFIED = "remoteCipherRequest";

  private static final HttpContextRetryCounter retryCounter = new HttpContextRetryCounter("yt-token-retry");

  private YoutubeAccessTokenTracker tokenTracker;
  private YoutubeOauth2Handler oauth2Handler;

  private String remoteCipherPass;
  private String remoteCipherUserAgent;
  private String pluginVersion;

  private String innertubeRelayUrl;
  private String innertubeRelayPass;

  // Only API traffic is worth relaying; media is both unblocked and far too
  // heavy to send on a detour.
  private static final Set<String> RELAYED_HOSTS = new HashSet<>(Arrays.asList(
      "youtubei.googleapis.com",
      "www.youtube.com",
      "m.youtube.com",
      "music.youtube.com",
      "www.youtube-nocookie.com"
  ));

  public void setTokenTracker(@NotNull YoutubeAccessTokenTracker tokenTracker) {
    this.tokenTracker = tokenTracker;
  }

  @Nullable
  public String getVisitorData() {
    return tokenTracker.getVisitorId();
  }

  public void setOauth2Handler(@NotNull YoutubeOauth2Handler oauth2Handler) {
    this.oauth2Handler = oauth2Handler;
  }

  /**
   * @param url base url of an innertube relay, or null to call YouTube directly.
   * @param pass shared secret the relay expects, if it requires one.
   */
  public void setInnertubeRelay(@Nullable String url, @Nullable String pass) {
    this.innertubeRelayUrl = url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    this.innertubeRelayPass = pass;
  }

  /**
   * Sends the request to the relay instead of YouTube, preserving path and query
   * so the relay can replay it verbatim.
   *
   * <p>This runs last, after every other rule in {@link #onRequest}, because
   * those rules match on the original YouTube host and would stop firing the
   * moment the URI is rewritten.
   */
  /**
   * Sends a media request back out through the relay after YouTube refused it.
   *
   * Stream URLs are handed out to whoever asked for them, and answering 403 to
   * a different address is a decision YouTube makes per video. Fetching the
   * audio directly is still right for most tracks, so this only kicks in once a
   * direct attempt has actually been refused.
   */
  private void applyMediaRelay(HttpUriRequest request) {
    if (DataFormatTools.isNullOrEmpty(innertubeRelayUrl) || !(request instanceof HttpRequestBase)) {
      return;
    }

    URI uri = request.getURI();

    if (uri.getHost() == null || !uri.getHost().contains("googlevideo")) {
      return;
    }

    String relayed = innertubeRelayUrl + "/m?url=" + urlEncode(uri.toString());
    log.debug("Retrying refused media request via {}", innertubeRelayUrl);
    ((HttpRequestBase) request).setURI(URI.create(relayed));

    if (!DataFormatTools.isNullOrEmpty(innertubeRelayPass)) {
      request.setHeader("X-Relay-Auth", innertubeRelayPass);
    }
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void applyInnertubeRelay(HttpUriRequest request) {
    if (DataFormatTools.isNullOrEmpty(innertubeRelayUrl) || !(request instanceof HttpRequestBase)) {
      return;
    }

    URI uri = request.getURI();
    String host = uri.getHost();

    if (host == null || !RELAYED_HOSTS.contains(host)) {
      return;
    }

    StringBuilder relayed = new StringBuilder(innertubeRelayUrl).append("/p/").append(host).append(uri.getRawPath());

    if (uri.getRawQuery() != null) {
      relayed.append('?').append(uri.getRawQuery());
    }

    log.debug("Relaying {} via {}", uri, innertubeRelayUrl);
    ((HttpRequestBase) request).setURI(URI.create(relayed.toString()));

    if (!DataFormatTools.isNullOrEmpty(innertubeRelayPass)) {
      request.setHeader("X-Relay-Auth", innertubeRelayPass);
    }
  }

  public void setCipherConfig(@Nullable String remotePass,
                              @Nullable String userAgent,
                              @NotNull String pluginVersion) {
    this.remoteCipherPass = remotePass;
    this.remoteCipherUserAgent = userAgent;
    this.pluginVersion = pluginVersion;
  }


  @Override
  public void onContextOpen(HttpClientContext context) {
    CookieStore cookieStore = context.getCookieStore();

    if (cookieStore == null) {
      cookieStore = new BasicCookieStore();
      context.setCookieStore(cookieStore);
    }

    // Reset cookies for each sequence of requests.
    cookieStore.clear();
  }

  @Override
  public void onRequest(HttpClientContext context,
                        HttpUriRequest request,
                        boolean isRepetition) {
    if (!isRepetition) {
      context.removeAttribute(ATTRIBUTE_RESET_RETRY);
      // Contexts are pooled per thread and outlive a single track, so the
      // "already retried" marker has to be dropped when a fresh request starts.
      // Leaving it set silently disables the media fallback for every later
      // request on that thread.
      context.removeAttribute(ATTRIBUTE_MEDIA_VIA_RELAY);
    }

    if (context.getAttribute(ATTRIBUTE_MEDIA_VIA_RELAY) == Boolean.TRUE) {
      // Downgrade rather than clear, so a relayed request that is also refused
      // ends there instead of looping.
      context.setAttribute(ATTRIBUTE_MEDIA_VIA_RELAY, false);
      applyMediaRelay(request);
      return;
    }

    retryCounter.handleUpdate(context, isRepetition);

    if (tokenTracker.isTokenFetchContext(context)) {
      // Used for fetching visitor id, let's not recurse.
      return;
    }

    if (oauth2Handler.isOauthFetchContext(context)) {
      return;
    }

    String userAgent = context.getAttribute(ATTRIBUTE_USER_AGENT_SPECIFIED, String.class);

    if (isRemoteCipherRequest(context)) {
      if (!DataFormatTools.isNullOrEmpty(remoteCipherPass)) {
        request.addHeader("Authorization", remoteCipherPass);
      }

      if (!DataFormatTools.isNullOrEmpty(remoteCipherUserAgent)) {
        request.addHeader("User-Agent", remoteCipherUserAgent);
      }

      request.addHeader("Plugin-Version", pluginVersion);
    } else if (!request.getURI().getHost().contains("googlevideo")) {
      if (userAgent != null) {
        request.setHeader("User-Agent", userAgent);

        String visitorData = context.getAttribute(ATTRIBUTE_VISITOR_DATA_SPECIFIED, String.class);
        request.setHeader("X-Goog-Visitor-Id", visitorData != null ? visitorData : tokenTracker.getVisitorId());

        context.removeAttribute(ATTRIBUTE_VISITOR_DATA_SPECIFIED);
        context.removeAttribute(ATTRIBUTE_USER_AGENT_SPECIFIED);
      }

      // fix: getAttribute is needed over removeAttribute as this renders subsequent requests where oauth is
      //      required useless, because the attribute has already been removed.
      boolean isRequestFromOauthedClient = context.getAttribute(Client.OAUTH_CLIENT_ATTRIBUTE) == Boolean.TRUE;

      if (isRequestFromOauthedClient && Client.PLAYER_URL.equals(request.getURI().toString())) {
        // Look at the userdata for any provided oauth-token
        String oauthToken = context.getAttribute(OAUTH_INJECT_CONTEXT_ATTRIBUTE, String.class);
        // only apply the token to /player requests.
        if (oauthToken != null && !oauthToken.isEmpty()) {
          oauth2Handler.applyToken(request, oauthToken);
        } else {
          oauth2Handler.applyToken(request);
        }

        // complements above fix, ensure we consume this attribute when we use it to ensure it doesn't leak
        // over into requests from non-oauth clients. This might be a dirty fix because this is assuming the
        // context this attribute is attached, always hits the player endpoint once. May need to revise this
        // in the future.
        context.removeAttribute(Client.OAUTH_CLIENT_ATTRIBUTE);
      }
    }

    applyInnertubeRelay(request);

//    try {
//      URI uri = new URIBuilder(request.getURI())
//          .setParameter("key", YoutubeConstants.INNERTUBE_ANDROID_API_KEY)
//          .build();
//
//      if (request instanceof HttpRequestBase) {
//        ((HttpRequestBase) request).setURI(uri);
//      } else {
//        throw new IllegalStateException("Cannot update request URI.");
//      }
//    } catch (URISyntaxException e) {
//      throw new RuntimeException(e);
//    }
  }

  @Override
  public boolean onRequestResponse(HttpClientContext context,
                                   HttpUriRequest request,
                                   HttpResponse response) {
    // A refused media URL is worth exactly one more attempt, from the address
    // that obtained it. Anything else, including a second refusal, is final.
    if (!DataFormatTools.isNullOrEmpty(innertubeRelayUrl)
        && response.getStatusLine().getStatusCode() == 403
        && request.getURI().getHost() != null
        && request.getURI().getHost().contains("googlevideo")
        && context.getAttribute(ATTRIBUTE_MEDIA_VIA_RELAY) == null) {
      context.setAttribute(ATTRIBUTE_MEDIA_VIA_RELAY, true);
      return true;
    }

    return false;
  }

  @Override
  public boolean onRequestException(HttpClientContext context,
                                    HttpUriRequest request,
                                    Throwable error) {
    // Some media edges are simply not routable from here, which surfaces as a
    // socket error rather than a status code. The relay reaches them, so give it
    // the same single retry a refusal gets.
    if (!DataFormatTools.isNullOrEmpty(innertubeRelayUrl)
        && error instanceof SocketException
        && request.getURI().getHost() != null
        && request.getURI().getHost().contains("googlevideo")
        && context.getAttribute(ATTRIBUTE_MEDIA_VIA_RELAY) == null) {
      context.setAttribute(ATTRIBUTE_MEDIA_VIA_RELAY, true);
      return true;
    }

    // Always retry once in case of connection reset exception.
    if (HttpClientTools.isConnectionResetException(error)) {
      if (context.getAttribute(ATTRIBUTE_RESET_RETRY) == null) {
        context.setAttribute(ATTRIBUTE_RESET_RETRY, true);
        return true;
      }
    }

    return false;
  }

  private boolean isRemoteCipherRequest(HttpClientContext context) {
    return context.removeAttribute(ATTRIBUTE_CIPHER_REQUEST_SPECIFIED) == Boolean.TRUE;
  }
}
