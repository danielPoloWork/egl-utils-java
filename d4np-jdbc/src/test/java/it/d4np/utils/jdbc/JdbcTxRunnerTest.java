package it.d4np.utils.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.d4np.utils.ErrorDetail;
import it.d4np.utils.Result;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-06 (RFC-0003): the contract of {@link JdbcTxRunner}.
 *
 * <p><strong>Most of what FR-06 promises is only observable while it happens</strong>, which is why
 * this suite leans on {@link ScriptedConnection} rather than on the database alone. "Auto-commit is
 * restored <em>before</em> the connection is closed" cannot be checked afterwards — the connection
 * is gone. "A rollback that itself fails is suppressed onto the original" needs a rollback that
 * fails, and a real database will not oblige. So the calls themselves are the evidence, and the
 * database underneath stays real so the commits and rollbacks are real too.
 *
 * <p>The one claim that could pass a test that had stopped testing it is the C-01 rule about log
 * lines, so it is asserted against a body whose exception message carries a secret.
 */
@DisplayName("JdbcTxRunner")
class JdbcTxRunnerTest {

  /** The value that must reach no log line this library writes (control C-01). */
  private static final String SECRET = "hunter2";

  private static final String INSERT =
      "insert into orders (id, sku, quantity, note) values (?, ?, ?, ?)";

  private String url;
  private CountingDataSource dataSource;
  private SimpleJdbcExecutor executor;
  private TxLogRecorder log;
  private JdbcTxRunner runner;

  @BeforeEach
  void createSchema() {
    url = JdbcFixtures.freshUrl();
    dataSource = new CountingDataSource(url);
    executor = SimpleJdbcExecutor.on(dataSource);
    JdbcFixtures.createSchema(executor);
    log = new TxLogRecorder();
    runner = JdbcTxRunner.on(dataSource, TxIsolation.DEFAULT, log);
  }

  /** How many rows the table holds, read outside any transaction. */
  private int rows() {
    return executor.query("select * from orders", JdbcFixtures.TO_ORDER).size();
  }

  @Nested
  @DisplayName("commit and rollback")
  class CommitAndRollback {

    @Test
    void commitsWhatTheBodyDid() {
      runner.inTransactionWithoutResult(
          connection -> SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null));

      assertThat(rows()).isEqualTo(1);
    }

    @Test
    void returnsTheBodysValue() {
      String sku =
          runner.inTransaction(
              connection -> {
                SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null);
                return "A-1";
              });

      assertThat(sku).isEqualTo("A-1");
      assertThat(rows()).isEqualTo(1);
    }

    @Test
    void rollsBackWhenTheBodyThrows() {
      RuntimeException boom = new IllegalArgumentException("boom");

      assertThatThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null);
                        throw boom;
                      }))
          .isSameAs(boom);

      assertThat(rows()).isZero();
    }

    /**
     * RFC-0003 is explicit that an {@link Error} rolls back too: leaving a transaction open on an
     * {@code OutOfMemoryError} holds database locks until the connection is reaped. It only looks
     * like a contradiction of ADR-0021's bounded {@code RuntimeException | LinkageError} catch —
     * that rule is about <em>swallowing</em>, and this is cleanup followed by propagation.
     */
    @Test
    void rollsBackWhenTheBodyThrowsAnError() {
      LinkageError boom = new LinkageError("boom");

      assertThatThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null);
                        throw boom;
                      }))
          .isSameAs(boom);

      assertThat(rows()).isZero();
    }

    /**
     * <strong>The decision most likely to be questioned, so it is pinned rather than
     * described.</strong> The exception channel demarcates the transaction; the value channel does
     * not. A body that must roll back on a business rule throws — FR-18 names {@code
     * BusinessException} for exactly that.
     */
    @Test
    void commitsWhenTheBodyReturnsResultErr() {
      Result<String> answer =
          runner.inTransaction(
              connection -> {
                SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null);
                return Result.<String>err(new ErrorDetail("RULE_BROKEN", "the rule was broken"));
              });

      assertThat(answer).isInstanceOf(Result.Err.class);
      assertThat(rows()).as("Err is a value, not a rollback signal").isEqualTo(1);
    }

    /**
     * Control <strong>C-02</strong>, and the reason {@link TxVoidCallback} exists at all. The
     * rollback matters as much as the refusal: committing work whose result we then reject would be
     * the worst of both.
     */
    @Test
    @SuppressWarnings("NullAway") // asserting the contract requires a body that breaks it
    void rollsBackAndRefusesANullReturn() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  runner.inTransaction(
                      connection -> {
                        SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null);
                        return null;
                      }))
          .withMessageContaining("inTransactionWithoutResult");

      assertThat(rows()).isZero();
    }
  }

  @Nested
  @DisplayName("what reaches the caller")
  class Translation {

    @Test
    void propagatesARuntimeExceptionUnchanged() {
      RuntimeException boom = new IllegalStateException("boom");

      assertThatThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        throw boom;
                      }))
          .isSameAs(boom);
    }

    /** A {@link SQLException} out of the body is the module's own failure shape, as everywhere. */
    @Test
    void translatesASqlExceptionFromTheBody() {
      SQLException raised = new SQLException("driver said no", "23505", 23505);

      assertThatExceptionOfType(JdbcAccessException.class)
          .isThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        throw raised;
                      }))
          .satisfies(
              thrown -> {
                assertThat(thrown.getCause()).isSameAs(raised);
                assertThat(thrown.sqlState()).isEqualTo("23505");
                assertThat(thrown.getMessage()).doesNotContain("driver said no");
              });
    }

    /**
     * Any other checked exception travels in the JDK's own name for this situation, rather than in
     * a type this library would have had to mint for a rare case (ADR-0030).
     */
    @Test
    void wrapsAnyOtherCheckedExceptionUndeclared() {
      IOException raised = new IOException("the file was not there");

      assertThatExceptionOfType(UndeclaredThrowableException.class)
          .isThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        throw raised;
                      }))
          .satisfies(thrown -> assertThat(thrown.getUndeclaredThrowable()).isSameAs(raised));
    }
  }

  @Nested
  @DisplayName("isolation and auto-commit")
  class Restoration {

    @Test
    void appliesTheRequestedIsolationInsideTheTransaction() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real);
        JdbcTxRunner serializable =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.SERIALIZABLE, log);

        int inside = serializable.inTransaction(connection -> connection.getTransactionIsolation());

        assertThat(inside).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
      }
    }

    /**
     * The ordering is the claim: both values go back <em>before</em> the connection does. A pooled
     * connection returned at {@code autoCommit=false} makes the next borrower's statements sit
     * uncommitted until something else commits or it is reaped — data loss attributable to nobody.
     */
    @Test
    void restoresBothBeforeClosing() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real);
        JdbcTxRunner serializable =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.SERIALIZABLE, log);

        serializable.inTransactionWithoutResult(connection -> {});

        List<String> calls = scripted.calls();
        assertThat(calls)
            .containsSubsequence(
                "setAutoCommit(false)",
                "setTransactionIsolation(" + Connection.TRANSACTION_SERIALIZABLE + ")",
                "commit",
                "setTransactionIsolation(" + Connection.TRANSACTION_READ_COMMITTED + ")",
                "setAutoCommit(true)",
                "close");
      }
    }

    /** {@link TxIsolation#DEFAULT} means untouched — not "read committed". */
    @Test
    void neverTouchesTheIsolationLevelAtDefault() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real);
        JdbcTxRunner byDefault =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.DEFAULT, log);

        byDefault.inTransactionWithoutResult(connection -> {});

        assertThat(scripted.called("setTransactionIsolation")).isFalse();
        assertThat(scripted.called("getTransactionIsolation")).isFalse();
      }
    }

    @Test
    void restoresAutoCommitEvenWhenTheBodyThrew() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real);
        JdbcTxRunner over =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.DEFAULT, log);

        assertThatThrownBy(
                () ->
                    over.inTransactionWithoutResult(
                        connection -> {
                          throw new IllegalStateException("boom");
                        }))
            .isInstanceOf(IllegalStateException.class);

        assertThat(scripted.calls())
            .containsSubsequence("rollback", "setAutoCommit(true)", "close");
      }
    }
  }

  @Nested
  @DisplayName("failure while failing")
  class FailureWhileFailing {

    /**
     * RFC-0003's second row. The original is the diagnosis and the rollback failure the
     * consequence; replacing it would point the on-call engineer at the wrong system. This is what
     * try-with-resources does, so Java programmers already read it correctly.
     */
    @Test
    void suppressesAFailedRollbackOntoTheOriginal() throws SQLException {
      RuntimeException boom = new IllegalStateException("boom");
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real).failingOn("rollback");
        JdbcTxRunner over =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.DEFAULT, log);

        assertThatThrownBy(
                () ->
                    over.inTransactionWithoutResult(
                        connection -> {
                          throw boom;
                        }))
            .isSameAs(boom)
            .satisfies(
                thrown ->
                    assertThat(thrown.getSuppressed())
                        .singleElement()
                        .isInstanceOf(SQLException.class));

        assertThat(log.messages())
            .anySatisfy(line -> assertThat(line).startsWith("WARNING").contains("rollback FAILED"));
      }
    }

    /** RFC-0003's third row: nothing was applied, and the failure is this module's own shape. */
    @Test
    void reportsARefusedCommitAsAJdbcAccessException() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real).failingOn("commit");
        JdbcTxRunner over =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.DEFAULT, log);

        assertThatExceptionOfType(JdbcAccessException.class)
            .isThrownBy(
                () ->
                    over.inTransactionWithoutResult(
                        connection ->
                            SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null)))
            .satisfies(
                thrown -> {
                  assertThat(thrown.getMessage()).contains("cannot commit the transaction");
                  assertThat(thrown.sqlState()).isEqualTo(ScriptedConnection.SCRIPTED_STATE);
                });

        assertThat(scripted.called("rollback"))
            .as("a refused commit is rolled back rather than left open")
            .isTrue();
        assertThat(rows()).isZero();
      }
    }

    /**
     * RFC-0003's fourth row, and the one where doing the obvious thing would be wrong: the work is
     * committed, so reporting a failure invites a retry that applies it twice. Logged and
     * swallowed.
     */
    @Test
    void logsAFailedRestoreAndDoesNotPropagateIt() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        // The runner calls setAutoCommit twice: false to begin, then back. Only the restore fails.
        ScriptedConnection scripted =
            new ScriptedConnection(real).failingOn("setAutoCommit").fromCall(2);
        JdbcTxRunner over =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.DEFAULT, log);

        over.inTransactionWithoutResult(
            connection -> SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null));

        assertThat(rows()).as("the work is committed").isEqualTo(1);
        assertThat(log.messages())
            .anySatisfy(
                line -> assertThat(line).startsWith("WARNING").contains("could not restore"));
      }
    }

    /** The same rule for the close itself — deliberately not a try-with-resources. */
    @Test
    void logsAFailedCloseAndDoesNotPropagateIt() throws SQLException {
      try (Connection real = JdbcFixtures.connect(url)) {
        ScriptedConnection scripted = new ScriptedConnection(real).failingOn("close");
        JdbcTxRunner over =
            JdbcTxRunner.on(handingOut(scripted.connection()), TxIsolation.DEFAULT, log);

        String value = over.inTransaction(connection -> "done");

        assertThat(value).isEqualTo("done");
        assertThat(log.messages())
            .anySatisfy(
                line -> assertThat(line).startsWith("WARNING").contains("close the connection"));
      }
    }
  }

  @Nested
  @DisplayName("nesting")
  class Nesting {

    @Test
    void refusesANestedTransaction() {
      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      outer -> runner.inTransactionWithoutResult(inner -> {})))
          .withMessageContaining("already inside a transaction");
    }

    /**
     * The detector spans every runner in the JVM, so two pools cannot be nested either — ADR-0031.
     * Two uncoordinated transactions give no atomicity, which is a false comfort rather than a
     * feature.
     */
    @Test
    void refusesNestingAcrossTwoRunners() {
      JdbcTxRunner other =
          JdbcTxRunner.on(
              new CountingDataSource(JdbcFixtures.freshUrl()), TxIsolation.DEFAULT, log);

      assertThatIllegalStateException()
          .isThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      outer -> other.inTransactionWithoutResult(inner -> {})))
          .withMessageContaining("already inside a transaction");
    }

    @Test
    void allowsASecondTransactionAfterTheFirstFinished() {
      runner.inTransactionWithoutResult(
          connection -> SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null));
      runner.inTransactionWithoutResult(
          connection -> SimpleJdbcExecutor.on(connection).update(INSERT, 2, "A-2", 1, null));

      assertThat(rows()).isEqualTo(2);
    }

    /** The flag is cleared on the failure path too, or one bad transaction poisons the thread. */
    @Test
    void clearsTheFlagWhenTheBodyThrew() {
      assertThatThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        throw new IllegalStateException("boom");
                      }))
          .isInstanceOf(IllegalStateException.class);

      runner.inTransactionWithoutResult(
          connection -> SimpleJdbcExecutor.on(connection).update(INSERT, 1, "A-1", 2, null));
      assertThat(rows()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("logging")
  class Logging {

    @Test
    void logsARollbackAtDebugAndNeverACommit() {
      runner.inTransactionWithoutResult(connection -> {});
      assertThat(log.messages()).as("a commit is not logged at all").isEmpty();

      assertThatThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        throw new IllegalStateException("boom");
                      }))
          .isInstanceOf(IllegalStateException.class);

      assertThat(log.messages())
          .singleElement()
          .satisfies(
              line ->
                  assertThat(line)
                      .startsWith("DEBUG")
                      .contains("rolled back")
                      .contains("java.lang.IllegalStateException"));
    }

    /**
     * Control <strong>C-01</strong> in the log rather than in an exception. A failure's
     * <em>message</em> is where a driver puts the failing statement, so the line carries the type
     * name and nothing else — and a {@code MessageFormat} placeholder that had been left unrendered
     * would show up here too.
     */
    @Test
    void noLogLineCarriesTheFailureMessage() {
      assertThatThrownBy(
              () ->
                  runner.inTransactionWithoutResult(
                      connection -> {
                        throw new IllegalStateException("credential " + SECRET + " rejected");
                      }))
          .isInstanceOf(IllegalStateException.class);

      assertThat(log.messages())
          .isNotEmpty()
          .allSatisfy(
              line ->
                  assertThat(line)
                      .doesNotContain(SECRET)
                      .doesNotContain("{0}")
                      .doesNotContain("{1}"));
    }
  }

  /**
   * <strong>The obligation demonstrated rather than asserted</strong>, the way item 2.4
   * demonstrated its leaky subclass. The connection belongs to the transaction: it is closed when
   * the body returns, and a body that squirrels it away holds a dead object. The library cannot
   * prevent this — {@link TxCallback} states it, and this is what the state looks like.
   */
  @Test
  void aRetainedConnectionIsDead() throws SQLException {
    AtomicReference<Connection> retained = new AtomicReference<>();

    runner.inTransactionWithoutResult(retained::set);

    Connection dead = Objects.requireNonNull(retained.get(), "the body ran, so it captured one");
    assertThat(dead.isClosed()).isTrue();
    assertThatThrownBy(() -> dead.prepareStatement("select 1")).isInstanceOf(SQLException.class);
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsNullArguments() {
    assertThatNullPointerException().isThrownBy(() -> JdbcTxRunner.on(null));
    assertThatNullPointerException()
        .isThrownBy(() -> JdbcTxRunner.on(dataSource, (TxIsolation) null));
    assertThatNullPointerException().isThrownBy(() -> runner.inTransaction((TxCallback<?>) null));
    assertThatNullPointerException().isThrownBy(() -> runner.inTransactionWithoutResult(null));
  }

  /**
   * A {@code DataSource} that hands out one connection the test controls.
   *
   * @param connection what every {@code getConnection} answers with
   * @return the data source
   */
  private static DataSource handingOut(Connection connection) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("getConnection".equals(method.getName())) {
                return connection;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
