package it.d4np.utils.security;

import java.security.interfaces.RSAPublicKey;

/**
 * Verifies tokens, and cannot sign them (FR-11, ADR-003).
 *
 * <p><strong>The narrower capability is a narrower type, and that is the point.</strong> A service
 * that only consumes an identity provider's tokens has no business signing any, and handing it a
 * {@link JwtTokenProvider} would give it a {@code sign} method it could reach by mistake — or that
 * a later refactor could reach on its behalf. ADR-0022's rule that a guarantee a consumer can
 * switch off is advisory, applied to a capability rather than a setting: the factories below hand
 * back an object with no signing key <em>and</em> no signing method.
 *
 * <p><strong>One verifier, one algorithm.</strong> Each factory takes exactly one kind of key, so
 * there is no configuration in which a verifier accepts both HS256 and RS256 — which is the
 * algorithm-confusion attack's precondition and ADR-003's central defence. The token's {@code alg}
 * header is compared against the verifier's fixed algorithm and is never used to select one.
 *
 * <p><strong>Thread safety.</strong> Every implementation is immutable and safe to share; the JWKS
 * cache behind {@link #rs256(JwksSource, JwtProfile)} is the only mutable state and is a single
 * atomically-published snapshot.
 *
 * @see JwtTokenProvider
 */
public interface JwtVerifier {

  /**
   * Verifies a token and returns its claims.
   *
   * <p>Checks run in this order, and the order is the contract: the token is parsed, its algorithm
   * is compared against this verifier's, its {@code typ} is checked, the <strong>signature is
   * verified</strong>, and only then is any claim read (spec §4). An {@code alg=none} or
   * algorithm-confusion token is rejected before key material is even resolved.
   *
   * @param token the compact serialization
   * @return the claims, which exist only on the far side of all of the above
   * @throws JwtVerificationException if the token is rejected; {@link
   *     JwtVerificationException#reason()} says which category
   * @throws NullPointerException if {@code token} is {@code null}
   */
  JwtClaims verify(String token);

  /**
   * A verifier for HS256 tokens signed with a shared secret.
   *
   * <p>Verify-only even though HS256 is symmetric and the same secret could sign: a service that
   * only validates should not be handed the ability to mint.
   *
   * @param secret the shared secret; at least 256 bits, per ADR-003
   * @param profile the hardened profile
   * @return a verifier
   * @throws IllegalArgumentException if the secret is shorter than 256 bits
   */
  static JwtVerifier hs256(byte[] secret, JwtProfile profile) {
    return new EngineVerifier(JwsEngine.hs256Verifying(secret, profile));
  }

  /**
   * A verifier for RS256 tokens checked against one static public key.
   *
   * @param publicKey the issuer's public key
   * @param profile the hardened profile
   * @return a verifier
   */
  static JwtVerifier rs256(RSAPublicKey publicKey, JwtProfile profile) {
    return new EngineVerifier(JwsEngine.rs256Verifying(publicKey, profile));
  }

  /**
   * A verifier for RS256 tokens whose keys are resolved from a JWKS endpoint by {@code kid}.
   *
   * <p>The endpoint's trust posture — origin allowlist, HTTPS only, no redirects, bounded fetch —
   * is {@link JwksSource}'s, and is the contract RFC-0005 added to close risk-register **R-06**.
   *
   * @param keys the JWKS endpoint
   * @param profile the hardened profile
   * @return a verifier
   */
  static JwtVerifier rs256(JwksSource keys, JwtProfile profile) {
    return new EngineVerifier(JwsEngine.rs256Verifying(keys, profile));
  }
}
