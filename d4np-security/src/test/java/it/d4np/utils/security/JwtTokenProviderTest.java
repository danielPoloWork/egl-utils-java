package it.d4np.utils.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-11's provider — what it signs, what it refuses to sign, and what it will not hand out. */
class JwtTokenProviderTest {

  private final JwtProfile profile = JwtFixtures.profile();

  @Nested
  @DisplayName("signing and verifying")
  class RoundTrip {

    @Test
    @DisplayName("an HS256 token round-trips with its subject and custom claims")
    void hs256RoundTrip() {
      JwtTokenProvider tokens = JwtTokenProvider.hs256(JwtFixtures.SECRET, profile);

      String token =
          tokens.sign(
              JwtClaims.forSigning("u-1024").with("role", "admin").with("tenant", "acme"),
              Duration.ofMinutes(15));
      JwtClaims claims = tokens.verify(token);

      assertThat(claims.subject()).isEqualTo("u-1024");
      assertThat(claims.stringClaim("role")).contains("admin");
      assertThat(claims.stringClaim("tenant")).contains("acme");
      assertThat(claims.stringClaim("absent")).isEmpty();
    }

    @Test
    @DisplayName("an RS256 token round-trips")
    void rs256RoundTrip() {
      JwtTokenProvider tokens =
          JwtTokenProvider.rs256(JwtFixtures.privateKey(), "k1", JwtFixtures.publicKey(), profile);

      String token = tokens.sign(JwtClaims.forSigning("u-7"), Duration.ofMinutes(5));

      assertThat(tokens.verify(token).subject()).isEqualTo("u-7");
    }

    @Test
    @DisplayName(
        "the provider sets iss, aud, iat and exp itself, from the profile and the lifetime")
    void theProviderSetsTheRegisteredClaims() {
      // Not the caller: a token with no expiry is unrepresentable rather than merely rejected,
      // which
      // is what ADR-003's "mandatory exp" means when it is structural.
      JwtTokenProvider tokens = JwtTokenProvider.hs256(JwtFixtures.SECRET, profile);
      Instant before = Instant.now();

      JwtClaims claims =
          tokens.verify(tokens.sign(JwtClaims.forSigning("u-1024"), Duration.ofMinutes(15)));

      assertThat(claims.issuer()).isEqualTo(JwtFixtures.ISSUER);
      assertThat(claims.audience()).containsExactly(JwtFixtures.AUDIENCE);
      assertThat(claims.expiresAt()).isAfter(before.plus(Duration.ofMinutes(14)));
      assertThat(claims.issuedAt()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    @DisplayName("a non-positive lifetime is refused")
    void refusesANonPositiveLifetime() {
      JwtTokenProvider tokens = JwtTokenProvider.hs256(JwtFixtures.SECRET, profile);

      assertThatThrownBy(() -> tokens.sign(JwtClaims.forSigning("u-1"), Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("lifetime must be positive");
    }
  }

  @Nested
  @DisplayName("what construction refuses")
  class Construction {

    @Test
    @DisplayName("an HS256 secret under 256 bits is rejected at construction, per ADR-003")
    void rejectsAShortSecret() {
      // At construction, where a startup failure is cheap -- not at the first token, where it is
      // 3am.
      assertThatThrownBy(() -> JwtTokenProvider.hs256(JwtFixtures.SHORT_SECRET, profile))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least 256 bits")
          .hasMessageContaining("248");
    }

    @Test
    @DisplayName("the secret is copied, so a caller mutating its array cannot change what verifies")
    void copiesTheSecret() {
      byte[] mutable = JwtFixtures.SECRET.clone();
      JwtTokenProvider tokens = JwtTokenProvider.hs256(mutable, profile);
      String token = tokens.sign(JwtClaims.forSigning("u-1024"), Duration.ofMinutes(5));

      java.util.Arrays.fill(mutable, (byte) 0);

      assertThat(tokens.verify(token).subject())
          .as("zeroing the caller's array does not change this provider")
          .isEqualTo("u-1024");
    }
  }

  @Nested
  @DisplayName("the capability split")
  class Capability {

    @Test
    @DisplayName("a JwtVerifier publishes no sign method at all")
    void aVerifierCannotSign() {
      // Structural rather than a runtime refusal: the verify-only factories hand back a type with
      // no
      // signing method, so the mistake is not available to be made. ADR-0022's rule applied to a
      // capability instead of a setting.
      List<String> methods =
          List.of(JwtVerifier.class.getMethods()).stream()
              .filter(m -> !m.isSynthetic())
              .map(Method::getName)
              .toList();

      assertThat(methods).contains("verify").doesNotContain("sign");
    }

    @Test
    @DisplayName("and the verify-only implementation is not castable to the signing one")
    void theVerifyOnlyImplementationIsNotAProvider() {
      JwtVerifier verifier = JwtVerifier.hs256(JwtFixtures.SECRET, profile);

      assertThat(verifier)
          .as("a cast would be a deliberate act, and it would fail")
          .isNotInstanceOf(JwtTokenProvider.class);
    }

    @Test
    @DisplayName("JwtTokenProvider is a JwtVerifier, so a signer can be passed where one is wanted")
    void aProviderIsAVerifier() {
      assertThat(JwtTokenProvider.hs256(JwtFixtures.SECRET, profile))
          .isInstanceOf(JwtVerifier.class);
    }
  }

  @Nested
  @DisplayName("what it renders")
  class Rendering {

    @Test
    @DisplayName("toString names the algorithm and never key material")
    void neverRendersKeyMaterial() {
      String secretText = new String(JwtFixtures.SECRET, java.nio.charset.StandardCharsets.UTF_8);

      String rendered = JwtTokenProvider.hs256(JwtFixtures.SECRET, profile).toString();

      assertThat(rendered).contains("HS256").contains("canSign=true").doesNotContain(secretText);
    }

    @Test
    @DisplayName("a verifier says it cannot sign")
    void aVerifierSaysSo() {
      assertThat(JwtVerifier.hs256(JwtFixtures.SECRET, profile).toString())
          .contains("canSign=false");
    }
  }
}
