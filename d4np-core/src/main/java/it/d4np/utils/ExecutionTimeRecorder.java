package it.d4np.utils;

import java.time.Duration;

/**
 * Where a measured execution time goes — the sink {@link ExecutionTimeMetricAspect} writes to
 * (FR-15, RFC-0002).
 *
 * <p><strong>This interface exists because core may not see Micrometer.</strong> ADR-001 fixes
 * {@code d4np-core} at zero third-party dependencies, and spec §3 puts Micrometer in {@code
 * spring-adapter}'s {@code provided} set, not core's. So core owns the timing and defines the sink
 * as a service-provider interface; the {@code MeterRegistry}-backed implementation lives in the
 * adapter, which is allowed to name Micrometer types, and core ships {@link
 * LoggingExecutionTimeRecorder} as the dependency-free default.
 *
 * <p><strong>A recorder must not throw, and the aspect assumes it will anyway.</strong>
 * Implementations should absorb their own backend failures; {@link ExecutionTimeMetricAspect}
 * treats that as a promise it cannot rely on and catches what escapes, because a business method
 * that fails <em>because</em> the metrics backend is down is an outage the instrumentation caused
 * (RFC-0002 §FR-15).
 *
 * <p><strong>Implementations must be thread-safe.</strong> One recorder is resolved once at
 * construction and then shared by every thread the measured code runs on; the aspect does no
 * synchronisation around the call, since a lock on the instrumentation path would serialise the
 * methods being measured and change the numbers it is there to collect.
 *
 * <p><strong>What a recorder must not do is record its arguments' values</strong> — {@code name} is
 * derived from a method signature, and nothing on this interface carries a parameter value. An
 * implementation that widened it to accept arguments would be putting request data into a metrics
 * backend, which is the C-01 problem in a store that is scraped and retained.
 *
 * @see ExecutionTimeMetricAspect
 * @see LoggingExecutionTimeRecorder
 */
@FunctionalInterface
public interface ExecutionTimeRecorder {

  /**
   * Records one completed execution.
   *
   * @param name what was measured — a stable identifier such as a method signature, never a value
   *     taken from the arguments; never {@code null} or blank
   * @param elapsed how long it took, measured on a monotonic clock; never {@code null}, never
   *     negative
   * @param failed {@code true} when the measured call completed by throwing, {@code false} when it
   *     returned — both are recorded, because timing only the successes biases every latency number
   *     in the direction that hides the problem
   */
  void record(String name, Duration elapsed, boolean failed);
}
