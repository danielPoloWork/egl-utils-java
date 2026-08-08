package it.d4np.utils.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The benchmark NFR-03 is stated against — ROADMAP item 4.3.
 *
 * <p><strong>The budget.</strong> NFR-03 puts {@link SimpleJdbcExecutor}'s row-mapping overhead at
 * <strong>≤ 10%</strong> against a hand-written {@link ResultSet} loop, over <strong>10 000
 * rows</strong> of an in-memory H2 database, and all three parts are reproduced literally: {@link
 * Rows#COUNT} rows are inserted once per trial, both arms read every one of them, and the database
 * is H2 in memory.
 *
 * <p><strong>This is the one performance budget in the project that could be a real CI gate today,
 * and the reason is not that it is looser.</strong> NFR-01's 2 ns/op and NFR-06's 400 MB/s are
 * <em>absolute</em> numbers against a named reference machine, so a hosted runner that is 30%
 * slower fails them while measuring nothing wrong — which is the problem ROADMAP item 8.3 exists to
 * solve. NFR-03 is a <strong>relative</strong> comparison between two arms measured in the same JMH
 * invocation, on the same machine, against the same database, in the same JVM generation. A slow
 * runner slows both arms, and the ratio is unmoved. A reader who has met item 8.3 will assume this
 * budget shares its problem; it does not.
 *
 * <p>What it does share is the sample size. CI runs one fork and one iteration, which proves the
 * harness executes and lands in the right range — <em>not</em> a ratio stable enough to fail a
 * build on. Item 8.8 owns turning this into a gate, and what it needs is fork and iteration counts,
 * not a reference machine.
 *
 * <h2>The three arms, and which two are the budget</h2>
 *
 * <ul>
 *   <li>{@link #handWrittenLoop} — prepare, execute, iterate, construct. The floor the budget is
 *       made of, and deliberately the <em>fast</em> version of it: it returns the mutable {@code
 *       ArrayList} it filled, where the executor returns an unmodifiable copy.
 *   <li>{@link #executorQuery} — the same work through {@code SimpleJdbcExecutor.on(Connection)}.
 *       <strong>These two are NFR-03.</strong>
 *   <li>{@link #executorQueryFromDataSource} — the same again through {@code on(DataSource)}, which
 *       additionally opens and closes a connection. <strong>Not part of the budget</strong>, and it
 *       is here because leaving it out would invite the reader to assume the measured arm includes
 *       pool acquisition. It does not, and NFR-03 does not ask it to: the requirement is about row
 *       mapping.
 * </ul>
 *
 * <p>Both budget arms borrow the <em>same</em> connection, so neither pays for acquiring one and
 * the comparison is about statement preparation, parameter binding, result iteration and mapping —
 * the framing. That framing is the whole of what the executor adds, because a {@code RowMapper}
 * lambda over the same {@code ResultSet} <em>is</em> the hand-written loop's body plus one virtual
 * call. Per-row reflection would have spent the budget before the framing was measured, which is
 * the third of RFC-0003's reasons for a caller-supplied mapper.
 *
 * <p>Every arm returns its list so JMH consumes it and dead-code elimination cannot delete the work
 * — an unread {@code ResultSet} loop is exactly the shape a JIT can prove pointless.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RowMappingBenchmark {

  /** The 10 000-row database NFR-03 names, opened once per trial. */
  // JMH constructs a @State with its no-argument constructor and populates it through @Setup, which
  // NullAway cannot see — so every field here is genuinely non-null by the time a @Benchmark runs
  // and genuinely uninitialized at construction. `NullAway.Init` is the annotation for exactly that
  // framework-initialization shape; core's benchmarks avoided needing it only because their state
  // was cheap enough to build inline, which a database connection is not.
  @SuppressWarnings("NullAway.Init")
  @State(Scope.Benchmark)
  public static class Rows {

    /** The scale NFR-03 names. */
    static final int COUNT = 10_000;

    static final String SELECT = "select id, sku, quantity from orders where quantity > ?";

    /**
     * Held open for the whole trial. Both budget arms run on this one connection, so neither pays
     * for acquiring one and H2 keeps the {@code mem:} database alive without a close delay.
     */
    Connection connection;

    javax.sql.DataSource dataSource;

    SimpleJdbcExecutor borrowing;

    SimpleJdbcExecutor pooling;

    /**
     * Creates the schema and inserts {@link #COUNT} rows.
     *
     * @throws SQLException if H2 cannot be reached
     */
    @Setup(Level.Trial)
    public void fill() throws SQLException {
      String url = "jdbc:h2:mem:nfr03-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
      connection = DriverManager.getConnection(url);
      dataSource = new BenchmarkDataSource(url);
      borrowing = SimpleJdbcExecutor.on(connection);
      pooling = SimpleJdbcExecutor.on(dataSource);

      borrowing.update(
          "create table orders (id int primary key, sku varchar(64) not null, quantity int not null)");
      for (int id = 0; id < COUNT; id++) {
        // Inserted through the executor for one reason only: nothing in this module can create a
        // plain Statement, so there is no other way to populate the table.
        borrowing.update(
            "insert into orders (id, sku, quantity) values (?, ?, ?)", id, "SKU-" + id, id + 1);
      }
    }

    /**
     * Closes the trial's connection, which is what lets H2 discard the database.
     *
     * @throws SQLException if the close fails
     */
    @TearDown(Level.Trial)
    public void close() throws SQLException {
      connection.close();
    }
  }

  /**
   * The floor: a hand-written {@code ResultSet} loop over a borrowed connection.
   *
   * @param rows the filled database
   * @return the mapped rows, returned so JMH consumes them
   * @throws SQLException if H2 fails
   */
  @Benchmark
  public List<Order> handWrittenLoop(Rows rows) throws SQLException {
    try (PreparedStatement statement = rows.connection.prepareStatement(Rows.SELECT)) {
      statement.setInt(1, 0);
      try (ResultSet result = statement.executeQuery()) {
        List<Order> mapped = new ArrayList<>();
        while (result.next()) {
          mapped.add(
              new Order(result.getInt("id"), result.getString("sku"), result.getInt("quantity")));
        }
        return mapped;
      }
    }
  }

  /**
   * The NFR-03 measurement: the same work through the executor, on the same connection.
   *
   * @param rows the filled database
   * @return the mapped rows, returned so JMH consumes them
   */
  @Benchmark
  public List<Order> executorQuery(Rows rows) {
    return rows.borrowing.query(Rows.SELECT, RowMappingBenchmark::toOrder, 0);
  }

  /**
   * The same query through a {@code DataSource}-backed executor, which opens and closes a
   * connection per call. Informational; see the class documentation for why it is not the budget.
   *
   * @param rows the filled database
   * @return the mapped rows, returned so JMH consumes them
   */
  @Benchmark
  public List<Order> executorQueryFromDataSource(Rows rows) {
    return rows.pooling.query(Rows.SELECT, RowMappingBenchmark::toOrder, 0);
  }

  /**
   * The mapper both executor arms use — the hand-written loop's body, extracted.
   *
   * @param row the result set, positioned on a row
   * @return the mapped row
   * @throws SQLException if a column read fails
   */
  private static Order toOrder(ResultSet row) throws SQLException {
    return new Order(row.getInt("id"), row.getString("sku"), row.getInt("quantity"));
  }

  /**
   * What a row becomes; identical in both arms, so neither is measuring a different allocation.
   *
   * @param id the primary key
   * @param sku the indexed text column, so the mapping reads a {@code String} as well as an int
   * @param quantity the second int, which the {@code where} clause also filters on
   */
  public record Order(int id, String sku, int quantity) {}

  /**
   * A {@code DataSource} over {@code DriverManager}, so the informational arm measures opening a
   * real connection rather than a pool's book-keeping.
   *
   * <p>Hand-written for the reason the tests are: no type in this module, at any scope, names an H2
   * class.
   */
  private static final class BenchmarkDataSource implements javax.sql.DataSource {

    private final String url;

    BenchmarkDataSource(String url) {
      this.url = url;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws SQLException {
      throw new java.sql.SQLFeatureNotSupportedException();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws SQLException {
      throw new java.sql.SQLFeatureNotSupportedException();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      throw new java.sql.SQLFeatureNotSupportedException();
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger()
        throws java.sql.SQLFeatureNotSupportedException {
      throw new java.sql.SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new java.sql.SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return false;
    }
  }
}
