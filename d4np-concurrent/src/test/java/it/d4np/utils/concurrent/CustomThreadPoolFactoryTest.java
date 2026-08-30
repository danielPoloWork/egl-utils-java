package it.d4np.utils.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-08's entry point — thin by design, so this asserts the two things it does own. */
class CustomThreadPoolFactoryTest {

  private static ThreadPoolSpec spec() {
    return ThreadPoolSpec.named("factory")
        .coreThreads(1)
        .maxThreads(2)
        .queueCapacity(4)
        .drainTimeout(Duration.ofSeconds(1))
        .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
        .build();
  }

  @Test
  @DisplayName("returns a pool that runs work and reports the spec it was built from")
  void returnsARunningPool() throws InterruptedException {
    AtomicBoolean ran = new AtomicBoolean();

    try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec())) {
      pool.execute(() -> ran.set(true));
      pool.shutdown();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(ran).isTrue();
  }

  @Test
  @DisplayName("carries the drain budget onto the pool, so it cannot be supplied per call site")
  void carriesTheDrainBudgetOntoThePool() {
    // The budget lives on the pool rather than on close(): two call sites draining the same pool
    // with two different timeouts is the advisory outcome a bare ExecutorService would allow.
    try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec())) {
      assertThat(pool.drainTimeout()).isEqualTo(Duration.ofSeconds(1));
      assertThat(pool.name()).isEqualTo("factory");
    }
  }

  @Test
  @DisplayName("cannot be instantiated")
  void cannotBeInstantiated() {
    Constructor<?>[] constructors = CustomThreadPoolFactory.class.getDeclaredConstructors();

    assertThat(constructors).hasSize(1);
    assertThat(constructors[0].canAccess(null)).isFalse();
    constructors[0].setAccessible(true);
    assertThatThrownBy(() -> constructors[0].newInstance())
        .hasRootCauseInstanceOf(AssertionError.class);
  }
}
