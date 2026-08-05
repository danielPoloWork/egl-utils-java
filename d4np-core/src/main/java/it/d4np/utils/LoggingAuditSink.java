package it.d4np.utils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/**
 * The dependency-free {@link AuditSink} — what {@link AuditLog#create()} installs when no store has
 * been handed in.
 *
 * <p>It writes through {@link System#getLogger(String)}, so records land in whatever log the host
 * already configured and core still requires nothing but {@code java.base} (ADR-0014).
 *
 * <p><strong>A fallback, not a compliance store, and the distinction matters more here than
 * anywhere.</strong> An application log is short-retention, rarely exported and often sampled — the
 * opposite of what an audit trail is for. This sink exists so that {@code AuditLog.create()} is a
 * working call and a host wiring FR-16 up sees its records immediately; a store that satisfies a
 * retention policy is an {@link AuditSink} the host supplies.
 *
 * <p><strong>{@code INFO}, and the contrast with {@link LoggingExecutionTimeRecorder} is
 * deliberate.</strong> That one logs at {@code DEBUG} because instrumentation is optional and runs
 * on every call; this one runs once per state change and carries a record a host is required to
 * keep. Timings a host never asked for should be invisible by default; audit records should not be.
 *
 * <p><strong>Every parameter reaches the logger already rendered as text.</strong> {@link
 * System.Logger}'s parameterised {@code log} substitutes with {@link java.text.MessageFormat},
 * which renders numbers and dates through the <em>default locale</em> — so an unrendered value
 * prints differently on an Italian and a US host, and one audit line stops being comparable with
 * another. The same trap ADR-0014 records for the format string's apostrophes, one argument along.
 *
 * <p><strong>Thread safety.</strong> Immutable and stateless beyond its logger, which is what
 * {@link AuditSink} requires of an implementation.
 *
 * @see AuditLog#create()
 */
public final class LoggingAuditSink implements AuditSink {

  /** Where records go unless a test supplied its own logger; see ADR-0014 for the seam. */
  private static final Logger DEFAULT_LOGGER = System.getLogger(LoggingAuditSink.class.getName());

  /**
   * The level every record is written at.
   *
   * <p>Package-private rather than public, for the reason {@link LoggingExecutionTimeRecorder}
   * states: a caller who needs a different level needs a different sink, which is a one-line
   * lambda, and publishing this would turn a documented choice into a combination this library has
   * to support.
   */
  static final Level LEVEL = Level.INFO;

  /**
   * No apostrophe anywhere in this format: {@link java.text.MessageFormat} treats a single quote as
   * an escape and would print the placeholder that follows it verbatim (ADR-0014).
   */
  private static final String FORMAT = "d4np audit: {0} performed {1} on {2} at {3}; {4}";

  /**
   * What an absent value reads as — distinct from a captured empty string, which renders as one.
   */
  private static final String ABSENT = "<absent>";

  private final Logger logger;

  private LoggingAuditSink(Logger logger) {
    this.logger = logger;
  }

  /**
   * A sink writing to the platform logger named after this class.
   *
   * @return the default sink; never {@code null}
   */
  public static LoggingAuditSink create() {
    return new LoggingAuditSink(DEFAULT_LOGGER);
  }

  /**
   * The seam that makes the record line assertable, package-private so it is not public surface
   * (ADR-0014).
   *
   * @param logger where records go
   * @return a sink writing to {@code logger}
   * @throws NullPointerException if {@code logger} is {@code null}
   */
  static LoggingAuditSink using(Logger logger) {
    return new LoggingAuditSink(Objects.requireNonNull(logger, "logger"));
  }

  @Override
  public void write(AuditEvent event) {
    Objects.requireNonNull(event, "audit event must not be null");
    if (!logger.isLoggable(LEVEL)) {
      // The record is dropped, and a host that needs one kept configures a store rather than a log
      // level — which is the whole reason this sink documents itself as a fallback.
      return;
    }
    logger.log(
        LEVEL,
        FORMAT,
        event.actor(),
        event.action(),
        event.subjectType(),
        event.occurredAt().toString(),
        renderChanges(event.changes()));
  }

  /**
   * Renders the change set as {@code path: before -> after (changed)}.
   *
   * <p>The {@code (changed)} suffix is not decoration: for a redacted component both sides read
   * {@code [REDACTED]}, so it is the only part of the line that carries information — {@code
   * password: [REDACTED] -> [REDACTED] (changed)} is FR-16's whole purpose in one line.
   *
   * @param changes the captured components, already ordered by path
   * @return the rendered change list
   */
  private static String renderChanges(List<AuditEvent.Change> changes) {
    StringBuilder out = new StringBuilder("changes: ");
    for (int i = 0; i < changes.size(); i++) {
      AuditEvent.Change change = changes.get(i);
      if (i > 0) {
        out.append(", ");
      }
      out.append(change.path())
          .append(": ")
          .append(side(change.before()))
          .append(" -> ")
          .append(side(change.after()));
      if (change.changed()) {
        out.append(" (changed)");
      }
    }
    return out.toString();
  }

  private static String side(@Nullable String value) {
    return value == null ? ABSENT : value;
  }
}
