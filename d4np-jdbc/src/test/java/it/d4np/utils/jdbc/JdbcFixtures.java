package it.d4np.utils.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The database FR-05's tests and NFR-03's benchmark run against — one file, so the schema two
 * harnesses measure against cannot drift into two schemas (core's {@code AuditFixtures} precedent).
 *
 * <p><strong>Not one H2 type is named here, and that is load-bearing rather than tidy.</strong>
 * Spec §3 says this module is "JDBC API only, no drivers", and a test that imported {@code
 * org.h2.*} would compile perfectly well — so the driver is reached through {@link DriverManager}
 * and a URL, which is the only form that cannot quietly become a dependency. It also means these
 * fixtures work against any database whose driver is on the test classpath.
 *
 * <p>Each caller gets its <strong>own</strong> in-memory database, named from a counter. H2 keeps a
 * {@code mem:} database alive only while a connection is open unless {@code DB_CLOSE_DELAY=-1} says
 * otherwise, and the tests here deliberately close every connection they take — so without the flag
 * the schema would vanish between two operations of the same test.
 */
final class JdbcFixtures {

  /** Distinguishes one test's database from another's; a shared name is a shared table. */
  private static final AtomicInteger NEXT = new AtomicInteger();

  private JdbcFixtures() {}

  /** The row shape every mapper here produces. */
  public record Order(int id, String sku, int quantity) {}

  /** The mapper FR-05's contract is stated against: read the current row, move no cursor. */
  static final RowMapper<Order> TO_ORDER =
      row -> new Order(row.getInt("id"), row.getString("sku"), row.getInt("quantity"));

  /**
   * A URL for a fresh, empty in-memory database.
   *
   * @return a JDBC URL nobody else in this JVM is using
   */
  static String freshUrl() {
    return "jdbc:h2:mem:fr05-" + NEXT.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
  }

  /**
   * Creates the {@code orders} table, through the executor rather than around it.
   *
   * <p>Using {@link SimpleJdbcExecutor#update} for the DDL is deliberate: it is the only way to
   * create a table here at all — no operation in this module accepts a plain {@code Statement} —
   * and it doubles as the assertion that a parameterless statement still goes through a {@code
   * PreparedStatement}.
   *
   * <p>{@code sku} is {@code unique} so a test can provoke a real integrity-constraint failure, and
   * {@code note} is nullable so a test can bind SQL {@code NULL}.
   *
   * @param executor the executor to create it with
   */
  static void createSchema(SimpleJdbcExecutor executor) {
    executor.update(
        "create table orders ("
            + " id int primary key,"
            + " sku varchar(64) not null unique,"
            + " quantity int not null,"
            + " note varchar(64))");
  }

  /**
   * Opens one connection to {@code url}.
   *
   * @param url the database to connect to
   * @return an open connection the caller closes
   * @throws SQLException if the driver cannot connect
   */
  static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url);
  }
}
