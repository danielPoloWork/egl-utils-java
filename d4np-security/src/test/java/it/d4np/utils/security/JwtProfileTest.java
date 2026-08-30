package it.d4np.utils.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.d4np.utils.BuilderValidationException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hardened profile — what it will not let you leave out, and what it will not let you widen.
 */
class JwtProfileTest {

  @Test
  @DisplayName("issuer and audience are constructor arguments, so no build order omits them")
  void issuerAndAudienceAreMandatory() {
    // ADR-003 says these checks are "on by default". Read as constructor arguments rather than
    // defaults, because a default can be turned off and neither of these has a sensible value to
    // default TO -- you cannot check an audience without being told which one.
    JwtProfile profile = JwtProfile.requiring("https://idp.example.com", "orders-api").build();

    assertThat(profile.issuer()).isEqualTo("https://idp.example.com");
    assertThat(profile.audience()).isEqualTo("orders-api");
  }

  @Test
  @DisplayName("defaults to ADR-003's 60-second skew and a JWT typ")
  void defaults() {
    JwtProfile profile = JwtProfile.requiring("iss", "aud").build();

    assertThat(profile.clockSkew()).isEqualTo(Duration.ofSeconds(60));
    assertThat(profile.tokenType()).isEqualTo("JWT");
  }

  @Test
  @DisplayName("refuses a clock skew large enough to disable exp")
  void refusesAnEnormousSkew() {
    // Skew is a security parameter wearing an operations parameter's clothes: it widens the window
    // in which an expired token is accepted, and a caller reaching for "an hour" to make a flaky
    // test pass has turned exp off for an hour.
    assertThatThrownBy(
            () -> JwtProfile.requiring("iss", "aud").clockSkew(Duration.ofHours(1)).build())
        .isInstanceOf(BuilderValidationException.class)
        .hasMessageContaining("must not exceed")
        .hasMessageContaining("disables exp");
  }

  @Test
  @DisplayName("refuses a negative skew")
  void refusesANegativeSkew() {
    assertThatThrownBy(
            () -> JwtProfile.requiring("iss", "aud").clockSkew(Duration.ofSeconds(-1)).build())
        .isInstanceOf(BuilderValidationException.class)
        .hasMessageContaining("must not be negative");
  }

  @Test
  @DisplayName("reports every violation at once rather than the first")
  void accumulatesViolations() {
    // FluentBuilder's return (FR-02, ADR-0017), and its first consumer in this module.
    assertThatThrownBy(
            () -> JwtProfile.requiring("  ", "  ").clockSkew(Duration.ofHours(1)).build())
        .isInstanceOf(BuilderValidationException.class)
        .hasMessageContaining("issuer must not be blank")
        .hasMessageContaining("audience must not be blank")
        .hasMessageContaining("must not exceed");
  }

  @Test
  @DisplayName("renders its settings, none of which is a secret")
  void rendersItsSettings() {
    assertThat(JwtProfile.requiring("https://idp.example.com", "orders-api").build().toString())
        .contains("https://idp.example.com")
        .contains("orders-api")
        .contains("PT1M");
  }
}
