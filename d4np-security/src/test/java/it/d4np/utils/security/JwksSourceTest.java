package it.d4np.utils.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The JWKS trust posture RFC-0005 pinned to close risk-register **R-06**.
 *
 * <p>R-06's finding was that ADR-003 specified caching and rate-limited refresh and said nothing
 * about <em>where the key document comes from</em>. The attack is total: influence the URL and
 * every subsequent token verifies against the attacker's key. These assertions are about
 * construction, which is where three of the four clauses live — and where they belong, because a
 * URL checked per request is a URL that can change per request.
 */
class JwksSourceTest {

  private static final Set<String> ALLOWED = Set.of("https://idp.example.com");

  /** The shared RSA public key, as a one-entry JWKS document. */
  private static String keySetJson() {
    return new com.nimbusds.jose.jwk.JWKSet(
            new com.nimbusds.jose.jwk.RSAKey.Builder(JwtFixtures.publicKey()).keyID("k1").build())
        .toString();
  }

  @Nested
  @DisplayName("clause 1: the origin allowlist")
  class OriginAllowlist {

    @Test
    @DisplayName("accepts a URL whose origin is allowed")
    void acceptsAnAllowedOrigin() {
      assertThatCode(
              () -> JwksSource.at("https://idp.example.com/.well-known/jwks.json", ALLOWED).build())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a URL whose origin is not allowed, which is the SSRF answer")
    void refusesAnUnlistedOrigin() {
      assertThatThrownBy(() -> JwksSource.at("https://evil.example.com/jwks.json", ALLOWED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("https://evil.example.com")
          .hasMessageContaining("not on the allowlist");
    }

    @Test
    @DisplayName("distinguishes ports, so a permitted host on another port is still refused")
    void distinguishesPorts() {
      assertThatThrownBy(() -> JwksSource.at("https://idp.example.com:8443/jwks.json", ALLOWED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not on the allowlist");
    }

    @Test
    @DisplayName("the refusal names the rejected origin and never the allowlist")
    void namesTheRejectedOriginAndNeverTheAllowlist() {
      // PageRequest's reasoning, ported: the rejected value is the host's own and they need it to
      // fix the configuration; the allowlist is internal configuration and naming it in an
      // exception
      // that may be logged widens what a misconfiguration discloses.
      assertThatThrownBy(
              () ->
                  JwksSource.at(
                      "https://evil.example.com/jwks.json",
                      Set.of("https://idp-a.internal", "https://idp-b.internal")))
          .hasMessageNotContaining("idp-a.internal")
          .hasMessageNotContaining("idp-b.internal");
    }

    @Test
    @DisplayName("refuses an empty allowlist rather than treating it as 'any'")
    void refusesAnEmptyAllowlist() {
      assertThatThrownBy(() -> JwksSource.at("https://idp.example.com/jwks.json", Set.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be empty");
    }
  }

  @Nested
  @DisplayName("clause 2: HTTPS only, refused rather than upgraded")
  class TransportSecurity {

    @Test
    @DisplayName("refuses http, and says it is refused rather than upgraded")
    void refusesPlainHttp() {
      assertThatThrownBy(
              () ->
                  JwksSource.at(
                      "http://idp.example.com/jwks.json", Set.of("https://idp.example.com")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be https")
          .hasMessageContaining("refused rather than upgraded");
    }

    @Test
    @DisplayName("refuses a relative or schemeless url")
    void refusesARelativeUrl() {
      assertThatThrownBy(() -> JwksSource.at("/jwks.json", ALLOWED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be absolute");
    }
  }

  @Nested
  @DisplayName("clause 4: the fetch is bounded")
  class Bounds {

    @Test
    @DisplayName("refuses a non-positive timeout")
    void refusesANonPositiveTimeout() {
      assertThatThrownBy(
              () ->
                  JwksSource.at("https://idp.example.com/jwks.json", ALLOWED)
                      .timeout(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("timeout must be positive");
    }

    @Test
    @DisplayName("refuses a zero cache TTL, which would refetch on every token")
    void refusesAZeroCacheTtl() {
      // ADR-003's rate-limited refresh, enforced: a zero TTL turns every verification into a fetch,
      // which is the JWKS refresh storm the threat model records against B3.
      assertThatThrownBy(
              () ->
                  JwksSource.at("https://idp.example.com/jwks.json", ALLOWED)
                      .cacheTtl(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("refresh storm");
    }

    @Test
    @DisplayName("refuses a non-positive response bound")
    void refusesANonPositiveResponseBound() {
      assertThatThrownBy(
              () -> JwksSource.at("https://idp.example.com/jwks.json", ALLOWED).maxResponseBytes(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be positive");
    }
  }

  @Nested
  @DisplayName("clause 3: redirects")
  class Redirects {

    @Test
    @DisplayName(
        "the JDK's own default is already NEVER, which is why the setting is not the control")
    void theJdkDefaultIsAlreadyNever() {
      // Measured, and it corrected this class's own Javadoc: the first draft asserted the JDK
      // defaults to NORMAL and therefore that followRedirects(NEVER) was a deliberate departure.
      // It is not -- the default is already NEVER on 17 and 21 alike.
      //
      // That matters, because a default that happens to be right is not a control: it says nothing
      // about a CALLER-SUPPLIED client, whose policy this library does not choose. So the clause is
      // enforced by refusing a 3xx on arrival, which holds for every configuration -- asserted by
      // the test below rather than by this one.
      assertThat(HttpClient.newHttpClient().followRedirects())
          .as("so the explicit call documents intent rather than changing behaviour")
          .isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    @DisplayName("a 3xx is refused on arrival, which is the clause that holds for any client")
    void refusesARedirectResponse() {
      // The real enforcement: even a caller-supplied client that DOES follow redirects cannot make
      // this source chase one, because a 3xx reaching the interpretation is refused outright.
      JwksSource source = source(new JwksSource.Response(302, ""));

      assertThatThrownBy(() -> source.rsaKey("k1"))
          .isInstanceOf(JwtVerificationException.class)
          .hasMessageContaining("redirected, which is not followed");
    }

    @Test
    @DisplayName("a non-200 is refused, and the endpoint's body never reaches the message")
    void refusesANon200AndNeverQuotesItsBody() {
      // C-01: an endpoint's error page is arbitrary text. The status code is ours to report because
      // we made the request; the body is not.
      JwksSource source = source(new JwksSource.Response(500, "hunter2 leaked here"));

      assertThatThrownBy(() -> source.rsaKey("k1"))
          .isInstanceOf(JwtVerificationException.class)
          .hasMessageContaining("answered 500")
          .hasMessageNotContaining("hunter2");
    }

    @Test
    @DisplayName("an oversized document is refused rather than parsed")
    void refusesAnOversizedDocument() {
      JwksSource source =
          JwksSource.at("https://idp.example.com/jwks.json", ALLOWED)
              .maxResponseBytes(16)
              .transport(uri -> new JwksSource.Response(200, keySetJson()))
              .build();

      assertThatThrownBy(() -> source.rsaKey("k1"))
          .isInstanceOf(JwtVerificationException.class)
          .hasMessageContaining("exceeds 16 bytes");
    }

    @Test
    @DisplayName("a document that is not a key set is refused")
    void refusesAMalformedDocument() {
      JwksSource source = source(new JwksSource.Response(200, "{\"not\":\"a key set\""));

      assertThatThrownBy(() -> source.rsaKey("k1"))
          .isInstanceOf(JwtVerificationException.class)
          .hasMessageContaining("not a key set");
    }

    private static JwksSource source(JwksSource.Response canned) {
      return JwksSource.at("https://idp.example.com/jwks.json", ALLOWED)
          .transport(uri -> canned)
          .build();
    }
  }

  @Nested
  @DisplayName("reading and caching a key set")
  class Reading {

    @Test
    @DisplayName("resolves a key by kid, and reports an unknown kid as empty rather than an error")
    void resolvesByKid() {
      JwksSource source =
          JwksSource.at("https://idp.example.com/jwks.json", ALLOWED)
              .transport(uri -> new JwksSource.Response(200, keySetJson()))
              .build();

      assertThat(source.rsaKey("k1")).isPresent();
      assertThat(source.rsaKey("unknown")).isEmpty();
    }

    @Test
    @DisplayName("fetches once within the TTL, which is ADR-003's rate-limited refresh")
    void cachesWithinTheTtl() {
      // A zero TTL would refetch on every token, which is the JWKS refresh storm the threat model
      // records against B3 -- and which the builder refuses outright.
      AtomicInteger fetches = new AtomicInteger();
      JwksSource source =
          JwksSource.at("https://idp.example.com/jwks.json", ALLOWED)
              .cacheTtl(Duration.ofMinutes(5))
              .transport(
                  uri -> {
                    fetches.incrementAndGet();
                    return new JwksSource.Response(200, keySetJson());
                  })
              .build();

      source.rsaKey("k1");
      source.rsaKey("k1");
      source.rsaKey("k1");

      assertThat(fetches).hasValue(1);
    }
  }

  @Nested
  @DisplayName("what it renders")
  class Rendering {

    @Test
    @DisplayName("names the endpoint the host configured, which holds no secret")
    void rendersTheEndpoint() {
      JwksSource source =
          JwksSource.at("https://idp.example.com/.well-known/jwks.json", ALLOWED).build();

      assertThat(source.toString()).contains("idp.example.com").contains("cacheTtl");
      assertThat(source.uri().getScheme()).isEqualTo("https");
    }
  }
}
