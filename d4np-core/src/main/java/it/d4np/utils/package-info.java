/**
 * The zero-dependency core of the {@code d4np} family — the shared error vocabulary and the
 * creational and structural patterns every other module builds on.
 *
 * <p><strong>Zero third-party dependencies, enforced by the build.</strong> Nothing here imports
 * anything outside {@code java.*}: {@code maven-enforcer} carries a default-deny allowlist for this
 * module (ADR-001, ADR-0006, NFR-08), so a Spring, Jackson or Redisson type reaching this package
 * fails the build rather than review. A team can therefore adopt one pattern from here without
 * inheriting a framework.
 *
 * <p><strong>This module owns the family root package</strong>, so no other module may place a type
 * in it — two modules sharing a package is a split package, which the module system rejects
 * outright. Capability modules live in {@code it.d4np.utils.<capability>} (ADR-0005).
 *
 * <h2>Conventions that hold for every type in this package</h2>
 *
 * <ul>
 *   <li><strong>Non-null by default.</strong> Every parameter, return and field is non-null unless
 *       it carries {@link it.d4np.utils.Nullable}. This is checked, not promised: NullAway runs at
 *       {@code ERROR} severity over {@code it.d4np} on the JDK 21+ build cells (ADR-0009).
 *   <li><strong>No {@code null} to signal absence.</strong> {@link java.util.Optional} for absence,
 *       an unchecked exception for a defect, {@link it.d4np.utils.Result} for an expected failure
 *       (RFC-0001).
 *   <li><strong>No checked exceptions.</strong> Nothing here declares one.
 *   <li><strong>Thread safety is documented per type</strong>, and a thread-safety claim is backed
 *       by a named jcstress harness rather than asserted in prose (spec §6).
 * </ul>
 *
 * <h2>The error model in one table (ADR-002)</h2>
 *
 * <table border="1">
 *   <caption>Which failure shape to use</caption>
 *   <tr><th>Shape</th><th>When</th></tr>
 *   <tr>
 *     <td>{@link it.d4np.utils.Result.Err} with {@link it.d4np.utils.ErrorDetail}</td>
 *     <td>an expected outcome the caller branches on</td>
 *   </tr>
 *   <tr>
 *     <td>{@link it.d4np.utils.BusinessException}</td>
 *     <td>a rule violation aborting the use case, handled at a boundary (mapped to 422 by FR-19)</td>
 *   </tr>
 *   <tr>
 *     <td>an unchecked exception from this library</td>
 *     <td>a defect or absent binding the caller cannot sensibly branch on</td>
 *   </tr>
 * </table>
 *
 * @see <a
 *     href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/rfc/0001-core-contracts.md">RFC-0001
 *     — core module contracts and error model</a>
 */
package it.d4np.utils;
