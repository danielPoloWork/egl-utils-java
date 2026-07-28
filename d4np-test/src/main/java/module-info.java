/**
 * Test-support helpers, consumed at {@code test} scope only (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces.
 *
 * <p>No {@code exports} yet: no production types exist, and exporting an empty package does not
 * compile. Item 7.4 adds {@code exports it.d4np.utils.test;} together with {@code ReflectionUtils}.
 *
 * <h2>The {@code --add-opens} contract (FR-25, spec §2 item 25)</h2>
 *
 * <p>{@code ReflectionUtils} will call {@code setAccessible} on the types <em>under test</em>.
 * Since JDK 9 that throws {@link java.lang.reflect.InaccessibleObjectException} unless the package
 * being reflected into has been <em>opened</em>. The obligation therefore falls on the consumer,
 * and it is stated here because a module descriptor is the one place a reader will look for it:
 *
 * <ul>
 *   <li>tests of a <strong>named</strong> module must open the package under test to this module:
 *       {@code --add-opens consumer.module/consumer.pkg=it.d4np.utils.test}
 *   <li>tests running on the <strong>classpath</strong> (the unnamed module — still the common case
 *       under Surefire) must instead target {@code ALL-UNNAMED}: {@code --add-opens
 *       consumer.module/consumer.pkg=ALL-UNNAMED}
 * </ul>
 *
 * <p>Note the direction: it is the <em>consumer</em> that opens its packages to this module. Making
 * this module {@code open} would achieve nothing, because the restriction is never about reflecting
 * into {@code d4np-test} — a point worth recording, since "add {@code open}" is the intuitive and
 * wrong first fix.
 *
 * <p>Item 7.4 owns the other half of FR-25: when the flag is absent the helpers must fail with an
 * actionable message naming the exact flag, rather than surfacing a raw {@code
 * InaccessibleObjectException}. v1 of the specification ignored this constraint entirely.
 *
 * <p>Importing this module from production code is a policy violation, not a style preference; item
 * 1.7 makes {@code maven-enforcer} fail the build for it (FR-25, NFR-08).
 */
module it.d4np.utils.test {
  requires it.d4np.utils;
}
