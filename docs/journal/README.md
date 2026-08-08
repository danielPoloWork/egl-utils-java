# Session Journal

Dated end-of-session checkpoints — what got done, where the project stands, and how the
next session resumes. One file per session that changed the project's state, at
`docs/journal/<YYYY>/<MM>/<YYYY-MM-DD>-<short-slug>.md`. The journal is the dated trail;
`ROADMAP.md` is the forward plan — checkpoints never live inline in the roadmap.

At the close of a state-changing session, the agent:

1. Creates the dated file under `docs/journal/<YYYY>/<MM>/`.
2. Adds a link row to this index (newest first, grouped by year/month).
3. Updates the *Latest checkpoint* pointer in `ROADMAP.md`.

## Index

### 2026

_(newest first)_

#### August

- [2026-08-07 — `ObjectMapperExtensions`, and a leak the wrapping rule did not reach (item 4.2)](2026/08/2026-08-07-object-mapper-extensions.md) —
  FR-21's conversion, partial mapping and type token over the mapper 4.1 built; ADR-0026 catches the
  **unchecked** `IllegalArgumentException` `convertValue` raises, which the RFC's checked-exception
  wrapping rule never reached, and ADR-0027 keeps a record's generated `toString()` from printing a
  payload; **FR-20 and FR-21 both closed — `d4np-json` is feature-complete**.
- [2026-08-07 — `JsonMapper`, and a claim the RFC could not keep (item 4.1)](2026/08/2026-08-07-json-mapper-hardened.md) —
  the first production code outside `d4np-core` and the first compile-scope dependency in the repo;
  ADR-0024 replaces an RFC claim with a measured consumer-module probe, ADR-0025 adds the fifth
  setting; **C-01's second defence found to be narrower than written — the cause chain still quotes
  the value**.
- [2026-08-05 — RFC-0003, and two collisions the specification does not admit to (item 4.0)](2026/08/2026-08-05-rfc-0003-jdbc-and-json-contracts.md) —
  contracts for all five Milestone 4 requirements rather than the two mandated, because FR-05 × FR-06 and
  FR-20 × FR-21 each break in the seam between them; ADR-0023 settles who approves an RFC here;
  **`Accepted` on the owner's authority (2026-08-06) — 4.1–4.5 unblocked**.
- [2026-08-05 — `AuditLog`, and a trap reproduced rather than described (item 3.3)](2026/08/2026-08-05-audit-log-redaction.md) —
  FR-16's four redaction layers behind an event no caller can mint; ADR-0022; C-05 added and C-03 found
  to be gated after all; **Milestone 3 complete**.
- [2026-08-04 — `ExecutionTimeMetricAspect`, and the aspect that cannot be one (item 3.2)](2026/08/2026-08-04-execution-time-metric-aspect.md) —
  FR-15 as an advice body core can own; ADR-0021; the Checkstyle escape hatch `AGENTS.md` §9 had always
  prescribed and the ruleset had never enabled.
- [2026-08-02 — `Validator` over Jakarta Bean Validation (item 3.1)](2026/08/2026-08-02-validator-jakarta-bean-validation.md) —
  core's first third-party edge, at `provided` scope behind `requires static`; ADR-0020 renders
  violations from the message template so a rejected credential cannot reach an RFC 7807 body.
- [2026-08-01 — RFC-0002, and the audit trail that would have leaked (item 3.0)](2026/08/2026-08-01-rfc-0002-cross-cutting-contracts.md) —
  the FR-16 redaction policy, FR-14 and FR-15 contracts, and ADR-0019 minting `Unit`; **`Accepted` on
  the owner's authority (2026-08-02)** — this row read *"the RFC is `Proposed`, not approved"* until item
  4.0, because the approval commit updated the checkpoint file and not its index row. Corrected here
  rather than left standing, since a stale index is worse than a terse one.
- [2026-08-01 — the last three core types, and Milestone 2 closes (item 2.5)](2026/08/2026-08-01-string-object-resource-utils.md) —
  `StringCaseConverter`, `ObjectUtils`, `ResourceLoaderUtils`; ADR-0018 resolves a contradiction inside
  RFC-0001's own FR-22; **Milestone 2 complete**.
- [2026-08-01 — the two creational patterns (item 2.4)](2026/08/2026-08-01-factory-and-builder.md) —
  `GenericFactory` and `FluentBuilder`; ADR-0016 and ADR-0017; two places where the pinned contract
  could not deliver its own promise.
- [2026-08-01 — `StrategyRegistry` and the library's first logging (item 2.3)](2026/08/2026-08-01-strategy-registry.md) —
  FR-04 closed and NFR-04 measured at 12.8 ns/op (JDK 21) / 17.8 (JDK 17); ADR-0014 and ADR-0015; a
  documented performance claim refuted by its own benchmark, on one toolchain only.
- [2026-08-01 — `Lazy<T>` and safe publication (item 2.2)](2026/08/2026-08-01-lazy-safe-publication.md) —
  FR-03 closed and NFR-01 measured at 0.83–0.95 ns/op; ADR-0013; one `volatile` found to be guarded by
  review rather than by any gate.

#### July

- [2026-07-30 — the core error vocabulary (item 2.1)](2026/07/2026-07-30-core-error-vocabulary.md) —
  `d4np-core` gets its first public API; ADR-0011 and ADR-0012; a jcstress regression found and fixed.

> Milestone 1's twelve items (2026-07-26 → 2026-07-29) produced no checkpoints — the journal was
> scaffolded and left empty. Their record lives in the `ROADMAP.md` item notes, the ADRs and
> `docs/releases/v0.1.0.md`; it is **not** back-filled here, because a dated checkpoint written after
> the fact would claim a trail that did not exist.
