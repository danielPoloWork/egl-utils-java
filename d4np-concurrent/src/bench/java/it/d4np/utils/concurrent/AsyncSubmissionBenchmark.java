package it.d4np.utils.concurrent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * The benchmark NFR-02 is stated against — ROADMAP item 5.2.
 *
 * <p><strong>The budget.</strong> NFR-02 puts {@link AsyncExecutor}'s <em>submission</em> overhead
 * at <strong>≤ 5 µs</strong> against raw {@link
 * CompletableFuture#supplyAsync(java.util.function.Supplier, Executor)}.
 *
 * <h2>Why there are two pairs, and which one is the budget</h2>
 *
 * <p>The obvious harness — submit to a real pool and {@code join()} — was written first and
 * measured <strong>~13 µs on every arm</strong>, including the raw one. That number is a thread
 * handoff: a park, an unpark and a context switch, which costs microseconds and has nothing to do
 * with the wrapper under test. The quantity NFR-02 names is buried inside it, and at CI's single
 * iteration the arms were not even ordered correctly — the executor with a context propagator came
 * out <em>faster</em> than raw, which is noise reporting itself as a result.
 *
 * <p>So the budget is measured against an <strong>inline executor</strong> ({@code Runnable::run}),
 * which both arms share. No handoff, no scheduling, no parking: the difference between {@link
 * #inlineRawSupplyAsync} and {@link #inlineAsyncExecutor} is this library's capture, wrap and
 * complete, and nothing else. That is what "submission overhead" means, and it is the pair NFR-02
 * is judged on.
 *
 * <p>The pooled pair is kept because deleting it would invite the reader to assume the budget
 * includes the handoff. It does not, and the pooled numbers are the ones that say what an async
 * call actually costs end to end.
 *
 * <h2>Why this budget is not a CI gate, and why the reason differs from NFR-03's</h2>
 *
 * <p>NFR-03 is a <em>relative</em> ratio between two arms in one JMH invocation, so a slow runner
 * slows both and the ratio holds; item 8.8 owns turning it into a gate and needs only fork and
 * iteration counts. NFR-02 is phrased relatively but <strong>bounded absolutely</strong> — 5 µs —
 * so a loaded runner moves the difference itself and not merely the scale. It belongs with NFR-01's
 * 2 ns/op and NFR-06's 400 MB/s in <strong>item 8.3</strong>'s stable-runner problem. Until 8.3
 * lands the numbers are tracked on the reference machine and advisory in CI (RFC-0004 §Scalability
 * budgets).
 *
 * <p><strong>The budget is also extremely loose against what it actually measures</strong>, and
 * saying so is part of reporting it honestly: an inline submission is sub-microsecond, so 5 µs
 * leaves room for a regression far larger than anything this code could plausibly introduce. Item
 * 5.2 therefore reports the measured overhead as a <em>number</em> rather than only a verdict, and
 * RFC-0004 routes the question of tightening the ceiling to the audit phase.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class AsyncSubmissionBenchmark {

  /** Enough slots that a submission is never refused during a measurement iteration. */
  private static final int QUEUE_CAPACITY = 1 << 16;

  /** A realistic MDC: a correlation id, a tenant, a user, and five more of the same shape. */
  private static final Map<String, String> POPULATED =
      Map.of(
          "correlationId", "8f14e45fea2b",
          "tenant", "acme",
          "user", "u-1024",
          "requestPath", "/orders",
          "method", "POST",
          "region", "eu-west-1",
          "version", "0.1.0",
          "sampled", "true");

  /** Runs the task on the calling thread, so neither arm pays for a handoff. */
  private static final Executor INLINE = Runnable::run;

  // JMH constructs the state object and only then calls @Setup, so these are genuinely
  // uninitialized at construction. `NullAway.Init` is the annotation for that framework-
  // initialization shape; item 4.3's RowMappingBenchmark reached for it first, for the same reason.
  @SuppressWarnings("NullAway.Init")
  private ManagedThreadPool pool;

  @SuppressWarnings("NullAway.Init")
  private AsyncExecutor inlinePlain;

  @SuppressWarnings("NullAway.Init")
  private AsyncExecutor inlineWithContext;

  @SuppressWarnings("NullAway.Init")
  private AsyncExecutor pooledPlain;

  /** Builds the pool and the executor variants once per trial. */
  @Setup(Level.Trial)
  public void setUp() {
    pool =
        CustomThreadPoolFactory.create(
            ThreadPoolSpec.named("bench")
                .coreThreads(1)
                .maxThreads(1)
                .queueCapacity(QUEUE_CAPACITY)
                .drainTimeout(Duration.ofSeconds(5))
                .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
                .build());
    inlinePlain = AsyncExecutor.over(INLINE);
    inlineWithContext = AsyncExecutor.over(INLINE).withContext(copyOf(POPULATED));
    pooledPlain = AsyncExecutor.over(pool);
  }

  /** Drains the pool so a trial cannot leak threads into the next one. */
  @TearDown(Level.Trial)
  public void tearDown() {
    pool.close();
  }

  /**
   * A propagator that copies a map on capture — the shape the {@link ContextPropagator} Javadoc
   * gives a host for SLF4J's MDC, with the thread-local replaced by a field so the benchmark
   * measures the copying rather than a {@code ThreadLocal} lookup.
   *
   * @param contents what each capture copies
   * @return the propagator
   */
  private static ContextPropagator copyOf(Map<String, String> contents) {
    return () -> {
      Map<String, String> captured = Map.copyOf(contents);
      return () ->
          () -> {
            // Reads the captured map so the JIT cannot fold the whole capture away as dead.
            if (captured.isEmpty()) {
              throw new AssertionError();
            }
          };
    };
  }

  /**
   * The budget's floor: the JDK's own submission, inline.
   *
   * @return the completed future's value
   */
  @Benchmark
  public Object inlineRawSupplyAsync() {
    return CompletableFuture.supplyAsync(() -> Boolean.TRUE, INLINE).join();
  }

  /**
   * The budget arm: the same submission through {@link AsyncExecutor}, carrying nothing.
   *
   * <p><strong>{@code inlineAsyncExecutor} minus {@code inlineRawSupplyAsync} is NFR-02.</strong>
   *
   * @return the completed future's value
   */
  @Benchmark
  public Object inlineAsyncExecutor() {
    return inlinePlain.supply(() -> Boolean.TRUE).join();
  }

  /**
   * What propagation costs with a realistic eight-entry context. Outside the budget: it is the
   * number a reader wants when deciding whether to enable it.
   *
   * @return the completed future's value
   */
  @Benchmark
  public Object inlineAsyncExecutorWithContext() {
    return inlineWithContext.supply(() -> Boolean.TRUE).join();
  }

  /**
   * The realistic end-to-end cost through a real pool — dominated by the thread handoff, and
   * outside the budget for exactly that reason.
   *
   * @return the completed future's value
   */
  @Benchmark
  public Object pooledRawSupplyAsync() {
    return CompletableFuture.supplyAsync(() -> Boolean.TRUE, pool).join();
  }

  /**
   * The same end-to-end path through {@link AsyncExecutor}, so the handoff-dominated pair can be
   * compared on its own terms.
   *
   * @return the completed future's value
   */
  @Benchmark
  public Object pooledAsyncExecutor() {
    return pooledPlain.supply(() -> Boolean.TRUE).join();
  }
}
