/**
 * Programmatic JDBC over the JDBC API alone — no driver, and no framework (spec §3, FR-05..FR-07).
 *
 * <p><strong>This module has no third-party dependency at all.</strong> The JDBC API ships inside
 * the JDK as {@code java.sql}, so a consumer that wants parameterized statements and, later,
 * pagination takes exactly one JAR and no dependency tree. The H2 driver these types are tested and
 * benchmarked against is {@code test} scope and is reached through {@code DriverManager}, so no
 * type in this package or its tests names a driver class.
 *
 * <h2>Conventions that hold for every type in this package</h2>
 *
 * <ul>
 *   <li><strong>Parameterized statements only.</strong> No operation accepts pre-interpolated SQL
 *       and no {@link java.sql.Statement} is created anywhere in the module — the
 *       string-concatenation row of the threat model is defended by construction rather than by
 *       advice (FR-05).
 *   <li><strong>Non-null by default.</strong> Every parameter, return and field is non-null unless
 *       it carries {@link it.d4np.utils.Nullable}, checked by NullAway at {@code ERROR} severity on
 *       the JDK 21+ build cells (ADR-0009). The one deliberate exception is a bind parameter: an
 *       individual {@code null} in the {@code params} array binds SQL {@code NULL}, which is a
 *       value rather than an absence.
 *   <li><strong>No checked exceptions.</strong> JDBC's {@code SQLException} is checked; it is
 *       wrapped in {@link it.d4np.utils.jdbc.JdbcAccessException} at every boundary, which extends
 *       {@link java.lang.RuntimeException} directly and <em>not</em> {@code BusinessException} —
 *       FR-19 maps a data-access failure to <strong>500 plus an alert</strong> and {@code
 *       BusinessException} to <strong>422</strong>. The one method that <em>does</em> declare
 *       {@code SQLException} is {@link it.d4np.utils.jdbc.RowMapper#map}, which the caller
 *       implements rather than calls.
 *   <li><strong>No message carries SQL or a parameter value.</strong> An exception message from
 *       this package is built from a fixed operation label plus the driver's SQLState and vendor
 *       code; the driver's own message survives as the {@code cause} for the log, never for the
 *       client (compliance control C-01).
 *   <li><strong>Thread safety is documented per type</strong>, and per <em>factory</em> where the
 *       two differ — {@code SimpleJdbcExecutor.on(DataSource)} is thread-safe and {@code
 *       on(Connection)} is exactly as thread-safe as the connection it was handed.
 * </ul>
 *
 * <h2>What a consumer still owns</h2>
 *
 * <p>The connection pool, its credentials and its configuration are the host's — this library never
 * creates a {@code DataSource}, reads a URL or loads a driver. It also never chooses a dialect:
 * every statement it runs is the caller's own SQL, and the only statements it issues on its own
 * behalf arrive with FR-06's transaction runner (item 4.4), which is the first thing here to change
 * the meaning of SQL it did not write.
 *
 * @see <a
 *     href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/rfc/0003-jdbc-and-json-contracts.md">RFC-0003
 *     — persistence and serialization contracts</a>
 */
package it.d4np.utils.jdbc;
