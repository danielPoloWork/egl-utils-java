package it.d4np.utils.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-05 (RFC-0003): the contract of {@link SimpleJdbcExecutor}.
 *
 * <p><strong>Three claims here could pass a test that had quietly stopped testing them, so each is
 * paired.</strong> "The connection is closed" is asserted by asking the <em>driver</em> whether it
 * is closed, not by trusting a {@code finally}. "No plain {@code Statement} is ever created" is
 * asserted by running every operation through a {@link Proxy} that fails the test if {@code
 * createStatement} is called, rather than by reading the source. And "no message carries the SQL or
 * a parameter" sits beside a companion showing the driver's <em>own</em> message carrying both, on
 * the same failure — without it the assertion would survive a driver that had merely become tidy.
 *
 * <p>The database is real (H2, in memory) rather than mocked, because every property under test
 * here is a property of talking to one.
 */
@DisplayName("SimpleJdbcExecutor")
class SimpleJdbcExecutorTest {

  /** The value that must reach no message this library produces (control C-01). */
  private static final String SECRET = "hunter2";

  private static final String INSERT =
      "insert into orders (id, sku, quantity, note) values (?, ?, ?, ?)";

  private String url;
  private CountingDataSource dataSource;
  private SimpleJdbcExecutor executor;

  @BeforeEach
  void createSchema() {
    url = JdbcFixtures.freshUrl();
    dataSource = new CountingDataSource(url);
    executor = SimpleJdbcExecutor.on(dataSource);
    JdbcFixtures.createSchema(executor);
  }

  @Nested
  @DisplayName("connection lifecycle")
  class Lifecycle {

    /** FR-05's try-with-resources promise, asked of the driver rather than of the source. */
    @Test
    void takesOneConnectionPerOperationAndClosesIt() throws SQLException {
      int afterSchema = dataSource.handedOut();

      executor.update(INSERT, 1, "A-1", 2, null);
      executor.query("select * from orders", JdbcFixtures.TO_ORDER);
      executor.queryOne("select * from orders where id = ?", JdbcFixtures.TO_ORDER, 1);

      assertThat(dataSource.handedOut()).isEqualTo(afterSchema + 3);
      assertThat(dataSource.allHandedOutAreClosed()).isTrue();
    }

    /** The connection is closed on the failure path too, which is where a leak would start. */
    @Test
    void closesTheConnectionWhenTheStatementFails() throws SQLException {
      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.query("select * from no_such_table", JdbcFixtures.TO_ORDER));

      assertThat(dataSource.allHandedOutAreClosed()).isTrue();
    }

    /**
     * The borrowed form never closes what it was handed — the property FR-06's transaction runner
     * depends on, since a closed connection mid-transaction loses the work (item 4.4).
     */
    @Test
    void neverClosesABorrowedConnection() throws SQLException {
      try (Connection borrowed = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor borrowing = SimpleJdbcExecutor.on(borrowed);

        borrowing.update(INSERT, 1, "A-1", 2, null);
        borrowing.query("select * from orders", JdbcFixtures.TO_ORDER);

        assertThat(borrowed.isClosed()).isFalse();
      }
    }

    /**
     * Two operations on one borrowed connection is one connection, not two — the property that
     * makes an executor inside a transaction run <em>in</em> that transaction.
     */
    @Test
    void reusesTheBorrowedConnectionForEveryOperation() throws SQLException {
      try (Connection borrowed = JdbcFixtures.connect(url)) {
        int beforeBorrowing = dataSource.handedOut();
        SimpleJdbcExecutor borrowing = SimpleJdbcExecutor.on(borrowed);

        borrowing.update(INSERT, 1, "A-1", 2, null);
        borrowing.update(INSERT, 2, "A-2", 1, null);

        assertThat(dataSource.handedOut()).isEqualTo(beforeBorrowing);
      }
    }

    /**
     * Control <strong>C-02</strong> at the one place a pool can break it: a {@code DataSource} that
     * answers with {@code null} would otherwise surface as a {@code NullPointerException} inside
     * this library, naming nothing useful.
     */
    @Test
    void refusesADataSourceThatHandsBackNoConnection() {
      SimpleJdbcExecutor overNothing = SimpleJdbcExecutor.on(handsBackNoConnection());

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> overNothing.query("select * from orders", JdbcFixtures.TO_ORDER))
          .withMessageContaining("the DataSource returned no connection")
          .satisfies(thrown -> assertThat(thrown.getCause()).isNull());
    }
  }

  @Nested
  @DisplayName("parameterized statements only")
  class Parameterized {

    /**
     * FR-05's headline, and the threat model's <em>SQL altered via string concatenation</em> row.
     * The payload is a classic terminator-and-comment injection; through a bind parameter it is a
     * string, and the table it names is still there afterwards.
     */
    @Test
    void treatsAnInjectionPayloadAsData() {
      String payload = "'; drop table orders; --";

      executor.update(INSERT, 1, payload, 1, null);

      List<JdbcFixtures.Order> found =
          executor.query("select * from orders where sku = ?", JdbcFixtures.TO_ORDER, payload);
      assertThat(found).singleElement().extracting(JdbcFixtures.Order::sku).isEqualTo(payload);
      assertThat(executor.query("select * from orders", JdbcFixtures.TO_ORDER)).hasSize(1);
    }

    /**
     * The structural half, asserted by <em>running</em> rather than by reading: every operation
     * goes through a connection that fails the test the moment {@code createStatement} is called. A
     * concatenating overload added later would have to reach for it.
     */
    @Test
    void neverCreatesAPlainStatement() throws SQLException {
      List<String> calls = new ArrayList<>();
      try (Connection real = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor watched = SimpleJdbcExecutor.on(refusingCreateStatement(real, calls));

        watched.update(INSERT, 1, "A-1", 2, null);
        watched.query("select * from orders", JdbcFixtures.TO_ORDER);
        watched.queryOne("select * from orders where id = ?", JdbcFixtures.TO_ORDER, 1);

        assertThat(calls).contains("prepareStatement").doesNotContain("createStatement");
      }
    }

    /** A parameterless statement is still prepared — there is no other path through this class. */
    @Test
    void preparesEvenAStatementWithNoParameters() throws SQLException {
      List<String> calls = new ArrayList<>();
      try (Connection real = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor watched = SimpleJdbcExecutor.on(refusingCreateStatement(real, calls));

        watched.query("select * from orders", JdbcFixtures.TO_ORDER);

        assertThat(calls).contains("prepareStatement").doesNotContain("createStatement");
      }
    }

    /** A {@code null} parameter is a value — SQL {@code NULL} — and not an absent argument. */
    @Test
    void bindsANullParameterAsSqlNull() {
      executor.update(INSERT, 1, "A-1", 2, null);
      executor.update(INSERT, 2, "A-2", 1, "shipped");

      assertThat(executor.query("select * from orders where note is null", JdbcFixtures.TO_ORDER))
          .singleElement()
          .extracting(JdbcFixtures.Order::sku)
          .isEqualTo("A-1");
    }
  }

  @Nested
  @DisplayName("query")
  class Query {

    @Test
    void mapsEveryRowInResultSetOrder() {
      executor.update(INSERT, 1, "A-1", 2, null);
      executor.update(INSERT, 2, "A-2", 1, null);

      assertThat(executor.query("select * from orders order by id", JdbcFixtures.TO_ORDER))
          .containsExactly(
              new JdbcFixtures.Order(1, "A-1", 2), new JdbcFixtures.Order(2, "A-2", 1));
    }

    @Test
    void answersWithAnEmptyListWhenNothingMatches() {
      assertThat(executor.query("select * from orders where id = ?", JdbcFixtures.TO_ORDER, 99))
          .isEmpty();
    }

    @Test
    void answersWithAnUnmodifiableList() {
      executor.update(INSERT, 1, "A-1", 2, null);

      List<JdbcFixtures.Order> rows = executor.query("select * from orders", JdbcFixtures.TO_ORDER);

      assertThatThrownBy(() -> rows.add(new JdbcFixtures.Order(2, "A-2", 1)))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Control <strong>C-02</strong>: a {@code null} in the returned list would fail at the caller's
     * next dereference. {@link IllegalStateException} rather than {@code JdbcAccessException},
     * because nothing about the database failed — the same shape {@code Lazy} gives an initializer
     * that returns {@code null}.
     */
    @Test
    @SuppressWarnings("NullAway") // asserting the contract requires a mapper that breaks it
    void refusesARowMapperThatReturnsNull() {
      executor.update(INSERT, 1, "A-1", 2, null);

      assertThatIllegalStateException()
          .isThrownBy(() -> executor.query("select * from orders", row -> null))
          .withMessageContaining("must never be null");
    }
  }

  @Nested
  @DisplayName("queryOne")
  class QueryOne {

    @Test
    void answersWithTheOnlyRow() {
      executor.update(INSERT, 1, "A-1", 2, null);

      assertThat(executor.queryOne("select * from orders where id = ?", JdbcFixtures.TO_ORDER, 1))
          .contains(new JdbcFixtures.Order(1, "A-1", 2));
    }

    /** Absence is an ordinary answer, so it is an empty {@code Optional} and not an exception. */
    @Test
    void answersWithEmptyWhenNothingMatches() {
      assertThat(executor.queryOne("select * from orders where id = ?", JdbcFixtures.TO_ORDER, 99))
          .isEmpty();
    }

    /**
     * The decision this operation exists to make. Returning the first row would answer a question
     * the caller did not ask, and it would look right forever: a duplicate in a column the schema
     * was meant to keep unique becomes an application reading one of two records at random.
     */
    @Test
    void refusesASecondRow() {
      executor.update(INSERT, 1, "A-1", 2, null);
      executor.update(INSERT, 2, "A-2", 1, null);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.queryOne("select * from orders", JdbcFixtures.TO_ORDER))
          .withMessageContaining("matched more than one row");
    }

    /**
     * A fault this library detected reports no driver codes, and the three signals agree — which is
     * how a consumer tells "the driver said 23505" from "we decided". Fabricating the standard's
     * own cardinality-violation SQLState would have made them indistinguishable.
     */
    @Test
    void reportsNoDriverCodeForAFaultItDetectedItself() {
      executor.update(INSERT, 1, "A-1", 2, null);
      executor.update(INSERT, 2, "A-2", 1, null);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.queryOne("select * from orders", JdbcFixtures.TO_ORDER))
          .satisfies(
              thrown -> {
                assertThat(thrown.sqlState()).isEmpty();
                assertThat(thrown.vendorCode()).isZero();
                assertThat(thrown.getCause()).isNull();
              });
    }
  }

  @Nested
  @DisplayName("update")
  class Update {

    @Test
    void answersWithTheAffectedRowCount() {
      executor.update(INSERT, 1, "A-1", 2, null);
      executor.update(INSERT, 2, "A-2", 1, null);

      assertThat(executor.update("update orders set quantity = ? where quantity < ?", 9, 2))
          .isEqualTo(1);
      assertThat(executor.update("delete from orders where id > ?", 0)).isEqualTo(2);
    }

    /**
     * Affecting nothing is an answer, not a failure — a delete that matches no row returns zero.
     *
     * <p>The DDL case is covered by every test in this class: {@code JdbcFixtures.createSchema}
     * runs its {@code create table} through this same method, which is the only way to create a
     * table here at all.
     */
    @Test
    void answersWithZeroForAStatementThatAffectsNothing() {
      assertThat(executor.update("delete from orders where id = ?", 99)).isZero();
    }
  }

  @Nested
  @DisplayName("errors")
  class Errors {

    /**
     * Control <strong>C-01</strong>. The failing statement holds a secret in a bound parameter and
     * violates a unique constraint, which is exactly when a driver wants to quote both the value
     * and the statement.
     */
    @Test
    void noMessageCarriesTheSqlOrAParameter() {
      executor.update(INSERT, 1, SECRET, 2, null);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.update(INSERT, 2, SECRET, 3, null))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage())
                    .doesNotContain(SECRET)
                    .doesNotContain("insert into")
                    .doesNotContain("orders")
                    .contains("cannot run an update")
                    .contains("SQLState");
                assertThat(thrown.toString()).doesNotContain(SECRET).doesNotContain("insert into");
              });
    }

    /**
     * The companion that keeps the assertion above honest: the driver's own message — kept as the
     * cause, for the log — carries both the parameter and the statement. So the redaction is ours,
     * and the cause chain is <strong>not</strong> safe to render into an RFC 7807 body, which is
     * the rule item 7.1 inherits from item 4.1 and now from here too.
     */
    @Test
    void theDriversOwnMessageStillCarriesBoth() {
      executor.update(INSERT, 1, SECRET, 2, null);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.update(INSERT, 2, SECRET, 3, null))
          .satisfies(
              thrown -> {
                assertThat(thrown.getCause()).isInstanceOf(SQLException.class);
                assertThat(String.valueOf(thrown.getCause())).contains(SECRET).contains("insert");
              });
    }

    /**
     * The two codes are what turns "the database said no" into a diagnosis, and they are the only
     * thing read off the driver's exception. {@code 23} is the standard's integrity-constraint
     * class; H2 reports {@code 23505} for a unique violation.
     */
    @Test
    void reportsTheDriversSqlStateAndVendorCode() {
      executor.update(INSERT, 1, "A-1", 2, null);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.update(INSERT, 2, "A-1", 3, null))
          .satisfies(
              thrown -> {
                assertThat(thrown.sqlState()).startsWith("23");
                assertThat(thrown.vendorCode()).isNotZero();
                assertThat(thrown.getMessage()).contains(thrown.sqlState());
              });
    }

    /** A mapper's own {@code SQLException} is translated like any other — one failure shape. */
    @Test
    void translatesAFailureRaisedInsideTheRowMapper() {
      executor.update(INSERT, 1, "A-1", 2, null);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(() -> executor.query("select * from orders", row -> row.getInt("no_column")))
          .withMessageContaining("cannot run a query")
          .satisfies(thrown -> assertThat(thrown.getCause()).isInstanceOf(SQLException.class));
    }

    @Test
    void rejectsBlankSql() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> executor.query("   ", JdbcFixtures.TO_ORDER))
          .withMessageContaining("sql must not be blank");
      assertThatIllegalArgumentException().isThrownBy(() -> executor.update(""));
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNullArguments() {
      assertThatNullPointerException().isThrownBy(() -> SimpleJdbcExecutor.on((DataSource) null));
      assertThatNullPointerException().isThrownBy(() -> SimpleJdbcExecutor.on((Connection) null));
      assertThatNullPointerException()
          .isThrownBy(() -> executor.query(null, JdbcFixtures.TO_ORDER));
      assertThatNullPointerException().isThrownBy(() -> executor.query("select 1", null));
      assertThatNullPointerException()
          .isThrownBy(() -> executor.query("select 1", JdbcFixtures.TO_ORDER, (Object[]) null));
      assertThatNullPointerException()
          .isThrownBy(() -> executor.queryOne(null, JdbcFixtures.TO_ORDER));
      assertThatNullPointerException().isThrownBy(() -> executor.queryOne("select 1", null));
      assertThatNullPointerException().isThrownBy(() -> executor.update(null));
      assertThatNullPointerException()
          .isThrownBy(() -> executor.update("select 1", (Object[]) null));
    }
  }

  /**
   * The structural guarantee, over the reflected surface: <strong>no published method offers a way
   * to run SQL without a parameter slot</strong>, and no signature mentions a {@link Statement}.
   * Asserted here rather than trusted to review, because a convenience overload added later would
   * compile and pass every other test in this file.
   */
  @Test
  void publishesNoWayToRunUnparameterizedSql() {
    for (Method method : SimpleJdbcExecutor.class.getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      boolean takesSql = parameters.length > 0 && parameters[0] == String.class;
      if (takesSql) {
        assertThat(parameters[parameters.length - 1])
            .as("public method %s takes SQL but no parameter array", method.getName())
            .isEqualTo(Object[].class);
      }
      assertThat(Statement.class.isAssignableFrom(method.getReturnType()))
          .as("public method %s returns a Statement", method.getName())
          .isFalse();
      for (Class<?> parameter : parameters) {
        assertThat(Statement.class.isAssignableFrom(parameter))
            .as("public method %s accepts a Statement", method.getName())
            .isFalse();
      }
    }
    assertThat(SimpleJdbcExecutor.class.getConstructors()).isEmpty();
  }

  /**
   * A connection that records what was asked of it and refuses to create a plain statement.
   *
   * @param real the connection that does the work
   * @param calls the log this appends every method name to
   * @return a proxy that delegates everything except {@code createStatement}
   */
  private static Connection refusingCreateStatement(Connection real, List<String> calls) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              calls.add(method.getName());
              if ("createStatement".equals(method.getName())) {
                throw new AssertionError(
                    "SimpleJdbcExecutor created a plain Statement, which FR-05 forbids");
              }
              try {
                return method.invoke(real, args);
              } catch (InvocationTargetException raised) {
                throw raised.getCause();
              }
            });
  }

  /**
   * A {@code DataSource} that answers {@link DataSource#getConnection()} with {@code null} — a
   * broken pool, which JDBC's own contract forbids and which nothing stops at run time.
   *
   * @return the broken data source
   */
  @SuppressWarnings("NullAway") // the whole point of this fixture is the null it returns
  private static DataSource handsBackNoConnection() {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("getConnection".equals(method.getName())) {
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
