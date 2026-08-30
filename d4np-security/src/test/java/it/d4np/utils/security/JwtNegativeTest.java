package it.d4np.utils.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ADR-003's negative tests, which spec §8 and the roadmap both make first-class deliverables.
 *
 * <p>These are the reason Nimbus was chosen at all: ADR-003's Context says <em>"the implementation
 * IS the security posture — JWT libraries differ exactly in the failure modes that matter"</em>. A
 * suite that only proves the happy path proves nothing about that choice.
 *
 * <p>Every token below is <strong>forged the way an attacker would forge it</strong>, using Nimbus
 * directly rather than through this library's API, because a test that can only build tokens
 * through the hardened path cannot build the tokens the hardening exists to reject.
 */
class JwtNegativeTest {

  private final JwtProfile profile = JwtFixtures.profile();

  private static JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .subject("u-1024")
        .issuer(JwtFixtures.ISSUER)
        .audience(List.of(JwtFixtures.AUDIENCE))
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))));
  }

  private static String signedWithHmac(JWSHeader header, JWTClaimsSet claims, byte[] secret)
      throws Exception {
    SignedJWT jwt = new SignedJWT(header, claims);
    jwt.sign(new MACSigner(secret));
    return jwt.serialize();
  }

  private static JWSHeader hs256Header() {
    return new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build();
  }

  @Nested
  @DisplayName("algorithm attacks, which are the ones ADR-003 chose Nimbus for")
  class AlgorithmAttacks {

    @Test
    @DisplayName("alg=none is rejected, and never reaches key resolution")
    void rejectsAlgNone() {
      // Forged by hand, because no library will produce one: header {"alg":"none","typ":"JWT"},
      // the claims, and an EMPTY signature segment.
      String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
      String payload =
          base64Url(
              "{\"sub\":\"u-1024\",\"iss\":\""
                  + JwtFixtures.ISSUER
                  + "\",\"aud\":\""
                  + JwtFixtures.AUDIENCE
                  + "\",\"exp\":"
                  + Instant.now().plusSeconds(900).getEpochSecond()
                  + "}");
      String unsigned = header + "." + payload + ".";

      JwtVerifier verifier = JwtVerifier.hs256(JwtFixtures.SECRET, profile);

      assertThatThrownBy(() -> verifier.verify(unsigned))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .as("rejected before any key is resolved")
          .isIn(
              JwtVerificationException.Reason.MALFORMED,
              JwtVerificationException.Reason.ALGORITHM_NOT_ALLOWED);
    }

    @Test
    @DisplayName("algorithm confusion: an HS256 token signed with the RSA PUBLIC key is rejected")
    void rejectsAlgorithmConfusion() throws Exception {
      // THE attack ADR-003's per-key allowlist exists for, and it is not hypothetical: the RSA
      // public key is public. An attacker takes it, uses its bytes as an HMAC secret, signs an
      // HS256 token, and presents it to a verifier that reads the algorithm FROM THE TOKEN. Such a
      // verifier computes HMAC-SHA256 with a key the attacker also has, and it verifies.
      //
      // Ours cannot: the algorithm is fixed at construction to RS256 and the header is compared
      // against it, never consulted to choose a verifier.
      byte[] publicKeyAsSecret = JwtFixtures.publicKeyBytes();
      String forged = signedWithHmac(hs256Header(), validClaims().build(), publicKeyAsSecret);

      JwtVerifier rsaVerifier = JwtVerifier.rs256(JwtFixtures.publicKey(), profile);

      assertThatThrownBy(() -> rsaVerifier.verify(forged))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.ALGORITHM_NOT_ALLOWED);
    }

    @Test
    @DisplayName("and the forged token IS a valid HS256 token, so the refusal is the defence")
    void theForgedTokenIsOtherwiseValid() throws Exception {
      // The companion test item 4.1 established as discipline: a rejection proves nothing unless
      // the
      // thing rejected would otherwise have worked. This same forged token verifies happily against
      // an HS256 verifier holding the same bytes -- which is exactly what a naive verifier becomes.
      byte[] publicKeyAsSecret = JwtFixtures.publicKeyBytes();
      String forged = signedWithHmac(hs256Header(), validClaims().build(), publicKeyAsSecret);

      JwtVerifier naive = JwtVerifier.hs256(publicKeyAsSecret, profile);

      assertThat(naive.verify(forged).subject())
          .as("the forgery is a well-formed, correctly-signed HS256 token")
          .isEqualTo("u-1024");
    }

    @Test
    @DisplayName("an RS256 token is rejected by an HS256 verifier")
    void rejectsTheOtherDirection() {
      JwtTokenProvider rsaSigner =
          JwtTokenProvider.rs256(JwtFixtures.privateKey(), "k1", JwtFixtures.publicKey(), profile);
      String rsaToken = rsaSigner.sign(JwtClaims.forSigning("u-1024"), Duration.ofMinutes(15));

      JwtVerifier hmacVerifier = JwtVerifier.hs256(JwtFixtures.SECRET, profile);

      assertThatThrownBy(() -> hmacVerifier.verify(rsaToken))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.ALGORITHM_NOT_ALLOWED);
    }
  }

  @Nested
  @DisplayName("claim attacks")
  class ClaimAttacks {

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsExpired() throws Exception {
      String expired =
          signedWithHmac(
              hs256Header(),
              validClaims()
                  .expirationTime(Date.from(Instant.now().minus(Duration.ofHours(1))))
                  .build(),
              JwtFixtures.SECRET);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(expired))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.EXPIRED);
    }

    @Test
    @DisplayName("a token with NO exp is rejected, which is the commonest misconfiguration")
    void rejectsMissingExp() throws Exception {
      String noExpiry =
          signedWithHmac(
              hs256Header(),
              new JWTClaimsSet.Builder()
                  .subject("u-1024")
                  .issuer(JwtFixtures.ISSUER)
                  .audience(List.of(JwtFixtures.AUDIENCE))
                  .build(),
              JwtFixtures.SECRET);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(noExpiry))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.EXPIRED);
    }

    @Test
    @DisplayName("a token expired within the clock skew is still accepted")
    void acceptsWithinSkew() throws Exception {
      String justExpired =
          signedWithHmac(
              hs256Header(),
              validClaims()
                  .expirationTime(Date.from(Instant.now().minus(Duration.ofSeconds(10))))
                  .build(),
              JwtFixtures.SECRET);

      assertThat(JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(justExpired).subject())
          .as("60s default skew, per ADR-003")
          .isEqualTo("u-1024");
    }

    @Test
    @DisplayName("a wrong-audience token is rejected")
    void rejectsWrongAudience() throws Exception {
      String wrongAudience =
          signedWithHmac(
              hs256Header(),
              validClaims().audience(List.of("some-other-api")).build(),
              JwtFixtures.SECRET);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(wrongAudience))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.AUDIENCE_MISMATCH);
    }

    @Test
    @DisplayName("a wrong-issuer token is rejected")
    void rejectsWrongIssuer() throws Exception {
      String wrongIssuer =
          signedWithHmac(
              hs256Header(),
              validClaims().issuer("https://evil.example.com").build(),
              JwtFixtures.SECRET);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(wrongIssuer))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.ISSUER_MISMATCH);
    }

    @Test
    @DisplayName("a token with no sub is rejected")
    void rejectsMissingSubject() throws Exception {
      String noSubject =
          signedWithHmac(
              hs256Header(),
              new JWTClaimsSet.Builder()
                  .issuer(JwtFixtures.ISSUER)
                  .audience(List.of(JwtFixtures.AUDIENCE))
                  .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                  .build(),
              JwtFixtures.SECRET);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(noSubject))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.CLAIMS_INVALID);
    }

    @Test
    @DisplayName("a wrong typ header is rejected")
    void rejectsWrongType() throws Exception {
      String wrongType =
          signedWithHmac(
              new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).build(),
              validClaims().build(),
              JwtFixtures.SECRET);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(wrongType))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.TYPE_NOT_ALLOWED);
    }
  }

  @Nested
  @DisplayName("signature attacks")
  class SignatureAttacks {

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void rejectsWrongSecret() throws Exception {
      byte[] attackerSecret =
          "ffffffffffffffffffffffffffffffff".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String forged = signedWithHmac(hs256Header(), validClaims().build(), attackerSecret);

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(forged))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("a tampered payload is rejected, and the signature is checked before any claim")
    void rejectsTamperedPayload() throws Exception {
      String valid = signedWithHmac(hs256Header(), validClaims().build(), JwtFixtures.SECRET);
      // Segmented by index rather than String.split, whose behaviour ErrorProne flags as surprising
      // and whose suggested replacement is a Guava type this module may not depend on.
      String header = valid.substring(0, valid.indexOf('.'));
      String signature = valid.substring(valid.lastIndexOf('.') + 1);
      // Swap the payload for one claiming a different subject, keeping the original signature.
      String tampered =
          header
              + "."
              + base64Url(
                  "{\"sub\":\"admin\",\"iss\":\""
                      + JwtFixtures.ISSUER
                      + "\",\"aud\":\""
                      + JwtFixtures.AUDIENCE
                      + "\",\"exp\":"
                      + Instant.now().plusSeconds(900).getEpochSecond()
                      + "}")
              + "."
              + signature;

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(tampered))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .as("SIGNATURE_INVALID rather than a claim reason: spec §4 checks the signature first")
          .isEqualTo(JwtVerificationException.Reason.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("garbage is rejected as malformed")
    void rejectsGarbage() {
      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify("not.a.token"))
          .isInstanceOf(JwtVerificationException.class)
          .extracting(thrown -> ((JwtVerificationException) thrown).reason())
          .isEqualTo(JwtVerificationException.Reason.MALFORMED);
    }
  }

  @Nested
  @DisplayName("what a rejection is allowed to say")
  class Disclosure {

    @Test
    @DisplayName("no message carries the token, a claim value, or the library's own text")
    void noMessageCarriesTheTokenOrItsClaims() throws Exception {
      // C-01. A JWT's claims are identity, and getMessage() is what reaches a log.
      String forged =
          signedWithHmac(
              hs256Header(),
              validClaims().subject("hunter2").claim("email", "ada@example.com").build(),
              "ffffffffffffffffffffffffffffffff".getBytes(java.nio.charset.StandardCharsets.UTF_8));

      assertThatThrownBy(() -> JwtVerifier.hs256(JwtFixtures.SECRET, profile).verify(forged))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage()).doesNotContain("hunter2");
                assertThat(thrown.getMessage()).doesNotContain("ada@example.com");
                assertThat(thrown.getMessage()).doesNotContain(forged);
                assertThat(thrown.getMessage()).contains("SIGNATURE_INVALID");
              });
    }
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
