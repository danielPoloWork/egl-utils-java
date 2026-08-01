# ADR-0017: Give `FluentBuilder` a second accumulator, and keep the defensive-copy rule a documented obligation

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.4; [RFC-0001](../rfc/0001-core-contracts.md) §FR-02 (the four members
  it sketches) and §Alternatives (compile-time enforcement, rejected);
  [ADR-0016](0016-generic-factory-atomic-duplicate-rejection.md) (the sibling type in this item);
  [ADR-0009](0009-errorprone-nullaway-on-jdk-21-cells.md) (why the null check still earns its place);
  FR-02, FR-19

## Context

RFC-0001 pins FR-02 as a template method with four members:

```
final T build()                     // validate(), then construct(); NOT overridable
protected abstract T construct()
protected abstract void validate()
protected final void require(Object value, String field)   // accumulates a missing field
```

plus three rules: `build()` **collects every violation** and throws one `BuilderValidationException`
listing all of them — never fail-on-first; `build()` is **repeatable and returns a distinct instance
per call**, with the builder neither reset nor frozen; and `construct()` must **defensively copy**
every collection or array it takes from the builder.

Writing it surfaced a gap between the first rule and the member list. **`require` can only record a
missing field**, because all it does is a null check. Every other invariant a real builder has — *"end
date must be after start date"*, *"at least one line item"*, *"discount cannot exceed total"* — has no
way to reach the accumulator. A subclass wanting to enforce one could only throw from `validate()`,
which drops the caller straight back to one-violation-per-round-trip, for exactly the invariants that
are hardest to get right.

So the RFC's headline promise — collect **every** violation — holds only for null checks, unless
something is added.

## Decision

**Add `protected final void reject(String problem)`, the general accumulator**, alongside `require`.
`validate()` records with either; `build()` collects both into one `BuilderValidationException`. This
is an **addition to the four members RFC-0001 sketched**, made because the RFC's own stated goal
requires it, and it is recorded here rather than slipped in.

Three supporting decisions:

- **`build()` clears the accumulator at its start, not its end**, so a `validate()` that throws cannot
  leave stale findings to be reported against the next call — which matters precisely because `build()`
  is repeatable.
- **`build()` rejects a `null` from `construct()`** with `IllegalStateException`, matching what `Lazy`
  and `GenericFactory` do to caller-supplied code.
- **`BuilderValidationException` stays outside the `BusinessException` hierarchy.**

## Alternatives Considered

- **Ship only the four members, and let cross-field rules throw.** The literal reading of the RFC.
  Rejected because it makes the accumulate-everything contract true only of the easy half of
  validation: a builder with two missing fields *and* a bad date range would report the two fields,
  then the date range on the next attempt. That is the behaviour the contract exists to prevent.
- **Ship only the four members, and let subclasses abuse `require(null, "...")`** to record an
  arbitrary violation. It works — passing `null` as the value forces a record with any message.
  Rejected as a documented hack: it reads as a null check that is not one, and the message would have
  to be phrased as a field name to make sense of the call.
- **Give `require` an overload taking a boolean condition.** `require(endDate.isAfter(startDate),
  "end date must follow start date")` reads well. Rejected on the argument order trap it creates: with
  both `require(Object, String)` and `require(boolean, String)` present, a caller passing a `Boolean`
  field to check for nullness silently gets the condition overload, and a builder validating a
  `Boolean` field is not exotic. A differently-named method cannot be confused this way.
- **Compile-time required-field enforcement** (staged/step builder, or a generated one). Not reopened —
  RFC-0001 §Alternatives already rejected it on a hard constraint: a generic base class cannot express
  "field X must be set" in the type system, and generating stages needs an annotation processor, which
  ADR-001 forbids `d4np-core` from carrying.
- **Make `build()` reset the builder**, which would remove the defensive-copy hazard entirely by
  ensuring the builder's collections are never reachable after a build. Rejected: it contradicts
  RFC-0001's explicit "not reset, stays mutable, a partly-configured builder is a legitimate
  prototype", which is a genuinely useful property. The cost of keeping it is the copy obligation
  below.
- **Enforce the defensive copy in `build()`** — for example by deep-copying the constructed object.
  Rejected as impossible in general: `build()` receives a finished `T` and has no idea which of its
  fields came from the builder, nor any way to copy an arbitrary consumer type.
- **Let `BuilderValidationException` extend `BusinessException`.** Superficially right — FR-19 maps
  "validation" to **400**. Rejected because that mapping is FR-14's `Validator`, which checks data
  that arrived *from a client*. A builder violation means this application's own code forgot to set a
  field before calling `build()`; reporting it as a bad request would misattribute the fault to the
  user. It stays a plain `RuntimeException` and falls to FR-19's 500.

## Consequences

- **The accumulate-everything promise now holds for real invariants**, not just null checks. A test
  asserts a cross-field rule reaching the accumulator, precisely because that is the case the RFC's
  member list could not express.
- **The public surface is one protected method wider than the RFC sketched.** Stated plainly so the
  maintainer can veto it cheaply: removing a `protected` method later is a MAJOR change under
  RFC-0001 §Versioning, so this is the decision worth objecting to now if it is going to be objected
  to at all. Nothing else in the item depends on it.
- **The defensive-copy rule remains an obligation this class cannot enforce**, and is therefore
  documented three times — class Javadoc, `construct()`'s Javadoc, and here. More usefully, it is
  **demonstrated by a test**: `FluentBuilderTest` builds a deliberately leaky subclass, mutates the
  builder afterwards, and asserts the already-"built" object changed. The rule is a shown consequence
  rather than a style note, and if `build()` ever started resetting the builder that test would fail
  and point here.
- **`build()`'s null check is unreachable from an annotated subclass, and that is worth knowing.**
  NullAway rejects a `construct()` that returns `null` at compile time — the test asserting the runtime
  behaviour needs `@SuppressWarnings("NullAway")` to exist at all. The check still earns its place,
  because ADR-0009 runs NullAway only on the JDK 21+ cells of *this* build, and no consumer's build
  runs it by default.
- **The builder is not thread-safe and never will be.** Documented, not synchronised, per RFC-0001.
  Two threads configuring one builder cannot agree on what they are building, so a lock would buy
  safety for a scenario that is a design error either way. No jcstress harness accompanies this type,
  which is correct rather than an omission: spec §6 requires a harness for a thread-safety *claim*, and
  this type makes the opposite one.

## References

- FR-02 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) — a `[GAP]` line resolved by
  RFC-0001.
- [RFC-0001](../rfc/0001-core-contracts.md) §FR-02, §Alternatives (compile-time enforcement),
  §Versioning.
- `d4np-core/src/main/java/it/d4np/utils/{FluentBuilder,BuilderValidationException}.java`;
  `.../src/test/java/it/d4np/utils/{FluentBuilderTest,BuilderValidationExceptionTest}.java`.
