package it.d4np.utils.security;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

/**
 * Signs and verifies JWTs under the ADR-003 hardened profile (FR-11).
 *
 * <pre>{@code
 * JwtProfile profile = JwtProfile.requiring("https://idp.example.com", "orders-api").build();
 * JwtTokenProvider tokens = JwtTokenProvider.hs256(secret, profile);
 *
 * String token = tokens.sign(JwtClaims.forSigning("u-1024").with("role", "admin"),
 *                            Duration.ofMinutes(15));
 * JwtClaims claims = tokens.verify(token);
 * }</pre>
 *
 * <p><strong>This type extends {@link JwtVerifier} and adds exactly one thing: the ability to
 * sign.</strong> A service that only consumes tokens takes a {@code JwtVerifier} and has no {@code
 * sign} method to reach; a service that issues them takes this. The split is the capability made
 * structural rather than documented.
 *
 * <h2>What ADR-003's hardened profile means here</h2>
 *
 * <ul>
 *   <li><strong>One provider, one algorithm.</strong> Each factory takes one kind of key, so no
 *       instance accepts both HS256 and RS256 — the algorithm-confusion attack's precondition.
 *   <li><strong>{@code alg=none} is structurally impossible.</strong> The algorithm is fixed at
 *       construction and the token's header is compared against it, never consulted to select a
 *       verifier, and the comparison happens before key material is resolved.
 *   <li><strong>{@code exp} is mandatory and cannot be omitted when signing.</strong> {@link
 *       #sign(JwtClaims, Duration)} sets {@code iss}, {@code aud}, {@code iat} and {@code exp} from
 *       the profile and the requested lifetime, and {@link JwtClaims#with} refuses those names — so
 *       a token with no expiry is not representable rather than merely rejected.
 *   <li><strong>{@code typ}, {@code aud} and {@code iss} are required, not defaulted.</strong>
 *       {@link JwtProfile} takes the issuer and audience as constructor arguments; see that type.
 *   <li><strong>An HS256 secret under 256 bits is rejected at construction</strong>, where a
 *       startup failure is cheap.
 * </ul>
 *
 * <p><strong>Thread safety.</strong> Immutable and safe to share.
 */
public final class JwtTokenProvider implements JwtVerifier {

  private final JwsEngine engine;

  private JwtTokenProvider(JwsEngine engine) {
    this.engine = engine;
  }

  /**
   * A provider that signs and verifies HS256 tokens with one shared secret.
   *
   * <p>The secret is copied on construction, so a caller that zeroes its array afterwards does not
   * break this provider — and, more usefully, a caller that mutates it cannot silently change what
   * this provider accepts.
   *
   * @param secret the shared secret; at least 256 bits, per ADR-003
   * @param profile the hardened profile
   * @return a provider
   * @throws IllegalArgumentException if the secret is shorter than 256 bits
   */
  public static JwtTokenProvider hs256(byte[] secret, JwtProfile profile) {
    return new JwtTokenProvider(JwsEngine.hs256(secret, profile));
  }

  /**
   * A provider that signs RS256 tokens with a private key and verifies them with the matching
   * public key.
   *
   * <p>For verifying an <em>identity provider's</em> tokens — where there is no private key and the
   * keys come from a JWKS endpoint — use {@link JwtVerifier#rs256(JwksSource, JwtProfile)}, which
   * hands back a type with no signing method at all.
   *
   * @param signingKey the private key
   * @param keyId the {@code kid} to publish in the header, so a verifier can select the right key
   * @param verificationKey the matching public key
   * @param profile the hardened profile
   * @return a provider
   */
  public static JwtTokenProvider rs256(
      RSAPrivateKey signingKey, String keyId, RSAPublicKey verificationKey, JwtProfile profile) {
    return new JwtTokenProvider(JwsEngine.rs256(signingKey, keyId, verificationKey, profile));
  }

  /**
   * Signs a token carrying the given claims, valid for the given lifetime.
   *
   * @param claims a subject and any custom claims, from {@link JwtClaims#forSigning(String)}
   * @param lifetime how long the token is valid; must be positive
   * @return the compact serialization
   * @throws IllegalArgumentException if {@code lifetime} is not positive
   */
  public String sign(JwtClaims claims, Duration lifetime) {
    return engine.sign(claims, lifetime);
  }

  @Override
  public JwtClaims verify(String token) {
    return engine.verify(token);
  }

  /**
   * Names the algorithm and, where there is one, the signing {@code kid}.
   *
   * <p>Never renders key material: a secret has no place in a {@code toString()}, and a {@code kid}
   * is a public identifier that appears in every token this provider issues.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "JwtTokenProvider[algorithm="
        + engine.algorithmName()
        + ", canSign=true, signingKeyId="
        + engine.signingKeyId().orElse("none")
        + "]";
  }
}
