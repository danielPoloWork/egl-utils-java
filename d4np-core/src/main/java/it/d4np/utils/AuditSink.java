package it.d4np.utils;

/**
 * Where an {@link AuditEvent} is written — FR-16's "service" half, as an SPI the host owns
 * (RFC-0002).
 *
 * <pre>{@code
 * AuditLog log = AuditLog.using(event -> auditRepository.save(toRow(event)));
 * }</pre>
 *
 * <p><strong>The event handed here is already redacted.</strong> Layer 1 and layer 2 of FR-16's
 * precedence were applied during capture, so an implementation cannot see a blocked value and does
 * not have to be trusted with one — which is the reason the redaction lives in capture rather than
 * here. An implementation is nevertheless the point where the data becomes long-lived: an audit
 * store is usually retained longer and replicated wider than an application log, so whatever
 * reaches this method should be assumed permanent.
 *
 * <p><strong>Throwing is the right thing to do when the write fails.</strong> {@link
 * AuditLog#record} wraps a failure in {@link AuditWriteException} and lets it out — the opposite of
 * the policy {@link ExecutionTimeRecorder} carries, where a failing sink is swallowed so
 * instrumentation cannot break a business call. The asymmetry is deliberate: a missing timing is a
 * lost number, a missing audit record is a compliance hole nobody finds until the next review.
 *
 * <p><strong>Thread safety.</strong> An implementation must be safe for concurrent use. One {@link
 * AuditLog} is shared by every thread that records, so a sink holding an unsynchronised buffer
 * becomes the data race the audit trail loses records to.
 *
 * @see LoggingAuditSink
 * @see AuditLog#using(AuditSink)
 */
@FunctionalInterface
public interface AuditSink {

  /**
   * Writes one already-redacted event.
   *
   * @param event the captured change set; never {@code null}
   */
  void write(AuditEvent event);
}
