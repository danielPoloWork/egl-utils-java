/**
 * JSON mapping over Jackson, configured once so that a consumer never has to remember the settings
 * that matter (spec §3, FR-20 and FR-21).
 *
 * <p><strong>Jackson is a compile dependency here and nowhere upstream.</strong> {@code d4np-core}
 * carries a default-deny {@code maven-enforcer} allowlist (ADR-001, ADR-0006, NFR-08), so a team
 * that wants a keyed factory or the {@code Result} error model takes neither Jackson nor its CVE
 * surface. This module exists in order to pay that cost for the consumers who want it.
 *
 * <p><strong>The configured {@code ObjectMapper} is never exposed.</strong> No type in this package
 * returns one, accepts one, or names one in a signature. FR-20's guarantee is a property of {@link
 * it.d4np.utils.json.JsonMapper} the type, not of the call path that built it — one {@code
 * activateDefaultTyping} call on a handed-out mapper re-opens the deserialization CVE class the
 * requirement exists to close (ADR-0022's rule: a guarantee a consumer can switch off is advisory).
 *
 * <h2>Conventions that hold for every type in this package</h2>
 *
 * <ul>
 *   <li><strong>Non-null by default.</strong> Every parameter, return and field is non-null unless
 *       it carries {@link it.d4np.utils.Nullable}, checked by NullAway at {@code ERROR} severity on
 *       the JDK 21+ build cells (ADR-0009).
 *   <li><strong>No checked exceptions.</strong> Jackson's {@code JsonProcessingException} is
 *       checked; it is wrapped in {@link it.d4np.utils.json.JsonConversionException} at every
 *       boundary, which extends {@link java.lang.RuntimeException} directly and <em>not</em> {@code
 *       BusinessException} — FR-19 maps a malformed payload to <strong>400</strong> and {@code
 *       BusinessException} to <strong>422</strong>.
 *   <li><strong>No message carries the document.</strong> An exception message from this package is
 *       built from the property path and the target type only; Jackson's own exception survives as
 *       the {@code cause} for the log, never for the client (compliance control C-01).
 *   <li><strong>Thread safety is documented per type.</strong>
 * </ul>
 *
 * <h2>What a consumer still owns</h2>
 *
 * <p>Jackson reads and writes a consumer's own types by reflection. Under the module system that
 * means the consumer's module must make them reachable — {@code exports} plus public members, or
 * {@code opens its.package to com.fasterxml.jackson.databind;} for anything else. This library
 * cannot do it on the consumer's behalf and does not try: {@code opens} is a privilege the owner of
 * a package grants, which is the same line RFC-0002 drew when it refused deep reflection for FR-16.
 *
 * @see <a
 *     href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/rfc/0003-jdbc-and-json-contracts.md">RFC-0003
 *     — persistence and serialization contracts</a>
 */
package it.d4np.utils.json;
