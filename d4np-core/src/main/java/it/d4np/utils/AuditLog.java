package it.d4np.utils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * State-change audit trails that cannot carry a secret — FR-16, under RFC-0002's redaction policy.
 *
 * <pre>{@code
 * // once, at start-up
 * AuditLog audit = AuditLog.using(event -> auditRepository.save(event));   // or .create()
 *
 * public @Audited record Account(String owner, @Sensitive String password, int loginCount) { }
 *
 * audit.record(audit.capture("alice", "PASSWORD_ROTATED", before, after));
 * // owner      -> alice      -> alice
 * // password   -> [REDACTED] -> [REDACTED]   (changed)
 * // loginCount -> 41         -> 41
 * }</pre>
 *
 * <p><strong>Redaction happens at capture, not at write, and that single decision is the
 * feature.</strong> The {@link AuditEvent} leaving {@link #capture} is already redacted and exposes
 * no raw value. The alternative — hold the real values and let the sink redact — fails because an
 * event is an ordinary object: on its way to a sink it can be logged by an interceptor, serialised
 * by a queue, captured in a heap dump or printed by a {@code toString()} in a debugger, and every
 * one of those is outside this library's control. <strong>A sink cannot leak what it never
 * receives.</strong>
 *
 * <p><strong>An audit store is the worst place for a leak, which is why this is not a hardening
 * pass bolted onto a simpler design.</strong> An application log is noisy and short-retention; an
 * audit store is deliberately long-retention, widely replicated and exported for compliance review.
 * "Redact it later" does not work there — the records are already written and already copied.
 *
 * <p><strong>Four layers, first match wins</strong> (RFC-0002's precedence table). The
 * specification asked for an allowlist, a {@code @Sensitive} opt-out and a never-capture list;
 * those are three different <em>defaults</em>, so the contract is the ordering between them:
 *
 * <ol>
 *   <li>{@link AuditPolicy}'s <strong>never-capture list</strong> — {@code [REDACTED]}, overridable
 *       by nothing;
 *   <li>{@link Sensitive} on the component — {@code [REDACTED]};
 *   <li>{@link Audited} on the component — captured;
 *   <li>{@link Audited} on the type — every component captured;
 *   <li>otherwise <strong>omitted entirely</strong>: the name does not appear at all.
 * </ol>
 *
 * <p><strong>Redacted and omitted are different outcomes on purpose.</strong> A blocked component
 * still appears, with {@link AuditEvent.Change#changed()} beside it, because <em>"the password was
 * changed at 14:02 by alice"</em> is the record an audit trail exists to hold and it carries no
 * plaintext. A component nobody audited does not appear at all.
 *
 * <p><strong>Only simple values are captured directly</strong> — text, numbers, booleans,
 * characters, enum constants, {@code java.time} types and {@code UUID}. Anything else must carry
 * {@link Audited} on its own type, and capture then recurses and applies all four layers again.
 * This is not fussiness: rendering a composite with {@code String.valueOf} would print a record's
 * generated {@code toString()}, so an {@code @Audited Credentials} field would emit the
 * {@code @Sensitive} password inside it — the marker present, correct, and completely bypassed. A
 * component that is neither simple nor {@code @Audited} therefore fails loudly at first capture
 * instead of producing a plausible-looking record.
 *
 * <p><strong>The layers are read from the declaration; the type is read from the value.</strong>
 * Markers come from the record component, field or accessor, but what a value <em>is</em> comes
 * from its runtime class — so an {@code Object}-declared component cannot smuggle a composite past
 * the rules by hiding behind its declared type.
 *
 * <p><strong>Recursion is bounded at three levels and cycles are refused by identity.</strong> Both
 * are errors rather than truncations, because a truncated audit record that looks complete is worse
 * than a refused one. A cycle would eventually hit the depth bound anyway; it is detected
 * separately so the message says <em>cycle</em> and sends the reader to the loop rather than to an
 * imagined deep graph.
 *
 * <p><strong>{@link #record} throws rather than returning a {@code Result},</strong> the one place
 * in this library where loudness beats composability: an ignored return value is silent, and an
 * audit trail that silently stops writing is a compliance hole found at the next review.
 *
 * <p><strong>No content inspection and no deep reflection.</strong> Redaction is by declaration
 * only — scanning values for things that look like card numbers has false positives that corrupt
 * records and false negatives that manufacture confidence. Values are read through public
 * accessors, so a consuming module needs no {@code opens} clause; asking for deep reflective access
 * over a host's packages in order to build a redaction engine is the wrong privilege to request.
 *
 * <p><strong>No plan cache, and that is a measured omission rather than an oversight.</strong>
 * Every capture re-reads the type reflectively. No NFR states a budget for FR-16, and this
 * project's rule is that a performance claim needs a benchmark behind it — so caching would be
 * either an unbacked claim or an invented budget. A {@code ClassValue} cache is a purely additive
 * change if a host ever measures one being needed.
 *
 * <p><strong>Thread safety.</strong> Safe for concurrent use and intended to be shared: the sink
 * and the policy are immutable references and capture holds all of its state on the stack, which
 * {@code AuditCaptureIsolationStress} proves rather than asserts. A sink is required to be
 * thread-safe for the same reason.
 *
 * @see Audited
 * @see Sensitive
 * @see AuditPolicy
 * @see AuditEvent
 */
public final class AuditLog {

  /**
   * How many levels of components below the root may be captured (RFC-0002).
   *
   * <p>Not configurable: a knob here would mean supporting every depth anyone chooses, and the
   * number exists to bound a reflective walk over a host's object graph rather than to be tuned.
   */
  static final int MAX_DEPTH = 3;

  private final AuditSink sink;

  private final AuditPolicy policy;

  private AuditLog(AuditSink sink, AuditPolicy policy) {
    this.sink = sink;
    this.policy = policy;
  }

  /**
   * An audit log writing to {@link LoggingAuditSink} under {@link AuditPolicy#defaults()}.
   *
   * <p>The logging sink is a <strong>fallback, not a compliance store</strong> — an application log
   * is short-retention and usually not exported for review. It exists so that a host wiring this up
   * sees its records immediately; a real audit store is an {@link AuditSink} the host supplies.
   *
   * @return an audit log over the platform logger; never {@code null}
   */
  public static AuditLog create() {
    return new AuditLog(LoggingAuditSink.create(), AuditPolicy.defaults());
  }

  /**
   * An audit log writing to {@code sink} under {@link AuditPolicy#defaults()}.
   *
   * @param sink where records go; must not be {@code null}
   * @return an audit log over {@code sink}; never {@code null}
   * @throws NullPointerException if {@code sink} is {@code null}
   */
  public static AuditLog using(AuditSink sink) {
    return using(sink, AuditPolicy.defaults());
  }

  /**
   * An audit log writing to {@code sink} under {@code policy}.
   *
   * @param sink where records go; must not be {@code null}
   * @param policy the never-capture list, typically {@link AuditPolicy#defaults()} plus a host's
   *     own entries; must not be {@code null}
   * @return an audit log over both; never {@code null}
   * @throws NullPointerException if either argument is {@code null}
   */
  public static AuditLog using(AuditSink sink, AuditPolicy policy) {
    return new AuditLog(
        Objects.requireNonNull(sink, "audit sink must not be null"),
        Objects.requireNonNull(policy, "audit policy must not be null"));
  }

  /**
   * Applies FR-16's four layers to a state change and returns an event holding no raw value.
   *
   * <p>Pass {@code null} for {@code before} to record a creation and for {@code after} to record a
   * deletion; the absent side's components render as absent. When both are present they must be
   * instances of the same class — a diff between two types is not a state change.
   *
   * @param actor who performed the change, from the host's own security context; must not be {@code
   *     null} or blank
   * @param action what was performed, such as {@code PASSWORD_ROTATED}; must not be {@code null} or
   *     blank
   * @param before the state before the change, or {@code null} for a creation
   * @param after the state after the change, or {@code null} for a deletion
   * @return the redacted event; never {@code null}, and never empty
   * @throws NullPointerException if {@code actor} or {@code action} is {@code null}
   * @throws IllegalArgumentException if {@code actor} or {@code action} is blank, or if both states
   *     are {@code null}
   * @throws AuditCaptureException if the object cannot be audited as declared — an un-audited
   *     composite, nesting past {@value #MAX_DEPTH} levels, a cycle, a non-public type, a marker on
   *     a field with no accessor, or a before/after pair of two different types
   */
  public AuditEvent capture(
      String actor, String action, @Nullable Object before, @Nullable Object after) {
    requireText(actor, "audit actor");
    requireText(action, "audit action");
    Class<?> type = classOf(after, before);
    if (type == null) {
      throw new IllegalArgumentException(
          "audit capture needs a before state, an after state or both; both were null");
    }
    requireOneType("", before, after);
    List<AuditEvent.Change> changes = new ArrayList<>();
    collect("", type, before, after, 1, Trail.rooted(before, after), changes);
    // Sorted so that two events over one type diff cleanly, the way Validator sorts its violations.
    changes.sort(Comparator.comparing(AuditEvent.Change::path));
    return new AuditEvent(actor, action, type.getName(), Instant.now(), changes);
  }

  /**
   * Writes {@code event} to the sink.
   *
   * <p>A sink that fails is <strong>not</strong> swallowed — see {@link AuditWriteException} for
   * why this is the opposite of the policy {@link ExecutionTimeMetricAspect} applies to a failing
   * metrics recorder. What is not caught is the rest of {@link Error}: an {@link OutOfMemoryError}
   * raised inside a sink says the VM is dying, and wrapping it as a write failure would send the
   * diagnosis to the wrong place.
   *
   * @param event an event from {@link #capture}; must not be {@code null}
   * @throws NullPointerException if {@code event} is {@code null}
   * @throws AuditWriteException if the sink threw; the event is carried on the exception
   */
  public void record(AuditEvent event) {
    Objects.requireNonNull(event, "audit event must not be null");
    try {
      sink.write(event);
    } catch (RuntimeException | LinkageError broken) {
      // LinkageError beside RuntimeException for the reason Validator.fromProvider catches the
      // pair:
      // a sink wired against a store whose classes are absent from the runtime image fails with
      // NoClassDefFoundError, and that is a write failure like any other rather than a VM failure.
      throw new AuditWriteException(event, broken);
    }
  }

  /**
   * Walks one level of components, applying the four layers to each.
   *
   * @param prefix the path of the value being walked, empty at the root
   * @param type the runtime class the layers are read from
   * @param before the state before the change, or {@code null}
   * @param after the state after the change, or {@code null}
   * @param depth 1 at the root, incremented per nested composite
   * @param trail the objects already on the path from the root, for cycle detection
   * @param out where captured components are appended
   */
  private void collect(
      String prefix,
      Class<?> type,
      @Nullable Object before,
      @Nullable Object after,
      int depth,
      Trail trail,
      List<AuditEvent.Change> out) {
    if (depth > MAX_DEPTH) {
      throw new AuditCaptureException(
          "audit capture stopped at ["
              + prefix
              + "]: nesting is deeper than the maximum of "
              + MAX_DEPTH
              + " levels, and a truncated record that looks complete is worse than a refused one");
    }
    List<AuditComponents.Component> components = AuditComponents.of(type);
    if (components.stream().noneMatch(AuditComponents.Component::audited)) {
      throw notAuditable(prefix, type);
    }
    for (AuditComponents.Component component : components) {
      if (!component.audited()) {
        continue; // layer 5 — omitted entirely, so the name never appears
      }
      String path = prefix.isEmpty() ? component.name() : prefix + '.' + component.name();
      Object rawBefore = before == null ? null : component.read(before);
      Object rawAfter = after == null ? null : component.read(after);
      boolean changed = !Objects.equals(rawBefore, rawAfter);
      if (component.sensitive() || policy.isNeverCaptured(component.name())) {
        // Layers 1 and 2. The raw values were read and are discarded here, unrendered: this is the
        // only place they are touched, and nothing downstream can be handed what it never got.
        out.add(AuditEvent.Change.redacted(path, changed));
        continue;
      }
      Class<?> nested = classOf(rawAfter, rawBefore);
      if (nested == null) {
        out.add(new AuditEvent.Change(path, null, null, false, false));
      } else if (simple(rawBefore) && simple(rawAfter)) {
        out.add(
            new AuditEvent.Change(path, rendered(rawBefore), rendered(rawAfter), false, changed));
      } else if (!AuditComponents.isVisible(nested)) {
        // A collection, an array or a package-private class. Answering "the type is not public"
        // here
        // would be true and unhelpful: what the caller needs to know is that this component's value
        // cannot be captured at all, which is what notAuditable says.
        throw notAuditable(path, nested);
      } else {
        requireOneType(path, rawBefore, rawAfter);
        trail.requireNoCycle(path, rawBefore, rawAfter);
        collect(
            path, nested, rawBefore, rawAfter, depth + 1, trail.descend(rawBefore, rawAfter), out);
      }
    }
  }

  /**
   * The runtime class the layers should be read from: the after state where there is one.
   *
   * <p>Preferring {@code after} matters for a deletion, where only {@code before} exists — and both
   * sides are required to be the same class anyway, so the preference only ever decides which of
   * two equal answers is used.
   */
  @Nullable
  private static Class<?> classOf(@Nullable Object after, @Nullable Object before) {
    if (after != null) {
      return after.getClass();
    }
    return before == null ? null : before.getClass();
  }

  private static boolean simple(@Nullable Object value) {
    return value == null || AuditComponents.isSimple(value);
  }

  @Nullable
  private static String rendered(@Nullable Object value) {
    return value == null ? null : AuditComponents.render(value);
  }

  /**
   * Refuses a before/after pair that is not two states of one object.
   *
   * <p>Accepting it would produce a record in which every component looks changed, which reads as a
   * catastrophic state change rather than as the programming error it is. A proxied entity — whose
   * runtime class is a generated subclass — is the case a host will hit here, and unwrapping it
   * before capture is the fix; a proxy would fail the {@link Audited} check first anyway, because a
   * type-level marker is not inherited.
   */
  private static void requireOneType(String path, @Nullable Object before, @Nullable Object after) {
    if (before == null || after == null || before.getClass() == after.getClass()) {
      return;
    }
    throw new AuditCaptureException(
        "audit capture needs two states of one type"
            + (path.isEmpty() ? "" : " at [" + path + "]")
            + ": before is "
            + before.getClass().getName()
            + " and after is "
            + after.getClass().getName());
  }

  private static AuditCaptureException notAuditable(String prefix, Class<?> type) {
    if (prefix.isEmpty()) {
      return new AuditCaptureException(
          "cannot audit "
              + type.getName()
              + ": nothing requests capture — annotate the type with @Audited, or its components"
              + " individually. @Sensitive alone is an opt-out and does not request capture");
    }
    return new AuditCaptureException(
        "audit capture cannot read ["
            + prefix
            + "] of type "
            + type.getName()
            + ": only simple values (text, numbers, booleans, characters, enums, java.time types,"
            + " UUID) are captured directly, and a composite must carry @Audited on its own type —"
            + " rendering it as text would publish every component it has, @Sensitive ones included");
  }

  /**
   * Rejects a blank attribution, which is the one kind of record an audit store must not accept.
   *
   * @param value the caller's actor or action
   * @param what which of the two, for the message
   */
  private static void requireText(String value, String what) {
    Objects.requireNonNull(value, what + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
  }

  /**
   * The objects on the path from the root, one set per side, compared by identity.
   *
   * <p>Two sets rather than one: a cycle in the before-graph and a cycle in the after-graph are
   * independent, and merging them would refuse the legal case where one side's nested value happens
   * to be the other side's root.
   *
   * <p>The ancestors of the current path, not everything visited — a diamond, where one object is
   * reachable twice as a sibling, is not a cycle and must not be refused as one.
   *
   * @param before the before-side ancestors
   * @param after the after-side ancestors
   */
  private record Trail(Set<Object> before, Set<Object> after) {

    /**
     * A trail holding just the root pair.
     *
     * @param before the before state, or {@code null}
     * @param after the after state, or {@code null}
     * @return the initial trail
     */
    static Trail rooted(@Nullable Object before, @Nullable Object after) {
      return new Trail(identitySet(), identitySet()).descend(before, after);
    }

    /**
     * Refuses a value that is already on the path from the root.
     *
     * @param path where the cycle was found
     * @param nextBefore the before-side value about to be walked into
     * @param nextAfter the after-side value about to be walked into
     */
    void requireNoCycle(String path, @Nullable Object nextBefore, @Nullable Object nextAfter) {
      if (holds(before, nextBefore) || holds(after, nextAfter)) {
        throw new AuditCaptureException(
            "audit capture found a cycle at ["
                + path
                + "]: the value is already on the path from the root, so capture would not"
                + " terminate");
      }
    }

    /**
     * This trail plus one level.
     *
     * @param nextBefore the before-side value being walked into, or {@code null}
     * @param nextAfter the after-side value being walked into, or {@code null}
     * @return a trail one level deeper
     */
    Trail descend(@Nullable Object nextBefore, @Nullable Object nextAfter) {
      return new Trail(plus(before, nextBefore), plus(after, nextAfter));
    }

    private static boolean holds(Set<Object> seen, @Nullable Object candidate) {
      return candidate != null && seen.contains(candidate);
    }

    private static Set<Object> plus(Set<Object> seen, @Nullable Object next) {
      Set<Object> grown = identitySet();
      grown.addAll(seen);
      if (next != null) {
        grown.add(next);
      }
      return grown;
    }

    /**
     * Identity, not equality: two equal-but-distinct nodes are not a cycle, and a host's {@code
     * equals} is not something a termination guarantee should depend on.
     */
    private static Set<Object> identitySet() {
      return Collections.newSetFromMap(new IdentityHashMap<>());
    }
  }
}
