# ADR-0022: Redact at capture, behind an event no caller can mint

- **Status:** Accepted
- **Date:** 2026-08-05
- **Deciders:** tech-lead (implementation of ROADMAP item 3.3), owner
- **Related:** [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §FR-16 (the redaction policy this
  implements); spec [§2 FR-16](../specs/01_spec_utils.md), §3 (module graph);
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (zero-dependency core);
  [ADR-0018](0018-tokenizer-word-threshold-and-utf8-default.md) (the tokenizer layer 1 matches with);
  [ADR-0014](0014-log-through-the-jdk-system-logger.md) (the fallback sink and its injection seam);
  [ADR-0021](0021-time-through-an-advice-body-core-can-own.md) (the sibling SPI, and the opposite
  failure policy); [ADR-0015](0015-strategy-registry-last-write-wins.md) (why an exception payload is
  rendered to text); compliance controls **C-03** and **C-05**

## Context

RFC-0002 spends most of its length on FR-16 and pins the policy: redaction happens **at capture**, four
layers with a fixed precedence, whole-token matching for the never-capture list, only simple values
captured directly, recursion bounded at depth 3, `record` throws, public accessors only, no content
inspection. That is more contract than any other item in this project has arrived with.

What it does not decide is the **shape** — and five of those decisions turn out to be load-bearing,
each with an answer that looks perfectly reasonable in review:

1. **What stops a caller from minting an event by hand.** The rule "no API returns a raw value" is a
   statement about the type, and a public constructor is an API.
2. **Where the four layers are read from,** when a record component, its backing field and its accessor
   are three different declaration sites for one component.
3. **What "captured" means for the value's type,** when the declared type is `Object` and the runtime
   value is a record.
4. **What happens to a component the never-capture list blocks that is itself a composite** — recurse
   and redact the leaves, or block the subtree.
5. **What a marker attached to nothing should do,** which is the failure a declaration-driven design
   creates and a scanning one does not.

## Decision

**1. `AuditEvent` is a final class, not a record, because a public record cannot have a non-public
canonical constructor.** The constructor is package-private, so `AuditLog.capture` is the only way to
obtain one. It carries `actor`, `action`, `subjectType`, `occurredAt` and a list of nested
`AuditEvent.Change` records — `path`, `before`, `after`, `redacted`, `changed`.

**2. `changed` is computed at capture on the raw values and is the only thing a blocked component
keeps.** Both of its sides read `[REDACTED]`, so no consumer could derive it; *"the password was
changed at 14:02 by alice"* is the sentence FR-16 exists to make writable, and it carries no plaintext.
A blocked component is `[REDACTED]` on both sides **even when the raw value was absent**, because
whether a secret is currently set is itself a fact worth withholding.

**3. Markers are read from every declaration site; the type is read from the value.** `@Audited` and
`@Sensitive` are honoured on the record component, the field *and* the accessor, and `@Audited`
additionally on the type. The runtime class of the value decides what the value *is*. Deep reflection
is never used — `setAccessible` is not called, so no consuming module needs an `opens` clause.

**4. Layer 1 blocks a whole subtree rather than walking into it.** A component the never-capture list
matches is redacted as one row, whatever it holds. This falls out of reading the precedence table
literally ("value replaced with `[REDACTED]`") and it is the safer reading, because the alternative
publishes the non-sensitive leaves of a component the list has already declared untouchable.

**5. Five conditions fail loudly with `AuditCaptureException`**, all of them developer errors surfaced
at first capture: a composite that is not `@Audited`, nesting past depth 3, a cycle, a type or accessor
that cannot be read without deep reflection, and a before/after pair that is not two states of one
type. Two more are additions this implementation makes rather than the RFC:

- **a marker on a field no accessor exposes** — `@Audited private String userName` beside a
  `getUser()` is present, correct-looking and attached to nothing, so the component would be missing
  from every record and the omission would surface during an audit;
- **two accessors mapping to one component** (`getActive()` beside `isActive()`), where picking one
  silently decides whether a value is captured.

**6. The sink is an SPI with one dependency-free implementation,** following ADR-0021's precedent:
`AuditSink` plus `LoggingAuditSink` over `System.Logger` (ADR-0014). **The failure policy is the exact
opposite of FR-15's:** a failing metrics recorder is swallowed, a failing audit sink is wrapped in
`AuditWriteException` and thrown. The catch is the same narrow `RuntimeException | LinkageError` pair
for the same reason, so an `OutOfMemoryError` still propagates.

**7. `LoggingAuditSink` logs at `INFO`,** where `LoggingExecutionTimeRecorder` logs at `DEBUG`.
Instrumentation a host never asked for should be invisible by default; an audit record should not be.
Its Javadoc states plainly that it is a fallback and not a compliance store.

**8. No plan cache.** Every capture re-reads the type reflectively. No NFR states a budget for FR-16,
and a performance claim in this project needs a benchmark behind it.

## Alternatives Considered

- **`AuditEvent` as a record.** Shorter, generated `equals`/`toString`, and rejected because the
  canonical constructor of a public record is public: it would be a documented way to hand
  `AuditLog.record` an "audit event" full of plaintext. The nested `Change` **is** a record, because it
  has nowhere to go without an event — and that split produced a useful contrast: a record's
  deserialisation runs its canonical constructor, so `Change` validates for free, while the class needs
  a `readResolve` to re-run the constructor a stream would otherwise skip.
- **Redact in the sink.** The design RFC-0002 rejected; not reopened. The event passes through
  interceptors, queues, heap dumps and `toString()` calls on its way, and every sink author would have
  to get redaction right independently.
- **Read markers from one declaration site only.** Simpler, and unsafe in the one direction that
  matters: a `@Sensitive` written on the field of a JavaBean while the engine looked only at the getter
  would be present, correct, and completely bypassed — indistinguishable from working, in review.
- **Trust the declared type instead of the runtime class.** Rejected: an `Object`-declared component
  would then be rendered with `String.valueOf`, which is the record-`toString()` trap arriving through
  the back door. Reading the runtime class costs one call and closes it.
- **`@Audited` as `@Inherited`.** Convenient for a domain hierarchy, and rejected on the failure mode:
  an inherited permit-everything marker means a subclass that adds a component leaks it **by default** —
  the same reason the RFC rejected a denylist, one class boundary further away from the reader.
  Member-level markers still travel with the member, so an inherited accessor keeps its own marker.
- **Recurse into a never-captured composite and redact its leaves.** Produces a more detailed record and
  publishes the shape and the non-sensitive parts of something the list already named. Blocking the
  subtree is the safe reading. The cost is real and is pinned by a test: a component literally named
  `credentials` is one `[REDACTED]` row, and the `user` inside it disappears too.
- **Ignore a marker that is attached to nothing.** The permissive option, and it fails silently in the
  direction that matters least until an audit: no record, no error, no clue.
- **Pick one accessor when two map to a component** (JavaBeans prefers `isX` for booleans). Rejected:
  the two may carry different markers, so the preference silently decides whether a value is published.
- **`AuditLog.record` returning `Result<Unit>`.** Consistent with the error model, rejected by the RFC,
  and not reopened: an ignored return value is silent, and silence is the failure an audit control
  cannot afford. FR-16 remains the one place in this library where loudness beats composability.
- **A convenience `record(actor, action, before, after)` that captures and writes in one call.** The
  ergonomic addition, and rejected because `log.record(log.capture(..))` is already one line: a wrapper
  that saves a nested call is public surface this library then owns forever.
- **A `ClassValue` plan cache.** Roughly fifteen lines and probably a real speed-up, and rejected for
  the reason item 2.4 declined a benchmark: no NFR names FR-16, so the choice is between an unbacked
  performance claim and an invented budget. The cache is purely additive if a host ever measures a need.
- **Supporting collections by capturing element-wise.** The most likely feature request, and it is a
  contract change rather than an implementation one: FR-16's four layers are defined over *components*
  and have no vocabulary for element-level policy, while `String.valueOf` on a `List<Credentials>` is
  the composite trap in bulk. Refused for now, with the remedy documented — expose a rendered `String`
  component — and an RFC-0002 amendment as the way in.

## Consequences

- **Core gains nine public types and no dependency edge.** `AuditLog`, `AuditEvent` (+ nested
  `Change`), `AuditPolicy`, `AuditSink`, `LoggingAuditSink`, `Audited`, `Sensitive`,
  `AuditWriteException`, `AuditCaptureException`, plus the package-private `AuditComponents`. The module
  descriptor is unchanged: reflection and `System.Logger` are both `java.base`.
- **Compliance control C-05 is created and enforced by test** — an audit record carries no secret. The
  argument is not "we redact"; it is that the type has no API returning a raw value, proven by breaking
  it: with the composite guard removed, `AuditLogTest.neverRendersACompositeAsText` reports
  `Credentials[user=alice, password=hunter2]` inside the event, exactly as RFC-0002 predicted.
- **C-03 moves from partial to enforced, and the gate turned out to already exist.** Its row said no
  gate forbids the default-locale `toLowerCase`, and reintroducing one proves otherwise: ErrorProne's
  `StringCaseLocaleUsage` fires and `failOnWarning` fails the build. The scope is exactly that overload
  pair, on the JDK 21+ cells where ErrorProne binds (ADR-0009) — not every locale hazard, and
  `MessageFormat` in particular still needs the pre-rendering discipline item 3.2 established.
- **RFC-0002's own illustration collides with RFC-0002's own list.** The recursion rule is shown with
  `@Audited Credentials credentials`, and `credentials` is a base-list entry, so that exact field is
  redacted whole and never walked into. The list wins, because layer 1 overrides everything by
  definition. Recorded here and pinned by
  `AuditLogTest.layerOneBlocksAWholeCompositeRatherThanWalkingIntoIt` rather than left for a reader to
  discover as a bug report.
- **A proxied entity is not auditable, and fails loudly rather than silently.** A CGLIB or Hibernate
  proxy is a generated subclass, and `@Audited` is not inherited, so capture refuses it with a message
  instead of producing an empty record. Unwrapping before capture is the host's fix; this is stated in
  the Javadoc because it is the first thing a Spring host will hit.
- **`changed` on a composite is the host's `equals`.** For a redacted composite that is the only
  information published, and a type without value semantics will report a change whenever the instance
  differs. Not fixable from here, so it is documented rather than smoothed over.
- **Item 8.6 still owns the threat model's missing boundary.** RFC-0002 filed "library → host-supplied
  audit store" against the next STRIDE pass because the security-auditor owns that document; this item
  updates the status of the two existing `AuditLog` rows it now satisfies and adds no boundary of its
  own.
- **`Unit` still has no call site,** as RFC-0002 predicted and ADR-0019 accepted in advance: `record`
  throws and `capture` returns an event.
- **The Javadoc's type-level examples read `public @Audited record Account(...)`,** which is valid Java
  and unusual to look at. Checkstyle's `JavadocType` reads a line-leading `@Audited` inside a
  `{@code}` block as an unknown Javadoc tag, and this is the one form that keeps the sample real
  without a suppression.

## References

- FR-16 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md);
  [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §FR-16 for the contract this implements.
- `d4np-core/src/main/java/it/d4np/utils/AuditLog.java`, `AuditEvent.java`, `AuditPolicy.java`,
  `AuditComponents.java`, `AuditSink.java`, `LoggingAuditSink.java`, `Audited.java`, `Sensitive.java`,
  `AuditWriteException.java`, `AuditCaptureException.java`.
- `d4np-core/src/test/java/it/d4np/utils/AuditLogTest.java`, `AuditPolicyTest.java`,
  `AuditEventTest.java`, `LoggingAuditSinkTest.java`, `AuditFixtures.java`;
  `d4np-core/src/jcstress/java/it/d4np/utils/AuditCaptureIsolationStress.java` for capture isolation
  under a race.
- Compliance controls **C-03** and **C-05** in [`docs/compliance/README.md`](../compliance/README.md).
