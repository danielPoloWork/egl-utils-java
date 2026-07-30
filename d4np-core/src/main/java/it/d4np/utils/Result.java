package it.d4np.utils;

import java.util.Objects;
import java.util.function.Function;

/**
 * An <em>expected</em> outcome carried as a value: either {@link Ok} with a payload or {@link Err}
 * with an {@link ErrorDetail}.
 *
 * <p><strong>When to use this instead of throwing (ADR-002).</strong> The rule is mechanical, and
 * it is the whole reason this type exists:
 *
 * <ul>
 *   <li>the caller is <em>expected to branch</em> on the failure — insufficient funds, duplicate
 *       email — so it is a {@code Result.Err} carrying an {@link ErrorDetail};
 *   <li>the failure <em>aborts the use case</em> and only a boundary handler cares — a broken
 *       invariant, an unauthorized operation — so it is a thrown {@link BusinessException};
 *   <li>the failure is a <em>defect or an absent binding</em> the caller cannot sensibly branch on,
 *       so it is an unchecked exception from this library (RFC-0001).
 * </ul>
 *
 * <p>The two channels are designed to meet at a boundary, which is what {@link
 * #orElseThrow(Function)} is for:
 *
 * <pre>{@code
 * Result<Account> result = accounts.debit(id, amount);
 * Account account = result.orElseThrow(BusinessException::new);   // 422 via FR-19
 * }</pre>
 *
 * <p><strong>Exactly two arms, enforced by the compiler.</strong> This interface is {@code sealed}
 * and permits only {@link Ok} and {@link Err}, so an {@code instanceof} chain over both is provably
 * exhaustive and no consumer can add a third state. The published baseline is JDK 17 (NFR-07),
 * where pattern matching for {@code switch} is not yet final, so the idiomatic consumer-side form
 * is:
 *
 * <pre>{@code
 * if (result instanceof Result.Ok<Account> ok) {
 *   use(ok.value());
 * } else if (result instanceof Result.Err<Account> err) {
 *   report(err.error());
 * }
 * }</pre>
 *
 * <p><strong>{@code Ok} never holds {@code null}</strong> — {@code null} is an absent value, not a
 * successful outcome, and permitting it would push a null check into every consumer of every {@code
 * Result}. A null payload is rejected at construction with {@link NullPointerException}. The
 * consequence for a use case that succeeds without producing anything is stated in ADR-0012 and is
 * not yet resolved: a successful {@code Result<Void>} cannot be constructed, because {@code Void}
 * has no non-null instance. Model such an operation on a payload that means something to the
 * caller, or throw, until the first real consumer settles the question.
 *
 * <p><strong>Nullability.</strong> No method here returns {@code null}, and no argument may be
 * {@code null}: every operation validates its function argument before inspecting the arm, so the
 * failure mode does not depend on whether the receiver happens to be {@code Ok} or {@code Err} (the
 * discipline {@link java.util.Optional} follows). A mapping function that <em>returns</em> {@code
 * null} is likewise a programming error and raises {@link NullPointerException}.
 *
 * <p><strong>Thread safety.</strong> Both arms are immutable and safely publishable without
 * synchronisation, asserted by a named jcstress harness rather than claimed (spec §6; {@code
 * ImmutablePublicationStress}). An {@code Ok} is only as immutable as the payload a caller put in
 * it.
 *
 * @param <T> the payload type of a successful outcome
 * @see ErrorDetail
 * @see BusinessException
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

  /**
   * A successful outcome carrying {@code value}.
   *
   * @param <T> the payload type
   * @param value the payload; must not be {@code null}
   * @return an {@link Ok}
   * @throws NullPointerException if {@code value} is {@code null}
   */
  static <T> Result<T> ok(T value) {
    return new Ok<>(value);
  }

  /**
   * A failed outcome carrying {@code error}.
   *
   * @param <T> the payload type the operation would have produced
   * @param error what went wrong; must not be {@code null}
   * @return an {@link Err}
   * @throws NullPointerException if {@code error} is {@code null}
   */
  static <T> Result<T> err(ErrorDetail error) {
    return new Err<>(error);
  }

  /**
   * Transforms the payload of an {@link Ok}, leaving an {@link Err} untouched.
   *
   * <p>On an {@code Err} the {@code mapper} is never invoked and the {@link ErrorDetail} is carried
   * through unchanged.
   *
   * @param <U> the payload type produced by {@code mapper}
   * @param mapper applied to the payload of an {@code Ok}; must not be {@code null}
   * @return an {@code Ok} of the mapped payload, or this failure re-typed
   * @throws NullPointerException if {@code mapper} is {@code null}, or if it returns {@code null}
   */
  <U> Result<U> map(Function<? super T, ? extends U> mapper);

  /**
   * Chains an operation that itself returns a {@code Result}, leaving an {@link Err} untouched.
   *
   * <p>Use this rather than {@link #map(Function)} when the next step can fail: {@code map} would
   * produce a {@code Result<Result<U>>}.
   *
   * @param <U> the payload type of the {@code Result} produced by {@code mapper}
   * @param mapper applied to the payload of an {@code Ok}; must not be {@code null}
   * @return the {@code Result} produced by {@code mapper}, or this failure re-typed
   * @throws NullPointerException if {@code mapper} is {@code null}, or if it returns {@code null}
   */
  <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> mapper);

  /**
   * Turns an {@link Err} back into an {@link Ok} by computing a fallback payload from the {@link
   * ErrorDetail}; leaves an {@code Ok} untouched.
   *
   * <p>On an {@code Ok} the {@code recovery} function is never invoked and the receiver is returned
   * as is. A {@code Result} is returned rather than a bare payload so that a recovery can sit in
   * the middle of a chain.
   *
   * @param recovery applied to the {@link ErrorDetail} of an {@code Err}; must not be {@code null}
   * @return an {@code Ok} — either the receiver or the recovered payload
   * @throws NullPointerException if {@code recovery} is {@code null}, or if it returns {@code null}
   */
  Result<T> recover(Function<? super ErrorDetail, ? extends T> recovery);

  /**
   * Returns the payload of an {@link Ok}, or throws the exception that {@code exceptionMapper}
   * derives from the {@link ErrorDetail} of an {@link Err}.
   *
   * <p>This is the documented bridge from the value channel to the exception channel at a use-case
   * boundary (ADR-002): {@code orElseThrow(BusinessException::new)}.
   *
   * <p>{@code X} is bounded by {@link Throwable} rather than {@link RuntimeException}, matching
   * {@link java.util.Optional#orElseThrow(java.util.function.Supplier)}. Nothing in this library
   * throws a checked exception of its own; a caller who maps to one is choosing to have the
   * compiler force the handling, and this method does not stand in the way.
   *
   * @param <X> the exception type to throw
   * @param exceptionMapper applied to the {@link ErrorDetail} of an {@code Err}; must not be {@code
   *     null}
   * @return the payload of an {@code Ok}; never {@code null}
   * @throws X if the receiver is an {@code Err}
   * @throws NullPointerException if {@code exceptionMapper} is {@code null}, or if it returns
   *     {@code null}
   */
  <X extends Throwable> T orElseThrow(Function<? super ErrorDetail, ? extends X> exceptionMapper)
      throws X;

  /**
   * The successful arm of a {@link Result}, carrying a non-null payload.
   *
   * @param <T> the payload type
   * @param value the payload; never {@code null}
   */
  record Ok<T>(T value) implements Result<T> {

    /**
     * Canonical constructor: rejects a null payload (FR-17).
     *
     * @param value the payload; must not be {@code null}
     */
    public Ok {
      Objects.requireNonNull(
          value, "Result.Ok forbids a null payload: a null is an absent value, not an outcome");
    }

    @Override
    public <U> Result<U> map(Function<? super T, ? extends U> mapper) {
      Objects.requireNonNull(mapper, "mapper must not be null");
      return new Ok<>(mapper.apply(value));
    }

    @Override
    public <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> mapper) {
      Objects.requireNonNull(mapper, "mapper must not be null");
      return Objects.requireNonNull(mapper.apply(value), "flatMap mapper must not return null");
    }

    @Override
    public Result<T> recover(Function<? super ErrorDetail, ? extends T> recovery) {
      Objects.requireNonNull(recovery, "recovery must not be null");
      return this;
    }

    @Override
    public <X extends Throwable> T orElseThrow(
        Function<? super ErrorDetail, ? extends X> exceptionMapper) {
      Objects.requireNonNull(exceptionMapper, "exceptionMapper must not be null");
      return value;
    }
  }

  /**
   * The failed arm of a {@link Result}, carrying what went wrong.
   *
   * <p>The type parameter is phantom: an {@code Err} holds no {@code T}, and {@link #map(Function)}
   * and {@link #flatMap(Function)} therefore re-type the failure rather than transforming anything.
   *
   * @param <T> the payload type the operation would have produced
   * @param error what went wrong; never {@code null}
   */
  record Err<T>(ErrorDetail error) implements Result<T> {

    /**
     * Canonical constructor: rejects a null {@link ErrorDetail}.
     *
     * @param error what went wrong; must not be {@code null}
     */
    public Err {
      Objects.requireNonNull(error, "Result.Err must carry an ErrorDetail");
    }

    @Override
    public <U> Result<U> map(Function<? super T, ? extends U> mapper) {
      Objects.requireNonNull(mapper, "mapper must not be null");
      return new Err<>(error);
    }

    @Override
    public <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> mapper) {
      Objects.requireNonNull(mapper, "mapper must not be null");
      return new Err<>(error);
    }

    @Override
    public Result<T> recover(Function<? super ErrorDetail, ? extends T> recovery) {
      Objects.requireNonNull(recovery, "recovery must not be null");
      return new Ok<>(recovery.apply(error));
    }

    @Override
    public <X extends Throwable> T orElseThrow(
        Function<? super ErrorDetail, ? extends X> exceptionMapper) throws X {
      Objects.requireNonNull(exceptionMapper, "exceptionMapper must not be null");
      throw Objects.requireNonNull(
          exceptionMapper.apply(error), "exceptionMapper must not return null");
    }
  }
}
