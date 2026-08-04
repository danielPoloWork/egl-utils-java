package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-15 (RFC-0002): the contract of {@link ExecutionTimeMetricAspect}.
 *
 * <p>Every row of the RFC's contract table is asserted here except the Micrometer recorder, which
 * is item 7.x's adapter work: the sink interface, the fallback, one-shot resolution, the
 * never-propagate rule and the both-completions rule. The default recorder's own output is in
 * {@link LoggingExecutionTimeRecorderTest}, and the concurrent half of the never-propagate rule —
 * one warning between two racing threads — is in {@code ExecutionTimeRecorderFailureStress}.
 */
@DisplayName("ExecutionTimeMetricAspect")
class ExecutionTimeMetricAspectTest {

  /**
   * A recorder that keeps what it was told, so the measurement can be asserted rather than logged.
   */
  private static final class Recording implements ExecutionTimeRecorder {

    private final List<String> records = new CopyOnWriteArrayList<>();
    private final List<Duration> elapsed = new CopyOnWriteArrayList<>();

    @Override
    public void record(String name, Duration took, boolean failed) {
      records.add(name + "/" + failed);
      elapsed.add(took);
    }
  }

  /** A recorder that is broken in the way a metrics backend is broken: on every call. */
  private static final class Broken implements ExecutionTimeRecorder {

    private final AtomicInteger calls = new AtomicInteger();
    private final RuntimeException failure = new IllegalStateException("meter registry is down");

    @Override
    public void record(String name, Duration took, boolean failed) {
      calls.incrementAndGet();
      throw failure;
    }
  }

  /**
   * The one checked exception a measured call can throw, to prove {@link
   * ExecutionTimeMetricAspect#time} does not wrap it.
   */
  private static final class Checked extends Exception {

    private static final long serialVersionUID = 1L;

    Checked() {
      super("checked");
    }
  }

  private final Recording recorder = new Recording();

  private final ExecutionTimeMetricAspect aspect = ExecutionTimeMetricAspect.using(recorder);

  @Test
  @SuppressWarnings("IllegalThrows") // the measured call's exception, not this test's; see ADR-0021
  void recordsANormalReturnAsNotFailed() throws Throwable {
    String result = aspect.time("Service.read", () -> "value");

    assertThat(result).isEqualTo("value");
    assertThat(recorder.records).containsExactly("Service.read/false");
  }

  @Test
  void recordsWhatTheSupplierAndTheActionDid() {
    assertThat(aspect.call("Service.load", () -> 42)).isEqualTo(42);
    aspect.run("Service.flush", () -> {});

    assertThat(recorder.records).containsExactly("Service.load/false", "Service.flush/false");
  }

  /**
   * The RFC's "coverage" row: recording only successes biases every latency number, so a call that
   * threw is still measured — and its exception still reaches its caller unchanged.
   */
  @Test
  void recordsAFailedCallAndPropagatesItsException() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                aspect.call(
                    "Service.reject",
                    () -> {
                      throw new IllegalArgumentException("rejected");
                    }))
        .withMessage("rejected");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                aspect.run(
                    "Service.rejectVoid",
                    () -> {
                      throw new IllegalArgumentException("rejected");
                    }));

    assertThat(recorder.records).containsExactly("Service.reject/true", "Service.rejectVoid/true");
  }

  /**
   * Why {@link ExecutionTimeMetricAspect#time} declares {@code throws Throwable}: an
   * {@code @Around} advice must hand its caller the exception the measured method threw, not a
   * wrapper around it.
   */
  @Test
  void propagatesACheckedExceptionUnwrapped() {
    Checked thrown = new Checked();

    assertThatThrownBy(
            () ->
                aspect.time(
                    "Service.io",
                    () -> {
                      throw thrown;
                    }))
        .isSameAs(thrown);

    assertThat(recorder.records).containsExactly("Service.io/true");
  }

  /**
   * An {@link Error} is a completion too, and dropping its measurement would hide the slow path.
   */
  @Test
  void recordsACallThatDiedOnAnError() {
    assertThatThrownBy(
            () ->
                aspect.run(
                    "Service.assertion",
                    () -> {
                      throw new AssertionError("boom");
                    }))
        .isInstanceOf(AssertionError.class);

    assertThat(recorder.records).containsExactly("Service.assertion/true");
  }

  @Test
  @SuppressWarnings("IllegalThrows") // the measured call's exception, not this test's; see ADR-0021
  void reportsNullReturnsWithoutJudgingThem() throws Throwable {
    assertThat(aspect.<String>time("Service.absent", () -> null)).isNull();
    assertThat(aspect.<String>call("Service.absentToo", () -> null)).isNull();

    assertThat(recorder.records).containsExactly("Service.absent/false", "Service.absentToo/false");
  }

  /**
   * The elapsed time is a real measurement on a monotonic clock, so it is positive and at least as
   * long as work that demonstrably took time. No upper bound is asserted: a CI cell can be
   * descheduled for an arbitrarily long moment, and a test that forbids that is a flaky test.
   */
  @Test
  void measuresOnAMonotonicClock() {
    aspect.run(
        "Service.slow",
        () -> {
          long deadline = System.nanoTime() + Duration.ofMillis(5).toNanos();
          while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
          }
        });

    assertThat(recorder.elapsed).hasSize(1);
    assertThat(recorder.elapsed.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(5)).isPositive();
  }

  /**
   * The RFC's "selection" row. The sink is a field fixed at construction, so every invocation
   * reports to the same instance — a per-invocation lookup would let the sink change mid-run, and
   * two measurements of one method would stop being comparable.
   */
  @Test
  void resolvesTheSinkOnceAtConstruction() {
    Recording other = new Recording();
    ExecutionTimeMetricAspect second = ExecutionTimeMetricAspect.using(other);

    aspect.run("first", () -> {});
    second.run("second", () -> {});
    aspect.run("first-again", () -> {});

    assertThat(recorder.records).containsExactly("first/false", "first-again/false");
    assertThat(other.records).containsExactly("second/false");
  }

  @Test
  void createsAnAspectOverTheLoggingFallback() {
    ExecutionTimeMetricAspect logging = ExecutionTimeMetricAspect.create();

    assertThat(logging.call("Service.logged", () -> "ok")).isEqualTo("ok");
  }

  /**
   * The never-propagate rule, which is the one place this class deliberately swallows a throwable:
   * a business method that fails <em>because</em> the metrics backend is down is an outage the
   * instrumentation caused.
   */
  @Test
  void neverLetsABrokenRecorderReachTheCaller() {
    Broken broken = new Broken();
    LogRecorder log = new LogRecorder();
    ExecutionTimeMetricAspect guarded = ExecutionTimeMetricAspect.using(broken, log);

    assertThat(guarded.call("Service.read", () -> "value")).isEqualTo("value");
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                guarded.call(
                    "Service.write",
                    () -> {
                      throw new IllegalStateException("business rule");
                    }))
        .withMessage("business rule");

    assertThat(broken.calls).hasValue(2);
  }

  /**
   * "At most once per aspect" — an unbounded warning on a sink that fails on every call is its own
   * denial of service, so the second and later failures are silent and the first names the
   * recorder.
   */
  @Test
  void warnsOnceAboutABrokenRecorder() {
    Broken broken = new Broken();
    LogRecorder log = new LogRecorder();
    ExecutionTimeMetricAspect guarded = ExecutionTimeMetricAspect.using(broken, log);

    for (int i = 0; i < 25; i++) {
      guarded.run("Service.hot", () -> {});
    }

    assertThat(broken.calls).hasValue(25);
    assertThat(log.messages()).hasSize(1);
    assertThat(log.messages().get(0))
        .startsWith("WARNING ")
        .contains(Broken.class.getName())
        .contains("the measured call is unaffected")
        .contains("meter registry is down")
        .doesNotContain("{0}");
  }

  /**
   * A recorder wired against a backend whose classes are absent fails with {@link
   * NoClassDefFoundError}, and killing a business call over a missing metrics jar is exactly the
   * outage the policy forbids — so {@link LinkageError} is absorbed like a {@link
   * RuntimeException}.
   */
  @Test
  void absorbsALinkageErrorFromTheRecorder() {
    LogRecorder log = new LogRecorder();
    ExecutionTimeMetricAspect guarded =
        ExecutionTimeMetricAspect.using(
            (name, took, failed) -> {
              throw new NoClassDefFoundError("io/micrometer/core/instrument/MeterRegistry");
            },
            log);

    assertThat(guarded.call("Service.read", () -> "value")).isEqualTo("value");
    assertThat(log.messages()).hasSize(1);
  }

  /**
   * The deliberate limit of the swallowing: an {@link OutOfMemoryError} raised inside a recorder
   * says the VM is dying, and hiding that behind a timing that looks fine would be worse than the
   * outage the rule prevents. {@link StackOverflowError} stands in for it because it can be
   * constructed without exhausting the heap.
   */
  @Test
  void letsAVirtualMachineErrorFromTheRecorderThrough() {
    ExecutionTimeMetricAspect guarded =
        ExecutionTimeMetricAspect.using(
            (name, took, failed) -> {
              throw new StackOverflowError("recorder recursed");
            });

    assertThatThrownBy(() -> guarded.run("Service.read", () -> {}))
        .isInstanceOf(StackOverflowError.class)
        .hasMessage("recorder recursed");
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsANameThatCannotIdentifyAMeasurement() {
    assertThatNullPointerException().isThrownBy(() -> aspect.call(null, () -> "v"));
    assertThatNullPointerException().isThrownBy(() -> aspect.run(null, () -> {}));
    assertThatNullPointerException().isThrownBy(() -> aspect.time(null, () -> "v"));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> aspect.call("   ", () -> "v"))
        .withMessage("measured name must not be blank");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> aspect.run("", () -> {}));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> aspect.time("\t", () -> "v"));

    assertThat(recorder.records).isEmpty();
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsAnAbsentMeasuredCallAndAnAbsentRecorder() {
    assertThatNullPointerException().isThrownBy(() -> aspect.call("Service.read", null));
    assertThatNullPointerException().isThrownBy(() -> aspect.run("Service.read", null));
    assertThatNullPointerException().isThrownBy(() -> aspect.time("Service.read", null));
    assertThatNullPointerException().isThrownBy(() -> ExecutionTimeMetricAspect.using(null));
    assertThatNullPointerException()
        .isThrownBy(() -> ExecutionTimeMetricAspect.using(recorder, null));
    assertThatNullPointerException()
        .isThrownBy(() -> ExecutionTimeMetricAspect.using(null, new LogRecorder()));

    assertThat(recorder.records).isEmpty();
  }
}
