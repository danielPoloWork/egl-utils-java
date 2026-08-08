package it.d4np.utils.jdbc;

import java.sql.Connection;

/**
 * The body of a transaction that produces nothing (FR-06, RFC-0003).
 *
 * <pre>{@code
 * runner.inTransaction(connection -> {
 *   SimpleJdbcExecutor tx = SimpleJdbcExecutor.on(connection);
 *   tx.update("update accounts set balance = balance - ? where id = ?", amount, from);
 *   tx.update("update accounts set balance = balance + ? where id = ?", amount, to);
 * });
 * }</pre>
 *
 * <p>Everything {@link TxCallback} documents about the connection, the obligations it carries and
 * what happens to a thrown exception applies here unchanged — this is the same contract without a
 * return value.
 *
 * <p><strong>It exists so that {@link TxCallback} can refuse {@code null}.</strong> Without a void
 * form, a body with nothing to say would have to return something, and the obvious something is
 * {@code null} — which the value-returning form rejects (ADR-0030). Two interfaces is the cost of
 * that rejection being available.
 *
 * @see JdbcTxRunner#inTransactionWithoutResult(TxVoidCallback)
 * @see TxCallback
 */
@FunctionalInterface
public interface TxVoidCallback {

  /**
   * Runs the transaction body.
   *
   * @param connection the transactional connection, open for the duration of this call and closed
   *     by the runner afterwards; never {@code null}
   * @throws Exception whatever the body needs to throw; any {@code Throwable} out of this method
   *     rolls the transaction back
   */
  void run(Connection connection) throws Exception;
}
