/**
 * JDBC helpers built on the JDBC API alone — no driver dependency (ADR-001, spec §3).
 *
 * <p>The internal {@code requires} edge below mirrors this module's POM exactly, which is the
 * invariant {@code consistency_lint.py} enforces: an internal dependency added to one and not the
 * other is a build failure rather than a discrepancy someone notices later.
 *
 * <p><strong>The {@code exports} clause arrived with the first types, in ROADMAP item 4.3</strong>
 * ({@code SimpleJdbcExecutor}, {@code RowMapper} and {@code JdbcAccessException}, FR-05). Until
 * then the descriptor deliberately exported nothing, because {@code exports} of a package that
 * holds no class is a compile error rather than a forward declaration. Items 4.4 and 4.5 add
 * FR-06's and FR-07's types to this same package and need no further clause.
 *
 * <p><strong>The {@code java.sql} edge is {@code transitive}, and it is the only such edge in this
 * repository</strong> — RFC-0003 §Consequences settled the direction and the reason is a property
 * of the API rather than a preference. {@code d4np-json} keeps every Jackson edge
 * <em>non</em>-transitive because a consumer only names a Jackson type when it builds a {@code
 * Module} (ADR-0024); here the consumer <strong>implements</strong> {@code RowMapper}, whose single
 * method takes a {@code java.sql.ResultSet} and throws a {@code java.sql.SQLException}. A consumer
 * cannot write that lambda without naming both, so a non-transitive edge would make every consumer
 * declare {@code requires java.sql} to use the module at all — ceremony for a JDK module that costs
 * nothing to re-export and that no future release will move out from under us.
 *
 * <p><strong>No driver appears here at any scope a consumer resolves.</strong> The JDBC API lives
 * in {@code java.sql}, which ships with the JDK, so this module's only third-party artifact is the
 * H2 driver its tests and its NFR-03 benchmark run against — {@code test} scope, absent from this
 * descriptor, and reached through {@code DriverManager} rather than by naming a single H2 type.
 */
module it.d4np.utils.jdbc {
  requires it.d4np.utils;
  requires transitive java.sql;

  exports it.d4np.utils.jdbc;
}
