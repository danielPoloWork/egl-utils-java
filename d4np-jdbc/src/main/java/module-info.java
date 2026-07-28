/**
 * JDBC helpers built on the JDBC API alone — no driver dependency (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly, which is the invariant
 * {@code consistency_lint.py} enforces: an internal dependency added to one and not the other is a
 * build failure rather than a discrepancy someone notices later.
 *
 * <p>No {@code exports} yet — no production types exist, and exporting an empty package does not
 * compile. FR-05 ({@code SimpleJdbcExecutor}) and FR-06 ({@code JdbcTxRunner}) add {@code exports
 * it.d4np.utils.jdbc;} together with the code. The {@code java.sql} edge those types need is
 * deliberately not declared in advance: this project does not pin what nothing uses yet.
 */
module it.d4np.utils.jdbc {
  requires it.d4np.utils;
}
