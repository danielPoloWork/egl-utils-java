package it.d4np.utils;

import java.util.Objects;

/**
 * The unchecked base for a rule violation that aborts the use case, carrying the same {@link
 * ErrorDetail} the value channel uses.
 *
 * <p><strong>When to throw this rather than return {@link Result.Err} (ADR-002).</strong> Throw
 * when the failure ends the use case and only a boundary handler cares — a broken invariant, an
 * unauthorized operation. Return an {@code Err} when the caller is expected to branch on the
 * outcome. The bridge between the two is {@link Result#orElseThrow(java.util.function.Function)}:
 *
 * <pre>{@code
 * Account account = accounts.debit(id, amount).orElseThrow(BusinessException::new);
 * }</pre>
 *
 * <p><strong>Unchecked on purpose.</strong> Checked exceptions compose with nothing modern —
 * lambdas, streams and {@code CompletableFuture} chains all force sneaky-throw or wrapper noise,
 * which is why FR-09's {@code AsyncExecutor} would otherwise pay for it in boilerplate. ADR-002
 * records the two rejected alternatives (checked everywhere; {@code Result} everywhere with no
 * exceptions) and why each loses.
 *
 * <p><strong>Designed to be extended.</strong> FR-18 specifies a <em>base</em>, so this class is
 * not final: a consuming domain subclasses it per rule family ({@code class InsufficientFunds
 * extends BusinessException}) and keeps the single 422 mapping. Subclasses inherit the {@link
 * #error()} contract and must keep it non-null.
 *
 * <p><strong>The message and the cause come from the detail</strong> rather than being passed
 * separately, so that {@link #getMessage()} and {@link #getCause()} can never disagree with {@link
 * #error()}. Consumers reading only the JDK surface still get the right text in a log line;
 * consumers that know this library read {@link #error()} for the machine-readable code.
 *
 * <p><strong>Mapping.</strong> FR-19's {@code GlobalExceptionHandler} maps this — and only this —
 * to an RFC 7807 {@code application/problem+json} response with status <strong>422</strong>.
 * Because {@link ErrorDetail#message()} therefore reaches the HTTP client, it must not carry
 * secrets, credentials or PII; diagnostics that must stay in the process belong in {@link
 * ErrorDetail#cause()}.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries (stack trace, suppressed exceptions).
 *
 * @see Result
 * @see ErrorDetail
 */
public class BusinessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Never null; the constructor rejects a null detail before {@code super()} is even reached. */
  private final ErrorDetail error;

  /**
   * Creates an exception reporting {@code error}.
   *
   * @param error what went wrong; must not be {@code null}
   * @throws NullPointerException if {@code error} is {@code null}
   */
  public BusinessException(ErrorDetail error) {
    super(
        Objects.requireNonNull(error, "BusinessException must carry an ErrorDetail").message(),
        error.cause());
    this.error = error;
  }

  /**
   * The failure this exception reports.
   *
   * @return the detail passed at construction; never {@code null}
   */
  public ErrorDetail error() {
    return error;
  }
}
