package it.d4np.utils.security;

/**
 * The verify-only face of a {@link JwsEngine}.
 *
 * <p>Package-private and deliberately not a {@link JwtTokenProvider} with its signer left null: the
 * capability is expressed by the <em>type</em>, so a verify-only construction cannot be cast into a
 * signing one, and there is no runtime state to inspect in order to find out which it is.
 */
final class EngineVerifier implements JwtVerifier {

  private final JwsEngine engine;

  EngineVerifier(JwsEngine engine) {
    this.engine = engine;
  }

  @Override
  public JwtClaims verify(String token) {
    return engine.verify(token);
  }

  /**
   * Names the algorithm and nothing else.
   *
   * @return a diagnostic rendering carrying no key material
   */
  @Override
  public String toString() {
    return "JwtVerifier[algorithm=" + engine.algorithmName() + ", canSign=false]";
  }
}
