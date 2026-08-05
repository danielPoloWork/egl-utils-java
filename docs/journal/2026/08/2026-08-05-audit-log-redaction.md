# 2026-08-05 — `AuditLog`, and a trap reproduced rather than described (ROADMAP item 3.3)

**Milestone 3, item 3.3 — the last item of the milestone, and the one RFC-0002 was written for.** The
RFC spends most of its length on FR-16, so unusually little was left to decide about *policy*; what was
left was **shape**, and five of those decisions turned out to hold the guarantee up.

## What changed

`AuditLog`, `AuditEvent` (with nested `Change`), `AuditPolicy`, `AuditSink`, `LoggingAuditSink`,
`@Audited`, `@Sensitive`, `AuditWriteException`, `AuditCaptureException` and the package-private
`AuditComponents` land in `d4np-core` (239 → **333** tests), plus one jcstress harness. No new
dependency and no new `requires` edge: reflection and `System.Logger` are both `java.base`.
[ADR-0022](../../../adr/0022-redact-at-capture-behind-a-typed-event.md) records the decisions;
compliance gains **C-05** and upgrades **C-03**; the patterns catalogue gains row 8 and its **first
rejection**.

## The decision that holds the guarantee up

**`AuditEvent` is a final class, not a record.** A public record's canonical constructor is public, so
as a record it would be a documented way to hand `AuditLog.record` an "audit event" full of plaintext —
and the redaction promise would then be about *our capture path* rather than about *the type*. The
constructor is package-private; `capture` is the only way to obtain one.

The nested `Change` **is** a record, and keeping both shapes in one file taught something worth
carrying: **a record's deserialisation runs its canonical constructor and a class's does not.** `Change`
validates for free; `AuditEvent` needs a `readResolve` to re-run the constructor, without which a
crafted stream decides whether `changes` is modifiable — an immutability promise broken by a code path
the author never wrote.

## The trap, reproduced

RFC-0002 predicts it in prose: render a composite with `String.valueOf` and a record's generated
`toString()` publishes every component, `@Sensitive` ones included. Deleting the composite guard and
re-running the suite prints it:

```
Change[path=principal, before=Credentials[user=alice, password=hunter2], ...]
```

The marker was present, correct, and completely bypassed by one method call. Removing layer 1 instead
turns four tests red, including the API-key one. **Neither guard is held by review any more.**

## RFC-0002 contradicts its own illustration, and the list wins

The RFC shows the recursion rule with `@Audited Credentials credentials`. `credentials` is an entry on
the RFC's own never-capture list, and layer 1 overrides everything — so that exact field is **one
`[REDACTED]` row**, never walked into, and the non-sensitive `user` inside it disappears too.

Found by writing a test from the RFC's example and watching it fail. It is the *correct* behaviour:
blocking the subtree beats publishing the leaves of a component the list already named untouchable.
Pinned by `layerOneBlocksAWholeCompositeRatherThanWalkingIntoIt` so the next reader meets it as a
contract rather than as a bug report.

## A control the register called unenforceable was already enforced

C-03's row said: *"no gate forbids the default-locale overload, so a future call site could reintroduce
it; an ErrorProne pattern would be the way to close that."* Reintroducing `name.toLowerCase()` in
`AuditPolicy` produces **`StringCaseLocaleUsage`**, and `failOnWarning` turns it into a build failure.
The gate has been there the whole time and nobody had checked.

C-03 is now ✅ with its scope stated — that overload pair, on the JDK 21+ cells where ErrorProne binds —
because the control is wider than the gate: `MessageFormat` is still held by the pre-rendering
discipline item 3.2 established, not by a check.

**The general lesson is the one this project keeps relearning: a register entry that says "not
enforced" is a claim, and claims get verified by running something.**

## Two smaller things worth carrying forward

- **`@Audited` is deliberately not `@Inherited`.** An inherited permit-everything marker means a
  subclass that adds a component leaks it by default — the failure mode the RFC rejected the denylist
  for, one class boundary further from the reader. The consequence is that **a proxied entity is not
  auditable**: a CGLIB or Hibernate proxy is a generated subclass, so capture refuses it loudly rather
  than producing an empty record. Unwrap before capture; the Javadoc says so, because it is the first
  thing a Spring host will hit.
- **The sink is item 3.2's SPI with the opposite failure policy.** A failing metrics recorder is
  swallowed; a failing audit sink throws. A missing timing is a lost number, a missing audit record is a
  compliance hole found at the next review — and the catch is the same narrow
  `RuntimeException | LinkageError`, so a dying VM still says so.

## The gate `clean verify` does not run — again

Item 3.2 found `checkstyle:check`; this item found **`javadoc -Xdoclint:all -Xwerror`**, which no build
phase runs yet (item 8.4 owns it). Two results:

- `StrategyNotFoundException` has carried a dangling `{@value #MAX_KEYS_IN_MESSAGE}` since **item 2.4**
  moved that constant into `KeyDiagnostics`. Fixed here in one line — leaving a knowingly broken doc
  reference for 8.4 to trip over costs more than a two-line diff.
- **Item 2.1's JDK-17 doclint finding recurs on a second type.** Javadoc is clean on 21 and reports 8
  "no comment" warnings on 17, every one of them a component of a `Serializable` **record**
  (`AuditEvent.Change`, and the pre-existing `ErrorDetail`). The plain-class half of the same warning is
  fixable and was fixed — `AuditEvent`'s five serialisable fields now carry `@serial` — which is exactly
  what isolates the residue to the record-component case. **Item 8.4 should build the Javadoc JAR on
  21**, and this is now the second independent measurement saying so.

## Where the project stands

**Milestone 3 is complete** — 3.0 through 3.3 — and the README milestone table says so. M4 (`json` and
`jdbc`), M5 (`concurrent`) and M6 (`security`) are mutually independent and each needs its RFC first
(items 4.0, 5.0, 6.0); the order among them is the owner's priority call, not a technical constraint.

## What the next session needs to know

- **`Unit` still has no call site**, exactly as RFC-0002 predicted and ADR-0019 accepted in advance:
  `record` throws and `capture` returns an event. Three items have now passed without one.
- **Collections are refused, and it is a contract gap rather than a bug.** FR-16's layers are defined
  over components and have no vocabulary for element-level policy, while `String.valueOf` on a
  `List<Credentials>` is the composite trap in bulk. The workaround is a rendered `String` component;
  the way in is an RFC-0002 amendment, not an implementation change.
- **Item 8.6 still owns the missing trust boundary.** "Library → host-supplied audit store" is now a
  real outbound flow with real code behind it, and §1 of the threat model has no row for it. The two
  `AuditLog` rows this item did update say so explicitly, so the ✅ is not read as covering the flow.
- **`AuditComponents` is the reflection seam.** Anything later that needs to walk a host's type —
  FR-20's JSON shape rules, say — should extend that class rather than start a second reflective walk
  with its own idea of what a component is.
- **The two unreproducible CI commands are unchanged** from item 3.1: `japicmp:cmp` resolves no plugin
  (item 8.1) and `-Pcoverage` matches no profile (item 8.2), so a green local `-Pcoverage` is still a
  vacuous green.
