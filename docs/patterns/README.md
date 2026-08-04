# Design Patterns Catalogue

Living index of every design pattern **adopted**, **planned**, **considered and rejected**,
or **under evaluation** for `egl-utils-java`. Mandatory reading whenever a PR introduces
or removes a pattern, and updated in the same PR.

- **Rules** — [`AGENTS.md`](../../AGENTS.md) §8.
- **Canonical taxonomy** — [`design-patterns.md`](design-patterns.md). All pattern names
  used here, in ADRs, and in commit messages must match its spelling and categorisation.

## Architecture style

_No single architectural style committed at intake (typical for a library, which exposes an API
rather than an application architecture). Record one in an ADR here if that changes._


## How to use this catalogue

- **Adding a pattern** — when a PR lands one, add a row to *Implemented / Planned* as
  `Implemented`, with the ADR link and the code location (a real path under
  `src/main/java/...`); a pattern decided in an ADR but not yet in code is added as `Planned`.
- **Refining** — update the row and link the new ADR.
- **Rejecting** — add it to *Rejected* with the reason; do not silently drop it.
- **Removing** — move the row to *Superseded*, link the superseding ADR, keep the history.

Status vocabulary: `Planned` (decided in an ADR, not yet landed) · `Implemented` (present
in `src/main/...`, ADR `Accepted`) · `Considered` · `Rejected` · `Superseded`.

## Implemented / Planned

_Patterns named in the spec at intake are seeded below as **Planned**; each becomes
**Implemented** with its ADR and a real code location in the PR that introduces it._

| # | Pattern | Status | Problem it addresses | Code location | ADR / PR |
|---|---------|--------|----------------------|---------------|----------|
| 1 | Result / Either | Implemented | FR-17 `Result<T>` — expected business outcomes as values instead of control flow | [`Result.java`](../../d4np-core/src/main/java/it/d4np/utils/Result.java) | [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) · [RFC-0001](../rfc/0001-core-contracts.md) · [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) |
| 2 | Lazy initialization | Implemented | FR-03 `Lazy<T>` — deferred, once-only, safely published initialization under a ≤ 2 ns/op steady-state budget (NFR-01) | [`Lazy.java`](../../d4np-core/src/main/java/it/d4np/utils/Lazy.java) | [ADR-0013](../adr/0013-lazy-initialization-by-double-checked-volatile.md) · [RFC-0001](../rfc/0001-core-contracts.md) |
| 4 | Abstract Factory / keyed factory | Implemented | FR-01 `GenericFactory<T,K>` — construction by key without exposing concrete types; duplicate keys rejected atomically, `replace()` as the explicit override | [`GenericFactory.java`](../../d4np-core/src/main/java/it/d4np/utils/GenericFactory.java) | [ADR-0016](../adr/0016-generic-factory-atomic-duplicate-rejection.md) · [RFC-0001](../rfc/0001-core-contracts.md) |
| 5 | Builder (fluent) + Template method | Implemented | FR-02 `FluentBuilder<T>` — readable construction of multi-field domain objects, with every validation failure reported at once | [`FluentBuilder.java`](../../d4np-core/src/main/java/it/d4np/utils/FluentBuilder.java) | [ADR-0017](../adr/0017-fluent-builder-accumulated-validation.md) · [RFC-0001](../rfc/0001-core-contracts.md) |
| 3 | Strategy + Registry | Implemented | FR-04 `StrategyRegistry<K,S>` — runtime strategy selection with an explicit missing-key contract, lock-free reads under a ≤ 50 ns/op budget (NFR-04) | [`StrategyRegistry.java`](../../d4np-core/src/main/java/it/d4np/utils/StrategyRegistry.java) | [ADR-0015](../adr/0015-strategy-registry-last-write-wins.md) · [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) · [RFC-0001](../rfc/0001-core-contracts.md) |
| 6 | Adapter (provider wrapper) | Implemented | FR-14 `Validator` — Jakarta Bean Validation's vocabulary (`ConstraintViolation`, an unordered `Set`, an interpolated message) translated into this library's own (`Result`, `ErrorDetail`, an ordered `List<String>`); the translation layer is where the C-01 rule — a violation never carries the rejected value — is applied once for every caller | [`Validator.java`](../../d4np-core/src/main/java/it/d4np/utils/Validator.java) | [ADR-0020](../adr/0020-render-violations-from-the-message-template.md) · [RFC-0002](../rfc/0002-cross-cutting-contracts.md) |
| — | Service Provider Interface | Planned | FR-12 KeyProvider and FR-10 DistributedLock — substitutable implementations behind an interface (ADR-001) | _TBD_ | _spec (intake)_ |
| — | Adapter (framework edge) | Planned | spring-adapter and lock-redisson — host/framework coupling confined to the edge. Distinct from row 6, which adapts a *provider API* inside core; this one adapts a *host framework* from a module that may see it (ADR-001) | _TBD_ | _spec (intake)_ |
| — | Template method / callback | Planned | FR-05 SimpleJdbcExecutor and FR-06 JdbcTxRunner — the library owns the resource lifecycle, the caller supplies the body | _TBD_ | _spec (intake)_ |
| 7 | Decorator (aspect) + Strategy | Implemented | FR-15 `ExecutionTimeMetricAspect` — timing added without touching the measured code: the aspect wraps a call it does not know, and the sink it reports to is an interchangeable `ExecutionTimeRecorder` fixed at construction. Applied as an **advice body**, not an AspectJ aspect — core may not see `aspectjrt` or Micrometer (ADR-001), so the `@Aspect`, the pointcut and the Micrometer recorder are the adapter's half | [`ExecutionTimeMetricAspect.java`](../../d4np-core/src/main/java/it/d4np/utils/ExecutionTimeMetricAspect.java) · [`ExecutionTimeRecorder.java`](../../d4np-core/src/main/java/it/d4np/utils/ExecutionTimeRecorder.java) | [ADR-0021](../adr/0021-time-through-an-advice-body-core-can-own.md) · [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) · [RFC-0002](../rfc/0002-cross-cutting-contracts.md) |


## Rejected

_No rejections recorded yet._

| # | Pattern | Considered for | Rejected because | ADR / PR |
|---|---------|----------------|------------------|----------|
| — | —       | —              | —                | —        |

## Superseded

_No superseded patterns yet._

| # | Pattern | Superseded by | When | ADR / PR |
|---|---------|---------------|------|----------|
| — | —       | —             | —    | —        |

## Candidate patterns to consider

The taxonomy in [`design-patterns.md`](design-patterns.md) lists every pattern in scope. As
the architecture takes shape, narrow that universe to the patterns plausibly applicable to
*this* artifact and list them here by category, each with a one-line "possible application".
A candidate remains a candidate until adopted (own ADR) or explicitly rejected.

## Out-of-scope categories

Record here any taxonomy category pre-classified as not applicable to this artifact (with a
one-line reason), so the policy of explicit rejection is honoured without filling the
*Rejected* table with N/A noise.
