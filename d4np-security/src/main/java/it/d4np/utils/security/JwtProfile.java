package it.d4np.utils.security;

import it.d4np.utils.FluentBuilder;
import java.time.Duration;
import java.util.Objects;

/**
 * The validation settings ADR-003 calls the hardened profile (FR-11).
 *
 * <p>Every check ADR-003 lists as <em>"on by default"</em> is mandatory here, which is a stronger
 * reading of that phrase and a deliberate one. A default can be turned off; a required constructor
 * argument cannot. `iss` and `aud` in particular have no sensible default — you cannot check an
 * audience without being told which one — so a profile that made them optional would be a profile
 * whose two most-forgotten checks were silently absent.
 *
 * <pre>{@code
 * JwtProfile profile = JwtProfile.requiring("https://idp.example.com", "orders-api").build();
 * }</pre>
 *
 * <p><strong>Thread safety.</strong> Immutable and safe to share. Its {@link Builder} is not, per
 * {@link FluentBuilder}'s own contract.
 */
public final class JwtProfile {

  /** ADR-003's stated default, and the value every well-behaved deployment should keep. */
  private static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);

  /**
   * The largest skew this profile will accept.
   *
   * <p>Bounded because skew is a security parameter wearing an operations parameter's clothes: it
   * widens the window in which an expired token is still accepted, and a caller reaching for "an
   * hour" to make a flaky test pass has disabled {@code exp} for an hour.
   */
  private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

  private final String issuer;
  private final String audience;
  private final Duration clockSkew;
  private final String tokenType;

  private JwtProfile(Builder builder) {
    this.issuer = builder.issuer;
    this.audience = builder.audience;
    this.clockSkew = builder.clockSkew;
    this.tokenType = builder.tokenType;
  }

  /**
   * Starts a profile requiring the given issuer and audience.
   *
   * <p>Both are constructor arguments rather than builder options, so there is no order of calls in
   * which a profile is built without them.
   *
   * @param issuer the exact {@code iss} a token must carry
   * @param audience the {@code aud} a token must contain
   * @return a builder
   */
  public static Builder requiring(String issuer, String audience) {
    return new Builder(issuer, audience);
  }

  /**
   * The {@code iss} every token must carry.
   *
   * @return the required issuer
   */
  public String issuer() {
    return issuer;
  }

  /**
   * The {@code aud} every token must contain.
   *
   * @return the required audience
   */
  public String audience() {
    return audience;
  }

  /**
   * How far past {@code exp} a token is still accepted.
   *
   * @return the skew; never negative, never more than five minutes
   */
  public Duration clockSkew() {
    return clockSkew;
  }

  /**
   * The {@code typ} header every token must carry.
   *
   * @return the required type, {@code JWT} unless overridden
   */
  public String tokenType() {
    return tokenType;
  }

  /**
   * Renders the profile, which contains no secret.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "JwtProfile[issuer="
        + issuer
        + ", audience="
        + audience
        + ", clockSkew="
        + clockSkew
        + ", tokenType="
        + tokenType
        + "]";
  }

  /** Accumulating builder for a {@link JwtProfile}. */
  public static final class Builder extends FluentBuilder<JwtProfile> {

    private final String issuer;
    private final String audience;
    private Duration clockSkew = DEFAULT_CLOCK_SKEW;
    private String tokenType = "JWT";

    private Builder(String issuer, String audience) {
      this.issuer = Objects.requireNonNull(issuer, "issuer");
      this.audience = Objects.requireNonNull(audience, "audience");
    }

    /**
     * Overrides the clock skew.
     *
     * @param clockSkew how far past {@code exp} a token is still accepted; must not be negative and
     *     must not exceed five minutes
     * @return this builder
     */
    public Builder clockSkew(Duration clockSkew) {
      this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
      return this;
    }

    /**
     * Overrides the required {@code typ} header.
     *
     * @param tokenType the required type
     * @return this builder
     */
    public Builder tokenType(String tokenType) {
      this.tokenType = Objects.requireNonNull(tokenType, "tokenType");
      return this;
    }

    @Override
    protected void validate() {
      if (issuer.isBlank()) {
        reject("issuer must not be blank");
      }
      if (audience.isBlank()) {
        reject("audience must not be blank");
      }
      if (tokenType.isBlank()) {
        reject("tokenType must not be blank");
      }
      if (clockSkew.isNegative()) {
        reject("clockSkew must not be negative; was " + clockSkew);
      } else if (clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
        reject(
            "clockSkew must not exceed "
                + MAX_CLOCK_SKEW
                + "; was "
                + clockSkew
                + ". A large skew disables exp for its own duration");
      }
    }

    @Override
    protected JwtProfile construct() {
      return new JwtProfile(this);
    }
  }
}
