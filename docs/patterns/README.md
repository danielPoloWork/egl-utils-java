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
| 9 | Facade | Implemented | FR-20 `JsonMapper` — one small, safe entry point over Jackson's configuration surface, where the point of the facade is what it does **not** expose: no `ObjectMapper` is reachable, so the hardened settings cannot be switched off by the consumer that received them. Distinct from row 6's Adapter, which translates a provider's vocabulary into ours; this one keeps a subsystem's vocabulary out of reach entirely. Customisation is additive-only through Jackson's own `Module`, the one place a Jackson type appears in a signature | [`JsonMapper.java`](../../d4np-json/src/main/java/it/d4np/utils/json/JsonMapper.java) | [ADR-0024](../adr/0024-take-a-jackson-type-in-one-signature.md) · [ADR-0025](../adr/0025-render-java-time-as-iso-8601.md) · [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) |
| — | Service Provider Interface | Planned | FR-12 KeyProvider and FR-10 DistributedLock — substitutable implementations behind an interface (ADR-001) | _TBD_ | _spec (intake)_ |
| — | Adapter (framework edge) | Planned | spring-adapter and lock-redisson — host/framework coupling confined to the edge. Distinct from row 6, which adapts a *provider API* inside core; this one adapts a *host framework* from a module that may see it (ADR-001) | _TBD_ | _spec (intake)_ |
| 10 | Template Method + Strategy | Implemented | FR-05 `SimpleJdbcExecutor` — the library owns the resource lifecycle and the caller supplies the body. The skeleton is fixed and private: acquire a connection, prepare a **`PreparedStatement`** (never a `Statement`), bind positionally, run, translate every `SQLException`, close in reverse. The one step a caller varies is the row mapping, supplied as a `RowMapper<T>` — a Strategy in the small, and the reason it is a lambda rather than reflection is NFR-03: a mapper over the same `ResultSet` *is* the hand-written loop's body plus one virtual call, where per-row reflection would spend the whole 10% budget before the framing was measured. Distinct from row 7's Decorator, which wraps a call it does not know; this one owns the call and lets the caller fill one hole. **FR-06's `JdbcTxRunner` is the second instance of the same pattern and arrives with item 4.4** | [`SimpleJdbcExecutor.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/SimpleJdbcExecutor.java) · [`RowMapper.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/RowMapper.java) | [ADR-0028](../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md) · [ADR-0029](../adr/0029-annotate-the-varargs-so-a-null-parameter-compiles.md) · [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) |
| 7 | Decorator (aspect) + Strategy | Implemented | FR-15 `ExecutionTimeMetricAspect` — timing added without touching the measured code: the aspect wraps a call it does not know, and the sink it reports to is an interchangeable `ExecutionTimeRecorder` fixed at construction. Applied as an **advice body**, not an AspectJ aspect — core may not see `aspectjrt` or Micrometer (ADR-001), so the `@Aspect`, the pointcut and the Micrometer recorder are the adapter's half | [`ExecutionTimeMetricAspect.java`](../../d4np-core/src/main/java/it/d4np/utils/ExecutionTimeMetricAspect.java) · [`ExecutionTimeRecorder.java`](../../d4np-core/src/main/java/it/d4np/utils/ExecutionTimeRecorder.java) | [ADR-0021](../adr/0021-time-through-an-advice-body-core-can-own.md) · [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) · [RFC-0002](../rfc/0002-cross-cutting-contracts.md) |
| 8 | Specification + Strategy (SPI) | Implemented | FR-16 `AuditLog` — the never-capture rule is an immutable predicate object (`AuditPolicy`) that composes by union and never by removal, so "which component names may never be captured" is a value a host can extend, read back and test rather than a condition buried in the engine; the destination is the interchangeable `AuditSink`, the same sink shape row 7 uses, with `LoggingAuditSink` as core's dependency-free implementation and the **opposite** failure policy — a failing audit sink throws where a failing metrics recorder is swallowed | [`AuditPolicy.java`](../../d4np-core/src/main/java/it/d4np/utils/AuditPolicy.java) · [`AuditSink.java`](../../d4np-core/src/main/java/it/d4np/utils/AuditSink.java) | [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) · [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) · [RFC-0002](../rfc/0002-cross-cutting-contracts.md) |


## Rejected

| # | Pattern | Considered for | Rejected because | ADR / PR |
|---|---------|----------------|------------------|----------|
| 1 | Visitor | FR-16 `AuditLog` — walking a host's object graph to capture before/after state | Classic Visitor requires the visited structure to cooperate: an `accept(Visitor)` method on every audited type. A library cannot ask a consumer's domain objects to implement its interface in order to be auditable, and an audit trail that only works on types written after the library arrived is not one. The **intent** — add an operation to an object structure without changing it — is met by a reflective walk over public accessors instead, which is the same goal reached without the contract. Recorded rather than silently skipped, because the traversal in `AuditLog.collect` reads like a Visitor and the next reader should know why it is not one | [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) |
| 2 | Message Translator (EIP) | FR-21 `ObjectMapperExtensions.convert` — deep conversion between two POJO shapes | The **intent** matches almost exactly — transform a representation between schemas — and the name is still wrong here, so it is recorded rather than adopted. Message Translator is an integration pattern: it presumes a message, a channel and an endpoint, and its value is that the translation is a routable step a pipeline can compose. `convert` is an in-process call between two of the caller's own types, with no message, no channel and nothing to route; naming it Message Translator would put integration vocabulary on a two-argument static method and invite a reader to look for the pipeline it belongs to. AGENTS.md §8.1 forbids the force-fit, and §8.3 asks for the reason — this is it | [ADR-0026](../adr/0026-rewrite-jacksons-unchecked-conversion-failure.md) · [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) |

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
