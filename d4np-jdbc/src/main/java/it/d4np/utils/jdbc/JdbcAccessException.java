package it.d4np.utils.jdbc;

import java.sql.SQLException;

/**
 * A JDBC operation failed (FR-05, FR-06, RFC-0003).
 *
 * <p><strong>Not a {@code BusinessException}, and the reason is the status code.</strong> FR-19
 * maps {@code BusinessException} to <strong>422</strong> — a rule violation an end user can act on
 * — and this to <strong>500 plus an alert</strong>. A unique-constraint collision is a business
 * outcome in some applications, but that judgement belongs to the application: the library sees a
 * driver reporting that a statement did not run, which is infrastructure. Were it a subclass, a
 * handler whose {@code catch} clauses ran in the wrong order would report an operations failure as
 * a client error — the ordering trap {@code StrategyNotFoundException} already documents.
 *
 * <p><strong>{@code SQLException} is checked; this is not.</strong> RFC-0001's rule — no published
 * method of this library throws a checked exception — holds for every module, so JDBC's exception
 * is wrapped here at every boundary rather than declared.
 *
 * <h2>The message carries no SQL and no parameter value</h2>
 *
 * <p>Compliance control <strong>C-01</strong>. {@link #getMessage()} is a fixed sentence naming the
 * operation, plus the {@linkplain #sqlState() SQLState} and the {@linkplain #vendorCode() vendor
 * code}. Nothing in it comes from the driver's own message, because that is where the failing
 * statement lives: driver messages routinely embed the SQL, and a bound parameter can appear in a
 * vendor message — so a message built by concatenating the driver's is a credential-or-PII channel
 * that FR-19 turns into an RFC 7807 body. ADR-0020 established the rule for constraint violations
 * and ADR-0026 restated it in the form that survives an unchecked exception; this is the same rule
 * a third time.
 *
 * <p><strong>The two codes are safe to echo, and they are the two things worth echoing.</strong> An
 * SQLState is a five-character standard code and a vendor code is an integer; neither can carry a
 * value from a row or a parameter. Together they are what turns "the database said no" into a
 * specific diagnosis — {@code 23505} is a unique-constraint violation on every driver that follows
 * the standard.
 *
 * <p><strong>The cause is kept, and it is not safe to render.</strong> The driver's exception is
 * the diagnosis and belongs in the log, which is the line {@code ErrorDetail} already draws:
 * caller-facing message, process-facing cause. FR-19's fallback handler (item 7.1) must not put a
 * cause chain's {@code getMessage()} into a 7807 body — a requirement rather than a caution, for
 * the reason item 4.1 measured on Jackson's side of the same rule.
 *
 * <h2>Both payload fields are a {@code String} and an {@code int}, deliberately</h2>
 *
 * <p>Every {@link Throwable} is {@link java.io.Serializable}, so a field of the driver's own type —
 * or of a consumer's — would make this exception serialisable only when that type happened to be:
 * silently, and only in the hosts that serialise. ADR-0015 recorded the trap for {@code
 * ErrorDetail} and item 2.3 recorded it again for {@code StrategyNotFoundException}. A primitive
 * and a {@code String} cannot reproduce it.
 *
 * <h2>A fault this library detected reports no code</h2>
 *
 * <p>Not every failure comes from a driver. {@link SimpleJdbcExecutor#queryOne} refuses a second
 * row, and no {@code SQLException} was involved — so {@link #sqlState()} is <strong>empty</strong>,
 * {@link #vendorCode()} is <strong>zero</strong>, and {@link #getCause()} is {@code null}. The
 * three agree, and that combination is the signal: <em>this library decided, no driver
 * reported</em>.
 *
 * <p>Fabricating a plausible code instead — SQLState {@code 21000} is the standard's own
 * "cardinality violation" and would have fitted — was rejected. The SQLState field means *what the
 * driver said*, and writing our own conclusion into it makes a consumer that branches on the code
 * unable to tell the two apart. {@code JsonConversionException} took the same shape in item 4.1 for
 * the same reason: a refusal this library makes gets the cause-less form.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable apart from the mutable state every {@link Throwable} carries. Its two fields are set
 * at construction from values already materialised, so a driver reusing its exception cannot change
 * what this one reports.
 *
 * @see SimpleJdbcExecutor
 */
public final class JdbcAccessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What the driver reported, or {@code ""} when this library detected the fault itself. */
  private final String sqlState;

  /** The driver's vendor code, or {@code 0} when this library detected the fault itself. */
  private final int vendorCode;

  /**
   * Package-private: only this module's own operations may decide that data access failed.
   *
   * @param operation a fixed, code-derived label — never text from the driver, never the SQL
   * @param cause the driver's exception, read for its two codes and kept for the log
   */
  JdbcAccessException(String operation, SQLException cause) {
    super(describe(operation, cause), cause);
    String state = cause.getSQLState();
    this.sqlState = state == null ? "" : state;
    this.vendorCode = cause.getErrorCode();
  }

  /**
   * The cause-less form, for a refusal this library makes rather than one a driver reports.
   *
   * @param message a fixed sentence built in this module; carries no SQL and no parameter value
   */
  JdbcAccessException(String message) {
    super(message);
    this.sqlState = "";
    this.vendorCode = 0;
  }

  /**
   * The SQLState the driver reported.
   *
   * <p>Five characters where a driver follows the standard, and the first two are the class —
   * {@code 23} is an integrity-constraint violation, {@code 08} a connection exception. Branching
   * on the class is the portable move; branching on the full code is not, and branching on {@link
   * #vendorCode()} is portable only within one vendor.
   *
   * @return the SQLState, or {@code ""} when the driver reported none <em>or</em> when this library
   *     detected the fault itself — see the class documentation for how to tell those apart
   */
  public String sqlState() {
    return sqlState;
  }

  /**
   * The driver's own error code.
   *
   * @return the vendor code, or {@code 0} when this library detected the fault itself
   */
  public int vendorCode() {
    return vendorCode;
  }

  /**
   * Builds the one message this exception ever carries from a driver failure.
   *
   * <p>The single place control C-01 is applied on this path: the operation label is a constant
   * from {@link SimpleJdbcExecutor}, and the two codes are the only things read off the driver's
   * exception. {@code cause.getMessage()} is never touched.
   *
   * @param operation the fixed label naming what was being attempted
   * @param cause the driver's exception, read for its codes only
   * @return caller-facing text carrying no SQL and no parameter value
   */
  private static String describe(String operation, SQLException cause) {
    String state = cause.getSQLState();
    return "cannot "
        + operation
        + ": the database rejected it [SQLState "
        + (state == null || state.isEmpty() ? "unreported" : state)
        + ", vendor code "
        + cause.getErrorCode()
        + "]";
  }
}
