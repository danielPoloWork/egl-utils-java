/**
 * Cryptography, JWT and output-encoding helpers (ADR-001, ADR-003, spec §3, §4).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces.
 *
 * <p>No {@code exports} yet: no production types exist, and exporting an empty package does not
 * compile. Milestone 6 adds {@code exports it.d4np.utils.security;} with the first types, gated on
 * RFC-0005 (item 6.0), which still has to close the FR-12 and NFR-11 gaps.
 *
 * <p>The {@code nimbus-jose-jwt} edge of ADR-003 and the JDK's {@code java.crypto} needs are not
 * declared in advance — they arrive with the code that uses them. Note for whoever adds them: the
 * jakarta-only policy of spec §1.1 is enforced as lint on <em>imports</em>, and a module descriptor
 * carries no imports, so {@code requires} clauses are governed by review and by ADR-001's rules,
 * not by that Checkstyle rule.
 */
module it.d4np.utils.security {
  requires it.d4np.utils;
  requires com.nimbusds.jose.jwt;
  requires java.net.http;

  exports it.d4np.utils.security;
}
