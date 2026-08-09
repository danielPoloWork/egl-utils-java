package it.d4np.utils.jdbc;

import java.sql.Connection;

/**
 * The six call shapes that established ADR-0032, kept compiled so the ambiguity cannot come back.
 *
 * <p><strong>This file asserts nothing at run time and is not a test.</strong> What it checks is
 * checked by <em>compiling</em>: every shape a caller can plausibly write against {@link
 * JdbcTxRunner} appears below, so a future overload that made one of them ambiguous again would
 * break the build rather than a consumer's.
 *
 * <p>The shape that decided the naming is {@link #valueBearingStatementExpression}. Against the
 * overload pair RFC-0003's surface table specified — {@code inTransaction} for both the value form
 * and the void form — it does not compile:
 *
 * <pre>{@code
 * runner.inTransaction(c -> executor.update(sql, id));
 * // reference to inTransaction is ambiguous
 * //   both method inTransaction(TxCallback<T>) and method inTransaction(TxVoidCallback) match
 * }</pre>
 *
 * <p>An implicit lambda whose body is a <em>statement expression with a value</em> is compatible
 * with a function type that returns a value <em>and</em> with one that returns nothing, and Java's
 * most-specific rules do not break the tie. It is also the most idiomatic single-statement
 * transaction there is. Spring met the same problem in {@code TransactionTemplate} and solved it
 * the same way, which is where {@code inTransactionWithoutResult} gets its name.
 *
 * <p>Named without a {@code Test} prefix or suffix so surefire does not offer it to the JUnit
 * Platform as a test class.
 */
final class TxCallShapes {

  private TxCallShapes() {}

  /** A body with a value, in a value context. */
  static String valueInValueContext(JdbcTxRunner runner) {
    return runner.inTransaction(connection -> "value");
  }

  /** A block body with an explicit return. */
  static Integer valueFromABlock(JdbcTxRunner runner) {
    return runner.inTransaction(connection -> countOf(connection));
  }

  /**
   * <strong>The shape ADR-0032 exists for.</strong> A statement expression that also has a value,
   * with the value discarded — one line, and ambiguous under the overload pair.
   */
  static void valueBearingStatementExpression(JdbcTxRunner runner) {
    runner.inTransaction(connection -> countOf(connection));
  }

  /** A block body with nothing to return. */
  static void voidBlock(JdbcTxRunner runner) {
    runner.inTransactionWithoutResult(
        connection -> {
          justRuns(connection);
        });
  }

  /** A method reference to a void method. */
  static void voidMethodReference(JdbcTxRunner runner) {
    runner.inTransactionWithoutResult(TxCallShapes::justRuns);
  }

  /** A method reference to a value-returning method, with the value discarded. */
  static void valueMethodReference(JdbcTxRunner runner) {
    runner.inTransaction(TxCallShapes::countOf);
  }

  /** Stands in for any body that returns something. */
  private static Integer countOf(Connection connection) {
    return connection == null ? 0 : 1;
  }

  /** Stands in for any body that returns nothing. */
  private static void justRuns(Connection connection) {
    // deliberately empty: the shape is what is being checked, not the work
  }
}
