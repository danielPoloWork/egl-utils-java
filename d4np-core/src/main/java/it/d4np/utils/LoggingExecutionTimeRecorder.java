package it.d4np.utils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;

/**
 * The dependency-free {@link ExecutionTimeRecorder} — FR-15's "and logging otherwise" (RFC-0002).
 *
 * <p>This is what {@link ExecutionTimeMetricAspect#create()} installs when no metrics backend has
 * been handed in. It writes through {@link System#getLogger(String)}, so the line lands in whatever
 * log the host already configured and core still requires nothing but {@code java.base} (ADR-0014).
 *
 * <p><strong>{@code DEBUG}, deliberately, and it is a decision rather than a default.</strong> One
 * record per invocation at {@code INFO} would be on by default in every host that installed no
 * backend, and a per-call log line is the kind of output that quietly becomes the dominant cost of
 * the method it is measuring. A metrics fallback is a diagnostic: the host enables it while looking
 * at something, which is exactly what {@code DEBUG} means. ADR-0021 records the trade — the cost is
 * that a host expecting timings without configuring anything sees nothing, which the Javadoc here
 * and on the aspect states rather than leaves to be discovered.
 *
 * <p><strong>Microseconds, formatted here rather than by the backend.</strong> {@link
 * System.Logger}'s parameterised {@code log} substitutes with {@link java.text.MessageFormat},
 * which renders a {@code Long} through the <em>default locale</em> — so {@code 1234} prints as
 * {@code 1.234} on an Italian JVM and {@code 1,234} on a US one, and a log line that cannot be
 * diffed between two hosts is worth less than one that is slightly less pretty. Every parameter is
 * therefore already a {@link String} when it reaches the logger. Microseconds rather than
 * milliseconds because a millisecond rounds most in-process calls to {@code 0}.
 *
 * <p><strong>Thread safety.</strong> Immutable and stateless beyond its logger, so one instance
 * serves every thread — which is what {@link ExecutionTimeRecorder} requires of an implementation.
 *
 * @see ExecutionTimeMetricAspect
 */
public final class LoggingExecutionTimeRecorder implements ExecutionTimeRecorder {

  /** Where records go unless a test supplied its own logger; see ADR-0014 for the seam. */
  private static final Logger DEFAULT_LOGGER =
      System.getLogger(LoggingExecutionTimeRecorder.class.getName());

  /**
   * The level every record is written at.
   *
   * <p>Package-private rather than public: a caller who needs a different level has a different
   * recorder, and publishing this would turn a documented choice into a knob whose combinations
   * this library would then have to support.
   */
  static final Level LEVEL = Level.DEBUG;

  /**
   * No apostrophe anywhere in this format: {@link java.text.MessageFormat} treats a single quote as
   * an escape and would print the placeholder that follows it verbatim (ADR-0014).
   */
  private static final String FORMAT = "d4np execution time: {0} took {1} us (failed: {2})";

  private final Logger logger;

  private LoggingExecutionTimeRecorder(Logger logger) {
    this.logger = logger;
  }

  /**
   * A recorder writing to the platform logger named after this class.
   *
   * @return the default recorder; never {@code null}
   */
  public static LoggingExecutionTimeRecorder create() {
    return new LoggingExecutionTimeRecorder(DEFAULT_LOGGER);
  }

  /**
   * The seam that makes the log line assertable, package-private so it is not public surface.
   *
   * <p>ADR-0014 records why a test cannot install a {@link System.LoggerFinder} under surefire and
   * cannot reach {@code java.util.logging} from inside this module, which is what leaves injection
   * as the way to test a logging contract at all.
   *
   * @param logger where records go
   * @return a recorder writing to {@code logger}
   * @throws NullPointerException if {@code logger} is {@code null}
   */
  static LoggingExecutionTimeRecorder using(Logger logger) {
    return new LoggingExecutionTimeRecorder(Objects.requireNonNull(logger, "logger"));
  }

  @Override
  public void record(String name, Duration elapsed, boolean failed) {
    Objects.requireNonNull(name, "measured name must not be null");
    Objects.requireNonNull(elapsed, "elapsed duration must not be null");
    // Checked before the arguments are rendered: with DEBUG off — the common case in production —
    // this recorder should cost a level test and nothing else, since it sits on every call of every
    // measured method.
    if (!logger.isLoggable(LEVEL)) {
      return;
    }
    logger.log(
        LEVEL, FORMAT, name, Long.toString(elapsed.toNanos() / 1_000L), Boolean.toString(failed));
  }
}
