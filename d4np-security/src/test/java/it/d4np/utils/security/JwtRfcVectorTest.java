package it.d4np.utils.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The RFC 7515 / 7519 vectors spec §8 and ADR-003 both name as deliverables.
 *
 * <p><strong>The trick is that the standard vector is expired</strong> — RFC 7515's example token
 * carries {@code exp: 1300819380}, which is March 2011 — so a hardened verifier cannot simply
 * accept it. That turns out to be useful rather than awkward: because the signature is checked
 * <em>before</em> any claim (spec §4), the <em>reason code</em> the verifier reports is itself the
 * proof that the HMAC computation matched the RFC's bytes. A wrong MAC would report {@code
 * SIGNATURE_INVALID} and never reach the expiry check.
 */
class JwtRfcVectorTest {

  /**
   * RFC 7515 Appendix A.1.1 — the complete HMAC SHA-256 example.
   *
   * <p>Header {@code {"typ":"JWT",\r\n "alg":"HS256"}}, payload {@code {"iss":"joe",\r\n
   * "exp":1300819380,\r\n "http://example.com/is_root":true}}.
   */
  private static final String RFC_7515_A1_JWS =
      "eyJ0eXAiOiJKV1QiLA0KICJhbGciOiJIUzI1NiJ9"
          + ".eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ"
          + ".dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

  /** The RFC's key, as the base64url-encoded octets of its example JWK. */
  private static final String RFC_7515_A1_KEY =
      "AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow";

  @Test
  @DisplayName("RFC 7515 A.1: the vector's signature verifies, proven by which check rejects it")
  void rfc7515AppendixA1SignatureVerifies() {
    // The profile matches the vector's issuer so the run gets past iss, and the token is 2011-old
    // so
    // exp is what stops it. EXPIRED therefore means: parsed, algorithm matched, typ matched, and
    // the
    // HMAC over the RFC's exact bytes produced the RFC's exact signature. SIGNATURE_INVALID would
    // mean our MAC disagrees with the standard.
    JwtProfile profile = JwtProfile.requiring("joe", "any-audience").build();
    JwtVerifier verifier = JwtVerifier.hs256(JwtFixtures.base64Url(RFC_7515_A1_KEY), profile);

    assertThatThrownBy(() -> verifier.verify(RFC_7515_A1_JWS))
        .isInstanceOf(JwtVerificationException.class)
        .extracting(thrown -> ((JwtVerificationException) thrown).reason())
        .as("EXPIRED means every check before it passed, signature included")
        .isEqualTo(JwtVerificationException.Reason.EXPIRED);
  }

  @Test
  @DisplayName("and a one-bit change to the vector's signature is caught")
  void aTamperedVectorIsCaught() {
    // The companion assertion: without it, the test above would also pass if the verifier were
    // checking nothing and reporting EXPIRED from the claims alone.
    String tampered =
        RFC_7515_A1_JWS.substring(0, RFC_7515_A1_JWS.length() - 1)
            + (RFC_7515_A1_JWS.endsWith("k") ? "j" : "k");
    JwtProfile profile = JwtProfile.requiring("joe", "any-audience").build();
    JwtVerifier verifier = JwtVerifier.hs256(JwtFixtures.base64Url(RFC_7515_A1_KEY), profile);

    assertThatThrownBy(() -> verifier.verify(tampered))
        .isInstanceOf(JwtVerificationException.class)
        .extracting(thrown -> ((JwtVerificationException) thrown).reason())
        .as("the signature is checked, and it is checked BEFORE the expiry")
        .isEqualTo(JwtVerificationException.Reason.SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("RFC 7519 §3: a token this library issues is a three-segment compact serialization")
  void producesCompactSerialization() {
    JwtTokenProvider tokens = JwtTokenProvider.hs256(JwtFixtures.SECRET, JwtFixtures.profile());

    String token = tokens.sign(JwtClaims.forSigning("u-1024"), Duration.ofMinutes(15));

    assertThat(token.chars().filter(c -> c == '.').count())
        .as("header.payload.signature")
        .isEqualTo(2);
    assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
  }
}
