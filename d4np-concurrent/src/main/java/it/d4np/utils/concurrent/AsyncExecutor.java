package it.d4np.utils.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Runs work on someone else's executor and carries the caller's context with it (FR-09, NFR-02,
 * RFC-0004 §FR-09).
 *
 * <pre>{@code
 * AsyncExecutor async = AsyncExecutor.over(pool).withContext(mdcPropagator);
 *
 * CompletableFuture<Invoice> invoice = async.supply(() -> repository.load(id));
 * CompletableFuture<Void>    audited = async.run(() -> auditLog.record(event));
 * }</pre>
 *
 * <h2>Two names, not two overloads — and the reason is stronger than an ambiguity</h2>
 *
 * <p>The obvious surface is one overloaded {@code submit} taking a {@link Supplier} or a {@link
 * Runnable}. Item 4.4 met an ambiguity in exactly that shape and renamed the void form (ADR-0032),
 * so the pair was compiled before it was written down. <strong>It is not ambiguous, and what it
 * does instead is worse:</strong> all four call shapes resolve, and which overload wins depends on
 * the <em>syntax</em> of the body.
 *
 * <table border="1">
 *   <caption>Measured against a {@code Supplier}/{@code Runnable} overload pair</caption>
 *   <tr><th>Call</th><th>Binds to</th><th>Future</th></tr>
 *   <tr><td>{@code submit(() -> returnsInt())}</td><td>Supplier</td><td>{@code CompletableFuture<Integer>}</td></tr>
 *   <tr><td>{@code submit(() -> { returnsInt(); })}</td><td><strong>Runnable</strong></td><td><strong>{@code CompletableFuture<Void>}</strong></td></tr>
 * </table>
 *
 * <p>Those are the same call with a pair of braces added — to insert a log line, say — and the
 * result is silently discarded. An ambiguity is a compile error the author must fix; this compiles
 * and diverges, which is precisely the case ADR-001's naming-consequence rule says to rename. So
 * the methods are {@link #supply(Supplier)} and {@link #run(Runnable)} — the decision the JDK
 * already made in the very class this type wraps, where {@code supplyAsync} and {@code runAsync}
 * have never been an overloaded pair.
 *
 * <h2>One failure channel</h2>
 *
 * <p>A {@code CompletableFuture} is already a two-channel type, so a body that throws completes the
 * future exceptionally. The result is <strong>not</strong> wrapped in {@code Result}: two error
 * channels in one signature would force every {@code thenApply}/{@code exceptionally} in a caller's
 * chain to know which one carried the failure, which is RFC-0003's one-meaning-per-channel rule.
 *
 * <p><strong>A rejected submission is delivered as a failed future, not thrown.</strong> {@link
 * Executor#execute(Runnable)} on a saturated pool under an abort policy throws {@link
 * RejectedExecutionException} synchronously on the submitting thread — which would give one
 * operation two failure paths, a {@code try}/{@code catch} <em>and</em> an {@code exceptionally},
 * with the caller's chain silently never running. The cost is stated rather than hidden: a caller
 * who discards the returned future discards the rejection with it, which is the general hazard of
 * ignoring a future rather than a new one.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and safe to share. {@link #withContext(ContextPropagator)} returns a <em>new</em>
 * instance rather than mutating, which is what lets this be a claim about the type instead of about
 * its users. It takes an {@link Executor}, not an {@code ExecutorService}: {@code supply} and
 * {@code run} need only {@code execute}, and the narrower parameter accepts a {@link
 * ManagedThreadPool}, a raw {@code ExecutorService}, a {@code ForkJoinPool} or a virtual-thread
 * executor without this module naming any of them.
 *
 * @see ContextPropagator
 * @see ContextSnapshot
 */
public final class AsyncExecutor {

  private final Executor delegate;
  private final ContextPropagator propagator;

  private AsyncExecutor(Executor delegate, ContextPropagator propagator) {
    this.delegate = delegate;
    this.propagator = propagator;
  }

  /**
   * Wraps an executor, carrying no context until one is supplied.
   *
   * @param delegate where the work runs; this type never creates or owns a thread
   * @return an executor that propagates nothing
   */
  public static AsyncExecutor over(Executor delegate) {
    return new AsyncExecutor(
        Objects.requireNonNull(delegate, "delegate"), ContextPropagator.none());
  }

  /**
   * Returns a copy of this executor that captures and installs the given context.
   *
   * <p>A new instance rather than a mutation, so an {@code AsyncExecutor} already handed to another
   * thread cannot change behaviour underneath it.
   *
   * @param propagator what to carry across the thread boundary
   * @return a new executor over the same delegate
   */
  public AsyncExecutor withContext(ContextPropagator propagator) {
    return new AsyncExecutor(delegate, Objects.requireNonNull(propagator, "propagator"));
  }

  /**
   * Runs a value-returning body asynchronously.
   *
   * <p>Named {@code supply} rather than {@code submit} — see the class Javadoc for the measurement
   * behind that.
   *
   * @param <T> the body's result type
   * @param body what to run; must not return {@code null}
   * @return a future completing with the body's value, or completing exceptionally with whatever
   *     the body threw, or with a {@link RejectedExecutionException} if the delegate refused it
   */
  public <T> CompletableFuture<T> supply(Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    return dispatch(body);
  }

  /**
   * Runs a body with no result asynchronously.
   *
   * @param body what to run
   * @return a future completing with {@code null} when the body returns, or exceptionally with
   *     whatever it threw
   */
  public CompletableFuture<Void> run(Runnable body) {
    Objects.requireNonNull(body, "body");
    return dispatch(
        () -> {
          body.run();
          return null;
        });
  }

  /**
   * Captures the context, hands the work to the delegate, and completes the future.
   *
   * <p><strong>Deliberately not built on {@code CompletableFuture.supplyAsync(supplier, executor)},
   * and the reason was measured rather than assumed.</strong> That form lets the delegate's {@link
   * RejectedExecutionException} escape <em>synchronously on the submitting thread</em> — confirmed
   * identical on Temurin 17.0.20.1+1 and 21.0.12.1+1 for both {@code supplyAsync} and {@code
   * runAsync}, so this is the JDK's consistent behaviour and not a version quirk to code around. It
   * is exactly the two-failure-paths shape this contract exists to remove: a caller would need a
   * {@code try}/{@code catch} <em>and</em> an {@code exceptionally} for one operation. Completing
   * the future by hand is what makes "every failure arrives through the future" true of rejection
   * as well as of the body.
   *
   * @param <T> the body's result type
   * @param body the body, already adapted to a value-returning shape
   * @return the future
   */
  private <T> CompletableFuture<T> dispatch(Supplier<T> body) {
    // Captured on the SUBMITTING thread, before anything is handed over -- that is the whole point,
    // and doing it inside the task would capture the worker's context instead of the caller's.
    ContextSnapshot snapshot = propagator.capture();
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      delegate.execute(
          () -> {
            // install() on the WORKER thread; the Scope puts back whatever was here before, so a
            // pooled thread carries nothing of this task into the next one. try-with-resources
            // rather than a finally block, so the restore also runs when the body throws.
            try (ContextSnapshot.Scope scope = snapshot.install()) {
              future.complete(body.get());
            } catch (RuntimeException | Error thrown) {
              // The body's failure is the future's failure -- one channel. Error is caught for the
              // same reason: a future nobody completes is a caller waiting forever, which is a
              // worse
              // outcome than an Error the caller can see. It is not swallowed; it is delivered.
              future.completeExceptionally(thrown);
            }
          });
    } catch (RejectedExecutionException refused) {
      // Delivered through the future rather than thrown, so one operation has one failure path.
      future.completeExceptionally(refused);
    }
    return future;
  }

  /**
   * Names the delegate's type and the propagator, never a task.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "AsyncExecutor[delegate="
        + delegate.getClass().getName()
        + ", context="
        + propagator
        + "]";
  }
}
