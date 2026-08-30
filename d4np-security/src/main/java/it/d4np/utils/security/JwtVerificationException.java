package it.d4np.utils.security;

import java.util.Objects;

/**
 * A token was rejected (FR-11, ADR-003).
 *
 * <p>Unchecked and extending {@link RuntimeException} directly rather than {@code
 * BusinessException} — a rejected token is not a business rule violation, and FR-19 maps the two
 * differently.
 *
 * <h2>Why this says more than {@code CryptoException} will, which is a decision rather than an
 * inconsistency</h2>
 *
 * <p>RFC-0005 gives {@code AesEncryptor} a <strong>uniform</strong> message across wrong key,
 * tampered ciphertext and AAD mismatch, because distinguishing them hands an attacker a decryption
 * oracle one bit at a time. **The same reasoning does not transfer here, and the difference is
 * worth stating rather than leaving to look like an oversight.** An attacker holding a token
 * already knows whether it is expired, which audience they put in it and which key they signed it
 * with; the information a {@link Reason} would leak to them is information they supplied. Meanwhile
 * the *application* genuinely needs the distinction — an expired token is a re-authenticate, a
 * wrong-audience token is a misrouted request, and a bad signature is a security event worth an
 * alert. Collapsing the three would make all of them look the same in a dashboard.
 *
 * <p>What is <strong>not</strong> distinguished is anything the attacker does not already control:
 * the reason names a category, never <em>which</em> key failed, which issuer was expected, or what
 * the token contained.
 *
 * <h2>What never appears in the message</h2>
 *
 * <p>The token, any claim value, and Nimbus's own text. The first two are identity; the third is
 * where a library puts whatever it happened to be parsing — item 4.3 measured a JDBC driver
 * embedding an entire statement in exactly that position, and item 4.1 measured Jackson embedding a
 * credential. Compliance control <strong>C-01</strong>; the cause chain carries the detail for
 * whoever is entitled to it.
 */
public final class JwtVerificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Why a token was rejected, at the granularity an application can act on.
   *
   * <p>Every constant here describes something the presenter of the token already knows.
   */
  public enum Reason {
    /** Not a well-formed JWS at all — wrong number of segments, bad base64, unparseable JSON. */
    MALFORMED,
    /**
     * The header's {@code alg} is not the one this provider was built for.
     *
     * <p>This is where {@code alg=none} and the algorithm-confusion attack both land, and they land
     * here rather than at the signature check because the algorithm is decided by <em>this
     * verifier's construction</em> and never read from the token.
     */
    ALGORITHM_NOT_ALLOWED,
    /** The header's {@code typ} is not the one the profile requires. */
    TYPE_NOT_ALLOWED,
    /** The signature did not verify under this provider's key. */
    SIGNATURE_INVALID,
    /** {@code exp} is absent, or is in the past beyond the profile's clock skew. */
    EXPIRED,
    /** {@code iss} is absent or is not the issuer the profile requires. */
    ISSUER_MISMATCH,
    /** {@code aud} is absent or does not contain the audience the profile requires. */
    AUDIENCE_MISMATCH,
    /** A required registered claim is missing or the wrong shape. */
    CLAIMS_INVALID,
    /** The key material could not be resolved — a JWKS fetch failed, or no key matched the kid. */
    KEY_UNAVAILABLE
  }

  private final transient Reason reason;

  private JwtVerificationException(Reason reason, String detail, Throwable cause) {
    super("token rejected: " + reason + " (" + detail + ")", cause);
    this.reason = reason;
  }

  private JwtVerificationException(Reason reason, String detail) {
    super("token rejected: " + reason + " (" + detail + ")");
    this.reason = reason;
  }

  /**
   * Rejects a token for the given reason.
   *
   * @param reason the category
   * @param detail a fixed phrase from this library, never caller or token text
   * @return the exception to throw
   */
  static JwtVerificationException of(Reason reason, String detail) {
    return new JwtVerificationException(
        Objects.requireNonNull(reason, "reason"), Objects.requireNonNull(detail, "detail"));
  }

  /**
   * Rejects a token for the given reason, keeping the underlying failure as the cause.
   *
   * @param reason the category
   * @param detail a fixed phrase from this library, never caller or token text
   * @param cause the library failure, which never reaches the message
   * @return the exception to throw
   */
  static JwtVerificationException of(Reason reason, String detail, Throwable cause) {
    return new JwtVerificationException(
        Objects.requireNonNull(reason, "reason"),
        Objects.requireNonNull(detail, "detail"),
        Objects.requireNonNull(cause, "cause"));
  }

  /**
   * Why the token was rejected.
   *
   * @return the category, never {@code null}
   */
  public Reason reason() {
    return reason;
  }
}
