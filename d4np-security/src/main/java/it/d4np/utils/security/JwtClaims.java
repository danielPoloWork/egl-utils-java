package it.d4np.utils.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The claims of a token that has already been verified (FR-11, ADR-003).
 *
 * <p><strong>An instance of this type only ever exists on the far side of a signature
 * check.</strong> {@link JwtVerifier#verify(String)} is the sole way to obtain one from a token,
 * and it constructs one only after the signature, the expiry, the issuer, the audience and the type
 * have all passed. That ordering is the point: spec §4's rule is that claims are never trusted
 * before verification, and a type that cannot be built from an unverified token makes the rule
 * structural rather than a review note.
 *
 * <p>{@link #forSigning(String)} builds one for the opposite direction — claims on their way *into*
 * a token — and deliberately does not let a caller set {@code iss}, {@code aud}, {@code iat} or
 * {@code exp}: those come from the profile and the requested lifetime, so a token cannot be signed
 * without an expiry.
 *
 * <h2>What {@code toString()} renders, and why it is not the values</h2>
 *
 * <p>A JWT's claims are identity: a subject, often an email, frequently roles and a tenant. So this
 * renders the claim <em>names</em> and never the values, exactly as {@code PartialUpdate} does
 * (ADR-0027) and for the same reason — a {@code toString()} reaches a log far more casually than an
 * exception does, and the author of {@code log.debug("claims {}", claims)} is not thinking about
 * disclosure. The subject is the one exception and it is bounded, because a claims object that
 * cannot say which subject it is about is useless in exactly the debugging session it exists for.
 *
 * <p><strong>Thread safety.</strong> Immutable; the custom-claim map is copied on construction and
 * exposed only through {@link #stringClaim(String)}.
 */
public final class JwtClaims {

  /** Long enough to identify a subject, short enough that it cannot flood a log line. */
  private static final int MAX_RENDERED_SUBJECT = 64;

  private final String subject;
  private final String issuer;
  private final Set<String> audience;
  private final Instant issuedAt;
  private final Instant expiresAt;
  private final Map<String, String> custom;

  private JwtClaims(
      String subject,
      String issuer,
      Set<String> audience,
      Instant issuedAt,
      Instant expiresAt,
      Map<String, String> custom) {
    this.subject = subject;
    this.issuer = issuer;
    this.audience = Set.copyOf(audience);
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.custom = Map.copyOf(custom);
  }

  /**
   * Builds the claims of a verified token.
   *
   * <p>Package-private, which is what makes the ordering above a property of the type: nothing
   * outside this package can mint a {@code JwtClaims} that did not come out of a verification.
   *
   * @param subject the {@code sub} claim
   * @param issuer the {@code iss} claim
   * @param audience the {@code aud} claim, always as a set even when the token carried one string
   * @param issuedAt the {@code iat} claim
   * @param expiresAt the {@code exp} claim
   * @param custom every other claim, rendered as strings
   * @return the verified claims
   */
  static JwtClaims verified(
      String subject,
      String issuer,
      Set<String> audience,
      Instant issuedAt,
      Instant expiresAt,
      Map<String, String> custom) {
    return new JwtClaims(subject, issuer, audience, issuedAt, expiresAt, custom);
  }

  /**
   * Starts the claims for a token about to be signed.
   *
   * <p>{@code iss}, {@code aud}, {@code iat} and {@code exp} are deliberately absent from this
   * builder: {@link JwtTokenProvider#sign(JwtClaims, java.time.Duration)} sets all four from the
   * profile and the requested lifetime. A caller therefore cannot mint a token with no expiry,
   * which is the misconfiguration ADR-003's "mandatory {@code exp}" exists to prevent — and
   * preventing it at the type is stronger than checking for it at the boundary.
   *
   * @param subject the {@code sub} claim; must not be blank
   * @return claims carrying only a subject
   */
  public static JwtClaims forSigning(String subject) {
    Objects.requireNonNull(subject, "subject");
    if (subject.isBlank()) {
      throw new IllegalArgumentException("subject must not be blank");
    }
    return new JwtClaims(subject, "", Set.of(), Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  /**
   * Returns a copy carrying one more custom claim.
   *
   * <p>A copy rather than a mutation, so claims already handed to a signer cannot change underneath
   * it.
   *
   * @param name the claim name; must not be blank, and must not be one of the registered claims
   *     this library sets ({@code iss}, {@code aud}, {@code iat}, {@code exp}, {@code sub})
   * @param value the claim value
   * @return a new instance
   */
  public JwtClaims with(String name, String value) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(value, "value");
    if (name.isBlank()) {
      throw new IllegalArgumentException("claim name must not be blank");
    }
    if (RESERVED.contains(name)) {
      // Refused rather than silently overwritten: a caller who sets `exp` here would believe they
      // had changed the token's lifetime, and the profile would overwrite it. A quiet no-op on a
      // security-relevant field is the shape of defect this project refuses elsewhere too.
      throw new IllegalArgumentException(
          "claim " + name + " is set by the provider from the profile and cannot be supplied here");
    }
    Map<String, String> extended = new LinkedHashMap<>(custom);
    extended.put(name, value);
    return new JwtClaims(subject, issuer, audience, issuedAt, expiresAt, extended);
  }

  /** The claims this library sets itself, which a caller may therefore not supply. */
  private static final Set<String> RESERVED = Set.of("iss", "aud", "iat", "exp", "sub");

  /**
   * The {@code sub} claim.
   *
   * @return the subject
   */
  public String subject() {
    return subject;
  }

  /**
   * The {@code iss} claim, as verified against the profile.
   *
   * @return the issuer, or an empty string on claims built for signing
   */
  public String issuer() {
    return issuer;
  }

  /**
   * The {@code aud} claim, always as a set.
   *
   * <p>A JWT's {@code aud} is a string <em>or</em> an array, and treating the two shapes
   * differently is a known source of validation bugs. It is normalised here so a caller never has
   * to.
   *
   * @return the audience; empty on claims built for signing
   */
  public Set<String> audience() {
    return audience;
  }

  /**
   * The {@code iat} claim.
   *
   * @return when the token was issued; {@link Instant#EPOCH} on claims built for signing
   */
  public Instant issuedAt() {
    return issuedAt;
  }

  /**
   * The {@code exp} claim.
   *
   * @return when the token expires; {@link Instant#EPOCH} on claims built for signing
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * A custom claim, if the token carried one under that name.
   *
   * @param name the claim name
   * @return the value, or empty
   */
  public Optional<String> stringClaim(String name) {
    return Optional.ofNullable(custom.get(Objects.requireNonNull(name, "name")));
  }

  /**
   * The names of every custom claim, so a caller can see what is present without reading values.
   *
   * @return the names, unmodifiable
   */
  public Set<String> claimNames() {
    return custom.keySet();
  }

  /**
   * Renders the subject and the claim <em>names</em>, and never a claim value.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "JwtClaims[subject="
        + bounded(subject)
        + ", audience="
        + audience.size()
        + " entr"
        + (audience.size() == 1 ? "y" : "ies")
        + ", expiresAt="
        + expiresAt
        + ", claims="
        + custom.keySet()
        + "]";
  }

  /** Strips control characters and truncates, so a crafted subject cannot fold a log line. */
  private static String bounded(String value) {
    StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_RENDERED_SUBJECT));
    value
        .codePoints()
        .filter(codePoint -> !Character.isISOControl(codePoint))
        .limit(MAX_RENDERED_SUBJECT)
        .forEach(safe::appendCodePoint);
    return value.length() > safe.length() ? safe + "..." : safe.toString();
  }
}
