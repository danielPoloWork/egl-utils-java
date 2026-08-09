package it.d4np.utils.jdbc;

import java.sql.Connection;

/**
 * The body of a transaction that produces a value (FR-06, RFC-0003).
 *
 * <pre>{@code
 * Order placed = runner.inTransaction(connection -> {
 *   SimpleJdbcExecutor tx = SimpleJdbcExecutor.on(connection);   // borrows, never closes
 *   tx.update("insert into orders (id, sku) values (?, ?)", id, sku);
 *   return tx.queryOne("select * from orders where id = ?", Order::from, id).orElseThrow();
 * });
 * }</pre>
 *
 * <p><strong>The connection is passed, not ambient, and that is the load-bearing decision of
 * FR-06.</strong> Binding it to a {@code ThreadLocal} would have been the ergonomic choice and
 * RFC-0003 rejected it on its failure mode: an executor built from a {@code DataSource} at start-up
 * would change its transactional semantics depending on whether an enclosing transaction happened
 * to exist on the calling thread, and any hand-off to another thread would silently revert to
 * auto-commit. Two identical call sites, two different behaviours, and no local signal which one
 * you got.
 *
 * <h2>Two obligations the library cannot enforce</h2>
 *
 * <ul>
 *   <li><strong>Do not retain the connection.</strong> It is closed when {@code inTransaction}
 *       returns, and using it afterwards fails with the driver's "object is already closed" —
 *       demonstrated rather than asserted by {@code JdbcTxRunnerTest.aRetainedConnectionIsDead},
 *       the way item 2.4 demonstrated its leaky subclass.
 *   <li><strong>Do not share it with another thread.</strong> A JDBC {@code Connection} is not
 *       thread-safe, and neither this callback's parameter nor anything downstream of it becomes so
 *       by being handed over. This is the same class of stated obligation as RFC-0001's
 *       defensive-copy rule for {@code FluentBuilder}.
 * </ul>
 *
 * <h2>{@code throws Exception}, and what happens to what you throw</h2>
 *
 * <p>{@code Exception} rather than {@code Throwable}: JDBC's own methods throw the checked {@code
 * SQLException}, so a body has to be allowed to, and {@code Exception} covers it. ADR-0021 chose
 * {@code Throwable} for {@code Invocation.proceed} only because {@code ProceedingJoinPoint.proceed}
 * forced it, and no such constraint exists here — the wider declaration would push every caller
 * into {@code catch (Throwable)}. An {@code Error} thrown by a body still rolls the transaction
 * back; it is simply not in the declared {@code throws}.
 *
 * <p>What reaches the caller is the subject of <a
 * href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/adr/0030-the-two-channels-out-of-a-transaction-body.md">ADR-0030</a>
 * and is summarised on {@link JdbcTxRunner#inTransaction(TxCallback)}.
 *
 * @param <T> what the transaction produces
 * @see JdbcTxRunner
 * @see TxVoidCallback
 */
@FunctionalInterface
public interface TxCallback<T> {

  /**
   * Runs the transaction body.
   *
   * @param connection the transactional connection, open for the duration of this call and closed
   *     by the runner afterwards; never {@code null}
   * @return the transaction's value; <strong>must not be {@code null}</strong> — a body with
   *     nothing to return uses {@link TxVoidCallback} instead, and a {@code null} here is refused
   *     with the transaction rolled back (ADR-0030)
   * @throws Exception whatever the body needs to throw; any {@code Throwable} out of this method
   *     rolls the transaction back
   */
  T run(Connection connection) throws Exception;
}
