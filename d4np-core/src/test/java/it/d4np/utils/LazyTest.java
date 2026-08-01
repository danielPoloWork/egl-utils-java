package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code Lazy<T>} against the RFC-0001 contract — ROADMAP item 2.2 (FR-03, NFR-01).
 *
 * <p>The contract has four rows and each one is a group below: at-most-once initialization, the
 * failure policy (retry by default, memoizing on request), the two defect cases, and safe
 * publication. The publication row is the only one a unit test cannot actually prove — {@code
 * LazyPublicationStress} does that — so the concurrency test here is a fast regression net, not the
 * proof.
 */
@DisplayName("Lazy")
class LazyTest {

  private static final String VALUE = "computed";

  // --- laziness and at-most-once ---

  @Test
  @DisplayName("does not call the initializer until get() is called")
  void isActuallyLazy() {
    AtomicInteger calls = new AtomicInteger();

    Lazy<String> lazy = Lazy.of(counting(calls, VALUE));

    assertThat(calls).hasValue(0);
    assertThat(lazy.get()).isEqualTo(VALUE);
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("calls the initializer at most once, and returns the same instance every time")
  void initializesAtMostOnce() {
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy = Lazy.of(counting(calls, VALUE));

    String first = lazy.get();
    String second = lazy.get();
    String third = lazy.get();

    assertThat(calls).hasValue(1);
    assertThat(first).isSameAs(second).isSameAs(third);
  }

  @Test
  @DisplayName("is final and offers no public constructor — the factories are the whole surface")
  void isConstructedOnlyThroughItsFactories() {
    assertThat(Modifier.isFinal(Lazy.class.getModifiers())).isTrue();
    assertThat(Lazy.class.getConstructors()).isEmpty();
  }

  // --- the retry policy (default) ---

  @Test
  @DisplayName("retry is the default: a failure propagates unchanged and is not remembered")
  void retriesAfterAFailure() {
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy =
        Lazy.of(
            () -> {
              if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("attempt " + calls.get());
              }
              return VALUE;
            });

    assertThat(catchThrowable(lazy::get)).hasMessage("attempt 1");
    assertThat(catchThrowable(lazy::get)).hasMessage("attempt 2");
    assertThat(lazy.get()).isEqualTo(VALUE);
    assertThat(calls).hasValue(3);

    // Once it has succeeded, at-most-once takes over again.
    assertThat(lazy.get()).isEqualTo(VALUE);
    assertThat(calls).hasValue(3);
  }

  @Test
  @DisplayName("retry propagates the initializer's own exception instance, not a wrapper")
  void retryPropagatesTheOriginalException() {
    IllegalStateException thrown = new IllegalStateException("boom");
    Lazy<String> lazy =
        Lazy.of(
            () -> {
              throw thrown;
            });

    assertThat(catchThrowable(lazy::get)).isSameAs(thrown);
    assertThat(catchThrowable(lazy::get)).isSameAs(thrown);
  }

  // --- the memoizing policy (opt-in) ---

  @Test
  @DisplayName("memoizingFailures calls the initializer once and rethrows the same instance")
  void memoizesTheFirstFailure() {
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy =
        Lazy.memoizingFailures(
            () -> {
              throw new IllegalStateException("attempt " + calls.incrementAndGet());
            });

    Throwable first = catchThrowable(lazy::get);
    Throwable second = catchThrowable(lazy::get);
    Throwable third = catchThrowable(lazy::get);

    assertThat(calls).as("the initializer is not retried").hasValue(1);
    assertThat(first).hasMessage("attempt 1");
    assertThat(second).isSameAs(first);
    assertThat(third).isSameAs(first);
  }

  @Test
  @DisplayName("memoizingFailures remembers an Error too, and rethrows it as an Error")
  void memoizesAnError() {
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy =
        Lazy.memoizingFailures(
            () -> {
              calls.incrementAndGet();
              throw new BootError();
            });

    Throwable first = catchThrowable(lazy::get);

    assertThat(first).isInstanceOf(BootError.class);
    assertThat(catchThrowable(lazy::get)).isSameAs(first);
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("memoizingFailures changes nothing when the initializer succeeds")
  void memoizingIsInvisibleOnTheHappyPath() {
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy = Lazy.memoizingFailures(counting(calls, VALUE));

    assertThat(lazy.get()).isEqualTo(VALUE);
    assertThat(lazy.get()).isEqualTo(VALUE);
    assertThat(calls).hasValue(1);
  }

  // --- the two defects ---

  @Test
  @DisplayName("an initializer returning null is a defect, because get() never returns null")
  void rejectsANullResult() {
    Lazy<String> lazy = Lazy.of(() -> null);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(lazy::get)
        .withMessageContaining("returned null");
  }

  @Test
  @DisplayName("a null result is retried like any other failure, so it is not a permanent wedge")
  void aNullResultIsRetried() {
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy = Lazy.of(() -> calls.incrementAndGet() == 1 ? null : VALUE);

    assertThat(catchThrowable(lazy::get)).isInstanceOf(IllegalStateException.class);
    assertThat(lazy.get()).isEqualTo(VALUE);
  }

  @Test
  @DisplayName("a re-entrant initializer throws rather than recursing into a StackOverflowError")
  void rejectsReEntrantInitialization() {
    // A lambda cannot capture the local it initialises, so the self-reference goes through a
    // holder. requireNonNull rather than a NullAway suppression: the holder is set before any
    // get() runs, and if that stops being true this fails at the defect rather than reporting
    // re-entrancy.
    AtomicReference<Lazy<String>> self = new AtomicReference<>();
    Lazy<String> lazy = Lazy.of(() -> Objects.requireNonNull(self.get()).get());
    self.set(lazy);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(lazy::get)
        .withMessageContaining("re-entrant");
  }

  @Test
  @DisplayName("a re-entrant failure does not wedge the Lazy — the next get() initializes normally")
  void reEntrancyLeavesNoStuckState() {
    AtomicReference<Lazy<String>> self = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy =
        Lazy.of(
            () -> calls.incrementAndGet() == 1 ? Objects.requireNonNull(self.get()).get() : VALUE);
    self.set(lazy);

    assertThat(catchThrowable(lazy::get)).isInstanceOf(IllegalStateException.class);

    // If the re-entrancy guard had not been cleared, this would report re-entrancy forever.
    assertThat(lazy.get()).isEqualTo(VALUE);
    assertThat(calls).hasValue(2);
  }

  // --- publication: the fast net, not the proof (LazyPublicationStress is) ---

  @Test
  @DisplayName(
      "eight threads racing on get() all receive the one instance the initializer produced")
  void isSafeUnderConcurrentFirstUse() throws Exception {
    int threads = 8;
    AtomicInteger calls = new AtomicInteger();
    Lazy<String> lazy = Lazy.of(() -> VALUE + calls.incrementAndGet());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier startTogether = new CyclicBarrier(threads);
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  return lazy.get();
                }));
      }

      String expected = lazy.get();
      for (Future<String> result : results) {
        assertThat(result.get()).isSameAs(expected);
      }
      assertThat(calls).as("the initializer ran once across all nine callers").hasValue(1);
    } finally {
      pool.shutdownNow();
    }
  }

  /** Both factories reject a null initializer at construction, not at first use. */
  @Nested
  @DisplayName("rejects a null initializer")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  class RejectsNulls {

    @Test
    @DisplayName("of(null)")
    void ofRejectsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> Lazy.of(null))
          .withMessageContaining("initializer");
    }

    @Test
    @DisplayName("memoizingFailures(null)")
    void memoizingFailuresRejectsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> Lazy.memoizingFailures(null))
          .withMessageContaining("initializer");
    }
  }

  private static Supplier<String> counting(AtomicInteger calls, String value) {
    return () -> {
      calls.incrementAndGet();
      return value;
    };
  }

  /**
   * A deliberate {@link Error}, so the memoizing policy can be tested on the non-Runtime branch.
   */
  private static final class BootError extends Error {
    private static final long serialVersionUID = 1L;
  }
}
