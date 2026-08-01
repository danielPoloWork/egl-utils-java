package it.d4np.utils;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A value computed on first use, at most once, and safely visible to every thread afterwards.
 *
 * <pre>{@code
 * private static final Lazy<Config> CONFIG = Lazy.of(ConfigLoader::load);
 * ...
 * CONFIG.get().timeout();   // loads on the first call, a volatile read on every later one
 * }</pre>
 *
 * <p><strong>Failure policy — retry by default, memoizing on request.</strong> The two behave
 * differently only when the initializer throws:
 *
 * <table border="1">
 *   <caption>Choosing a policy</caption>
 *   <tr><th>Factory</th><th>An initializer that throws</th><th>Use when</th></tr>
 *   <tr>
 *     <td>{@link #of(Supplier)}</td>
 *     <td>the exception propagates and is <em>not</em> remembered; the next {@code get()} tries again</td>
 *     <td>the default — the failure may be transient (absent config at startup, a network blip)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #memoizingFailures(Supplier)}</td>
 *     <td>the first failure is remembered and rethrown, unchanged, on every later {@code get()}</td>
 *     <td>the failure is expensive and deterministic, so retrying only burns time</td>
 *   </tr>
 * </table>
 *
 * <p>Retry is the default deliberately: memoizing permanently poisons a singleton after one
 * transient fault, which is the harder failure to diagnose (RFC-0001 records the trade-off and
 * ADR-002's alternatives). The cost of retry is a <strong>thundering herd</strong> — a persistently
 * failing initializer is re-run by every caller — so an initializer whose failure is both expensive
 * and permanent belongs on {@code memoizingFailures}.
 *
 * <p><strong>A memoized failure is rethrown as the same instance</strong>, so its stack trace names
 * the <em>first</em> call site, not yours. That is what "remembers the failure" means, and it is
 * the documented reason this policy is opt-in rather than default.
 *
 * <p><strong>Two things are defects rather than supported modes</strong>, and both fail loudly with
 * {@link IllegalStateException}:
 *
 * <ul>
 *   <li>an initializer that returns {@code null} — {@code get()} never returns {@code null}, so
 *       there is no value to publish (spec §5);
 *   <li>an initializer that calls {@code get()} on the same {@code Lazy} — a re-entrant initializer
 *       cannot terminate in a way that preserves "at most once", and silently recursing would end
 *       in a {@link StackOverflowError} that names nothing you wrote.
 * </ul>
 *
 * <p><strong>Thread safety.</strong> Fully thread-safe, and the guarantee is asserted rather than
 * claimed (spec §6: a thread-safety claim without a named jcstress test is not a claim). {@code
 * LazyPublicationStress} proves that two threads racing on {@code get()} observe one fully
 * initialized value and run the initializer exactly once; {@code LazyMemoizedFailureStress} proves
 * the same for the remembered-failure path. Publication rests on a single {@code volatile} field:
 * the initializer's writes happen before the volatile write that publishes the value, and any
 * thread that reads the value through that field sees them.
 *
 * <p>What {@code Lazy} does <em>not</em> promise is anything about the value it holds: a mutable
 * payload is exactly as thread-safe as its own type, and {@code Lazy} only guarantees that every
 * thread sees the same, fully constructed instance.
 *
 * <p><strong>Performance (NFR-01, ≤ 2 ns/op).</strong> The steady-state path is one {@code
 * volatile} read and one branch — no lock, no allocation. {@link #get()} is kept deliberately small
 * and the slow path lives in a separate method, so the hot path stays inlinable; folding
 * initialization into {@code get()} would put a monitor and an exception path into every call
 * site's inlining budget. Measured by {@code LazyGetBenchmark} against the raw volatile read of
 * {@code PublicationBaselineBenchmark}, which is the floor this budget is made of.
 *
 * @param <T> the type of the lazily computed value; never {@code null}
 * @see Result
 */
public final class Lazy<T> {

  /**
   * Guards initialization.
   *
   * <p>A <strong>private</strong> monitor, not {@code synchronized(this)}: publishing the lock a
   * type uses internally lets a caller stall or deadlock it from the outside, and monitor ownership
   * is not part of this class's contract. The cost is one extra object per instance, which is
   * nothing beside a value worth deferring.
   */
  private final Object lock = new Object();

  private final Supplier<? extends T> initializer;

  /** Whether the {@link #memoizingFailures(Supplier)} policy is in force; see the class Javadoc. */
  private final boolean memoizeFailures;

  /**
   * The computed value, or {@code null} while initialization has not yet succeeded.
   *
   * <p>{@code null} <em>is</em> the "not initialized" marker, which needs no sentinel object
   * precisely because a {@code null} result is rejected as a defect — the two rules hold each other
   * up. This is the only field the steady-state path touches, and {@code volatile} is what makes
   * that one read sufficient: it both orders the initializer's writes before publication and makes
   * them visible to every reader that observes the reference.
   */
  @Nullable private volatile T value;

  /** The remembered failure under the memoizing policy. Guarded by {@link #lock}. */
  @Nullable private Throwable failure;

  /** Set while the initializer is running, so a re-entrant call can be told apart from a race. */
  private boolean initializing;

  private Lazy(Supplier<? extends T> initializer, boolean memoizeFailures) {
    this.initializer = Objects.requireNonNull(initializer, "Lazy initializer must not be null");
    this.memoizeFailures = memoizeFailures;
  }

  /**
   * A {@code Lazy} that <strong>retries</strong> after a failed initialization — the default
   * policy.
   *
   * @param <T> the type of the value
   * @param initializer computes the value on first use; must not be {@code null}, and must not
   *     return {@code null}
   * @return an uninitialized {@code Lazy}; the initializer is not called here
   * @throws NullPointerException if {@code initializer} is {@code null}
   */
  public static <T> Lazy<T> of(Supplier<? extends T> initializer) {
    return new Lazy<>(initializer, false);
  }

  /**
   * A {@code Lazy} that <strong>remembers</strong> the first failed initialization and rethrows it
   * on every later {@link #get()}.
   *
   * <p>Opt in when the failure is expensive and deterministic. The rethrown exception is the
   * original instance, so its stack trace points at the first caller.
   *
   * @param <T> the type of the value
   * @param initializer computes the value on first use; must not be {@code null}, and must not
   *     return {@code null}
   * @return an uninitialized {@code Lazy}; the initializer is not called here
   * @throws NullPointerException if {@code initializer} is {@code null}
   */
  public static <T> Lazy<T> memoizingFailures(Supplier<? extends T> initializer) {
    return new Lazy<>(initializer, true);
  }

  /**
   * The value, computing it on the first call.
   *
   * @return the computed value; never {@code null}
   * @throws IllegalStateException if the initializer returns {@code null}, or calls this method on
   *     the same {@code Lazy}
   * @throws RuntimeException whatever the initializer throws — propagated unchanged, and under
   *     {@link #memoizingFailures(Supplier)} rethrown on every later call
   */
  public T get() {
    T current = value;
    if (current != null) {
      return current;
    }
    return initialize();
  }

  /**
   * The slow path: at most one thread computes, the rest wait and then see the published value.
   *
   * <p>Separate from {@link #get()} so the hot path stays small enough to inline (NFR-01).
   */
  private T initialize() {
    synchronized (lock) {
      T current = value;
      if (current != null) {
        // Won the race by a hair: another thread published while this one waited for the lock. This
        // is the second half of double-checked locking, and the reason it is correct here is the
        // volatile read above — without it this check could pass on a half-constructed value.
        return current;
      }
      // Rethrown as the same instance, by contract. Only RuntimeException and Error can reach the
      // field (see the catch below), so `instanceof` covers every case and needs no cast; it also
      // covers `failure == null`, which is the ordinary uninitialized state.
      Throwable remembered = failure;
      if (remembered instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (remembered instanceof Error error) {
        throw error;
      }
      if (initializing) {
        throw new IllegalStateException(
            "re-entrant Lazy.get(): the initializer called get() on the Lazy it is initializing");
      }
      initializing = true;
      try {
        T computed = initializer.get();
        if (computed == null) {
          throw new IllegalStateException(
              "Lazy initializer returned null; get() never returns null, so there is nothing to"
                  + " publish");
        }
        // The volatile write that publishes everything the initializer did.
        value = computed;
        // Returning the local rather than re-reading the field costs one less volatile read.
        return computed;
      } catch (RuntimeException | Error e) {
        // Every failure is remembered under the memoizing policy, including the two defects above:
        // a null return and a re-entrant call are as deterministic as a failure gets.
        if (memoizeFailures) {
          failure = e;
        }
        throw e;
      } finally {
        // The re-entrancy check above throws BEFORE this flag is set, so a re-entrant call never
        // reaches this finally and cannot clear the outer invocation's flag.
        initializing = false;
      }
    }
  }
}
