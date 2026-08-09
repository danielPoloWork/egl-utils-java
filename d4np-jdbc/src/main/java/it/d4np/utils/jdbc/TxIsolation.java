package it.d4np.utils.jdbc;

import java.sql.Connection;

/**
 * The transaction isolation level a {@link JdbcTxRunner} applies, or {@link #DEFAULT} to apply none
 * (FR-06, RFC-0003).
 *
 * <p><strong>An enum rather than JDBC's {@code int} constants, and the reason is what the {@code
 * int} lets through.</strong> {@code Connection.setTransactionIsolation} takes an {@code int}, so
 * every {@code int} in the language type-checks — including {@code 3}, which is nothing, and
 * including {@link Connection#TRANSACTION_NONE}, which is a real JDBC constant meaning *this
 * connection does not support transactions at all*. Passing that to a transaction runner is a
 * question with no sensible answer, and the type is what stops it being asked.
 *
 * <h2>{@code DEFAULT} means untouched, not "read committed"</h2>
 *
 * <p>The distinction is the one this enum exists to make. A {@code DataSource} in a real host is a
 * pool whose isolation level is the host's own configured decision — sometimes deliberately raised
 * for a whole service. A library that quietly wrote {@code READ_COMMITTED} over it would be
 * changing the meaning of SQL it did not write, which is the single line RFC-0003 drew around this
 * module's behaviour. So {@code DEFAULT} is not a level at all: it is the instruction *not to call*
 * {@code setTransactionIsolation}.
 *
 * @see JdbcTxRunner#on(javax.sql.DataSource, TxIsolation)
 */
public enum TxIsolation {

  /**
   * Leave the connection's level exactly as the pool handed it over.
   *
   * <p>{@code setTransactionIsolation} is never called, so nothing has to be restored either. This
   * is what {@link JdbcTxRunner#on(javax.sql.DataSource)} uses.
   */
  DEFAULT(-1),

  /** Dirty reads are possible; the weakest level any database offers. */
  READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

  /** Only committed data is read; non-repeatable reads and phantoms are still possible. */
  READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

  /** A row read twice reads the same; phantoms are still possible. */
  REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

  /** Transactions behave as if run one after another; the strongest and the most contended. */
  SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

  /** The JDBC constant, or {@code -1} for {@link #DEFAULT}, which names no level. */
  private final int level;

  TxIsolation(int level) {
    this.level = level;
  }

  /**
   * The JDBC constant this level maps to.
   *
   * <p>Package-private: the {@code int} is what this type exists to keep out of a caller's hands,
   * so publishing it would hand back the vocabulary the enum replaced. A host that genuinely needs
   * to call {@code setTransactionIsolation} itself holds the {@code Connection} and can.
   *
   * @return the {@code java.sql.Connection} constant
   * @throws IllegalStateException if called on {@link #DEFAULT}, which names no level — a defect in
   *     this module rather than a caller's, and one no caller can trigger
   */
  int level() {
    if (this == DEFAULT) {
      throw new IllegalStateException("DEFAULT names no isolation level; it means do not set one");
    }
    return level;
  }
}
