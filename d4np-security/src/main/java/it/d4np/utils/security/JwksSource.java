package it.d4np.utils.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A remote JWKS endpoint, under the trust posture RFC-0005 §FR-11 pins (risk-register **R-06**).
 *
 * <p>ADR-003 chose Nimbus and pinned caching and rate-limited refresh. It said nothing about
 * <em>where the key document comes from or whether the transport is trustworthy</em>, which the
 * audit recorded as R-06 and routed to RFC-0005. The attack is concrete and total: if the JWKS URL
 * can be influenced — SSRF, config injection, DNS — an attacker serves their own key document and
 * <strong>every subsequent token verifies</strong>. A hardened algorithm profile is irrelevant
 * against a verifier holding the attacker's key.
 *
 * <h2>The four clauses, and which one is easy to omit</h2>
 *
 * <ol>
 *   <li><strong>The URL is fixed at construction and its origin must be on an allowlist.</strong>
 *       There is no per-request or per-token URL, and in particular the {@code jku} and {@code x5u}
 *       header claims — attacker-controlled by definition, and warned about by RFC 8725 — are never
 *       consulted. The set of reachable origins is decided by the host at wiring time.
 *   <li><strong>HTTPS only, refused rather than upgraded.</strong> An {@code http://} URL is an
 *       {@link IllegalArgumentException} at construction; silently promoting it would hide a
 *       misconfiguration that matters.
 *   <li><strong>Redirects are not followed.</strong> <em>This is the clause an implementer who
 *       satisfied the first two would omit</em>, and it undoes them both: a permitted origin that
 *       answers {@code 302} to an attacker origin defeats the allowlist entirely.
 *       <p><strong>The enforcement is the explicit 3xx refusal in the fetch, not the client
 *       setting</strong>, and the distinction was measured rather than assumed. {@link
 *       HttpClient}'s own default is already {@link HttpClient.Redirect#NEVER} — on both JDK 17 and
 *       21 — so the {@code followRedirects(NEVER)} call below documents intent and changes nothing.
 *       What it cannot do is speak for a <em>caller-supplied</em> client, whose policy this library
 *       does not control. So a {@code 3xx} is refused on arrival regardless of who built the
 *       client, which is the only version of this clause that holds for every configuration.
 *   <li><strong>The fetch is bounded</strong> in connect time, read time and response size. The
 *       rate-limited refresh ADR-003 pins bounds how <em>often</em> the endpoint is called; this
 *       bounds what one call can cost, because a permitted origin that is slow or enormous is a
 *       denial of service against the verifier.
 * </ol>
 *
 * <p><strong>Certificate pinning is deliberately not offered</strong>, and RFC-0005 records the
 * refusal rather than leaving it as an omission: it is the strongest control available and it turns
 * an identity provider's routine certificate rotation into an outage in a system the host does not
 * operate. A host that wants it supplies its own {@link javax.net.ssl.SSLContext} through {@link
 * Builder#httpClient(HttpClient)}.
 *
 * <p><strong>Thread safety.</strong> Safe to share. The cache is a single {@link AtomicReference}
 * holding an immutable snapshot, so a concurrent refresh at worst fetches twice and never publishes
 * a half-built key set.
 */
public final class JwksSource {

  /** A JWKS document is a few kilobytes; a megabyte is already pathological. */
  private static final int DEFAULT_MAX_RESPONSE_BYTES = 512 * 1024;

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  /** ADR-003's rate-limited refresh: how long a fetched key set is reused before refetching. */
  private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

  private final URI uri;
  private final HttpClient http;
  private final Duration timeout;
  private final Duration cacheTtl;
  private final int maxResponseBytes;
  private final AtomicReference<Cached> cache = new AtomicReference<>();
  private final Transport transport;

  private JwksSource(Builder builder) {
    this.uri = builder.uri;
    this.timeout = builder.timeout;
    this.cacheTtl = builder.cacheTtl;
    this.maxResponseBytes = builder.maxResponseBytes;
    this.http =
        builder.http != null
            ? builder.http
            : HttpClient.newBuilder()
                .connectTimeout(builder.timeout)
                // Clause 3, stated explicitly although it is already the JDK's default (measured on
                // 17 and 21). A default that happens to be right is not a control; the control is
                // the 3xx refusal in fetch(), which also covers a caller-supplied client.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    this.transport = builder.transport != null ? builder.transport : this::httpGet;
  }

  /**
   * Starts a JWKS source for a URL whose origin must appear in {@code allowedOrigins}.
   *
   * @param url the JWKS endpoint; must be {@code https} and its origin must be allowed
   * @param allowedOrigins the permitted origins, each as {@code https://host} or {@code
   *     https://host:port}; must not be empty
   * @return a builder
   * @throws IllegalArgumentException if the URL is not {@code https}, is not absolute, or its
   *     origin is not allowed; or if {@code allowedOrigins} is empty
   */
  public static Builder at(String url, Set<String> allowedOrigins) {
    return new Builder(url, allowedOrigins);
  }

  /**
   * The endpoint this source reads, which is fixed for its lifetime.
   *
   * @return the URL
   */
  public URI uri() {
    return uri;
  }

  /**
   * Resolves the RSA public key with the given key id, fetching and caching as needed.
   *
   * @param keyId the {@code kid} from a token's header
   * @return the key, or empty if the key set does not contain one under that id
   * @throws JwtVerificationException if the endpoint could not be read or its document is not a
   *     JWKS
   */
  Optional<RSAPublicKey> rsaKey(String keyId) {
    JWKSet keys = currentKeys();
    JWK jwk = keys.getKeyByKeyId(keyId);
    if (jwk == null || !KeyType.RSA.equals(jwk.getKeyType())) {
      return Optional.empty();
    }
    try {
      return Optional.of(jwk.toRSAKey().toRSAPublicKey());
    } catch (com.nimbusds.jose.JOSEException malformed) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS entry is not a usable RSA key",
          malformed);
    }
  }

  private JWKSet currentKeys() {
    Cached snapshot = cache.get();
    if (snapshot != null && snapshot.freshUntil().isAfter(Instant.now())) {
      return snapshot.keys();
    }
    JWKSet fetched = fetch();
    cache.set(new Cached(fetched, Instant.now().plus(cacheTtl)));
    return fetched;
  }

  private JWKSet fetch() {
    return interpret(transport.get(uri));
  }

  /**
   * Turns one endpoint response into a key set, or refuses it.
   *
   * <p>Package-private and separated from the transport so the three refusals below are testable
   * without standing up a TLS endpoint — the seam this project already uses for {@code
   * System.Logger} (ADR-0014), applied to HTTP.
   *
   * @param response what the endpoint said
   * @return the parsed key set
   */
  JWKSet interpret(Response response) {
    // Clause 3's real enforcement. A 3xx reaching here means the client did not chase it, and this
    // refuses to chase it either -- which is what makes the clause hold for a CALLER-SUPPLIED
    // client
    // whose redirect policy this library does not choose.
    if (response.statusCode() / 100 == 3) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS endpoint redirected, which is not followed");
    }
    if (response.statusCode() != 200) {
      // The status code is ours to report -- we made the request -- but the body never is: an
      // endpoint's error page is arbitrary text (C-01).
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS endpoint answered " + response.statusCode());
    }
    // Clause 4. This bounds what is PARSED rather than what is transferred, and is stated as such
    // rather than implied to be more: a streaming handler that aborts mid-transfer is the stronger
    // version and is left to the item that has a reason to need it.
    if (response.body().length() > maxResponseBytes) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS document exceeds " + maxResponseBytes + " bytes");
    }
    try {
      return JWKSet.parse(response.body());
    } catch (java.text.ParseException malformed) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS document is not a key set",
          malformed);
    }
  }

  /** One endpoint response, reduced to the two things the decisions above depend on. */
  record Response(int statusCode, String body) {}

  /** How a {@link JwksSource} reaches its endpoint; the seam that makes the refusals testable. */
  @FunctionalInterface
  interface Transport {
    Response get(URI uri);
  }

  private Response httpGet(URI target) {
    HttpRequest request =
        HttpRequest.newBuilder(target)
            .GET()
            .timeout(timeout)
            .header("Accept", "application/json")
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (IOException unreachable) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS endpoint could not be read",
          unreachable);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the JWKS fetch was interrupted",
          interrupted);
    }
  }

  /**
   * Renders the endpoint, which the host configured and which contains no secret.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "JwksSource[uri=" + uri + ", cacheTtl=" + cacheTtl + "]";
  }

  /** One fetched key set and the moment it stops being reused. */
  private record Cached(JWKSet keys, Instant freshUntil) {}

  /** Builder for a {@link JwksSource}, which validates the URL before anything else. */
  public static final class Builder {

    private final URI uri;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration cacheTtl = DEFAULT_CACHE_TTL;
    private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private @it.d4np.utils.Nullable HttpClient http;
    private @it.d4np.utils.Nullable Transport transport;

    private Builder(String url, Set<String> allowedOrigins) {
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(allowedOrigins, "allowedOrigins");
      if (allowedOrigins.isEmpty()) {
        // An empty allowlist would permit nothing, which is safe -- and it is almost always a
        // caller who meant "any". Refusing says so rather than failing later with a puzzling
        // origin rejection.
        throw new IllegalArgumentException(
            "allowedOrigins must not be empty; name the origins this verifier may read keys from");
      }
      URI parsed = URI.create(url);
      if (!parsed.isAbsolute() || parsed.getHost() == null) {
        throw new IllegalArgumentException("the JWKS url must be absolute");
      }
      if (!"https".equals(lower(parsed.getScheme()))) {
        // Clause 2: refused, never upgraded.
        throw new IllegalArgumentException(
            "the JWKS url must be https; "
                + parsed.getScheme()
                + " is refused rather than upgraded");
      }
      String origin = originOf(parsed);
      if (!allowedOrigins.contains(origin)) {
        // The rejected origin is named because the host supplied it and needs to fix it; the
        // allowlist is NOT, on the same reasoning PageRequest's whitelist message follows -- it is
        // internal configuration, and naming it in an exception that may be logged widens what a
        // misconfiguration discloses.
        throw new IllegalArgumentException(
            "the JWKS origin " + origin + " is not on the allowlist");
      }
      this.uri = parsed;
    }

    private static String lower(@it.d4np.utils.Nullable String value) {
      return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String originOf(URI uri) {
      String host = lower(uri.getHost());
      return uri.getPort() == -1 ? "https://" + host : "https://" + host + ":" + uri.getPort();
    }

    /**
     * Overrides the connect and read timeout.
     *
     * @param timeout must be positive
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      this.timeout = Objects.requireNonNull(timeout, "timeout");
      if (timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("timeout must be positive; was " + timeout);
      }
      return this;
    }

    /**
     * Overrides how long a fetched key set is reused before refetching.
     *
     * @param cacheTtl must be positive; this is ADR-003's rate-limited refresh
     * @return this builder
     */
    public Builder cacheTtl(Duration cacheTtl) {
      this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
      if (cacheTtl.isNegative() || cacheTtl.isZero()) {
        throw new IllegalArgumentException(
            "cacheTtl must be positive; a zero TTL refetches on every token, which is the refresh "
                + "storm ADR-003's rate limiting exists to prevent");
      }
      return this;
    }

    /**
     * Overrides the maximum JWKS document size accepted.
     *
     * @param maxResponseBytes must be positive
     * @return this builder
     */
    public Builder maxResponseBytes(int maxResponseBytes) {
      if (maxResponseBytes <= 0) {
        throw new IllegalArgumentException("maxResponseBytes must be positive");
      }
      this.maxResponseBytes = maxResponseBytes;
      return this;
    }

    /**
     * Supplies the HTTP client, which is how a host adds certificate pinning or a private trust
     * store.
     *
     * <p><strong>A client supplied here must be configured with {@link
     * HttpClient.Redirect#NEVER}</strong>; this library cannot check it, and clause 3 above is the
     * reason it matters.
     *
     * @param http the client
     * @return this builder
     */
    public Builder httpClient(HttpClient http) {
      this.http = Objects.requireNonNull(http, "http");
      return this;
    }

    /**
     * Replaces the HTTP transport, for tests that exercise the refusals without a TLS endpoint.
     *
     * @param transport what to call instead of the HTTP client
     * @return this builder
     */
    Builder transport(Transport transport) {
      this.transport = Objects.requireNonNull(transport, "transport");
      return this;
    }

    /**
     * Builds the source.
     *
     * @return the configured source
     */
    public JwksSource build() {
      return new JwksSource(this);
    }
  }
}
