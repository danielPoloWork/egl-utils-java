package it.d4np.utils.concurrent;

import it.d4np.utils.Nullable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A pool that keeps the configuration it was built with (FR-08, NFR-05, RFC-0004 §FR-08).
 *
 * <p><strong>Why this type exists rather than a bare {@code ExecutorService}.</strong> NFR-05 asks
 * that "graceful shutdown drains within the configured timeout", and a <em>factory</em> has no
 * lifecycle — so something has to own that budget. Returning a {@link ThreadPoolExecutor} would
 * publish {@code setCorePoolSize}, {@code setRejectedExecutionHandler} and {@code getQueue}, making
 * every guarantee {@link CustomThreadPoolFactory} just established one a consumer can switch off
 * (ADR-0022: a guarantee a consumer can switch off is advisory). Returning a bare {@link
 * ExecutorService} is correctly narrow but carries no configured timeout, which leaves NFR-05 as
 * advice. This class is the third option: it delegates to a {@code ThreadPoolExecutor} it never
 * publishes, and {@link #close()} applies the budget.
 *
 * <p>It is {@code final} rather than an interface on purpose — an interface anyone can implement
 * makes the drain guarantee per-implementation, which is the advisory outcome above.
 *
 * <h2>{@code close()}, and the JDK 19 default that would otherwise replace it</h2>
 *
 * <p>{@link ExecutorService} became {@link AutoCloseable} in <strong>Java 19</strong>, with a
 * default {@code close()} that calls {@code shutdown()} and then {@code awaitTermination(1, DAYS)}
 * in a loop until the pool terminates. This module compiles at {@code --release 17}, where that
 * method does not exist, and runs on 17 and 21 alike. Three consequences, each measured rather than
 * reasoned about (RFC-0004 §FR-08 carries the numbers):
 *
 * <ul>
 *   <li><strong>{@code close()} is declared here, never inherited.</strong> Without the
 *       declaration, a JDK 21 consumer's try-with-resources runs the interface default and the
 *       configured {@code drainTimeout} is never consulted — measured at 3017 ms against a 3 s task
 *       where the budget was 500 ms.
 *   <li><strong>{@link AutoCloseable} is declared on the class, and it does three jobs rather than
 *       the one RFC-0004 credited it with.</strong> It lets a JDK <strong>17</strong> consumer
 *       write try-with-resources at all (the interface it would otherwise need does not exist
 *       there); it makes {@code @Override} on {@code close()} <em>legal</em> at {@code --release
 *       17}, where without it the annotation is a compile error; and it makes {@code close()} an
 *       <em>abstract method this class must implement</em>, so deleting the method fails the build
 *       instead of silently reverting to the JDK default.
 *   <li><strong>The guard is the interface declaration, not the method</strong>, and it has a
 *       expiry date. Removing {@code implements AutoCloseable} — which reads as redundant on JDK
 *       21, where {@code ExecutorService} already extends it — turns the {@code @Override} into a
 *       compile error today. It stops doing so the moment the {@code --release} baseline moves to
 *       19 or later, at which point deleting {@code close()} compiles cleanly and restores the
 *       one-day drain. Raising the baseline is already a MAJOR bump (RFC-0001 §Versioning); this is
 *       one more thing that release has to re-check. ADR-0035 carries the measurements.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Thread-safe. Every field is final and every operation delegates to a {@code
 * ThreadPoolExecutor}, which is itself thread-safe; this class adds no mutable state of its own.
 * {@link #close()} is idempotent and safe to call from several threads — the second caller finds
 * the pool already terminated and returns.
 *
 * @see ThreadPoolSpec
 * @see CustomThreadPoolFactory
 */
public final class ManagedThreadPool implements ExecutorService, AutoCloseable {

  private static final Logger DEFAULT_LOGGER = System.getLogger(ManagedThreadPool.class.getName());

  /**
   * No apostrophe in any format below: {@link java.text.MessageFormat} treats a single quote as an
   * escape and would silently stop substituting — ADR-0014's trap, measured by item 3.2.
   */
  private static final String DRAIN_INCOMPLETE =
      "pool {0} did not drain within {1}; {2} queued task(s) never started and were cancelled";

  private static final String THREAD_FAILED = "uncaught {0} escaped a task on pool thread {1}";

  private final ThreadPoolExecutor delegate;
  private final ThreadPoolSpec spec;
  private final Logger logger;

  private ManagedThreadPool(ThreadPoolSpec spec, Logger logger) {
    this.spec = spec;
    this.logger = logger;
    this.delegate =
        new ThreadPoolExecutor(
            spec.coreThreads(),
            spec.maxThreads(),
            spec.keepAlive().toNanos(),
            TimeUnit.NANOSECONDS,
            new LinkedBlockingQueue<>(spec.queueCapacity()),
            new NamedThreadFactory(spec, logger),
            spec.rejectionPolicy());
  }

  /**
   * Builds a pool from a spec, logging through the JDK platform logger.
   *
   * @param spec the configuration
   * @return a running pool
   */
  static ManagedThreadPool from(ThreadPoolSpec spec) {
    return new ManagedThreadPool(spec, DEFAULT_LOGGER);
  }

  /**
   * Builds a pool that logs through a caller-supplied logger.
   *
   * <p>Package-private, and the reason is ADR-0014's: a {@code System.LoggerFinder} registered
   * through {@code META-INF/services} can never win inside a surefire fork, so a test that wants to
   * read what this class logged has to be handed the logger.
   *
   * @param spec the configuration
   * @param logger where the two warning lines go
   * @return a running pool
   */
  static ManagedThreadPool from(ThreadPoolSpec spec, Logger logger) {
    return new ManagedThreadPool(spec, logger);
  }

  /**
   * The pool's name, as it prefixes every thread name.
   *
   * @return the name
   */
  public String name() {
    return spec.name();
  }

  /**
   * The budget {@link #close()} spends draining before it interrupts.
   *
   * @return the configured drain timeout
   */
  public Duration drainTimeout() {
    return spec.drainTimeout();
  }

  /**
   * Stops accepting work, drains for at most {@link #drainTimeout()}, then interrupts what is left.
   *
   * <p><strong>Never throws</strong>, including when the drain does not finish: a failure to drain
   * is reported as a {@code WARNING} carrying the <em>count</em> of tasks that never started —
   * never the tasks themselves, because a {@link Runnable} is a caller-supplied object whose {@code
   * toString()} this library does not control (compliance control C-01). Throwing here would also
   * suppress the body's exception inside a try-with-resources, in the one place a caller is least
   * able to react.
   *
   * <p>Idempotent: calling it on an already-terminated pool does nothing.
   *
   * <p><strong>The {@code @Override} below is load-bearing and so is {@code implements
   * AutoCloseable} on the class</strong> — see the class Javadoc. Removing the interface makes this
   * annotation a compile error at {@code --release 17}, which is the guard; removing the annotation
   * trips ErrorProne's {@code MissingOverride} at {@code failOnWarning}.
   */
  @Override
  public void close() {
    if (delegate.isTerminated()) {
      return;
    }
    delegate.shutdown();
    boolean drained = false;
    try {
      drained = delegate.awaitTermination(drainNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      // Restore the flag rather than swallowing it: a caller that interrupted this thread is
      // entitled to see the interruption after close() has done its cleanup.
      Thread.currentThread().interrupt();
    }
    if (!drained) {
      List<Runnable> neverStarted = delegate.shutdownNow();
      logger.log(
          Level.WARNING,
          DRAIN_INCOMPLETE,
          spec.name(),
          spec.drainTimeout().toString(),
          // String.valueOf, not the int: MessageFormat would render 1234 as a grouped "1,234"
          // under the default locale, which is the same family of trap as the apostrophe above.
          String.valueOf(neverStarted.size()));
    }
  }

  /**
   * The drain budget in nanoseconds, saturating rather than overflowing.
   *
   * <p>{@link Duration#toNanos()} throws for anything beyond about 292 years. A budget that large
   * is a caller's mistake rather than a value to honour precisely, and {@link Long#MAX_VALUE} nanos
   * is the same wait for every practical purpose — so it saturates instead of failing a shutdown.
   *
   * @return the budget in nanoseconds
   */
  private long drainNanos() {
    try {
      return spec.drainTimeout().toNanos();
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /**
   * Initiates an orderly shutdown without waiting.
   *
   * <p>Prefer {@link #close()}, which applies the configured drain budget; this is the raw {@link
   * ExecutorService} operation and honours no timeout.
   */
  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  /**
   * Interrupts running tasks and returns those that never started.
   *
   * @return the queued tasks that were never started
   */
  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  /**
   * Whether shutdown has been initiated.
   *
   * @return {@code true} once shutdown has begun
   */
  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  /**
   * Whether every task has completed following shutdown.
   *
   * @return {@code true} once the pool has terminated
   */
  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  /**
   * Waits for termination, up to the caller's own timeout.
   *
   * @param timeout how long to wait
   * @param unit the timeout's unit
   * @return {@code true} if the pool terminated within the timeout
   * @throws InterruptedException if the waiting thread is interrupted
   */
  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  /**
   * Submits a value-returning task.
   *
   * @param <T> the task's result type
   * @param task the task
   * @return a future for the task's result
   */
  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return delegate.submit(task);
  }

  /**
   * Submits a task and a fixed result.
   *
   * @param <T> the result type
   * @param task the task
   * @param result what the future yields on success
   * @return a future for {@code result}
   */
  @Override
  public <T> Future<T> submit(Runnable task, @Nullable T result) {
    return delegate.submit(task, result);
  }

  /**
   * Submits a task with no result.
   *
   * @param task the task
   * @return a future that completes when the task does
   */
  @Override
  public Future<?> submit(Runnable task) {
    return delegate.submit(task);
  }

  /**
   * Runs every task and returns when all have finished.
   *
   * @param <T> the tasks' result type
   * @param tasks the tasks
   * @return one future per task, each already completed
   * @throws InterruptedException if the waiting thread is interrupted
   */
  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
      throws InterruptedException {
    return delegate.invokeAll(tasks);
  }

  /**
   * Runs every task, giving up after a timeout.
   *
   * @param <T> the tasks' result type
   * @param tasks the tasks
   * @param timeout how long to wait
   * @param unit the timeout's unit
   * @return one future per task
   * @throws InterruptedException if the waiting thread is interrupted
   */
  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    return delegate.invokeAll(tasks, timeout, unit);
  }

  /**
   * Runs tasks until one succeeds, then returns its result.
   *
   * @param <T> the tasks' result type
   * @param tasks the tasks
   * @return the first successful result
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws ExecutionException if no task completed successfully
   */
  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    return delegate.invokeAny(tasks);
  }

  /**
   * Runs tasks until one succeeds, giving up after a timeout.
   *
   * @param <T> the tasks' result type
   * @param tasks the tasks
   * @param timeout how long to wait
   * @param unit the timeout's unit
   * @return the first successful result
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws ExecutionException if no task completed successfully
   * @throws TimeoutException if none completed within the timeout
   */
  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate.invokeAny(tasks, timeout, unit);
  }

  /**
   * Executes a task, applying the configured rejection policy when the queue is full.
   *
   * @param command the task
   */
  @Override
  public void execute(Runnable command) {
    delegate.execute(command);
  }

  /**
   * Names the pool and its configuration, never the queued work.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "ManagedThreadPool[" + spec + ", terminated=" + delegate.isTerminated() + "]";
  }

  /**
   * Names threads, applies the spec's thread properties, and installs the failure handler.
   *
   * <p><strong>The handler's reach is narrower than it looks, and that is {@code
   * ThreadPoolExecutor}'s contract rather than a choice here.</strong> A task given to {@link
   * ManagedThreadPool#execute(Runnable)} that throws reaches the uncaught-exception handler; a task
   * given to any {@code submit} overload does not, because {@code FutureTask} captures the
   * throwable and hands it back through {@link Future#get()}. Both are covered — one by the
   * handler, one by the future — but only the first is silent if the handler is missing, which is
   * why the handler exists.
   */
  private static final class NamedThreadFactory implements ThreadFactory {

    private final ThreadPoolSpec spec;
    private final Thread.UncaughtExceptionHandler handler;
    private final AtomicLong created = new AtomicLong();

    private NamedThreadFactory(ThreadPoolSpec spec, Logger logger) {
      this.spec = spec;
      this.handler =
          spec.uncaughtExceptionHandler()
              .orElseGet(
                  () ->
                      (thread, failure) ->
                          // The failure's TYPE and the thread's name, never the failure's message:
                          // a task is a caller's own code and its message is its own to leak
                          // (C-01).
                          logger.log(
                              Level.ERROR,
                              THREAD_FAILED,
                              failure.getClass().getName(),
                              thread.getName()));
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, spec.name() + "-" + created.incrementAndGet());
      thread.setDaemon(spec.daemon());
      spec.priority().ifPresent(thread::setPriority);
      thread.setUncaughtExceptionHandler(handler);
      return thread;
    }
  }
}
