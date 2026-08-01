# ADR-0015: Keep `StrategyNotFoundException` outside the `BusinessException` hierarchy, and carry its keys as text

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.3; [RFC-0001](../rfc/0001-core-contracts.md) §FR-04 (the contract) and
  §Alternatives (the factory/registry asymmetry);
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) (the error model this sits beside);
  [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) (the reversibility rule reused
  here); [ADR-0014](0014-log-through-the-jdk-system-logger.md) (how the collision warning is emitted);
  [ADR-0013](0013-lazy-initialization-by-double-checked-volatile.md) (the fast/slow split tried and
  rejected here); FR-04, FR-19, NFR-04

## Context

RFC-0001 carried FR-04 over from the specification "unchanged", and it reads as four settled clauses:
`Optional<S> find(K)`, `S getOrThrow(K)` throwing `StrategyNotFoundException` **with the known-keys
list**, `ConcurrentHashMap`-backed lock-free reads, and last-write-wins `register` with a warning log.

Implementing it surfaced three questions the contract does not answer, each of which affects the
published surface and therefore cannot be settled in a commit message:

1. **Where does `StrategyNotFoundException` sit in the type hierarchy?** Core already publishes an
   unchecked `BusinessException`, and a new unchecked exception naturally invites being a subclass of
   it. FR-19's mapping table, however, sends the two to **different HTTP statuses**.
2. **What type are the "known keys"?** The registry is generic in `K`, and the obvious field is
   `Set<K>` — but every `Throwable` is `Serializable`.
3. **How much of the key list belongs in the message?** NFR-04 sizes this registry at **1000
   strategies**, so "list the known keys" is not the small thing it sounds like.

## Decision

**`StrategyNotFoundException` extends `RuntimeException` directly — never `BusinessException` — is
`final`, carries its key and known keys as `String`, exposes the complete set through
`knownKeys()`, and truncates the rendered message at 20 keys while saying how many it hid.** Its
constructor is package-private: consumers catch this exception, they do not throw it.

`StrategyRegistry` itself is a thin, final wrapper over `ConcurrentHashMap` with exactly the three
methods FR-04 names — no `keys()`, no `unregister()`, no `size()`, because no requirement asks and
each is additive later.

## Alternatives Considered

- **`StrategyNotFoundException extends BusinessException`.** Rejected on FR-19's own table: a
  `BusinessException` maps to **422**, a missing strategy to **500 plus an alert**. They are different
  events — one is a rule the caller broke, the other is a module that failed to register itself, which
  no end user can act on. As a subclass, a `GlobalExceptionHandler` whose `catch` clauses ran in the
  wrong order would silently report an operations failure as a client error, and the wrong-order
  handler is the easy mistake to make. A unit test asserts the *negative* (`isNotInstanceOf`), because
  this is a decision a later refactor could reverse by accident.
- **Keep the keys as `Set<K>`.** The natural signature, and it preserves the caller's type. Rejected on
  serialisation: `Throwable` implements `Serializable`, so a `Set<K>` field makes this exception
  serialisable **only when the consumer's key type happens to be** — failing silently, and only in the
  hosts that serialise (session replication, JMS, RMI). Item 2.1 hit exactly this shape with
  `ErrorDetail` and resolved it by making the payload serialisable; here the payload is
  consumer-supplied and cannot be constrained, so the rendering happens at construction instead. The
  cost is real — a caller cannot get the typed key back — and it is the reversible direction under
  RFC-0001 §Versioning: adding a typed accessor later is MINOR, removing one is MAJOR.
- **Put every known key in the message.** Rejected by NFR-04's own scale: 1000 keys is the *designed*
  size, and a thousand-key exception message is not a diagnostic but an incident — it bloats every log
  line, every alert payload and every error-tracker fingerprint. Truncation at 20 with an explicit
  *"and N more"* keeps the message readable while `knownKeys()` keeps the data complete. The count is
  stated rather than the list silently stopping, because a list that just ends looks complete.
- **A public constructor for `StrategyNotFoundException`.** Rejected as premature: only the registry
  can determine that a key is absent, and publishing the constructor would let a caller report a
  registry failure the registry never had. Widening is MINOR later.
- **Reject duplicate registrations, matching FR-01's `GenericFactory`.** Not open — RFC-0001
  §Alternatives already settled the asymmetry, and this record only restates why it survives contact
  with the code: a registry is designed for runtime reconfiguration, where replacement is the feature,
  while a factory is wired once at startup, where a duplicate key is two modules claiming one
  discriminator. What implementation added is that the replacement is detected with the **return value
  of `put`** rather than a `containsKey` check, so the collision cannot be missed or invented by a
  concurrent registration.
- **Expose `keys()` / `size()` on the registry.** Rejected as speculation, the same discipline that
  kept `exports` out of the descriptors until item 2.1 and a `Unit` type out of ADR-0012. Diagnostics
  already have `knownKeys()` on the failure path, and nothing has asked for the success path.

## Consequences

- **`find` meets NFR-04 on both toolchains: 12.8 ns/op on JDK 21 and 17.8 on JDK 17**, at exactly the
  budgeted shape — 1000 strategies, 8 threads, `AverageTime` — against a 50 ns/op budget. Numbers and
  caveats in
  [`docs/benchmarks/2026-08-01-strategy-registry-find.md`](../benchmarks/2026-08-01-strategy-registry-find.md).
- **A documented performance claim was refuted by its own benchmark, and the code now says the
  measurement instead.** `getOrThrow` reads the map directly and allocates no `Optional`, so it was
  written up as the cheaper call. It is not cheaper on either toolchain, and on **JDK 21 it is ~2 ns/op
  slower** — 15.0 vs 12.8, reproduced across three independent multi-fork runs with non-overlapping
  confidence intervals and an ordering that never inverts. The obvious explanation, that constructing
  the exception inline pushes the method past the JIT's inlining size threshold, was **tested by moving
  the throw into a private method — and changed nothing** (14.57 before, 14.57 after), so it is wrong
  too, and the split was reverted rather than kept as ceremony borrowed from `Lazy` (ADR-0013), where
  the same split *is* load-bearing.
- **The gap does not generalise across toolchains, which is the part worth carrying forward.** On JDK
  17 the two lookups are **indistinguishable** — 17.8 ± 4.3 against 18.0 ± 0.7, intervals overlapping,
  with `find`'s spread wide enough that this toolchain cannot resolve a 2 ns difference at all. So the
  portable statement is the weak one: **`getOrThrow` is never faster, and on at least one supported
  toolchain it is slower.** That is enough to retract the original claim and not enough to support a
  new one about why. **The cause is narrowed, not settled**, and is deliberately left open at ~2 ns
  inside a 50 ns budget: the honest candidates are the JMH harness itself (the two benchmarks return
  different types, so the blackhole work differs) and an escape-analysis effect on the returned
  `Optional` — both JIT-dependent, so a JDK-21-only gap discriminates between them not at all. The
  standing lesson is the transferable part: **avoiding an allocation is not the same as being faster, a
  plausible mechanism is not a measurement, and a measurement on one toolchain is not a property of the
  code.**
- **Callers must not choose between the two lookups on speed**, and the Javadoc now says so and gives
  the reason. They choose on what a missing key *means*.
- **The thread-safety claim is asserted, not stated** (spec §6): `StrategyRegistryPublicationStress`
  proves a strategy registered by one thread is seen fully constructed by another — with a
  deliberately non-`final` payload field, so the `ConcurrentHashMap` happens-before edge is the only
  thing ordering it — and `StrategyRegistryRegistrationStress` proves two threads registering the same
  key leave exactly one winner and **never an empty slot**, which is the failure a sequential test
  cannot reach.
- **The registration harness has to silence the logger**, and that is a finding rather than a detail:
  every iteration is a deliberate key collision, which is exactly what `register` logs at `WARNING`, so
  on the platform logger a run would emit millions of lines and measure the console. It uses the same
  package-private seam ADR-0014 introduced for the unit tests, which is the second time that seam paid
  for itself.
- **The truncation branch is the normal case at NFR-04's scale, so it is tested at 1000 keys**, not at
  a convenient three.
- **`StrategyNotFoundException` is `final` while `BusinessException` is not.** Deliberate asymmetry:
  FR-18 specifies `BusinessException` as a *base* for a consuming domain to extend per rule family,
  whereas there is exactly one way to fail to find a strategy. Unsealing later is MINOR.

## References

- FR-04 and NFR-04 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md); FR-19 for the
  mapping table that separates 422 from 500.
- [RFC-0001](../rfc/0001-core-contracts.md) §FR-04, §Alternatives (factory vs registry), §Versioning.
- `d4np-core/src/main/java/it/d4np/utils/{StrategyRegistry,StrategyNotFoundException}.java`;
  `.../src/test/java/it/d4np/utils/Strategy*Test.java`;
  `.../src/jcstress/java/it/d4np/utils/StrategyRegistry*Stress.java`;
  `.../src/bench/java/it/d4np/utils/StrategyRegistryFindBenchmark.java`.
- [`docs/benchmarks/2026-08-01-strategy-registry-find.md`](../benchmarks/2026-08-01-strategy-registry-find.md)
  — the NFR-04 measurement, the refuted claim and the inlining experiment.
