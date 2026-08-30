package it.d4np.utils.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-08's pool — the lifecycle it owns, and the configuration it will not hand back. */
class ManagedThreadPoolTest {

  private static ThreadPoolSpec.Builder spec(String name) {
    return ThreadPoolSpec.named(name)
        .coreThreads(1)
        .maxThreads(1)
        .queueCapacity(1)
        .drainTimeout(Duration.ofMillis(300))
        .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy());
  }

  /** A task that blocks until released, so a drain has something real to fail to finish. */
  private static Runnable blockingUntil(CountDownLatch release) {
    return () -> {
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    };
  }

  @Nested
  @DisplayName("close(), which is the whole reason this type exists")
  class Closing {

    @Test
    @DisplayName("drains within the configured timeout rather than the JDK's one-day default")
    void drainsWithinTheConfiguredTimeout() {
      // The measurement behind ADR-0035: inheriting AutoCloseable's default close() would wait
      // awaitTermination(1, DAYS) in a loop, so this task's 10s block would be waited out in full.
      CountDownLatch neverReleased = new CountDownLatch(1);
      ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("drain").build());
      pool.execute(blockingUntil(neverReleased));

      long startedAt = System.nanoTime();
      pool.close();
      long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(elapsedMillis)
          .as("close() honoured the 300ms budget instead of waiting the task out")
          .isBetween(200L, 5_000L);
      assertThat(pool.isShutdown()).isTrue();
    }

    @Test
    @DisplayName(
        "honours the budget when dispatched through ExecutorService, which is the call that regresses")
    void honoursTheBudgetThroughTheInterfaceType() throws Exception {
      // RFC-0004 asks for THIS test by name, and asks for it as try-with-resources over an
      // ExecutorService variable. THAT CANNOT BE WRITTEN HERE: these test sources compile at
      // --release 17 too, where ExecutorService is not AutoCloseable, so the form the RFC describes
      // fails with "ExecutorService cannot be converted to AutoCloseable" -- measured, and recorded
      // in ADR-0035.
      //
      // Reflection is not a workaround for that, it is the faithful version: what regresses is
      // INVOKEINTERFACE on ExecutorService.close(), and Method.invoke performs exactly that
      // dispatch. A test through the concrete type would keep passing after the regression.
      Method interfaceClose;
      try {
        interfaceClose = ExecutorService.class.getMethod("close");
      } catch (NoSuchMethodException noCloseOnThisRuntime) {
        // A JDK 17 runtime. The interface declares no close(), so there is no default to fall back
        // to and nothing to regress. Asserted rather than skipped, so the reason stays visible.
        assertThat(Runtime.version().feature()).isLessThan(19);
        return;
      }

      CountDownLatch neverReleased = new CountDownLatch(1);
      ExecutorService pool = CustomThreadPoolFactory.create(spec("iface").build());
      pool.execute(blockingUntil(neverReleased));

      long startedAt = System.nanoTime();
      interfaceClose.invoke(pool);
      long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(elapsedMillis)
          .as(
              "ExecutorService.close() dispatched to ManagedThreadPool.close(), not the JDK default")
          .isLessThan(5_000L);
      assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("honours the budget through try-with-resources over AutoCloseable")
    void honoursTheBudgetThroughAutoCloseable() throws Exception {
      // The half of the RFC's intent that DOES compile at --release 17, and the reason the class
      // declares AutoCloseable explicitly: without that declaration this would not compile either.
      CountDownLatch neverReleased = new CountDownLatch(1);
      ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("acloseable").build());
      pool.execute(blockingUntil(neverReleased));

      long startedAt = System.nanoTime();
      try (AutoCloseable closing = pool) {
        assertThat(closing).isSameAs(pool);
      }
      long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(elapsedMillis).isLessThan(5_000L);
      assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    @DisplayName(
        "declares AutoCloseable itself rather than inheriting it, which is what holds the guard up")
    void declaresAutoCloseableItself() {
      // ADR-0035: the interface declaration is the guard, not the method. With it, deleting close()
      // is "does not override abstract method close() in AutoCloseable" at --release 17. Remove the
      // interface and the @Override on close() stops compiling -- which is the failure that catches
      // the removal. Asserted structurally so a "redundant on 21" cleanup fails here too.
      assertThat(ManagedThreadPool.class.getInterfaces())
          .as("close()'s @Override, and the deletion guard, both rest on this being declared")
          .contains(AutoCloseable.class);
    }

    @Test
    @DisplayName("logs the COUNT of tasks that never started, never the tasks")
    void logsTheCountAndNeverTheTasks() {
      // C-01: a Runnable is a caller-supplied object whose toString() this library does not
      // control.
      CountDownLatch neverReleased = new CountDownLatch(1);
      PoolLogRecorder log = new PoolLogRecorder();
      ManagedThreadPool pool =
          CustomThreadPoolFactory.create(spec("counted").queueCapacity(4).build(), log);
      pool.execute(blockingUntil(neverReleased));
      pool.execute(leakyTask());
      pool.execute(leakyTask());

      pool.close();

      assertThat(log.messages()).anySatisfy(line -> assertThat(line).contains("2 queued task(s)"));
      assertThat(log.messages()).noneSatisfy(line -> assertThat(line).contains("hunter2"));
      assertThat(log.messages()).anySatisfy(line -> assertThat(line).contains("counted"));
    }

    @Test
    @DisplayName("never throws, even when the drain does not finish")
    void neverThrows() {
      // Throwing from close() would suppress the body's exception inside try-with-resources, in the
      // one place a caller is least able to react.
      CountDownLatch neverReleased = new CountDownLatch(1);
      ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("quiet").build());
      pool.execute(blockingUntil(neverReleased));

      assertThatCode(pool::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is idempotent")
    void isIdempotent() {
      ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("twice").build());

      pool.close();
      assertThatCode(pool::close).doesNotThrowAnyException();
      assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("logs nothing when the work drains in time")
    void logsNothingOnACleanDrain() {
      PoolLogRecorder log = new PoolLogRecorder();
      ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("clean").build(), log);
      pool.execute(() -> {});

      pool.close();

      assertThat(log.messages()).isEmpty();
    }

    private static Runnable leakyTask() {
      return new Runnable() {
        @Override
        public void run() {
          // never reached; the pool has one thread and it is blocked
        }

        @Override
        public String toString() {
          return "hunter2";
        }
      };
    }
  }

  @Nested
  @DisplayName("the threads it creates")
  class Threads {

    @Test
    @DisplayName("names them after the pool, numbered from one")
    void namesThreadsAfterThePool() {
      AtomicReference<String> seen = new AtomicReference<>("");
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("ingest").build())) {
        pool.execute(() -> seen.set(Thread.currentThread().getName()));
        awaitQuiet(pool);
      }

      assertThat(seen.get()).isEqualTo("ingest-1");
    }

    @Test
    @DisplayName("makes them non-daemon by default and daemon on request")
    void appliesDaemonStatus() {
      assertThat(daemonOf(spec("nd").build())).isFalse();
      assertThat(daemonOf(spec("d").daemon(true).build())).isTrue();
    }

    @Test
    @DisplayName("applies the requested priority to the thread, which is all a library can assert")
    void appliesThePriorityToTheThread() {
      // Thread.setPriority is advisory and on common Linux configurations has no scheduling effect
      // at all. What IS assertable is that the value reached the thread; what is NOT is that the OS
      // acted on it, so no test here claims that -- spec 6's rule cuts both ways.
      AtomicInteger seen = new AtomicInteger(-1);
      try (ManagedThreadPool pool =
          CustomThreadPoolFactory.create(spec("prio").priority(Thread.MIN_PRIORITY).build())) {
        pool.execute(() -> seen.set(Thread.currentThread().getPriority()));
        awaitQuiet(pool);
      }

      assertThat(seen.get()).isEqualTo(Thread.MIN_PRIORITY);
    }

    private static boolean daemonOf(ThreadPoolSpec configured) {
      AtomicBoolean seen = new AtomicBoolean();
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(configured)) {
        pool.execute(() -> seen.set(Thread.currentThread().isDaemon()));
        awaitQuiet(pool);
      }
      return seen.get();
    }
  }

  @Nested
  @DisplayName("a task that throws")
  class Failures {

    @Test
    @DisplayName("reaches the uncaught handler through execute()")
    void reachesTheHandlerThroughExecute() {
      AtomicReference<String> caught = new AtomicReference<>("");
      CountDownLatch handled = new CountDownLatch(1);
      ThreadPoolSpec configured =
          spec("boom")
              .uncaughtExceptionHandler(
                  (thread, failure) -> {
                    caught.set(failure.getClass().getSimpleName());
                    handled.countDown();
                  })
              .build();

      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(configured)) {
        pool.execute(
            () -> {
              throw new IllegalStateException("hunter2");
            });
        awaitLatch(handled);
      }

      assertThat(caught.get()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("does NOT reach the handler through submit(), because the Future captures it")
    void doesNotReachTheHandlerThroughSubmit() {
      // ThreadPoolExecutor's contract, not a choice here -- FutureTask catches the throwable and
      // hands it back through get(). Documented on NamedThreadFactory and asserted here, because a
      // reader who assumes the handler is universal would stop checking futures.
      AtomicReference<String> caught = new AtomicReference<>("untouched");
      ThreadPoolSpec configured =
          spec("submitted")
              .uncaughtExceptionHandler((thread, failure) -> caught.set("handler ran"))
              .build();

      Future<?> future;
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(configured)) {
        future =
            pool.submit(
                () -> {
                  throw new IllegalStateException("hunter2");
                });
        awaitQuiet(pool);
      }

      assertThat(caught.get()).isEqualTo("untouched");
      assertThatThrownBy(future::get)
          .isInstanceOf(ExecutionException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the default handler logs the failure's type and never its message")
    void theDefaultHandlerLogsTheTypeNeverTheMessage() {
      // C-01 on an outbound log line -- the weaker of the control's two boundaries, precisely
      // because nobody reviews a log line for disclosure (item 4.4 established this reading).
      PoolLogRecorder log = new PoolLogRecorder();
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("logged").build(), log)) {
        pool.execute(
            () -> {
              throw new IllegalStateException("hunter2");
            });
        awaitQuiet(pool);
      }
      // The handler runs during the worker thread's DEATH sequence --
      // Thread.dispatchUncaughtException
      // fires after run() returns, which can be after the pool already reports termination. So
      // awaitTermination is not a synchronisation point for this line and polling is the honest
      // fix,
      // not a flake-suppressant: without it the assertion races and sees an empty recorder.
      awaitAnyLine(log);

      assertThat(log.messages())
          .anySatisfy(line -> assertThat(line).contains("java.lang.IllegalStateException"));
      assertThat(log.messages()).noneSatisfy(line -> assertThat(line).contains("hunter2"));
      // The MessageFormat trap ADR-0014 recorded: an unescaped apostrophe emits {0} verbatim.
      assertThat(log.messages()).noneSatisfy(line -> assertThat(line).contains("{0}"));
    }
  }

  @Nested
  @DisplayName("the rejection policy, which the queue bound is what makes reachable")
  class Rejection {

    @Test
    @DisplayName("fires once the bounded queue is full")
    void firesWhenTheQueueIsFull() {
      // The point of ThreadPoolSpec making the capacity mandatory: over an unbounded queue this
      // assertion could never pass, and FR-08's explicit handler would be decoration.
      CountDownLatch neverReleased = new CountDownLatch(1);
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("full").build())) {
        pool.execute(blockingUntil(neverReleased)); // occupies the single thread
        pool.execute(() -> {}); // fills the single queue slot

        assertThatThrownBy(() -> pool.execute(() -> {}))
            .isInstanceOf(RejectedExecutionException.class);
      }
    }

    @Test
    @DisplayName("a CallerRunsPolicy applies backpressure instead of refusing")
    void callerRunsAppliesBackpressure() {
      CountDownLatch neverReleased = new CountDownLatch(1);
      AtomicReference<String> ranOn = new AtomicReference<>("");
      ThreadPoolSpec configured =
          spec("backpressure").rejectionPolicy(new ThreadPoolExecutor.CallerRunsPolicy()).build();

      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(configured)) {
        pool.execute(blockingUntil(neverReleased));
        pool.execute(() -> {});
        pool.execute(() -> ranOn.set(Thread.currentThread().getName()));
      }

      assertThat(ranOn.get()).isEqualTo(Thread.currentThread().getName());
    }
  }

  @Nested
  @DisplayName("what it refuses to hand back")
  class Encapsulation {

    @Test
    @DisplayName("publishes no method returning the executor or its queue")
    void publishesNoHandleToTheConfiguredExecutor() {
      // Structural, not editorial -- the shape FR-20 and FR-07 each used. A convenience accessor
      // added later fails wherever it is written, rather than passing every other test in the file.
      List<Method> leaks =
          List.of(ManagedThreadPool.class.getMethods()).stream()
              .filter(m -> m.getDeclaringClass() == ManagedThreadPool.class)
              .filter(
                  m ->
                      ThreadPoolExecutor.class.isAssignableFrom(m.getReturnType())
                          || BlockingQueue.class.isAssignableFrom(m.getReturnType()))
              .toList();

      assertThat(leaks)
          .as(
              "a getter for the executor or its queue would make every configured guarantee optional")
          .isEmpty();
    }

    @Test
    @DisplayName("exposes no setter at all, so nothing the spec fixed can be changed")
    void exposesNoSetter() {
      List<String> setters =
          List.of(ManagedThreadPool.class.getMethods()).stream()
              .filter(m -> m.getDeclaringClass() == ManagedThreadPool.class)
              .map(Method::getName)
              .filter(name -> name.startsWith("set"))
              .toList();

      assertThat(setters).isEmpty();
    }

    @Test
    @DisplayName("renders its configuration and never the queued work")
    void rendersConfigurationNeverWork() {
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("shown").build())) {
        assertThat(pool.toString()).contains("shown").contains("queueCapacity=1");
      }
    }
  }

  private static void awaitQuiet(ExecutorService pool) {
    pool.shutdown();
    try {
      pool.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Waits, bounded, for the first line to arrive from a thread this test cannot join. */
  private static void awaitAnyLine(PoolLogRecorder log) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (log.messages().isEmpty() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(5);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
