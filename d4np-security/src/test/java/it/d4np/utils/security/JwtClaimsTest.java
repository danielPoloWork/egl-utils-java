package it.d4np.utils.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The claims type — what it will let a caller set, and what it will put in a log. */
class JwtClaimsTest {

  @Test
  @DisplayName("refuses a claim name the provider sets from the profile")
  void refusesReservedClaims() {
    // Refused rather than silently overwritten: a caller who sets `exp` here would believe they had
    // changed the token's lifetime, and the provider would overwrite it. A quiet no-op on a
    // security-relevant field is the defect shape this project refuses elsewhere too.
    JwtClaims claims = JwtClaims.forSigning("u-1024");

    for (String reserved : new String[] {"iss", "aud", "iat", "exp", "sub"}) {
      assertThatThrownBy(() -> claims.with(reserved, "anything"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("set by the provider");
    }
  }

  @Test
  @DisplayName("refuses a blank subject")
  void refusesABlankSubject() {
    assertThatThrownBy(() -> JwtClaims.forSigning("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subject must not be blank");
  }

  @Test
  @DisplayName(
      "with() copies rather than mutating, so claims already handed to a signer are stable")
  void withCopies() {
    JwtClaims original = JwtClaims.forSigning("u-1024");

    JwtClaims extended = original.with("role", "admin");

    assertThat(original.claimNames()).isEmpty();
    assertThat(extended.claimNames()).containsExactly("role");
  }

  @Test
  @DisplayName("toString renders claim NAMES and never claim values")
  void rendersNamesNeverValues() {
    // A JWT's claims are identity, and a toString() reaches a log far more casually than an
    // exception does. ADR-0027's reasoning for PartialUpdate, applied to the type that carries a
    // verified user's attributes.
    JwtTokenProvider tokens = JwtTokenProvider.hs256(JwtFixtures.SECRET, JwtFixtures.profile());
    String token =
        tokens.sign(
            JwtClaims.forSigning("u-1024").with("email", "ada@example.com").with("role", "admin"),
            Duration.ofMinutes(5));

    String rendered = tokens.verify(token).toString();

    assertThat(rendered).doesNotContain("ada@example.com").doesNotContain("admin");
    assertThat(rendered).contains("email").contains("role").contains("u-1024");
  }

  @Test
  @DisplayName("a subject holding control characters cannot fold one log line into two")
  void boundsTheRenderedSubject() {
    String rendered = JwtClaims.forSigning("u-1024\r\nWARNING: fabricated").toString();

    assertThat(rendered).doesNotContain("\r").doesNotContain("\n");
  }

  @Test
  @DisplayName("an enormous subject is truncated")
  void truncatesAnEnormousSubject() {
    String rendered = JwtClaims.forSigning("s".repeat(500)).toString();

    assertThat(rendered).contains("...");
    assertThat(rendered.length()).isLessThan(300);
  }

  @Test
  @DisplayName("aud is normalised to a set, whether the token carried a string or an array")
  void normalisesAudience() {
    // A JWT's aud is a string OR an array, and treating the two shapes differently is a known
    // source
    // of validation bugs. Normalised once, here, so no caller has to.
    JwtTokenProvider tokens = JwtTokenProvider.hs256(JwtFixtures.SECRET, JwtFixtures.profile());
    String token = tokens.sign(JwtClaims.forSigning("u-1024"), Duration.ofMinutes(5));

    assertThat(tokens.verify(token).audience()).containsExactly(JwtFixtures.AUDIENCE);
  }
}
