# ADR-0019: Mint `Unit` so a `Result` can succeed without a payload

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 3.0; [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md)
  (which costed the options and deferred the choice to this moment);
  [RFC-0002](../rfc/0002-cross-cutting-contracts.md) (which makes it);
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) (the error model);
  [RFC-0001](../rfc/0001-core-contracts.md) §Versioning; FR-17

## Context

ADR-0012 recorded, while item 2.1 was implementing it, that one sentence of the specification cannot
be honoured in both halves:

> `Ok(null)` is forbidden — use `Result<Void>`.

`java.lang.Void` is uninhabited — its only value *is* `null` — and a record's canonical constructor
cannot be bypassed, so if `new Ok<>(null)` throws then **no** code, not even a factory inside this
library, can build the void success. ADR-0012 chose the reversible direction: enforce the rejection,
ship no `Unit`, and route the decision to "the first RFC with real call sites", naming item 3.0.

That is this item. Four milestones of implementation have happened since, so the question can be asked
with evidence instead of speculation.

**The honest evidence is that the call sites did not decide it.** RFC-0002's two candidates both fail
to force the issue: `Validator.validate` returns `Result<T>` carrying the validated object, and
`AuditLog.record` throws rather than returning a `Result` — deliberately, because an ignored return
value is silent and an audit trail that silently stops writing is a compliance hole. So the choice
falls back on the error model itself.

## Decision

**Core mints `Unit`, a single-valued type, and `Result` gains a no-argument `ok()` factory returning
`Result<Unit>`.** FR-17's guidance is amended from `Result<Void>` to `Result<Unit>` in the manifest and
the published spec is re-rendered (ADR-0010).

`Unit` is an **enum with one constant**:

```java
public enum Unit { INSTANCE }
```

The enum is not stylistic. A `final class` with a `private` constructor and a `public static final`
instance needs `readResolve` to stay a singleton across deserialisation, and `Result`'s arms are
carried inside `BusinessException`, which is `Serializable` by inheritance from `Throwable`. An enum
gets singleton-across-serialisation from the language, which is the JDK's own idiom for exactly this.

## Alternatives Considered

- **Leave the gap: no void success, effectful operations throw or invent a payload.** The status quo,
  and the cheapest option in lines of code. Rejected because it makes the error model **asymmetric**:
  an expected failure can be a value only when the operation also has something to return, so every
  no-payload operation is pushed into the exception channel — the outcome ADR-002 adopted `Result` to
  avoid. "Delete this record; it may not exist" is an expected outcome with no payload, and it is not
  an exotic shape.
- **Invent a payload instead.** `AuditLog.record` could return the written record's id; a delete could
  return the number of rows. Rejected as the anti-pattern the gap creates rather than a solution to it:
  the payload exists to satisfy the type system, so it will be ignored at every call site, and one of
  them will eventually be wrong about what it means.
- **Permit `null` in `Ok` and mark the payload `@Nullable`.** Not reopened. ADR-0012 rejected it on
  where the cost lands — `Ok.value()` becomes conditionally-null **public API**, so every consumer of
  every `Result` inherits a null check on the happy path for a case that cannot apply to them — and
  nothing since has changed that.
- **Name it `Nothing`, `Void`, or `Empty`.** `Void` collides with `java.lang.Void`, which is the
  confusion this whole record exists to remove. `Nothing` reads as the bottom type it is not — a
  `Result<Nothing>` should be uninhabited, not universally successful. `Unit` is the term ADR-0012
  already used and the one Kotlin, Rust (`()`), Scala and Vavr agree on.
- **A `final class` with a public constant.** Rejected for the serialisation reason above; the enum
  removes a `readResolve` nobody would remember to test.
- **Wait for RFC-0003 and a `JdbcTxRunner` call site.** Rejected as the third deferral of the same
  question. ADR-0012 deferred it once with a named owner, that owner is this item, and deferring again
  would mean the amended FR-17 keeps pointing at a construction that does not exist.

## Consequences

- **`Result.ok()` exists and returns `Result<Unit>`**, so an operation with an expected failure and no
  payload can finally be expressed in the value channel. The `Result<Void>` sentence in the spec stops
  being a promise the code cannot keep.
- **The decision was made on the model, not on a call site, and that is stated rather than dressed
  up.** The roadmap asked this RFC to choose *with* real call sites; neither of the two it named forces
  the type. Recording that keeps the reasoning auditable, and it is why the "wait again" alternative
  was weighed seriously.
- **One new permanent public type.** Under RFC-0001 §Versioning adding a type is **MINOR**, and core
  stays `0.x` until M8 with no `japicmp` baseline in force yet, so the cost of being wrong is a
  deletion rather than a major bump. That asymmetry is what makes now a cheap moment to be wrong and
  M8 an expensive one.
- **`Ok`'s null rejection is untouched.** `Unit` adds a way to say "nothing"; it does not make `null`
  sayable. `Result.ok()` passes `Unit.INSTANCE` through the same canonical constructor as every other
  payload, so there is no back door and no second code path.
- **`Result.ok()` and `Result.ok(T)` are overloads, and the no-argument form is unambiguous** — a call
  with zero arguments cannot bind to the one-argument method. A reader who sees `Result.ok()` and
  expects `Result<T>` gets a compile error, not a surprise.
- **ADR-0012 is not superseded; it is completed.** Its analysis stands unchanged and its deferral had
  a named owner, which is the mechanism working as intended. It stays `Accepted`, and this record is
  the answer to the question it left open.
- **Item 3.0's scope grew by roughly forty lines of production code**, which is a deliberate,
  stated widening: amending the specification to recommend `Result<Unit>` while `Unit` did not exist
  would recreate precisely the spec-outruns-code gap ADR-0012 was written to record. The RFC and the
  type land together or neither does.

## References

- FR-17 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md), and its source line in
  `orchestrator/project.yaml` — amended in the same change (ADR-0010).
- [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) — the proof that `Result<Void>`
  is unconstructible, and the three costed options.
- [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §The error model.
- `d4np-core/src/main/java/it/d4np/utils/{Unit,Result}.java` and `ResultTest`.
- Effective Java, Item 3 — the single-element enum as the serialisation-safe singleton.
