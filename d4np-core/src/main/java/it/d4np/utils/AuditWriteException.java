package it.d4np.utils;

/**
 * An audit record that could not be written — thrown by {@link AuditLog#record(AuditEvent)}.
 *
 * <p><strong>FR-16 throws where the rest of this library would return a {@code Result}, and this is
 * the one place loudness beats composability.</strong> A returned {@code Result} that a caller
 * ignores is silent; an audit trail that silently stops writing is a hole discovered at the next
 * compliance review, months of records later. A host that wants tolerance catches this exception
 * and says so in its own code, which is a decision visible in a diff.
 *
 * <p><strong>Not a {@link BusinessException}, and the status code is why.</strong> FR-19 maps
 * {@code BusinessException} to <strong>422</strong> — a rule the caller violated — while a failed
 * audit write is an operations failure that belongs with {@link StrategyNotFoundException}'s
 * <strong>500 plus an alert</strong>. Reporting a broken audit store as a client error would send
 * the incident to the wrong team, so a test asserts the negative rather than trusting the hierarchy
 * to stay this way.
 *
 * <p><strong>It carries the event that was not written</strong>, so a host can queue it, retry it,
 * or put it on a dead-letter path without having to capture it again. That is safe precisely
 * because {@link AuditEvent} holds no raw value — the exception may be logged, and its message and
 * payload carry no more than {@code [REDACTED]} would.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the state every {@link Throwable}
 * carries; the event it holds is deeply immutable and serialisable, so this exception survives the
 * serialisation {@code Throwable} promises.
 *
 * @see AuditLog#record(AuditEvent)
 * @see AuditSink
 */
public final class AuditWriteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The record that did not reach the sink; serialisable because {@link AuditEvent} is. */
  private final AuditEvent event;

  /**
   * Package-private: {@link AuditLog} is the only thing that knows a write failed.
   *
   * @param event the event that was not written
   * @param cause what the sink threw
   */
  AuditWriteException(AuditEvent event, Throwable cause) {
    super(describe(event), cause);
    this.event = event;
  }

  /**
   * The event the sink refused, unchanged and still redacted.
   *
   * @return the event; never {@code null}
   */
  public AuditEvent event() {
    return event;
  }

  private static String describe(AuditEvent event) {
    return "audit event ["
        + event.action()
        + "] by ["
        + event.actor()
        + "] on ["
        + event.subjectType()
        + "] was not written";
  }
}
