package it.d4np.utils.jdbc;

import it.d4np.utils.Nullable;
import it.d4np.utils.ObjectUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Parameterized JDBC, with the {@code PreparedStatement} enforced rather than recommended (FR-05,
 * RFC-0003).
 *
 * <pre>{@code
 * SimpleJdbcExecutor executor = SimpleJdbcExecutor.on(dataSource);   // once, at start-up
 *
 * List<Order> open =
 *     executor.query("select sku, quantity from orders where status = ?", Order::from, "OPEN");
 *
 * Optional<Order> one =
 *     executor.queryOne("select sku, quantity from orders where id = ?", Order::from, 42);
 *
 * int updated = executor.update("update orders set status = ? where id = ?", "SHIPPED", 42);
 * }</pre>
 *
 * <h2>There is no way to concatenate SQL, and that is the whole guarantee</h2>
 *
 * <p>Every operation takes {@code (String sql, ..., Object... params)}, binds through a {@link
 * PreparedStatement}, and <strong>no {@link java.sql.Statement} is created anywhere in this
 * module.</strong> There is no overload without a params slot and none that accepts
 * pre-interpolated SQL; a zero-parameter call still goes through a prepared statement. The threat
 * model's <em>"SQL altered via string concatenation"</em> row is defended by construction rather
 * than by advice, which is a property of <em>the type</em> — the same shape FR-20's absent getter
 * has, and for the same reason (ADR-0022: a guarantee a consumer can switch off is advisory).
 *
 * <p>What it does not defend is a caller that builds the {@code sql} string itself before passing
 * it in. Nothing in Java can stop that, so the honest claim is narrower and worth stating: this API
 * offers no path where concatenation is the <em>convenient</em> option, and a column or table name
 * — which cannot be a bind parameter in any database — is what FR-07's allowlist exists for (item
 * 4.5).
 *
 * <h2>Two ways to get a connection, and the difference is who closes it</h2>
 *
 * <table border="1">
 *   <caption>Connection lifecycle</caption>
 *   <tr><th>Factory</th><th>Lifecycle</th></tr>
 *   <tr>
 *     <td>{@link #on(DataSource)}</td>
 *     <td><strong>Owns</strong> the connection: acquires one per operation and closes it in a
 *         {@code finally}, which is FR-05's try-with-resources promise</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #on(Connection)}</td>
 *     <td><strong>Borrows</strong> the connection: never closes it. This is the form used inside a
 *         transaction, where the transaction owns the lifecycle</td>
 *   </tr>
 * </table>
 *
 * <p>The pair exists because FR-06's transaction runner (item 4.4) hands its callback the
 * transactional {@code Connection} explicitly rather than binding it to a {@code ThreadLocal}.
 * RFC-0003 rejected ambient transport on its failure mode: an executor built from a {@code
 * DataSource} at start-up would change its transactional semantics depending on whether an
 * enclosing transaction happened to exist on the calling thread, and any hand-off to another thread
 * would silently revert to auto-commit.
 *
 * <h2>The {@code @Nullable} on {@code params} says less than it looks like</h2>
 *
 * <p>A bind parameter that is {@code null} is a <em>value</em> — SQL {@code NULL} — so a caller has
 * to be able to write {@code update(sql, id, null)}. Under NullAway that call does not compile
 * unless the varargs parameter is annotated, and {@link it.d4np.utils.Nullable} is a
 * <strong>declaration</strong> annotation by ADR-0011's deliberate choice: it can say "the array
 * may be null" and has no vocabulary for "the array is present, its elements may not be". So the
 * annotation on {@code params} is the only spelling that makes the call site compile, and it
 * therefore claims something this contract does not offer.
 *
 * <p>The array itself must <strong>not</strong> be {@code null}, and passing one raises {@code
 * NullPointerException} — enforced at run time and asserted by a test, because the annotation
 * cannot enforce it. This is the same expressiveness gap RFC-0003 met on the other side of this
 * milestone, where {@code Map<String, @Nullable Object>} was inexpressible for FR-21; ADR-0029
 * records why widening the annotation's {@code @Target} to fix it is the wrong trade.
 *
 * <h2>Errors</h2>
 *
 * <p>Every {@link SQLException} becomes a {@link JdbcAccessException} whose message carries the
 * operation, the SQLState and the vendor code — <strong>never the SQL and never a parameter
 * value</strong> (control C-01). The driver's own exception survives as the cause, for the log.
 *
 * <p>A failure while closing is <em>suppressed onto</em> the original rather than replacing it,
 * because every acquisition here is a try-with-resources and that is what the language does. The
 * original failure is the diagnosis; a close that failed afterwards is the consequence.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #on(DataSource)} is <strong>thread-safe</strong>: this class holds one final field and
 * mutates nothing, so the claim reduces to the {@code DataSource} being safe to share — which every
 * pool guarantees and this library cannot improve on.
 *
 * <p>{@link #on(Connection)} is <strong>as thread-safe as that connection, which is to say
 * not</strong>. A JDBC {@code Connection} is not safe for concurrent use, this class does not
 * synchronize, and pretending otherwise by adding a lock would serialise a resource the caller
 * already owns. Documented rather than synchronized, exactly as RFC-0001 documented {@code
 * FluentBuilder}.
 *
 * <p><strong>No jcstress harness is owed, and spec §6 is the reason it has to be said.</strong>
 * That section makes a thread-safety claim without a named harness not a claim — so: there is no
 * shared mutable state here to stress. A harness over {@code on(DataSource)} would be measuring the
 * host's pool, and one over {@code on(Connection)} would be asserting a property this class
 * explicitly does not claim. Item 3.1 reached the same conclusion for {@code Validator} over a Bean
 * Validation provider, and item 4.1 for {@code JsonMapper} over Jackson. Item 4.4 <em>does</em> owe
 * one, because FR-06's nesting detector is real per-thread state.
 *
 * @see RowMapper
 * @see JdbcAccessException
 */
public final class SimpleJdbcExecutor {

  /** Where a connection comes from, and whether this executor closes it. */
  private final Connections connections;

  private SimpleJdbcExecutor(Connections connections) {
    this.connections = connections;
  }

  /**
   * An executor that takes a connection from {@code dataSource} for each operation and closes it
   * again.
   *
   * <p>The ordinary form: build one at start-up and hold it for the life of the process.
   *
   * @apiNote <strong>An executor built this way does not join a transaction, whatever it looks like
   *     at the call site.</strong> Captured from outside an FR-06 {@code inTransaction} block and
   *     used inside it, it takes a <em>second</em> connection from the pool, so the work it does
   *     commits independently of the transaction the caller believes they are in — legal Java that
   *     does the wrong thing quietly. Inside a transaction, build a fresh executor over the {@code
   *     Connection} the callback was handed, with {@link #on(Connection)}. What makes the mistake
   *     acceptable rather than a design flaw is that it is <em>visible in the lambda's capture
   *     list</em>: a reviewer reading the block sees the executor arrive from somewhere else.
   *     Ambient thread-local transport would have hidden the same mistake inside a passing test,
   *     which is why RFC-0003 refused it.
   * @param dataSource the pool to borrow from; must not be {@code null}
   * @return an executor that owns the connection lifecycle; never {@code null}
   * @throws NullPointerException if {@code dataSource} is {@code null}
   */
  public static SimpleJdbcExecutor on(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    return new SimpleJdbcExecutor(
        () -> {
          Connection connection = dataSource.getConnection();
          if (connection == null) {
            throw new JdbcAccessException(
                "cannot run a statement: the DataSource returned no connection");
          }
          return new Lease(connection, true);
        });
  }

  /**
   * An executor that runs every operation on {@code connection} and never closes it.
   *
   * <p>The form used inside a transaction: FR-06's callback is handed the transactional {@code
   * Connection}, and an executor built over it runs in that transaction because it is the same
   * connection, not because of anything ambient.
   *
   * @param connection the connection to borrow; must not be {@code null}, and its lifecycle stays
   *     with whoever opened it
   * @return an executor that borrows the connection; never {@code null}
   * @throws NullPointerException if {@code connection} is {@code null}
   */
  public static SimpleJdbcExecutor on(Connection connection) {
    Objects.requireNonNull(connection, "connection must not be null");
    return new SimpleJdbcExecutor(() -> new Lease(connection, false));
  }

  /**
   * Runs {@code sql} and maps every row it returns.
   *
   * <p>The mapper comes before the parameters because varargs must be last — the ordering {@code
   * JdbcTemplate} settled on for the same reason, so it will read as expected.
   *
   * <p><strong>The result is unbounded.</strong> A query matching a million rows produces a list of
   * a million objects, and this operation deliberately imposes no cap: a library-chosen limit would
   * silently truncate an answer the caller asked for. Bounding the request is FR-07's job — {@code
   * PageRequest} arrives with item 4.5 — and until then the {@code sql} is where a {@code limit}
   * belongs.
   *
   * @param <T> the type each row becomes
   * @param sql the statement to prepare; must not be {@code null} or blank
   * @param mapper the row mapper; must not be {@code null} and must not return {@code null}
   * @param params the bind parameters, positionally; the array must not be {@code null} — the
   *     annotation on it is about the call site, not the contract (see the class documentation) —
   *     and an individual {@code null} binds SQL {@code NULL}
   * @return the mapped rows in result-set order, unmodifiable and possibly empty; never {@code
   *     null}
   * @throws NullPointerException if {@code sql}, {@code mapper} or {@code params} is {@code null}
   * @throws IllegalArgumentException if {@code sql} is blank
   * @throws IllegalStateException if {@code mapper} returns {@code null} for any row
   * @throws JdbcAccessException if the statement fails — the message names the operation and the
   *     driver's two codes, never the SQL and never a parameter
   */
  public <T> List<T> query(String sql, RowMapper<T> mapper, @Nullable Object... params) {
    Objects.requireNonNull(mapper, "row mapper must not be null");
    return execute(
        "run a query",
        sql,
        params,
        statement -> {
          try (ResultSet rows = statement.executeQuery()) {
            List<T> mapped = new ArrayList<>();
            while (rows.next()) {
              mapped.add(mapped(mapper, rows));
            }
            return List.copyOf(mapped);
          }
        });
  }

  /**
   * Runs {@code sql} and maps the single row it is expected to return.
   *
   * <p><strong>A second row is refused, not ignored.</strong> Returning the first and dropping the
   * rest would answer a question the caller did not ask, and the answer would look right: a
   * duplicate in a column the schema was supposed to keep unique becomes an application that reads
   * one of two records at random, forever, with nothing in a log. So the second row raises a {@link
   * JdbcAccessException} — the same reasoning that makes {@code readValue} refuse the literal
   * {@code null} document, applied to a shape rather than a value.
   *
   * <p>No row is an ordinary answer, and it is {@link Optional#empty()} rather than an exception —
   * "this id does not exist" is a question worth asking.
   *
   * <p>Only two rows are ever fetched: the cursor stops as soon as a second one is seen, and the
   * result set is closed. No row limit is set on the statement, because {@code setMaxRows} is a
   * method a driver may refuse and the streaming already bounds the work.
   *
   * @param <T> the type the row becomes
   * @param sql the statement to prepare; must not be {@code null} or blank
   * @param mapper the row mapper; must not be {@code null} and must not return {@code null}
   * @param params the bind parameters, positionally; the array must not be {@code null} — the
   *     annotation on it is about the call site, not the contract (see the class documentation) —
   *     and an individual {@code null} binds SQL {@code NULL}
   * @return the mapped row, or {@link Optional#empty()} when nothing matched; never {@code null}
   * @throws NullPointerException if {@code sql}, {@code mapper} or {@code params} is {@code null}
   * @throws IllegalArgumentException if {@code sql} is blank
   * @throws IllegalStateException if {@code mapper} returns {@code null}
   * @throws JdbcAccessException if the statement fails, or if it matched more than one row
   */
  public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, @Nullable Object... params) {
    Objects.requireNonNull(mapper, "row mapper must not be null");
    return execute(
        "run a single-row query",
        sql,
        params,
        statement -> {
          try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
              return Optional.empty();
            }
            T only = mapped(mapper, rows);
            if (rows.next()) {
              throw new JdbcAccessException(
                  "cannot run a single-row query: the statement matched more than one row");
            }
            return Optional.of(only);
          }
        });
  }

  /**
   * Runs an {@code insert}, {@code update}, {@code delete} or DDL statement.
   *
   * @param sql the statement to prepare; must not be {@code null} or blank
   * @param params the bind parameters, positionally; the array must not be {@code null} — the
   *     annotation on it is about the call site, not the contract (see the class documentation) —
   *     and an individual {@code null} binds SQL {@code NULL}
   * @return the number of rows the statement affected, or {@code 0} for a statement that returns
   *     nothing (DDL)
   * @throws NullPointerException if {@code sql} or {@code params} is {@code null}
   * @throws IllegalArgumentException if {@code sql} is blank
   * @throws JdbcAccessException if the statement fails
   * @implNote The count is an {@code int} because {@code executeUpdate} returns one. JDBC's {@code
   *     executeLargeUpdate} returns a {@code long} and is an optional feature whose default
   *     implementation throws, so a {@code long}-returning overload would work on some drivers and
   *     not others — omitted in the reversible direction, since adding one later is MINOR under
   *     RFC-0001 §Versioning and removing one is MAJOR.
   */
  public int update(String sql, @Nullable Object... params) {
    return execute("run an update", sql, params, PreparedStatement::executeUpdate);
  }

  /**
   * Validates, acquires, prepares, binds, runs and translates — the one place all six happen.
   *
   * <p>Every operation funnels through here, which is what makes two guarantees checkable rather
   * than reviewable: <strong>the only statement this module creates is a {@link
   * PreparedStatement}</strong>, and <strong>every {@link SQLException} becomes a {@link
   * JdbcAccessException}</strong>. A future operation that bypassed this method would have to
   * restate both.
   *
   * @param <R> what the operation produces
   * @param operation the fixed label for the failure message; never derived from the driver
   * @param sql the caller's statement
   * @param params the caller's bind parameters
   * @param call what to do with the prepared, bound statement
   * @return the operation's result
   */
  private <R> R execute(
      String operation, String sql, @Nullable Object[] params, StatementCall<R> call) {
    ObjectUtils.requireNonBlank(sql, "sql");
    Object[] bound = Objects.requireNonNull(params, "params must not be null");
    try (Lease lease = connections.lease();
        PreparedStatement statement = lease.connection().prepareStatement(sql)) {
      bind(statement, bound);
      return call.run(statement);
    } catch (SQLException failed) {
      throw new JdbcAccessException(operation, failed);
    }
  }

  /**
   * Binds the parameters positionally, in the order given.
   *
   * <p>A {@code null} binds through {@code setNull} with {@link Types#NULL} rather than through
   * {@code setObject}, on JDBC's own advice: {@code setObject}'s documentation says not every
   * database accepts an untyped {@code NULL} and points at {@code setNull} when the type is not
   * known. It is not known here, and inventing one would guess at the caller's column. A driver
   * that demands a concrete type — historically Oracle — needs a vocabulary this API deliberately
   * does not have; a typed-null overload is MINOR to add if a consumer meets one.
   *
   * @param statement the prepared statement
   * @param params the caller's parameters, possibly empty and possibly holding {@code null}
   * @throws SQLException if the driver rejects a binding
   */
  private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
    for (int index = 0; index < params.length; index++) {
      Object value = params[index];
      if (value == null) {
        statement.setNull(index + 1, Types.NULL);
      } else {
        statement.setObject(index + 1, value);
      }
    }
  }

  /**
   * Maps one row and refuses a {@code null} result.
   *
   * <p>{@link IllegalStateException} rather than {@link JdbcAccessException}, and the distinction
   * is worth the sentence: nothing about the database failed. A caller-supplied function returned
   * {@code null} where the contract forbids it, which is the shape {@code Lazy} already meets when
   * an initializer returns {@code null}, and RFC-0001's table assigns "a defect the caller cannot
   * sensibly branch on" to the unchecked shape. Reporting it as data access would name the wrong
   * system in the log that follows.
   *
   * @param <T> the mapped type
   * @param mapper the caller's mapper
   * @param row the result set, positioned on a row
   * @return the mapped value, never {@code null}
   * @throws SQLException if the mapper's own column reads fail
   */
  private static <T> T mapped(RowMapper<T> mapper, ResultSet row) throws SQLException {
    T value = mapper.map(row);
    if (value == null) {
      throw new IllegalStateException(
          "the row mapper returned null; a mapped row must never be null (control C-02)");
    }
    return value;
  }

  /** Supplies the connection an operation runs on, together with the decision to close it. */
  @FunctionalInterface
  private interface Connections {

    /**
     * Acquires a connection for one operation.
     *
     * @return the lease, which the caller closes
     * @throws SQLException if the pool cannot supply one
     */
    Lease lease() throws SQLException;
  }

  /**
   * A connection plus whether closing this lease closes it.
   *
   * <p>Making the lease {@link AutoCloseable} rather than releasing in a {@code finally} is what
   * buys the suppression semantics: if the body fails and the close fails too, Java attaches the
   * second to the first instead of replacing it. Doing it by hand is how the original diagnosis
   * gets lost.
   *
   * @param connection the connection to run on
   * @param owned whether this executor acquired it and must therefore close it
   */
  private record Lease(Connection connection, boolean owned) implements AutoCloseable {

    @Override
    public void close() throws SQLException {
      if (owned) {
        connection.close();
      }
    }
  }

  /**
   * What an operation does with a prepared, bound statement.
   *
   * @param <R> what it produces
   */
  @FunctionalInterface
  private interface StatementCall<R> {

    /**
     * Runs the statement.
     *
     * @param statement the prepared and bound statement, closed by the caller
     * @return the operation's result
     * @throws SQLException if the driver rejects it
     */
    R run(PreparedStatement statement) throws SQLException;
  }
}
