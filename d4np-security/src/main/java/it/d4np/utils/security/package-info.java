/**
 * JWT and cryptography under the hardened profiles the ADRs pin (spec §3, FR-11..FR-13).
 *
 * <p><strong>One third-party dependency, chosen for its failure modes rather than its API.</strong>
 * {@code nimbus-jose-jwt} arrives at compile scope, which ADR-001 permits for exactly this module,
 * and ADR-003 chose it because <em>the implementation is the security posture</em>: JOSE libraries
 * differ precisely in algorithm confusion, {@code alg=none} and key-type validation. Its
 * <strong>zero transitive dependencies</strong> are what keep it inside the dependency budget, and
 * that is measured rather than assumed — see item 6.1's roadmap entry.
 *
 * <h2>Conventions that hold for every type in this package</h2>
 *
 * <ul>
 *   <li><strong>No Nimbus type appears in a published signature.</strong> A token is a {@link
 *       java.lang.String} and a verified token is a {@link it.d4np.utils.security.JwtClaims}, so a
 *       future library swap is an implementation change plus ADR-003's supersession. This is the
 *       rule ADR-0024 measured the cost of in {@code d4np-json}.
 *   <li><strong>Non-null by default.</strong> Every parameter, return and field is non-null unless
 *       it carries {@link it.d4np.utils.Nullable}, checked by NullAway at {@code ERROR} severity on
 *       the JDK 21+ build cells (ADR-0009).
 *   <li><strong>No checked exceptions.</strong> Nimbus throws {@code JOSEException} and {@code
 *       ParseException}; both are wrapped at every boundary.
 *   <li><strong>A rejected token says why in a category and never in detail.</strong> No message,
 *       and no {@code toString()}, carries the token, a claim value, or Nimbus's own text
 *       (compliance control C-01).
 *   <li><strong>A misconfiguration fails at construction, not at the first token.</strong> An HS256
 *       secret under 256 bits, a JWKS URL outside the origin allowlist, a non-HTTPS URL: each is
 *       refused when the provider is built, where a startup failure is cheap and a 3 a.m. one is
 *       not.
 * </ul>
 *
 * <h2>The two rules that shape the surface</h2>
 *
 * <p><strong>One verifier, one algorithm.</strong> ADR-003's central defence is that HS256 and
 * RS256 never share a verifier — the algorithm-confusion attack is an HS256 token verified against
 * an RS256 public key, and it only works where one verifier accepts both. Here that is structural:
 * a provider is built by a factory that takes exactly one kind of key, so there is no configuration
 * in which two algorithms are accepted.
 *
 * <p><strong>Verifying and signing are different capabilities, and the narrower one is a narrower
 * type.</strong> {@link it.d4np.utils.security.JwtVerifier} verifies; {@link
 * it.d4np.utils.security.JwtTokenProvider} extends it and can also sign. A service that only
 * consumes an identity provider's tokens is handed a {@code JwtVerifier} and has no {@code sign}
 * method to call by mistake — ADR-0022's rule that a guarantee a consumer can switch off is
 * advisory, applied to a capability instead of a setting.
 *
 * @see <a
 *     href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/rfc/0005-security-contracts.md">RFC-0005
 *     — security module contracts</a>
 */
package it.d4np.utils.security;
