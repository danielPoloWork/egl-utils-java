package it.d4np.utils.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-09's async wrapper — the context it carries, and the one channel failures arrive through. */
class AsyncExecutorTest {

  /** One thread and one queue slot, so rejection is reachable and ordering is deterministic. */
  private static ThreadPoolSpec.Builder spec(String name) {
    return ThreadPoolSpec.named(name)
        .coreThreads(1)
        .maxThreads(1)
        .queueCapacity(1)
        .drainTimeout(Duration.ofSeconds(2))
        .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy());
  }

  @AfterEach
  void clearContext() {
    ThreadLocalContext.clear();
  }

  private static <T> T await(CompletableFuture<T> future) {
    return future.orTimeout(10, TimeUnit.SECONDS).join();
  }

  @Nested
  @DisplayName("the context it carries")
  class Context {

    @Test
    @DisplayName("captures on the submitting thread, not on the worker")
    void capturesOnTheSubmittingThread() {
      // Capturing inside the task would read the WORKER's context, which is the caller's context
      // only by accident. This is why capture() runs before the work is handed over.
      ThreadLocalContext.set("request-42");
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("cap").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool).withContext(ThreadLocalContext.propagator());

        assertThat(await(async.supply(ThreadLocalContext::get))).isEqualTo("request-42");
      }
    }

    @Test
    @DisplayName("carries nothing by default, and says so rather than guessing")
    void carriesNothingByDefault() {
      // The default is a real no-op, not a reflective MDC lookup: a fallback that works only when
      // some library happens to be present fails silently and is found in production.
      ThreadLocalContext.set("request-42");
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("none").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool);

        assertThat(await(async.supply(ThreadLocalContext::get))).isEqualTo("<none>");
      }
    }

    @Test
    @DisplayName("a pooled worker carries NOTHING of one task into the next")
    void doesNotLeakContextBetweenTasksOnOneWorker() {
      // THE test this whole SPI exists for, and the threat model's information-disclosure row.
      // One thread, so both tasks provably run on the same worker.
      List<String> seen = new ArrayList<>();
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("leak").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool).withContext(ThreadLocalContext.propagator());

        ThreadLocalContext.set("tenant-A");
        await(async.run(() -> seen.add("first sees " + ThreadLocalContext.get())));
        ThreadLocalContext.clear();
        await(async.run(() -> seen.add("second sees " + ThreadLocalContext.get())));
      }

      assertThat(seen).containsExactly("first sees tenant-A", "second sees <none>");
    }

    @Test
    @DisplayName("leaves the worker thread exactly as it found it, for whoever uses the pool next")
    void leavesTheWorkerAsItFoundIt() {
      // A pool is shared. The leak that matters is not what the NEXT AsyncExecutor task sees --
      // that one installs its own capture over the residue -- it is what anyone ELSE running on the
      // same worker sees, which is why this observer goes straight to the pool.
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("restores").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool).withContext(ThreadLocalContext.propagator());

        ThreadLocalContext.set("tenant-A");
        await(async.run(() -> {}));
        ThreadLocalContext.clear();

        assertThat(observeDirectlyOn(pool)).isEqualTo("<none>");
      }
    }

    @Test
    @DisplayName("and the same pool DOES leak when the scope restores nothing")
    void theSamePoolLeaksWhenTheScopeRestoresNothing() {
      // The companion test item 4.1 established as discipline: a safety assertion that cannot be
      // shown to fail is not evidence. Identical to the test above but for a Scope that is a no-op.
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("leaky").build())) {
        AsyncExecutor async =
            AsyncExecutor.over(pool).withContext(ThreadLocalContext.leakyPropagator());

        ThreadLocalContext.set("tenant-A");
        await(async.run(() -> {}));
        ThreadLocalContext.clear();

        assertThat(observeDirectlyOn(pool))
            .as("tenant-A surviving on the worker is exactly the disclosure the Scope prevents")
            .isEqualTo("tenant-A");
      }
    }

    /** Reads the worker thread's context without going through {@link AsyncExecutor}. */
    private static String observeDirectlyOn(ManagedThreadPool pool) {
      AtomicReference<String> seen = new AtomicReference<>("<unset>");
      CountDownLatch done = new CountDownLatch(1);
      pool.execute(
          () -> {
            seen.set(ThreadLocalContext.get());
            done.countDown();
          });
      try {
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      // requireNonNull rather than a cast: AtomicReference.get() is @Nullable to NullAway, and the
      // initial value above is what makes this total.
      return Objects.requireNonNull(seen.get());
    }

    @Test
    @DisplayName("restores even when the body throws")
    void restoresEvenWhenTheBodyThrows() {
      // try-with-resources rather than a bare finally, so the restore is not conditional on the
      // happy path. A leak that only happens on the error path is the hardest kind to notice.
      List<String> seen = new ArrayList<>();
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("throwing").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool).withContext(ThreadLocalContext.propagator());

        ThreadLocalContext.set("tenant-A");
        CompletableFuture<Void> failed =
            async.run(
                () -> {
                  throw new IllegalStateException("boom");
                });
        assertThatThrownBy(() -> await(failed)).isInstanceOf(CompletionException.class);

        ThreadLocalContext.clear();
        await(async.run(() -> seen.add("after a failure: " + ThreadLocalContext.get())));
      }

      assertThat(seen).containsExactly("after a failure: <none>");
    }

    @Test
    @DisplayName("restores nested installs in reverse order")
    void restoresNestedInstallsInReverseOrder() {
      // A task that submits another task: the inner install must not destroy the outer one when it
      // unwinds, which is the case "clear afterwards" gets wrong.
      try (ManagedThreadPool pool =
          CustomThreadPoolFactory.create(spec("nested").coreThreads(2).maxThreads(2).build())) {
        AsyncExecutor async = AsyncExecutor.over(pool).withContext(ThreadLocalContext.propagator());

        ThreadLocalContext.set("outer");
        String observed =
            await(
                async.supply(
                    () -> {
                      ThreadLocalContext.set("inner");
                      await(async.run(() -> {}));
                      return ThreadLocalContext.get();
                    }));

        assertThat(observed).isEqualTo("inner");
      }
    }
  }

  @Nested
  @DisplayName("how failures arrive")
  class Failures {

    @Test
    @DisplayName("a body that throws completes the future exceptionally, never the caller's thread")
    void aThrowingBodyFailsTheFuture() {
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("fail").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool);

        CompletableFuture<String> future =
            async.supply(
                () -> {
                  throw new IllegalStateException("boom");
                });

        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class);
      }
    }

    @Test
    @DisplayName("a REJECTED submission arrives through the future, not as a synchronous throw")
    void rejectionArrivesThroughTheFuture() {
      // The contract's headline: one operation, one failure path. Without this the caller needs a
      // try/catch AND an exceptionally, and the chain silently never runs.
      Executor rejecting =
          task -> {
            throw new RejectedExecutionException("full");
          };
      AsyncExecutor async = AsyncExecutor.over(rejecting);

      CompletableFuture<String> future = async.supply(() -> "never runs");

      assertThat(future).isCompletedExceptionally();
      assertThatThrownBy(future::get).hasRootCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("which is a deliberate divergence from supplyAsync, measured on both toolchains")
    void divergesFromSupplyAsyncWhichThrowsSynchronously() {
      // Measured on Temurin 17.0.20.1+1 and 21.0.12.1+1: CompletableFuture.supplyAsync lets the
      // RejectedExecutionException escape on the SUBMITTING thread on both, so this is the JDK's
      // consistent behaviour rather than a version quirk. Pinned here so the divergence stays a
      // decision rather than becoming an accident if the JDK ever changes.
      Executor rejecting =
          task -> {
            throw new RejectedExecutionException("full");
          };

      assertThatThrownBy(() -> CompletableFuture.supplyAsync(() -> "v", rejecting))
          .as("the JDK's own form throws on the submitting thread")
          .isInstanceOf(RejectedExecutionException.class);

      assertThat(AsyncExecutor.over(rejecting).supply(() -> "v"))
          .as("ours does not")
          .isCompletedExceptionally();
    }

    @Test
    @DisplayName("an Error is delivered through the future rather than stranding the caller")
    void anErrorIsDeliveredRatherThanStranding() {
      // A future nobody completes is a caller waiting forever, which is worse than an Error the
      // caller can see. It is delivered, not swallowed.
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("err").build())) {
        CompletableFuture<String> future =
            AsyncExecutor.over(pool)
                .supply(
                    () -> {
                      throw new StackOverflowError("deep");
                    });

        assertThatThrownBy(future::get).hasRootCauseInstanceOf(StackOverflowError.class);
      }
    }

    @Test
    @DisplayName("rejects null arguments at the boundary")
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNullArguments() {
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("nulls").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool);

        assertThatThrownBy(() -> AsyncExecutor.over(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> async.withContext(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> async.supply(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> async.run(null)).isInstanceOf(NullPointerException.class);
      }
    }
  }

  @Nested
  @DisplayName("the surface")
  class Surface {

    @Test
    @DisplayName("publishes supply and run, and no overloaded submit")
    void publishesTwoNamesAndNoOverloadedSubmit() {
      // Structural, because the defect the naming avoids is invisible: an overloaded submit would
      // compile and silently return CompletableFuture<Void> when a body gains braces.
      List<String> names =
          List.of(AsyncExecutor.class.getMethods()).stream()
              .filter(m -> m.getDeclaringClass() == AsyncExecutor.class)
              .map(java.lang.reflect.Method::getName)
              .distinct()
              .sorted()
              .toList();

      assertThat(names).contains("supply", "run").doesNotContain("submit");
    }

    @Test
    @DisplayName(
        "withContext returns a new instance, so a shared executor cannot change underneath")
    void withContextReturnsANewInstance() {
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("imm").build())) {
        AsyncExecutor plain = AsyncExecutor.over(pool);
        AsyncExecutor contextual = plain.withContext(ThreadLocalContext.propagator());

        assertThat(contextual).isNotSameAs(plain);

        ThreadLocalContext.set("request-7");
        assertThat(await(plain.supply(ThreadLocalContext::get))).isEqualTo("<none>");
        assertThat(await(contextual.supply(ThreadLocalContext::get))).isEqualTo("request-7");
      }
    }

    @Test
    @DisplayName("accepts a bare Executor, so it names no pool type of ours")
    void acceptsABareExecutor() {
      // The parameter is Executor, not ExecutorService: supply/run need only execute(), and the
      // narrower type accepts a ForkJoinPool or a virtual-thread executor without naming either.
      AtomicReference<String> ranOn = new AtomicReference<>("");
      Executor sameThread = Runnable::run;

      await(AsyncExecutor.over(sameThread).run(() -> ranOn.set(Thread.currentThread().getName())));

      assertThat(ranOn.get()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("renders the delegate's type and the propagator, never a task")
    void rendersTypesNeverTasks() {
      String rendered = AsyncExecutor.over((Executor) Runnable::run).toString();

      assertThat(rendered).contains("ContextPropagator.none()");
      assertThat(rendered).doesNotContain("hunter2");
    }
  }

  @Nested
  @DisplayName("ordering, so the leak test above means what it says")
  class Ordering {

    @Test
    @DisplayName("a single-thread pool really does run both tasks on one worker")
    void bothTasksRunOnOneWorker() {
      // If they did not, doesNotLeakContextBetweenTasksOnOneWorker would pass for the wrong reason.
      List<String> threads = new ArrayList<>();
      CountDownLatch done = new CountDownLatch(2);
      try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec("one").build())) {
        AsyncExecutor async = AsyncExecutor.over(pool);
        await(
            async.run(
                () -> {
                  threads.add(Thread.currentThread().getName());
                  done.countDown();
                }));
        await(
            async.run(
                () -> {
                  threads.add(Thread.currentThread().getName());
                  done.countDown();
                }));
      }

      assertThat(threads).hasSize(2);
      assertThat(threads.get(0)).isEqualTo(threads.get(1)).isEqualTo("one-1");
    }
  }
}
