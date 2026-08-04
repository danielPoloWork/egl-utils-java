package it.d4np.utils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Execution timing, measured here and reported through a sink chosen once (FR-15, RFC-0002).
 *
 * <pre>{@code
 * // once, at start-up — the sink is fixed at this moment and never re-resolved
 * ExecutionTimeMetricAspect timing = ExecutionTimeMetricAspect.create();          // logs (DEBUG)
 * ExecutionTimeMetricAspect timing = ExecutionTimeMetricAspect.using(recorder);   // Micrometer, etc.
 *
 * Order placed = timing.call("OrderService.place", () -> service.place(order));
 * timing.run("OrderService.audit", () -> service.audit(order));
 *
 * // and from an @Around advice in spring-adapter, where proceed() throws Throwable
 * return timing.time(joinPoint.getSignature().toShortString(), joinPoint::proceed);
 * }</pre>
 *
 * <p><strong>Despite the name this class is not an AspectJ aspect, and cannot be.</strong> Spec §2
 * names the type {@code ExecutionTimeMetricAspect} and spec §3 puts the AspectJ/Spring-AOP binding
 * in {@code spring-adapter} — core may not see {@code aspectjrt} any more than it may see
 * Micrometer (ADR-001). So what lives here is the <em>advice body</em>: the measurement, the sink
 * selection and the failure policy, all of which are testable with no weaving at all. The adapter
 * contributes the {@code @Aspect}, the pointcut and the annotation that selects the methods, and
 * its advice is one line — {@link #time} with {@code joinPoint::proceed}. The name is kept as
 * specified so the spec and the code do not drift apart over a word (ADR-0021).
 *
 * <p><strong>The sink is resolved once, at construction.</strong> A per-invocation lookup would pay
 * for itself on every call, and worse, it would let behaviour change mid-run as a registry appears
 * or goes away — so two measurements of the same method would not be comparable, which is the one
 * thing a timing number has to be. {@link #create()} installs {@link LoggingExecutionTimeRecorder};
 * a host with a metrics backend hands the recorder to {@link #using(ExecutionTimeRecorder)} at
 * wiring time.
 *
 * <p><strong>Instrumentation never breaks the measured call.</strong> A recorder that throws is
 * caught, and the measured method's outcome — its return value or its exception — is unchanged. A
 * metrics backend that is down is an operational event; a business method that fails
 * <em>because</em> the metrics backend is down is an outage the instrumentation caused. The
 * swallowed failure is reported <strong>at most once per aspect instance</strong>, since an
 * unbounded warning on a sink that fails on every call is its own denial of service — the second
 * and later failures are silent by design, and that is the trade this makes deliberately (RFC-0002
 * §FR-15).
 *
 * <p><strong>Failures are timed too.</strong> Every method records exactly one measurement, in a
 * {@code finally}, with {@code failed} distinguishing a call that threw from one that returned.
 * Recording only successes biases every latency number, usually in the direction that hides the
 * problem, because the failure path is so often the slow one.
 *
 * <p><strong>Three entry points, because the checked-exception shapes genuinely differ.</strong>
 * {@link #time} carries {@code throws Throwable} so an {@code @Around} advice can pass {@code
 * proceed()} straight through — anything narrower would force the adapter to wrap the measured
 * method's exception, changing what the caller sees. {@link #call} and {@link #run} exist for
 * ordinary code, which should not have to catch {@code Throwable} to time a lambda. They repeat the
 * eight-line measurement rather than delegating through {@code time}: laundering a {@code
 * Throwable} back into unchecked form needs a branch that cannot be reached and therefore cannot be
 * tested, and an untestable branch is a worse cost than a duplicated {@code finally}.
 *
 * <p><strong>Timing is on {@link System#nanoTime()}</strong> — monotonic, unaffected by a clock
 * adjustment mid-call. {@link java.time.Instant#now()} would let an NTP step produce a negative
 * duration, and a negative latency in a metrics backend is not a value anyone can interpret.
 *
 * <p><strong>Thread safety.</strong> Safe for concurrent use and intended to be shared: the
 * recorder and logger are immutable references and the only mutable state is the one-shot flag
 * behind the warning, which is an {@link AtomicBoolean} so that two threads whose recorder fails
 * simultaneously produce exactly one warning between them ({@code
 * ExecutionTimeRecorderFailureStress} proves it). Nothing here synchronises the measured call — a
 * lock on the instrumentation path would serialise the methods being measured and change the
 * numbers it exists to collect.
 *
 * @see ExecutionTimeRecorder
 * @see LoggingExecutionTimeRecorder
 */
public final class ExecutionTimeMetricAspect {

  /** Where the one swallowed-recorder warning goes; see ADR-0014 for why it is injectable. */
  private static final Logger DEFAULT_LOGGER =
      System.getLogger(ExecutionTimeMetricAspect.class.getName());

  private final ExecutionTimeRecorder recorder;

  private final Logger logger;

  /**
   * Guards the one warning a failing recorder is allowed to emit.
   *
   * <p>Atomic rather than a plain {@code boolean} because "at most once" is a claim about a race:
   * two threads reaching a broken recorder at the same moment on a plain flag would both read
   * {@code false} and both warn, which is the first step of the log flood the rule exists to
   * prevent.
   */
  private final AtomicBoolean recorderFailureReported = new AtomicBoolean();

  private ExecutionTimeMetricAspect(ExecutionTimeRecorder recorder, Logger logger) {
    this.recorder = recorder;
    this.logger = logger;
  }

  /**
   * Timing that logs, for a host with no metrics backend — FR-15's fallback.
   *
   * <p>The records land at {@code DEBUG} through {@link LoggingExecutionTimeRecorder}, so a host
   * that has enabled nothing sees nothing; that is stated on the recorder and decided in ADR-0021.
   *
   * @return an aspect recording through the platform logger; never {@code null}
   */
  public static ExecutionTimeMetricAspect create() {
    return new ExecutionTimeMetricAspect(LoggingExecutionTimeRecorder.create(), DEFAULT_LOGGER);
  }

  /**
   * Timing that reports to {@code recorder} — a Micrometer-backed sink from {@code spring-adapter},
   * a host's own, or a test double.
   *
   * @param recorder the sink, fixed for the life of this instance; must not be {@code null}
   * @return an aspect recording through {@code recorder}; never {@code null}
   * @throws NullPointerException if {@code recorder} is {@code null}
   */
  public static ExecutionTimeMetricAspect using(ExecutionTimeRecorder recorder) {
    return new ExecutionTimeMetricAspect(
        Objects.requireNonNull(recorder, "execution time recorder must not be null"),
        DEFAULT_LOGGER);
  }

  /**
   * The seam that makes the swallowed-recorder warning assertable, package-private so it is not
   * public surface (ADR-0014).
   *
   * @param recorder the sink
   * @param logger where the one warning goes
   * @return an aspect over both
   * @throws NullPointerException if either argument is {@code null}
   */
  static ExecutionTimeMetricAspect using(ExecutionTimeRecorder recorder, Logger logger) {
    return new ExecutionTimeMetricAspect(
        Objects.requireNonNull(recorder, "execution time recorder must not be null"),
        Objects.requireNonNull(logger, "logger must not be null"));
  }

  /**
   * Times {@code invocation}, propagating whatever it throws unchanged — the entry point an
   * {@code @Around} advice binds to.
   *
   * @param <T> what the measured call returns
   * @param name what to record the measurement under, typically the method signature; must not be
   *     {@code null} or blank, and must not contain a value taken from the arguments
   * @param invocation the measured call, usually {@code joinPoint::proceed}; must not be {@code
   *     null}
   * @return whatever {@code invocation} returned, {@code null} included — this method reports on a
   *     call, it does not judge its result
   * @throws NullPointerException if {@code name} or {@code invocation} is {@code null}
   * @throws IllegalArgumentException if {@code name} is blank
   * @throws Throwable whatever {@code invocation} threw, unwrapped and unchanged
   */
  @Nullable
  // Checkstyle's IllegalThrows is right in general and wrong here: the declared Throwable is not
  // this
  // method's own failure, it is the measured method's, and narrowing it would force the advice in
  // spring-adapter to wrap what ProceedingJoinPoint.proceed() throws (ADR-0021). Scoped to this one
  // method; call() and run() exist so ordinary code never sees it.
  @SuppressWarnings("IllegalThrows")
  public <T> T time(String name, Invocation<T> invocation) throws Throwable {
    requireName(name);
    Objects.requireNonNull(invocation, "measured invocation must not be null");
    long started = System.nanoTime();
    boolean failed = true;
    try {
      T result = invocation.proceed();
      // Reached only on a normal return, so the flag is still true for every exceptional exit —
      // including an Error, which is a completion the measurement should not silently drop.
      failed = false;
      return result;
    } finally {
      report(name, started, failed);
    }
  }

  /**
   * Times {@code supplier}, for call sites that are not advice.
   *
   * @param <T> what the measured call returns
   * @param name what to record the measurement under; must not be {@code null} or blank
   * @param supplier the measured call; must not be {@code null}
   * @return whatever {@code supplier} returned
   * @throws NullPointerException if {@code name} or {@code supplier} is {@code null}
   * @throws IllegalArgumentException if {@code name} is blank
   */
  @Nullable
  public <T> T call(String name, Supplier<T> supplier) {
    requireName(name);
    Objects.requireNonNull(supplier, "measured supplier must not be null");
    long started = System.nanoTime();
    boolean failed = true;
    try {
      T result = supplier.get();
      failed = false;
      return result;
    } finally {
      report(name, started, failed);
    }
  }

  /**
   * Times {@code action}, for a measured call that returns nothing.
   *
   * @param name what to record the measurement under; must not be {@code null} or blank
   * @param action the measured call; must not be {@code null}
   * @throws NullPointerException if {@code name} or {@code action} is {@code null}
   * @throws IllegalArgumentException if {@code name} is blank
   */
  public void run(String name, Runnable action) {
    requireName(name);
    Objects.requireNonNull(action, "measured action must not be null");
    long started = System.nanoTime();
    boolean failed = true;
    try {
      action.run();
      failed = false;
    } finally {
      report(name, started, failed);
    }
  }

  /**
   * Hands one measurement to the recorder, absorbing a recorder that misbehaves.
   *
   * <p>{@link LinkageError} is caught beside {@link RuntimeException} for the same reason {@code
   * Validator} catches it: a recorder wired against a backend whose classes are not on the runtime
   * image fails with {@code NoClassDefFoundError}, and killing a business call over a missing
   * metrics jar is precisely the outage this policy forbids. What is <em>not</em> caught is the
   * rest of {@link Error} — an {@link OutOfMemoryError} raised inside a recorder says the VM is
   * dying, and swallowing it would hide that behind a timing that looks fine.
   *
   * @param name what was measured
   * @param startedNanos the {@link System#nanoTime()} reading taken before the measured call
   * @param failed whether the measured call completed by throwing
   */
  private void report(String name, long startedNanos, boolean failed) {
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    try {
      recorder.record(name, elapsed, failed);
    } catch (RuntimeException | LinkageError broken) {
      // compareAndSet, not get-then-set: two threads on a broken recorder must produce one warning
      // between them, which is what makes "at most once" true under the load that triggers it.
      if (recorderFailureReported.compareAndSet(false, true)) {
        logger.log(
            Level.WARNING,
            "d4np execution time: recorder "
                + recorder.getClass().getName()
                + " failed and was ignored; the measured call is unaffected. Further failures from"
                + " this aspect will not be reported.",
            broken);
      }
    }
  }

  /**
   * Rejects a name that would produce an unusable measurement.
   *
   * @param name the caller's name for the measured call
   * @throws NullPointerException if {@code name} is {@code null}
   * @throws IllegalArgumentException if {@code name} is blank
   */
  private static void requireName(String name) {
    Objects.requireNonNull(name, "measured name must not be null");
    if (name.isBlank()) {
      // A blank series name is not a formatting problem: it produces a metric nobody can attribute
      // to a method, and it is far cheaper to refuse it at the call site than to find it later in a
      // dashboard.
      throw new IllegalArgumentException("measured name must not be blank");
    }
  }

  /**
   * The measured call, in the shape an {@code @Around} advice already has.
   *
   * <p>{@code throws Throwable} mirrors {@code ProceedingJoinPoint.proceed()} exactly, and that is
   * the point: the adapter can pass a method reference with no wrapping, so the exception the
   * measured method threw is the exception its caller catches. This interface is not a
   * general-purpose {@code Callable} substitute — {@link ExecutionTimeMetricAspect#call} is the
   * entry point for ordinary code.
   *
   * @param <T> what the measured call returns
   */
  @FunctionalInterface
  public interface Invocation<T> {

    /**
     * Runs the measured call.
     *
     * @return whatever the measured call returns, {@code null} included
     * @throws Throwable whatever the measured call throws
     */
    @Nullable
    // Mirrors ProceedingJoinPoint.proceed() exactly; see the note on time(..) for why the check is
    // suppressed rather than the signature narrowed.
    @SuppressWarnings("IllegalThrows")
    T proceed() throws Throwable;
  }
}
