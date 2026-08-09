package it.d4np.utils.jdbc;

import it.d4np.utils.Unit;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Programmatic transactions over a plain {@link DataSource} (FR-06, RFC-0003).
 *
 * <pre>{@code
 * JdbcTxRunner runner = JdbcTxRunner.on(dataSource);          // once, at start-up
 *
 * Order placed = runner.inTransaction(connection -> {
 *   SimpleJdbcExecutor tx = SimpleJdbcExecutor.on(connection);   // borrows, never closes
 *   tx.update("insert into orders (id, sku) values (?, ?)", id, sku);
 *   return tx.queryOne("select * from orders where id = ?", Order::from, id).orElseThrow();
 * });
 * }</pre>
 *
 * <p><strong>Scoped to hosts without a transaction manager.</strong> A Spring application already
 * has {@code TransactionTemplate} and {@code @Transactional}, which do this and more, and running
 * both against one {@code DataSource} means two things believe they own the connection. FR-06 says
 * so in its own text and this documentation repeats it rather than assuming a reader arrives from
 * the requirement: <strong>on Spring, use Spring's.</strong>
 *
 * <h2>The connection is passed, never ambient</h2>
 *
 * <p>The callback receives the transactional {@link Connection} and that is the only way to reach
 * it. Binding it to a {@code ThreadLocal} would have been the ergonomic choice, and RFC-0003
 * rejected it on its failure mode: a {@code SimpleJdbcExecutor} built from a {@code DataSource} at
 * start-up would change its transactional semantics depending on whether an enclosing transaction
 * happened to exist on the calling thread, and a hand-off to {@code CompletableFuture} or FR-09's
 * {@code AsyncExecutor} would silently revert to auto-commit.
 *
 * <p>The residual is stated rather than claimed away: an executor captured from <em>outside</em>
 * the block still takes its own connection and commits independently, which is legal Java that does
 * the wrong thing. What makes it acceptable is that it is visible in the lambda's capture list,
 * where ambient state would have hidden it — {@link SimpleJdbcExecutor#on(DataSource)} carries the
 * {@code @apiNote}.
 *
 * <h2>What commits, and what rolls back</h2>
 *
 * <table border="1">
 *   <caption>The two channels out of a body</caption>
 *   <tr><th>The body…</th><th>…and the transaction</th></tr>
 *   <tr><td>returns normally</td><td><strong>commits</strong>, and the value is returned</td></tr>
 *   <tr><td>throws anything, including an {@link Error}</td><td><strong>rolls back</strong></td></tr>
 *   <tr>
 *     <td>returns {@code Result.Err}</td>
 *     <td><strong>commits.</strong> This is the decision most likely to be questioned, so it is
 *         stated where a caller will meet it: <em>the exception channel demarcates the transaction,
 *         the value channel does not.</em> Reading {@code Err} as a rollback signal would give a
 *         core type a second meaning in exactly one method — it means "a value the caller branches
 *         on" everywhere else — and it is ambiguous the moment a body returns {@code
 *         Result<Result<T>>}. A body that must roll back on a business rule <strong>throws</strong>,
 *         and FR-18 already names the type: {@code BusinessException}</td>
 *   </tr>
 *   <tr>
 *     <td>returns {@code null}</td>
 *     <td><strong>rolls back</strong> and raises {@link IllegalStateException}. A body with nothing
 *         to return uses {@link #inTransactionWithoutResult(TxVoidCallback)}; see ADR-0030</td>
 *   </tr>
 * </table>
 *
 * <h2>Isolation and auto-commit are restored, and that is not tidiness</h2>
 *
 * <p>{@code close()} on a pooled connection returns it for reuse. A level left changed leaks into
 * whatever borrows it next — a cross-request correctness bug with no symptom at the site that
 * caused it — and a connection left at {@code autoCommit=false} makes the <em>next</em> borrower's
 * statements sit uncommitted until something else commits or the connection is reaped, which is
 * data loss attributable to nobody. So both are read on entry and written back in a {@code
 * finally}.
 *
 * <p><strong>Known limitation, carried rather than hidden:</strong> if the restoration itself
 * fails, JDBC gives a library no way to invalidate a pooled connection — {@code close()} returns it
 * regardless. The failure is logged at {@code WARNING} and the host's pool validation is the only
 * remaining defence.
 *
 * <h2>Nesting is refused, and that is the one place ambient state is correct</h2>
 *
 * <p>A second {@code inTransaction} on the same thread raises {@link IllegalStateException}.
 * Suspension — Spring's {@code REQUIRES_NEW} — needs a second connection and a manager owning both,
 * and building half a transaction manager whose behaviour diverges from Spring's in one case is
 * worse than building none.
 *
 * <p>Refusing requires detecting, and with explicit transport there is nothing to detect from — so
 * a thread-scoped flag does it. The asymmetry with the transport decision above is principled: as
 * <em>transport</em>, an ambient value silently changes semantics and fails <strong>open into the
 * wrong behaviour</strong>; as a <em>detector</em>, it can only produce a loud refusal, and when it
 * is wrong (work moved to another thread) it fails open into exactly the documented behaviour. A
 * detector that fails open is safe; transport that fails open is the bug.
 *
 * <p>The detector is <strong>shared by every runner in the JVM</strong>, so two runners over two
 * different pools cannot be nested either — see ADR-0031 for why that is deliberate rather than an
 * oversight.
 *
 * <p><strong>Savepoints are not offered.</strong> They are only useful with the nesting just
 * refused, and {@code Connection.setSavepoint} is optional in JDBC — an API that works on some
 * drivers and throws {@code SQLFeatureNotSupportedException} on others is worse than no API. The
 * caller holds the {@code Connection} and can.
 *
 * <h2>Logging</h2>
 *
 * <p>Exactly two lines, through {@link System#getLogger(String)} per ADR-0014: a <strong>rollback
 * at {@code DEBUG}</strong>, and a <strong>rollback that itself failed at {@code WARNING}</strong>.
 * A rollback is the normal outcome of a business rule, so {@code WARNING} would train operators to
 * ignore the level; a failed rollback means the connection's state is unknown, which is genuinely
 * exceptional. <strong>Commits are not logged at all</strong> — one line per transaction is how
 * instrumentation becomes the dominant cost of the thing it observes (ADR-0021's precedent).
 *
 * <p>No line carries SQL, a parameter, or the failure's <em>message</em> — only its type name and,
 * where there is one, an SQLState. A driver's message is where the failing statement lives (control
 * C-01), and it reaches the log through the propagating exception's own cause, which is the host's
 * to render.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and thread-safe: the {@code DataSource}, the isolation and the logger are resolved
 * once at construction and never change, which is ADR-0021's resolve-once rule. The nesting
 * detector is per-thread state reached through a shared type, so spec §6 asks for a harness rather
 * than a sentence — {@code JdbcTxNestingIsolationStress} is it, and it proves two threads
 * transacting through one runner never observe each other's detector.
 *
 * @see TxCallback
 * @see TxIsolation
 * @see SimpleJdbcExecutor#on(Connection)
 */
public final class JdbcTxRunner {

  /**
   * Whether this thread is already inside a transaction.
   *
   * <p><strong>A flag where RFC-0003 said "depth counter", and the difference is worth the
   * sentence.</strong> Nesting is refused, so the depth can only ever be 0 or 1 and a counter would
   * be a counter of one. The flag form buys something a counter does not: {@link
   * ThreadLocal#remove()} clears the entry outright, where a decrement leaves a thread permanently
   * marked if an increment ever went unbalanced — and these threads come from pools that outlive
   * the transaction by hours.
   *
   * <p>Static, so the detector spans every runner in the JVM. See ADR-0031.
   */
  private static final ThreadLocal<Boolean> IN_TRANSACTION = new ThreadLocal<>();

  /** Where the two log lines go unless a test supplied its own logger; the ADR-0014 seam. */
  private static final Logger DEFAULT_LOGGER = System.getLogger(JdbcTxRunner.class.getName());

  /**
   * No apostrophe in any format below: {@link java.text.MessageFormat} treats a single quote as an
   * escape and would print the placeholder that follows it verbatim (ADR-0014).
   */
  private static final String ROLLED_BACK = "d4np jdbc: transaction rolled back after {0}";

  private static final String ROLLBACK_FAILED =
      "d4np jdbc: transaction rollback FAILED after {0} [SQLState {1}]; "
          + "the connection state is unknown and the pool will take it back regardless";

  private static final String RESTORE_FAILED =
      "d4np jdbc: could not restore {0} on the connection [SQLState {1}]; "
          + "it returns to the pool in that state, and pool validation is the remaining defence";

  private final DataSource dataSource;

  private final TxIsolation isolation;

  private final Logger logger;

  private JdbcTxRunner(DataSource dataSource, TxIsolation isolation, Logger logger) {
    this.dataSource = dataSource;
    this.isolation = isolation;
    this.logger = logger;
  }

  /**
   * A runner that leaves the pool's isolation level exactly as it found it.
   *
   * @param dataSource the pool to take a connection from; must not be {@code null}
   * @return a runner at {@link TxIsolation#DEFAULT}; never {@code null}
   * @throws NullPointerException if {@code dataSource} is {@code null}
   */
  public static JdbcTxRunner on(DataSource dataSource) {
    return on(dataSource, TxIsolation.DEFAULT);
  }

  /**
   * A runner that applies {@code isolation} on entry and restores the previous level on exit.
   *
   * @param dataSource the pool to take a connection from; must not be {@code null}
   * @param isolation the level to apply, or {@link TxIsolation#DEFAULT} to apply none; must not be
   *     {@code null}
   * @return a runner; never {@code null}
   * @throws NullPointerException if either argument is {@code null}
   */
  public static JdbcTxRunner on(DataSource dataSource, TxIsolation isolation) {
    return on(dataSource, isolation, DEFAULT_LOGGER);
  }

  /**
   * The construction seam a test uses to read the two log lines back.
   *
   * <p>Package-private for the reason {@code LoggingAuditSink} states: FR-06 makes the rollback log
   * part of the contract, spec §6 does not accept a contract clause without a test, and a {@code
   * System.LoggerFinder} registered through {@code META-INF/services} can never win inside a
   * surefire fork — the JDK resolves the finder once per VM and something has already triggered
   * platform logging by then.
   *
   * @param dataSource the pool
   * @param isolation the level to apply
   * @param logger where the two lines go
   * @return a runner writing to {@code logger}
   */
  static JdbcTxRunner on(DataSource dataSource, TxIsolation isolation, Logger logger) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    Objects.requireNonNull(isolation, "isolation must not be null");
    Objects.requireNonNull(logger, "logger must not be null");
    return new JdbcTxRunner(dataSource, isolation, logger);
  }

  /**
   * Runs {@code body} in a transaction and returns its value.
   *
   * <p>Commits when the body returns, rolls back when it throws anything at all. See the class
   * documentation's table for the two cases a reader will want to check: a returned {@code
   * Result.Err} <strong>commits</strong>, and a returned {@code null} <strong>rolls back</strong>.
   *
   * <p><strong>What reaches you when the body throws</strong> (ADR-0030):
   *
   * <table border="1">
   *   <caption>Exception translation</caption>
   *   <tr><th>The body threw</th><th>You catch</th></tr>
   *   <tr><td>a {@link RuntimeException}</td><td>that exception, unchanged</td></tr>
   *   <tr><td>an {@link Error}</td><td>that error, unchanged</td></tr>
   *   <tr><td>a {@link SQLException}</td><td>{@link JdbcAccessException} wrapping it — the same
   *       translation every operation in this module makes</td></tr>
   *   <tr><td>any other checked exception</td><td>{@link UndeclaredThrowableException} wrapping it
   *       </td></tr>
   * </table>
   *
   * @param <T> what the transaction produces
   * @param body the transaction body; must not be {@code null} and must not return {@code null}
   * @return the body's value; never {@code null}
   * @throws NullPointerException if {@code body} is {@code null}
   * @throws IllegalStateException if this thread is already inside a transaction, or if {@code
   *     body} returned {@code null} — in the second case the transaction is rolled back first
   * @throws JdbcAccessException if the connection cannot be taken, the transaction cannot be
   *     started, the commit is refused, or the body raised a {@link SQLException}
   */
  public <T> T inTransaction(TxCallback<T> body) {
    Objects.requireNonNull(body, "transaction body must not be null");
    return transact(body);
  }

  /**
   * Runs {@code body} in a transaction, for a body with no value to return.
   *
   * <p>Identical to {@link #inTransaction(TxCallback)} in every respect except the value: commits
   * when the body returns, rolls back when it throws, refuses to nest.
   *
   * <p><strong>The name is not {@code inTransaction}, and that is a measured decision rather than a
   * stylistic one</strong> (ADR-0032). RFC-0003's surface table gave both methods the same name,
   * and two overloads over two functional interfaces — one returning a value, one not — make an
   * implicit lambda whose body is a <em>statement expression with a value</em> ambiguous:
   *
   * <pre>{@code
   * runner.inTransaction(c -> executor.update(sql, id));   // would not compile: `update` returns int
   * }</pre>
   *
   * <p>That is the single most idiomatic one-line transaction there is, and the compiler's answer —
   * {@code reference to inTransaction is ambiguous} — tells a reader nothing about the brace they
   * are missing. Spring met the same problem in the type FR-06 points Spring users at and solved it
   * the same way, adding {@code executeWithoutResult} beside {@code execute}. This name is that
   * one's analogue, so a reader arriving from {@code TransactionTemplate} recognises it.
   *
   * @param body the transaction body; must not be {@code null}
   * @throws NullPointerException if {@code body} is {@code null}
   * @throws IllegalStateException if this thread is already inside a transaction
   * @throws JdbcAccessException if the connection cannot be taken, the transaction cannot be
   *     started, the commit is refused, or the body raised a {@link SQLException}
   */
  public void inTransactionWithoutResult(TxVoidCallback body) {
    Objects.requireNonNull(body, "transaction body must not be null");
    transact(
        connection -> {
          body.run(connection);
          // Unit finds its first use in this library here, and not where ADR-0012 predicted: it
          // guessed this call site would be the first `Result<Unit>` and RFC-0003 recorded that the
          // guess was wrong, because a transaction runner's failures are infrastructure faults that
          // belong to the unchecked shape. The type is right, the signature was not — so it stands
          // in for "no value" one layer down, where the null-return refusal needs something to
          // return.
          return Unit.INSTANCE;
        });
  }

  /**
   * Refuses nesting, takes a connection, and runs the transaction on it.
   *
   * @param <T> what the transaction produces
   * @param body the transaction body, already checked
   * @return the body's value
   */
  private <T> T transact(TxCallback<T> body) {
    if (Boolean.TRUE.equals(IN_TRANSACTION.get())) {
      throw new IllegalStateException(
          "this thread is already inside a transaction, and nesting is not supported: "
              + "a nested call would take a second connection and wait on locks the outer "
              + "transaction holds, on one thread, which no pool can resolve");
    }
    IN_TRANSACTION.set(Boolean.TRUE);
    try {
      Connection connection = open();
      try {
        return runOn(connection, body);
      } finally {
        close(connection);
      }
    } finally {
      IN_TRANSACTION.remove();
    }
  }

  /**
   * Closes the connection, reporting rather than propagating a failure.
   *
   * <p><strong>Deliberately not a try-with-resources</strong>, which is the whole point: the
   * language would let a failing {@code close} replace the value the transaction just committed, or
   * attach itself to a failure that is already propagating. RFC-0003's table is explicit that a
   * close after a successful commit is <em>logged and suppressed, never propagated</em> — the work
   * is committed, and reporting a failure invites a retry that applies it twice, which is strictly
   * worse than a lost log line.
   *
   * @param connection the connection to return to the pool
   */
  private void close(Connection connection) {
    try {
      connection.close();
    } catch (SQLException failed) {
      logRestoreFailure("close the connection", failed);
    }
  }

  /**
   * Takes a connection, refusing a pool that hands back nothing.
   *
   * @return an open connection; never {@code null}
   */
  private Connection open() {
    Connection connection;
    try {
      connection = dataSource.getConnection();
    } catch (SQLException failed) {
      throw new JdbcAccessException("start a transaction", failed);
    }
    if (connection == null) {
      throw new JdbcAccessException(
          "cannot start a transaction: the DataSource returned no connection");
    }
    return connection;
  }

  /**
   * The transaction itself: begin, run, commit or roll back, restore.
   *
   * <p>The previous {@code autoCommit} and isolation are read <em>before</em> anything is written,
   * so the {@code finally} always knows what to put back; if that read fails, nothing has changed
   * yet and there is nothing to restore.
   *
   * @param <T> what the transaction produces
   * @param connection the connection this transaction runs on
   * @param body the transaction body
   * @return the body's value
   */
  private <T> T runOn(Connection connection, TxCallback<T> body) {
    Restore restore = capture(connection);
    boolean began = false;
    try {
      connection.setAutoCommit(false);
      if (isolation != TxIsolation.DEFAULT) {
        connection.setTransactionIsolation(isolation.level());
      }
      began = true;
      T value = body.run(connection);
      if (value == null) {
        throw new IllegalStateException(
            "the transaction body returned null; use inTransactionWithoutResult when there is no "
                + "value to return");
      }
      commit(connection);
      return value;
    } catch (Throwable failed) {
      if (began) {
        rollback(connection, failed);
      }
      if (failed instanceof Error error) {
        throw error;
      }
      throw asUnchecked(failed);
    } finally {
      restore.apply(connection);
    }
  }

  /**
   * Reads what has to be put back before anything is changed.
   *
   * @param connection the connection about to become transactional
   * @return the values the {@code finally} will restore
   */
  private Restore capture(Connection connection) {
    try {
      return new Restore(
          connection.getAutoCommit(),
          isolation == TxIsolation.DEFAULT ? 0 : connection.getTransactionIsolation(),
          isolation != TxIsolation.DEFAULT,
          logger);
    } catch (SQLException failed) {
      throw new JdbcAccessException("start a transaction", failed);
    }
  }

  /**
   * Commits, translating a refusal into this module's own exception.
   *
   * @param connection the transactional connection
   */
  private static void commit(Connection connection) {
    try {
      connection.commit();
    } catch (SQLException failed) {
      throw new JdbcAccessException("commit the transaction", failed);
    }
  }

  /**
   * Rolls back, and never lets its own failure replace the one that caused it.
   *
   * <p>A rollback that fails is attached to the original with {@link Throwable#addSuppressed} — the
   * shape try-with-resources uses, so Java programmers already read it correctly. Replacing the
   * original would point the on-call engineer at the wrong system: the original is the diagnosis,
   * the rollback failure is the consequence.
   *
   * @param connection the transactional connection
   * @param failed what the body (or the commit) threw
   */
  private void rollback(Connection connection, Throwable failed) {
    try {
      connection.rollback();
      logger.log(Level.DEBUG, ROLLED_BACK, failed.getClass().getName());
    } catch (SQLException broken) {
      failed.addSuppressed(broken);
      logger.log(
          Level.WARNING,
          ROLLBACK_FAILED,
          failed.getClass().getName(),
          String.valueOf(broken.getSQLState()));
    }
  }

  /**
   * Reports a restoration or close failure without propagating it.
   *
   * @param what the thing that could not be put back, pre-rendered
   * @param failed the driver's exception, read for its SQLState only
   */
  private void logRestoreFailure(String what, SQLException failed) {
    logger.log(Level.WARNING, RESTORE_FAILED, what, String.valueOf(failed.getSQLState()));
  }

  /**
   * Turns whatever the body threw into something this method may throw (ADR-0030).
   *
   * @param failed the body's exception, never an {@link Error} by the time this is called
   * @return the unchecked exception to throw
   */
  private static RuntimeException asUnchecked(Throwable failed) {
    if (failed instanceof RuntimeException runtime) {
      return runtime;
    }
    if (failed instanceof SQLException sql) {
      return new JdbcAccessException("run the transaction", sql);
    }
    return new UndeclaredThrowableException(failed);
  }

  /**
   * What has to be written back before the connection returns to the pool.
   *
   * <p>A record rather than two locals so that the restoration is one call in the {@code finally}
   * and cannot be half-written by a later edit.
   *
   * @param autoCommit the value {@code getAutoCommit} reported on entry
   * @param isolation the level the connection carried on entry, meaningful only when {@code
   *     isolationWasSet}
   * @param isolationWasSet whether this runner changed the level at all — {@link
   *     TxIsolation#DEFAULT} never does, so there is nothing to put back
   * @param logger where a failure to restore is reported
   */
  private record Restore(
      boolean autoCommit, int isolation, boolean isolationWasSet, Logger logger) {

    /**
     * Puts both values back, reporting rather than propagating a failure.
     *
     * <p>Each is attempted independently: a driver that refuses the isolation must not cost us the
     * auto-commit restoration, which is the one whose absence loses a later borrower's data.
     *
     * @param connection the connection about to be closed
     */
    void apply(Connection connection) {
      if (isolationWasSet) {
        try {
          connection.setTransactionIsolation(isolation);
        } catch (SQLException failed) {
          logger.log(
              Level.WARNING,
              RESTORE_FAILED,
              "the isolation level",
              String.valueOf(failed.getSQLState()));
        }
      }
      try {
        connection.setAutoCommit(autoCommit);
      } catch (SQLException failed) {
        logger.log(
            Level.WARNING, RESTORE_FAILED, "auto-commit", String.valueOf(failed.getSQLState()));
      }
    }
  }
}
