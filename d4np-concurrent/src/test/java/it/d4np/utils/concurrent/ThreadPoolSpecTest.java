package it.d4np.utils.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.d4np.utils.BuilderValidationException;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-08's configuration object — what it makes mandatory, and what it refuses. */
class ThreadPoolSpecTest {

  private static ThreadPoolSpec.Builder valid() {
    return ThreadPoolSpec.named("ingest")
        .coreThreads(2)
        .maxThreads(4)
        .queueCapacity(8)
        .drainTimeout(Duration.ofSeconds(5))
        .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy());
  }

  @Nested
  @DisplayName("what it will not let you leave out")
  class Mandatory {

    @Test
    @DisplayName("reports every missing mandatory field at once, not the first")
    void reportsEveryMissingFieldAtOnce() {
      // The whole return on extending FluentBuilder (FR-02, ADR-0017): a spec has nine parameters,
      // and one-violation-per-round-trip would be four builds to learn four things.
      assertThatThrownBy(() -> ThreadPoolSpec.named("ingest").build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("queueCapacity")
          .hasMessageContaining("drainTimeout")
          .hasMessageContaining("rejectionPolicy")
          .hasMessageContaining("maxThreads");
    }

    @Test
    @DisplayName("refuses a spec with no queue bound, because an unbounded queue never rejects")
    void refusesAnUnboundedQueue() {
      // The finding behind the mandatory parameter: Executors.newFixedThreadPool uses an unbounded
      // queue, so FR-08's "explicit RejectedExecutionHandler" could never run over it. There is no
      // overload that omits the capacity, so the mistake is unavailable rather than discouraged.
      assertThatThrownBy(
              () ->
                  ThreadPoolSpec.named("ingest")
                      .coreThreads(1)
                      .maxThreads(1)
                      .drainTimeout(Duration.ofSeconds(1))
                      .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
                      .build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("queueCapacity must be at least 1");
    }

    @Test
    @DisplayName("accepts an explicitly unbounded queue, which is a decision rather than a default")
    void acceptsAnExplicitlyUnboundedQueue() {
      ThreadPoolSpec spec = valid().queueCapacity(Integer.MAX_VALUE).build();

      assertThat(spec.queueCapacity()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("refuses a spec with no rejection policy")
    void refusesAMissingRejectionPolicy() {
      assertThatThrownBy(
              () ->
                  ThreadPoolSpec.named("ingest")
                      .coreThreads(1)
                      .maxThreads(1)
                      .queueCapacity(1)
                      .drainTimeout(Duration.ofSeconds(1))
                      .build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("rejectionPolicy is required");
    }

    @Test
    @DisplayName("refuses a spec with no drain timeout, because NFR-05's budget has no default")
    void refusesAMissingDrainTimeout() {
      assertThatThrownBy(
              () ->
                  ThreadPoolSpec.named("ingest")
                      .coreThreads(1)
                      .maxThreads(1)
                      .queueCapacity(1)
                      .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
                      .build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("drainTimeout must be set");
    }
  }

  @Nested
  @DisplayName("bounds it applies to what it is given")
  class Bounds {

    @Test
    @DisplayName("the fixture itself is valid, so every refusal below is about what it changed")
    void theFixtureIsValid() {
      assertThatCode(() -> valid().build()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a blank name, because a nameless pool is what FR-08 exists to replace")
    void refusesABlankName() {
      assertThatThrownBy(
              () ->
                  ThreadPoolSpec.named("   ")
                      .coreThreads(1)
                      .maxThreads(1)
                      .queueCapacity(1)
                      .drainTimeout(Duration.ofSeconds(1))
                      .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
                      .build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("name must not be blank");
    }

    @Test
    @DisplayName("refuses control characters in a name, which would fold one log line into two")
    void refusesControlCharactersInAName() {
      // A thread name reaches a thread dump and every log line the pool writes. This is the same
      // reasoning KeyDiagnostics, JsonDiagnostics and PageDiagnostics each applied to a rendered
      // value -- applied here at construction, because the name is fixed once and reused forever.
      assertThatThrownBy(
              () ->
                  ThreadPoolSpec.named("ingest\r\nWARN fake log line")
                      .coreThreads(1)
                      .maxThreads(1)
                      .queueCapacity(1)
                      .drainTimeout(Duration.ofSeconds(1))
                      .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
                      .build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("name must not contain control characters");
    }

    @Test
    @DisplayName("refuses maxThreads below coreThreads, and says which pair disagreed")
    void refusesMaxBelowCore() {
      assertThatThrownBy(() -> valid().coreThreads(8).maxThreads(2).build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("maxThreads (2) must be at least coreThreads (8)");
    }

    @Test
    @DisplayName("refuses a priority outside the platform range")
    void refusesAnOutOfRangePriority() {
      assertThatThrownBy(() -> valid().priority(Thread.MAX_PRIORITY + 1).build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("priority must be between");
    }

    @Test
    @DisplayName("refuses a non-positive drain timeout")
    void refusesANonPositiveDrainTimeout() {
      assertThatThrownBy(() -> valid().drainTimeout(Duration.ZERO).build())
          .isInstanceOf(BuilderValidationException.class)
          .hasMessageContaining("drainTimeout must be positive");
    }
  }

  @Nested
  @DisplayName("the defaults it chooses when you say nothing")
  class Defaults {

    @Test
    @DisplayName("threads are non-daemon, so a forgotten close() hangs rather than losing work")
    void threadsAreNonDaemonByDefault() {
      // The alternative -- daemon by default -- lets the JVM exit with work in flight, which is
      // silent loss and the direct contradiction of NFR-05's drain. A hang is diagnosable.
      assertThat(valid().build().daemon()).isFalse();
    }

    @Test
    @DisplayName("no priority is requested, so the platform default stands")
    void noPriorityByDefault() {
      assertThat(valid().build().priority()).isEmpty();
    }

    @Test
    @DisplayName("no uncaught-exception handler is set, which selects the logging one")
    void noHandlerByDefault() {
      assertThat(valid().build().uncaughtExceptionHandler()).isEmpty();
    }

    @Test
    @DisplayName("keep-alive defaults to a minute")
    void keepAliveDefaultsToAMinute() {
      assertThat(valid().build().keepAlive()).isEqualTo(Duration.ofSeconds(60));
    }
  }

  @Nested
  @DisplayName("what it renders")
  class Rendering {

    @Test
    @DisplayName("names each handler's type and never the handler itself")
    void namesHandlerTypesAndNeverInstances() {
      // C-01: a RejectedExecutionHandler is a caller-supplied object whose toString() this library
      // does not control, and a pool spec is exactly the kind of thing a startup log prints.
      Thread.UncaughtExceptionHandler leaky =
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable failure) {
              // no-op
            }

            @Override
            public String toString() {
              return "hunter2";
            }
          };

      String rendered = valid().uncaughtExceptionHandler(leaky).build().toString();

      assertThat(rendered).doesNotContain("hunter2");
      assertThat(rendered).contains(leaky.getClass().getName());
      assertThat(rendered)
          .contains("rejectionPolicy=java.util.concurrent.ThreadPoolExecutor$AbortPolicy");
    }

    @Test
    @DisplayName("says 'default' rather than 'empty' where nothing was configured")
    void saysDefaultWhereNothingWasConfigured() {
      String rendered = valid().build().toString();

      assertThat(rendered).contains("priority=default");
      assertThat(rendered).contains("uncaughtExceptionHandler=default");
    }
  }
}
