# ADR-0012: Enforce `Ok(null)` mechanically, and leave a successful `Result<Void>` unconstructible

- **Status:** Accepted
- **Date:** 2026-07-30
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP items 2.1 and 3.0 (which inherits the open question);
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) and
  [RFC-0001](../rfc/0001-core-contracts.md) (which state the rule);
  [ADR-0010](0010-single-specification-authority.md) (the precedence ladder);
  [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) (the sibling null decision);
  FR-17, FR-18, NFR-09

## Context

Three documents state the same rule in the same words, and it is one sentence with two halves:

> `Ok(null)` is forbidden — use `Result<Void>`.
> — spec §2 FR-17, spec §5, ADR-002, restated by RFC-0001

**The two halves cannot both be honoured in Java, and this was found while implementing them.** The
proof is short:

1. `java.lang.Void` is **uninhabited**. It cannot be instantiated, so the only value of type `Void` is
   `null`. An `Ok<Void>` with a non-null payload therefore cannot exist — not as an implementation
   difficulty, as a type-system fact.
2. `Ok` is a record, and **a record's canonical constructor cannot be bypassed**: every other
   constructor must delegate to it. So if `new Ok<>(null)` throws, then *no* code can build the void
   success — including a factory inside this library. There is no private back door to give
   `Result.ok()` and withhold from callers.

So the design space has exactly three shapes, and the decision is a choice among them rather than an
implementation detail:

| Shape | What it costs |
|---|---|
| **(a)** Enforce the rejection; no void success exists | a use case that succeeds with no payload is not expressible |
| **(b)** Permit `null` in `Ok`, mark the payload `@Nullable` | `Ok.value()` becomes conditionally null **public API**, so every consumer of every `Result` inherits a null check, and their own NullAway reports it |
| **(c)** Mint a `Unit` type and use `Result<Unit>` | a new permanent public type, and a documented deviation from FR-17's stated `Result<Void>` guidance, decided with zero call sites in existence |

There is a fourth thing that looks like an option and is not: an unchecked cast that smuggles a
non-null sentinel into an `Ok<Void>` through erasure. It "works" until someone reads `value()`, whose
implicit cast then throws `ClassCastException` — deliberate heap pollution in the most-used type in the
library.

## Decision

**Take (a): `Ok`'s canonical constructor rejects `null` unconditionally, and core ships no
void-success factory and no `Unit` type.** The consequence is documented on `Result` itself, and the
choice between (b) and (c) is routed to the first RFC that has a real consumer to decide it —
**RFC-0002 (ROADMAP item 3.0)**, whose scope is extended in this PR to say so.

Two supporting details, stated because a reviewer will ask:

- **The rejection raises `NullPointerException`**, via `Objects.requireNonNull` with a message that
  explains the rule (`"Result.Ok forbids a null payload: a null is an absent value, not an
  outcome"`). NPE is the JDK's convention for a rejected constructor argument. RFC-0001's use of
  `IllegalStateException` for `Lazy` is a different situation — there a caller-supplied *initializer*
  misbehaves at an arbitrary later moment, which is a state problem, not a bad argument.
- **A mapping function that returns `null` is rejected the same way**, in `map`, `flatMap`, `recover`
  and `orElseThrow`. Letting it through would reintroduce exactly the null this decision keeps out,
  one call further along.

## Alternatives Considered

- **(b) Permit `null` in `Ok` and mark the payload `@Nullable`.** This is the reading of FR-17 that
  keeps `Result<Void>` usable, and it is the one most libraries end up with. Rejected on where the cost
  lands: it is paid by **every consumer of every `Result`**, forever, so that one uninhabited type
  parameter can be expressed. `Ok.value()` would return `@Nullable T`; a consumer writing
  `case Ok<Account> ok -> ok.value().id()` would get a nullability finding in their own build, on the
  happy path, for a case that cannot apply to them. It also makes the type's central promise
  conditional, and a conditional promise is the kind reviewers stop reading.
- **(c) Mint a `Unit` singleton and document `Result<Unit>` for effects.** Type-sound, and cheap
  today. Rejected as premature rather than wrong: it adds a permanent public type under `japicmp`
  (NFR-09) and contradicts a sentence three governing documents state, on the strength of **zero call
  sites** — there is no `Result`-returning API in the repository yet. Items 3.1 (`Validator`), 3.3
  (`AuditLog`) and 4.4 (`JdbcTxRunner`) are the first plausible consumers, and RFC-0002/RFC-0003 are
  where their signatures get pinned. Deciding there means deciding with evidence. This is the same
  no-speculation discipline that kept `exports` out of the module descriptors until item 2.1
  (ADR-0005) and the harness opt-in per module until there were sources (ADR-0007).
- **Loosen the rule to "`Ok(null)` is discouraged" and enforce nothing.** Rejected: the rule is stated
  normatively in an Accepted ADR and the specification, and an unenforced prohibition in the most-used
  type of a shared library is a review promise — the exact thing items 1.3, 1.7, 1.8 and 1.11 each
  replaced with a gate.
- **Amend FR-17's text now, so the specification stops promising something unimplementable.**
  Deferred, not rejected. Under ADR-0010 that means editing the manifest's `spec.*` block and
  re-rendering `docs/specs/01_spec_utils.md` — the right procedure — but the amendment cannot be
  written until the replacement is chosen, and choosing it is what this ADR defers. Editing the spec to
  say "and the void case is unresolved" would add ceremony without adding a decision; this record and
  the `Result` Javadoc carry that state instead, and RFC-0002 amends the spec once.
- **Add a third permitted subtype for the void success.** Rejected on the contract: ADR-002 and
  RFC-0001 both say `Result` is sealed over *exactly* `Ok` and `Err`, and a third arm would break every
  consumer's exhaustive `instanceof` chain — which is the property that makes a sealed hierarchy worth
  having.

## Consequences

- **`Ok.value()` is unconditionally non-null**, so consumers pattern-matching on the success arm need
  no null check and their own static analysis stays quiet. This is the win the decision buys.
- **A use case that succeeds without producing anything cannot be expressed as a `Result` today.**
  Stated plainly in the `Result` class Javadoc, pointing here, with the interim guidance: model the
  operation on a payload the caller can use, or throw. A library that quietly lacked this would be
  worse than one that says so.
- **The direction is the reversible one, which is why it is safe to defer.** Under RFC-0001 §Versioning,
  adding a type or a factory later is **MINOR**; making an existing non-null return nullable would be
  **MAJOR**. Starting at (a) leaves both (b)-lite and (c) reachable; starting at (b) forecloses nothing
  but degrades the API immediately and permanently.
- **FR-17 is now partially unimplemented, on the record.** The value channel, the four operations, the
  sealed arms, the `ErrorDetail` shape and the null prohibition are all delivered; the "use
  `Result<Void>`" guidance is not, and cannot be as written. Recording that here is the AGENTS.md §7
  route for an implementation that diverges from a frozen spec ("update the spec in the same PR **or**
  add an ADR explaining the deviation").
- **ROADMAP item 3.0's scope grows by one clause**, so the question has an owner with a deadline rather
  than living only in a Javadoc paragraph. It is not filed as a new Milestone 2 item on purpose: an
  item that cannot be closed until a later milestone's RFC exists would leave M2 permanently
  incomplete, and the README ↔ ROADMAP milestone check would be right to say so.
- **`ErrorDetail` is `Serializable`, and that is part of the same null boundary.** `BusinessException`
  inherits `Serializable` from `Throwable` and carries an `ErrorDetail` field; a non-serialisable
  payload would make every `BusinessException` fail to serialise — silently, and only in the hosts that
  do it (session replication, JMS, RMI). It is asserted by a round-trip test rather than assumed. Cost,
  stated: the serialised form joins the compatibility surface. Second-order cost, measured while
  verifying the Javadoc gate: **JDK 17's doclint emits three spurious "no comment" warnings for the
  components of a `Serializable` record** — reproduced by swapping `Serializable` for `Cloneable`,
  which silences them, and absent on JDK 21. It is a doclint defect in the serialized-form pass, not a
  documentation gap, and `@serial` does not suppress it. There is no Javadoc gate in the build yet;
  **item 8.4 owns one, and should build the Javadoc JAR on JDK 21 or pass `-Xdoclint:-missing` on 17.**

## References

- FR-17, FR-18 and spec §5 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md).
- [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) — options A/B/C for the error model; this
  ADR does not revisit that choice, only the null boundary inside option A.
- [RFC-0001](../rfc/0001-core-contracts.md) §Error model and §Versioning.
- `d4np-core/src/test/java/it/d4np/utils/ResultTest.java` — the null boundary asserted on both arms.
- Javadoc measurements above taken on Temurin 17.0.20+8 and 21.0.12+8.
