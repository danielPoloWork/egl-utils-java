package it.d4np.utils;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One state change, already redacted — the only thing FR-16 lets out of {@link AuditLog#capture}
 * (RFC-0002).
 *
 * <p><strong>There is no API here that returns a raw value, and that is the load-bearing decision
 * of the whole feature.</strong> The obvious design holds the real values in the event and lets the
 * sink redact; it fails for a specific reason. An event is an ordinary object, and between capture
 * and sink it can be logged by an interceptor, serialised by a queue, captured in a heap dump or
 * printed by a {@code toString()} in a debugger. Every one of those is outside this library's
 * control and every one of them would see plaintext. <strong>A sink cannot leak what it never
 * receives</strong>, so the trust decision is made once, by us, in {@link AuditLog#capture} — not
 * distributed across every sink anyone ever writes.
 *
 * <p><strong>Which is why {@link #toString()} is safe by construction rather than by care.</strong>
 * It prints every component, exactly as the generated {@code toString()} of a record would; that is
 * harmless here only because nothing raw ever entered. The same trap in the other direction is what
 * forbids rendering a composite value with {@code String.valueOf} during capture — see {@link
 * AuditLog}.
 *
 * <p><strong>It is not a record, and the reason is that a public record cannot have a non-public
 * canonical constructor.</strong> A public constructor here would be a documented way to mint an
 * "audit event" holding whatever the caller likes and hand it to {@link AuditLog#record} — the
 * redaction rule would then hold for our capture path rather than for the type, which is a weaker
 * promise than the one this class exists to make. So the constructor is package-private and {@link
 * AuditLog#capture} is the only way to obtain one.
 *
 * <p><strong>{@link Serializable}, deliberately.</strong> RFC-0002's own list of the places an
 * event travels through includes a queue, and a host whose transport uses Java serialisation should
 * not be unable to send its audit records. Every field is serialisable — {@code String}, {@link
 * Instant} and an immutable {@code List} of records — which is the same constraint {@link
 * ErrorDetail} carries for {@link BusinessException} and for the same reason: {@link
 * AuditWriteException} holds an event, and a non-serialisable payload would make every such
 * exception fail to serialise, silently and only in the hosts that do it.
 *
 * <p><strong>Deserialisation does not run the constructor, so it is re-run explicitly.</strong> A
 * stream is free to claim any field values it likes, including a mutable {@code changes} list —
 * which would quietly break the immutability this class promises. {@code readResolve} rebuilds the
 * instance through the constructor, restoring both the validation and the copy. The nested {@link
 * Change} needs none of that: a record's deserialisation goes through its canonical constructor, so
 * its checks hold for free — a difference between the two shapes worth knowing before choosing one.
 *
 * <p><strong>Thread safety.</strong> Deeply immutable and safe to publish by any means: the fields
 * are final, {@code changes} is an unmodifiable copy, and every element is an immutable record of
 * {@code String}s and {@code boolean}s.
 *
 * @see AuditLog#capture(String, String, Object, Object)
 * @see AuditSink
 */
public final class AuditEvent implements Serializable {

  /**
   * What stands in for a value FR-16 blocks — layers 1 and 2 of the precedence table.
   *
   * <p>Public because a sink or a test comparing against it should not have to transcribe the
   * literal, which is exactly how two spellings of one marker end up in one store.
   */
  public static final String REDACTED = "[REDACTED]";

  private static final long serialVersionUID = 1L;

  /**
   * Who performed the change.
   *
   * <p>Every field below carries a doc comment because they are <em>serialisable</em> fields, and
   * doclint requires the serialized form of a {@link Serializable} class to be documented — the
   * plain-class counterpart of the record-component warnings item 2.1 recorded, and unlike those,
   * fixable.
   *
   * @serial
   */
  private final String actor;

  /**
   * What was performed.
   *
   * @serial
   */
  private final String action;

  /**
   * The fully-qualified runtime class name of the audited object.
   *
   * @serial
   */
  private final String subjectType;

  /**
   * When capture happened, on the wall clock.
   *
   * @serial
   */
  private final Instant occurredAt;

  /**
   * The captured components, ordered by path and already redacted.
   *
   * @serial
   */
  private final List<Change> changes;

  /**
   * Package-private: {@link AuditLog#capture} is the only thing that may declare a state change
   * audited.
   *
   * @param actor who performed the change, supplied by the caller and non-blank
   * @param action what was performed, non-blank
   * @param subjectType the fully-qualified runtime class name of the audited object
   * @param occurredAt when capture happened
   * @param changes the captured components; copied, not retained
   */
  AuditEvent(
      String actor, String action, String subjectType, Instant occurredAt, List<Change> changes) {
    this.actor = Objects.requireNonNull(actor, "audit actor must not be null");
    this.action = Objects.requireNonNull(action, "audit action must not be null");
    this.subjectType = Objects.requireNonNull(subjectType, "audit subject type must not be null");
    this.occurredAt = Objects.requireNonNull(occurredAt, "audit timestamp must not be null");
    this.changes = List.copyOf(changes);
  }

  /**
   * Who performed the change.
   *
   * <p>Supplied by the caller, never read from a security context: core may not depend on a
   * framework (ADR-001), so "who" is the host's to provide — and a blank one is refused at capture,
   * because an unattributable record is the one kind an audit store must not accept.
   *
   * @return the actor; never {@code null} or blank
   */
  public String actor() {
    return actor;
  }

  /**
   * What was performed — the caller's own verb, such as {@code PASSWORD_ROTATED}.
   *
   * @return the action; never {@code null} or blank
   */
  public String action() {
    return action;
  }

  /**
   * The fully-qualified runtime class name of the audited object.
   *
   * <p>The <em>runtime</em> class, not a declared type: FR-16's layers are applied to the object
   * that was actually passed, so this is the type they were read from.
   *
   * @return the class name; never {@code null}
   */
  public String subjectType() {
    return subjectType;
  }

  /**
   * When capture happened, on the wall clock.
   *
   * <p>A wall clock is right here and wrong two classes away: {@link ExecutionTimeMetricAspect}
   * times with {@link System#nanoTime()} because an NTP step could otherwise produce a negative
   * duration, while an audit record needs a calendar instant a human can correlate with an
   * incident. Different questions, different clocks.
   *
   * @return the capture instant; never {@code null}
   */
  public Instant occurredAt() {
    return occurredAt;
  }

  /**
   * Every captured component, ordered by path.
   *
   * <p>Ordered so that two events over the same type are comparable by eye and by diff — the same
   * reason {@link Validator} sorts its violations. The order is {@link String#compareTo}, which is
   * locale-independent; a collator would make the order depend on the host that captured.
   *
   * @return an unmodifiable list, never empty — a capture with nothing audited fails rather than
   *     returning an empty event
   */
  public List<Change> changes() {
    return changes;
  }

  @Override
  public String toString() {
    return "AuditEvent["
        + "actor="
        + actor
        + ", action="
        + action
        + ", subjectType="
        + subjectType
        + ", occurredAt="
        + occurredAt
        + ", changes="
        + changes
        + ']';
  }

  /**
   * Rebuilds a deserialised event through the constructor, which deserialisation itself skips.
   *
   * <p>An invalid stream therefore fails with the constructor's own {@code NullPointerException}
   * rather than a wrapped {@code InvalidObjectException}. Converting it would add a catch branch no
   * test can reach — the only way to reach it is a hand-forged stream — and item 3.2 established
   * the trade this project makes there: an untestable branch costs more than a slightly less
   * polished exception type.
   *
   * @return an equivalent event whose invariants have been checked
   */
  private Object readResolve() {
    return new AuditEvent(actor, action, subjectType, occurredAt, changes);
  }

  /**
   * One audited component, before and after, with the raw values already gone.
   *
   * <p><strong>{@code before} and {@code after} are {@code null} when there was no value on that
   * side</strong> — either the whole object was absent, which is what a creation or a deletion
   * looks like, or the component itself was {@code null}. The two are deliberately not
   * distinguished: in both cases there was nothing to record, and a third state would have to be
   * explained at every call site to buy information nobody has asked for.
   *
   * <p><strong>{@code changed} is the only surviving fact about a redacted component,</strong>
   * which is why it is computed at capture on the raw values and not derived here. For a blocked
   * component both sides read {@code [REDACTED]}, so no consumer could reconstruct it — and "the
   * password was changed at 14:02 by alice" is exactly the sentence FR-16 exists to be able to
   * write.
   *
   * <p><strong>It is {@link Objects#equals(Object, Object)} on the raw values,</strong> so for a
   * composite component it is the host's own {@code equals}. A composite without value semantics
   * reports a change whenever the instance differs; that is the host's definition of equality, not
   * a judgement this library is in a position to improve.
   *
   * @param path the component's name, dot-joined through any {@link Audited} composites above it
   * @param before the value before the change, or {@code null} if there was none
   * @param after the value after the change, or {@code null} if there was none
   * @param redacted whether FR-16 blocked the value, in which case both sides are {@link #REDACTED}
   * @param changed whether the raw values differed
   */
  public record Change(
      String path,
      @Nullable String before,
      @Nullable String after,
      boolean redacted,
      boolean changed)
      implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Validates what a hand-built change could otherwise get wrong; capture cannot.
     *
     * <p>The tags below repeat the record's own: JDK 18+ infers a compact constructor's {@code
     * @param} tags from the components and JDK 17 does not, so omitting them fails doclint on one
     * supported toolchain only (item 2.1's finding, met again here).
     *
     * @param path the component's dot-joined path
     * @param before the value before the change, or {@code null} if there was none
     * @param after the value after the change, or {@code null} if there was none
     * @param redacted whether FR-16 blocked the value
     * @param changed whether the raw values differed
     */
    public Change {
      Objects.requireNonNull(path, "audit change path must not be null");
      if (path.isBlank()) {
        throw new IllegalArgumentException("audit change path must not be blank");
      }
    }

    /**
     * A blocked component: both sides {@link #REDACTED}, whatever the raw values were.
     *
     * <p><strong>Including when a raw value was absent.</strong> Rendering {@code null} on one side
     * would publish whether a secret is currently set, which is itself a fact worth withholding —
     * and {@code changed} already carries the one bit FR-16 wants.
     *
     * @param path the component's dot-joined path
     * @param changed whether the raw values differed
     * @return a redacted change at {@code path}
     */
    static Change redacted(String path, boolean changed) {
      return new Change(path, REDACTED, REDACTED, true, changed);
    }
  }
}
