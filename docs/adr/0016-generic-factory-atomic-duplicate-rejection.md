# ADR-0016: Make `GenericFactory` thread-safe, and reject duplicates atomically

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.4; [RFC-0001](../rfc/0001-core-contracts.md) §FR-01 (the operation
  table) and §Alternatives (the factory/registry asymmetry);
  [ADR-0015](0015-strategy-registry-last-write-wins.md) (the sibling type, and the exception
  decisions this record reuses); [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md);
  FR-01, FR-19

## Context

RFC-0001 pins FR-01 as a five-row operation table — `register` rejecting duplicates, `replace`
overriding, `create` throwing `FactoryKeyNotFoundException`, `tryCreate` returning `Optional`,
`keys()` returning an unmodifiable snapshot — plus the rule that a supplier returning `null` is a
programming error.

**What the table does not contain is a thread-safety row.** FR-03 `Lazy` has one; FR-04
`StrategyRegistry` has one; FR-01 has none, and the specification's own §5 flags exactly this gap
("only 5 of ~25 public types carry a nullability/error/thread-safety contract row"). So the question
is open, and it is not cosmetic: **duplicate rejection is a check-then-act operation**, and whether it
is safe under a race depends entirely on how it is implemented.

There is a second question the contract does not answer, because item 2.3 only just created it. FR-01
requires `FactoryKeyNotFoundException` whose *"message lists the known keys"* — which is, to the
character, what `StrategyNotFoundException` already does. Two exceptions now need the same four
non-obvious behaviours: render keys to text, sort them, truncate the message at a cap, and snapshot
them into a serialisable set.

## Decision

**`GenericFactory` is thread-safe, backed by a `ConcurrentHashMap`, and `register` rejects duplicates
with a single `putIfAbsent` rather than a `containsKey`-then-`put` pair.** The claim is asserted by
`GenericFactoryRegistrationStress` rather than stated, per spec §6.

**The shared key diagnostic is extracted into a package-private `KeyDiagnostics`**, used by both
`StrategyNotFoundException` and `FactoryKeyNotFoundException`. The two exceptions stay **siblings, not
a hierarchy**: `FactoryKeyNotFoundException` extends `RuntimeException` directly, exactly as ADR-0015
decided for its counterpart, and for the same reason — it is a wiring defect (FR-19's 500 fallback),
not a `BusinessException` (422).

## Alternatives Considered

- **Leave thread safety undefined, matching the contract.** Rejected. "Undefined" is not a neutral
  default for a type whose entire purpose is to be populated at startup by whatever wires the
  application — which, in a Spring or ServiceLoader context, is frequently more than one thread. A
  library that declines to answer forces every consumer to assume the worst and wrap it, which is
  strictly more expensive than a `ConcurrentHashMap`.
- **`containsKey` then `put`, or `get`-then-`put`.** The obvious reading of "rejects a duplicate", and
  **wrong under exactly the conditions this factory is used in**: two threads registering the same key
  can both observe it absent, both write, and both believe they won — so the duplicate the method
  exists to reject is silently accepted, and which supplier survives depends on scheduling. It passes
  every sequential test, which is why it needs a jcstress harness and not a unit test.
- **`synchronized` on all five methods.** Correct and simple. Rejected because it makes `create` — the
  hot, repeatedly-called operation — pay for a mutual exclusion that only `register` needs, and
  `putIfAbsent` gives the atomicity for free on a map that is already lock-free for reads.
- **Give `FactoryKeyNotFoundException` and `StrategyNotFoundException` a common public supertype.**
  Tempting: it would let FR-19's handler catch one type, and remove the duplicated accessor pair.
  Rejected because it puts a permanent coupling in the *published hierarchy* to save four lines: the
  two describe different failures of different types, and a consumer catching "strategy missing" would
  silently begin catching "factory key missing" too. A package-private helper shares the behaviour
  without sharing the identity, which is the part that matters.
- **Copy the diagnostic code into the second exception.** Rejected as the option that fails silently:
  the two copies would drift — someone changes the truncation cap, or the sort, or the serialisable
  snapshot in one place — and **both exceptions' tests would still pass**, because each tests its own
  copy. The extraction is the smaller risk even though it edits a type merged one PR ago.
- **Keep `keys()` as an unmodifiable view over the live key set.** Cheaper — no copy. Rejected on the
  contract's own word: RFC-0001 says **snapshot**, and a view keeps changing under a caller who
  reasonably read that word as meaning it would not. A test asserts a registration after the call does
  not appear in the set already handed out.
- **Let `tryCreate` return `Optional.empty()` when the supplier returns `null`.** Superficially tidier
  — one "nothing came back" answer. Rejected because it destroys the only distinction `tryCreate`'s
  return type exists to make: an unbound key (an ordinary answer) would become indistinguishable from
  a broken supplier (a defect). Both `create` and `tryCreate` throw `IllegalStateException` naming the
  key.

## Consequences

- **Duplicate rejection holds under a race, and that is proven, not argued.**
  `GenericFactoryRegistrationStress` forbids both failure modes by name: `2, 0` (both accepted — the
  check-then-act bug) and `0, 2` (both rejected, key left unbound). Only `1, 1` is acceptable.
- **A unit test would not have caught the bug this harness forbids**, which is the transferable point:
  `containsKey`-then-`put` is sequentially indistinguishable from `putIfAbsent`. The eight-thread test
  in `GenericFactoryTest` is a fast regression net, not the proof.
- **`GenericFactory` and `StrategyRegistry` now disagree about duplicates on purpose**, and both
  Javadocs carry the table explaining why: a factory is wired once at startup, where a duplicate key is
  two modules claiming one discriminator and silent overwrite makes the winner depend on classpath
  order; a registry is designed for runtime reconfiguration, where replacement is the feature.
  `replace()` keeps the override available with the intent visible at the call site.
- **`KeyDiagnostics` is package-private and stays that way.** It is an implementation detail of two
  exceptions, not a utility offered to consumers; publishing it would mean supporting a key-rendering
  format as API.
- **The extraction changed one shipped message.** `StrategyNotFoundException`'s empty-registry text
  moved from *"the registry is empty"* to *"nothing is registered"*, so one wording serves both types.
  Both are unreleased (`[Unreleased]` in the changelog, no `japicmp` baseline yet), and exception
  message text is not part of the compatibility surface — but it is recorded rather than left for a
  reader to notice in a diff.
- **No benchmark, deliberately.** No NFR names this type, unlike `StrategyRegistry.find`. Adding one
  would invent a budget the specification does not have, and item 8.3 already owns the problem of
  making absolute perf numbers meaningful in CI. `tryCreate` allocating an `Optional` is noted in
  RFC-0001 §Performance and is not a measured concern.

## References

- FR-01 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md), and its §5 `[GAP]` line about
  missing contract rows — this record closes one of them.
- [RFC-0001](../rfc/0001-core-contracts.md) §FR-01, §Alternatives, §Performance.
- [ADR-0015](0015-strategy-registry-last-write-wins.md) — the exception-hierarchy and
  keys-as-text decisions reused here.
- `d4np-core/src/main/java/it/d4np/utils/{GenericFactory,FactoryKeyNotFoundException,KeyDiagnostics}.java`;
  `.../src/jcstress/java/it/d4np/utils/GenericFactoryRegistrationStress.java`.
