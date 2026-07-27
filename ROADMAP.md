# ROADMAP — egl-utils-java

The negotiated milestone roadmap. Authored by the **producer** in the `plan` phase
(`.eados-core/orchestrator/commands/plan.md`) from the approved RFCs recorded in
`orchestrator/project.yaml` → `delivery_state.refs.rfcs`. The `roadmap-covers-rfcs` gate
(`traceability.py`) reads the `## Milestone N` sections below; `plan → scaffold` is illegal until
every recorded RFC is addressed by at least one milestone.

**Every item carries a size and a route.** Size is a macroscopic T-shirt estimate
(`plan.yaml sizing_scale: XS S M L XL`) — not story points. Route is the advisory model/effort
recommendation from the `os/routing` policy (ADR-0017), stated as **tier / effort**, never a model
name — names rot, the dated `catalog:` owns them. Routes below were resolved with
`route_advice.py`, not asserted; the signals that earned each one are listed under
[Routing basis](#routing-basis). **The route is advisory: the human keeps final model authority.**

> **Note on section order.** The context sections come first and the milestones last, deliberately.
> `traceability.parse_milestones` slices each `## Milestone N` section up to the *next* one, so
> anything placed after the last milestone is absorbed into its body — an appendix that merely
> *mentions* an RFC id would register as a coverage edge from Milestone 8. Keeping the milestones
> last means the RFC → milestone edges the gate derives are exactly the real ones.

## Negotiation inputs — stated, because they shape everything below

| Input | Value | Where it comes from |
|---|---|---|
| Approved RFCs | **RFC-0001** only | `delivery_state.refs.rfcs` |
| Capacity | **one person** — the owner | `ownership.owner`/`maintainer`/`author` all = Daniel Polo; `governance` notes reviewers "stay deferred until there is a second collaborator" |
| Dates | **none, deliberately** | `governance.version_start: "pre-1.0 milestone-driven"`. This project's own release policy is milestone-driven, not calendar-driven, so this roadmap sequences work and does **not** invent dates |
| Ordering | the ADR-001 dependency DAG | core first, then capability leaves, then adapters, then release |
| Milestone vocabulary | `semver` | `domains/software.yaml milestone_vocabulary` |

**Scope is the owner's call.** The producer reconciles and sequences; cuts and additions are a human
decision (`plan.md` Boundary). Nothing below is committed scope until the owner says so.

## The design debt this roadmap makes visible

The negotiation protocol is explicit: *"Each item references an approved RFC — no roadmap item
without a design behind it."* Measured against that rule, **one milestone of eight is covered**:

| Milestone | RFC behind it | Status |
|---|---|---|
| M1 repository foundations | ADR-001 + ADR-004 (both Accepted) — no RFC | structural decisions already settled by ADR; see note below |
| **M2 core foundations** | **RFC-0001** | **covered** |
| M3 core cross-cutting | — | **needs an RFC** (item 3.0) |
| M4 json and jdbc | — | **needs an RFC** (item 4.0) |
| M5 concurrent | — | **needs an RFC** (item 5.0) |
| M6 security | ADR-003 covers the JWT library choice only | **needs an RFC** (item 6.0) |
| M7 adapters and test support | — | **needs an RFC** (item 7.0) |
| M8 release engineering | — | see 8.0 note |

So this roadmap schedules **five more RFCs** as first-class items (3.0, 4.0, 5.0, 6.0, 7.0). That is
not padding: `workflow.yaml` defines `plan → design` as a legal, non-human-gated transition precisely
so a new RFC can re-open planning without restarting the project. Each of those items is a return to
the `design` phase, and the milestone's implementation items are blocked on it.

**M1 is the deliberate exception.** Its content is structural and already decided by two Accepted
ADRs (ADR-001 module split, ADR-004 generated layout). Writing an RFC to re-decide an accepted ADR
adds ceremony, not a decision. M1 items therefore cite the ADR that governs them.

**M8 carries no RFC item.** Its work is CI and release plumbing implementing NFR-09 through NFR-12,
which the specification already states normatively. Item 8.3 is the exception — it changes a stated
NFR's enforcement model — and is flagged `decision-heavy` accordingly.

## Sequencing

No dates — the project is milestone-driven pre-1.0 (`governance.version_start`). This is the
dependency order, which is what actually constrains the work:

```
M1 ─────────────────────────────────────────► everything (ADR-004: no code before the reactor)
 └─ 1.1 blocks 1.2–1.9 and all of M2

M2 ──► M3, M4, M5              [Result/ErrorDetail is the shared error vocabulary]
 │
 ├─ M3 ──┐
 ├─ M4 ──┤
 └─ M5 ──┴──► M7               [adapters bind what the modules expose]
              M6 ─┘            [independent of M3/M4; needs only M2]

M6 + M7 ──► M8                 [japicmp needs a published surface; 8.6 audits the finished one]
```

M3, M4, M5 and M6 are mutually independent once M2 lands — with capacity of one they are sequential
in practice, and the order among them is the owner's priority call, not a technical constraint.
**8.3 is the exception to that ordering:** it can be pulled forward at any time after 1.8, and doing
so would stop the M2 perf numbers from sitting in an advisory limbo for the whole project.

## Routing basis

Routes were resolved with `route_advice.py` against the `os/routing` policy, not asserted. The
signal sets and their verdicts:

| Signals | Verdict | Applied to |
|---|---|---|
| `severity:high` + flag `decision-heavy` | **frontier-reasoning / max** | 1.1, 8.3 — a wrong foundational call is the most expensive artifact here |
| `severity:high` + flag `sets-pattern` | **frontier-reasoning / high** | 1.6, 1.7, 2.1, 2.2, 8.1 — first of its class; every follower copies the template |
| `security` + `severity:high` | **frontier-reasoning / high** | 3.3, 4.1, 4.3, 6.1–6.4, 8.4, 8.5, 8.6 — on security posture a subtle miss dwarfs the routing saving |
| `adr` | **frontier-reasoning / high** | 1.10, 3.0, 4.0, 5.0, 6.0, 7.0, 5.3 — an RFC-, ADR- or interface-defining item is decision-heavy by definition |
| `severity:high` | **standard / high** | 1.4, 1.5, 2.3, 2.4, 4.4, 4.5, 5.1, 5.2, 7.1, 7.3, 7.4 |
| `severity:medium` | **standard / medium** | 1.2, 1.3, 1.8, 1.9, 2.5, 3.1, 3.2, 4.2, 7.2, 8.2 |

Two judgment calls, stated because the tool cannot make them: **4.5 `PageRequest` was not labelled
`security`** even though its sort whitelist is an injection defence — its primary job is pagination,
and labelling every item containing one validation as `security` would route the whole roadmap to the
top tier and make the signal meaningless. Conversely **4.1 `JsonMapper` was**, despite being a
three-flag configuration change: getting default typing wrong is a known CVE class, so the
consequence — not the size — earns the route.

At run time, `route_advice.py --check --current-model <id>` compares a step's route against the
session model and prints `ROUTE-OK` / `ROUTE-MISMATCH`. It never switches the model; model authority
is the human's (ADR-0017).

## Traceability

- **RFC → milestone:** the only approved RFC is addressed by Milestone 2 (items 2.1–2.5). Verified by
  `python .eados-core/tools/traceability.py ROADMAP.md RFC-0001`.
- **Recorded refs:** `orchestrator/project.yaml` → `delivery_state.refs`.
- **Spec → item:** every item cites its FR/NFR id from `.spec/d4np-java.md` §2/§6, mirrored in the
  manifest's `spec.functional_reqs` / `spec.nonfunctional_reqs`.
- **Gate:** `roadmap-covers-rfcs` must pass before `plan → scaffold` is legal.

## Spec Coverage Map

Tracks which spec section is fulfilled by which roadmap item(s). Every spec section has a row with at
least one fulfilling item and a status glyph. Legend: ⏳ not started · 🚧 in progress · ✅ done ·
❎ N/A. Sections follow the six-section shape of `docs/specs/01_spec_util.md`. Read with the
`check_spec_map` lint: it requires a non-empty items cell and a recognised glyph per row.

Placed **before** the milestone sections on purpose — `traceability.parse_milestones` would otherwise
absorb this table into Milestone 8's body, the same appendix-bleed that produced a false
`RFC-0001 → M8` edge in the first draft.

| Spec § | Requirement | Roadmap items | Status |
|--------|-------------|---------------|--------|
| §1 | Objective & business context | 1.1, 1.7 | ⏳ |
| §2 | Functional requirements | 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 4.1, 4.2, 4.3, 4.4, 4.5, 5.1, 5.2, 5.3, 6.1, 6.2, 6.3, 7.1, 7.2, 7.3, 7.4 | ⏳ |
| §3 | Non-functional requirements | 1.5, 1.7, 1.8, 2.2, 2.3, 5.1, 5.2, 6.2, 6.4, 8.1, 8.2, 8.3, 8.4, 8.5 | ⏳ |
| §4 | Logical architecture | 1.1, 1.6, 1.7 | ⏳ |
| §5 | Public interface | 2.1, 2.2, 2.3, 2.4, 2.5, 5.3, 8.1 | ⏳ |
| §6 | Verification & test strategy | 1.3, 1.5, 1.8, 2.2, 5.1, 6.1, 8.2, 8.3, 8.6 | ⏳ |

Two rows worth reading closely. **§1** maps to the reactor and the enforcer rather than to a feature:
the objective is *framework independence*, and 1.1 + 1.7 are what make it a build property instead of
a claim (NFR-08). **§3** is the widest row because the NFRs are enforcement, not code — most of them
land in M1 and M8 rather than in the milestone that implements the thing being measured.

---

## Milestone 1 — Project bootstrap & CI

**Goal:** the thinnest slice that compiles, tests and ships under the full quality bar — which for
this project means the ADR-001 Maven reactor exists first, then the build, tests, quality configs and
CI matrix stand up on top of it. **Hard prerequisite for every later milestone**: no component code
lands before the reactor exists (ADR-004).

> Items 1.2–1.5 are the factory's universal day-zero bootstrap (`templates/ROADMAP.md.tmpl`); 1.1 and
> 1.6–1.9 come from `spec.milestone1_items`. The scaffold render and the plan-phase negotiation were
> merged here rather than one replacing the other — the render carried five bootstrap items the
> negotiation lacked, and the negotiation carried the sizes and routes the render has no concept of.
> Title taken from the render so it stays congruent with the generated README milestone table.

- [ ] 1.1 Maven reactor skeleton — parent POM, the nine modules of ADR-001, and the BOM; relocate the rendered tree to become the `core` module's source root, updating every generated `src_main`/`src_test`/`src_bench` reference **and** the `consistency_lint.py` path assertions in the same item (ADR-004). Blocks 1.2–1.9 and all of M2. — size: **L** · route: **frontier-reasoning / max**
- [ ] 1.2 Make the reactor buildable and seed the version constant — `mvn -B clean verify` green on the skeleton, `<version>0.0.0</version>` in the parent `pom.xml` matching the README `Status-v` badge (`version-lockstep`) — size: **S** · route: **standard / medium**
- [ ] 1.3 Wire JUnit 5 + AssertJ with one passing smoke test under `src/test/java/it/d4np/util/` — size: **S** · route: **standard / medium**
- [ ] 1.4 Formatter and linter configs at the reactor root — Spotless (google-java-format), ErrorProne + NullAway + Checkstyle — inherited by every module from the parent POM — size: **M** · route: **standard / high**
- [ ] 1.5 Stand up the CI matrix (Linux / Windows / macOS on Temurin JDK 17 & 21) with build + test + format + lint. This is what makes every later gate actually run — size: **M** · route: **standard / high**
- [ ] 1.6 Per-module `module-info.java` (JPMS) for all nine modules, with the test module's `--add-opens` requirement documented (ADR-001, spec §1.1) — size: **M** · route: **frontier-reasoning / high**
- [ ] 1.7 `maven-enforcer` wired with the ADR-001 banned-dependency and convergence rules — this is what makes NFR-08 a build property rather than a review promise — size: **M** · route: **frontier-reasoning / high**
- [ ] 1.8 JMH (`bench/`) and jcstress (`jcstress/`) harness skeletons so the NFR gates have somewhere to land — size: **S** · route: **standard / medium**
- [x] 1.9 `SECURITY.md` with a coordinated-disclosure policy, and `.github/CODEOWNERS` — **done by the scaffold render**: both artifacts landed with the bootstrap, and CODEOWNERS (`* @danielPoloWork`) now supersedes `ownership.owner` as the live contribution-policy fallback — size: **S** · route: **standard / medium**
- [ ] 1.10 Reconcile the ADR record — `docs/adr/README.md` indexes **two** ADRs (0001, 0002, both generated) while this project has **six**: the four Accepted decisions (module split, error model, JWT library, generated layout) live in `.spec/adr/` as `d4np_java_adr_00N_*.md`, outside the docs system, unindexed, and in a different naming convention. A reader of `docs/adr/` therefore sees two decisions where six were made. The decision this item must settle: renumber them into the `docs/adr/NNNN-kebab.md` convention as 0003–0006 (correct, but invalidates every existing `ADR-001`…`ADR-004` reference in the spec, the manifest, the core-contracts RFC (`docs/rfc/0001-core-contracts.md`) and this file) **or** index them in place and record why the numbering diverges. Not prescribed here — size: **M** · route: **frontier-reasoning / high**

## Milestone 2 — core foundations

**Goal:** the zero-dependency core — creational and structural patterns plus the ADR-002 error model.
**Covered by RFC-0001** (`docs/rfc/0001-core-contracts.md`), which pins the nullability, error and
thread-safety contract for all nine types before any of them is written.

Note on routing: RFC-0001 already made these design decisions, so no M2 item carries
`decision-heavy`. That drop in route cost is the return on contract-first ordering, and it is the
argument for items 3.0–7.0 doing the same for their milestones.

- [ ] 2.1 `Result<T>` sealed `Ok`/`Err`, `ErrorDetail(String code, String message, Throwable cause)`, unchecked `BusinessException` (RFC-0001; FR-17, FR-18, ADR-002) — the shared error vocabulary every later module depends on — size: **M** · route: **frontier-reasoning / high**
- [ ] 2.2 `Lazy<T>` with the jcstress harness proving safe publication; default failure policy **retry**, `memoizingFailures()` opt-in, re-entrant initializer throws (RFC-0001; FR-03, NFR-01) — size: **M** · route: **frontier-reasoning / high**
- [ ] 2.3 `StrategyRegistry<K,S>` with the `find`/`getOrThrow` contract (RFC-0001; FR-04, NFR-04) — size: **S** · route: **standard / high**
- [ ] 2.4 `GenericFactory<T,K>` (duplicate keys rejected, explicit `replace()`) and `FluentBuilder<T>` (template method, accumulated validation, repeatable `build()`) (RFC-0001; FR-01, FR-02) — size: **M** · route: **standard / high**
- [ ] 2.5 `StringCaseConverter` (`Locale.ROOT`, the pinned tokenizer table), `ObjectUtils` (only what `java.util.Objects` lacks), `ResourceLoaderUtils` (`Class<?>` anchor resolution) (RFC-0001; FR-22, FR-23, FR-24) — size: **M** · route: **standard / medium**

## Milestone 3 — core cross-cutting

**Goal:** validation, metrics, and an audit trail that cannot leak secrets.

- [ ] 3.0 **RFC-0002 — cross-cutting core contracts.** Must settle the FR-16 `AuditLog` redaction policy before any code: as specified, `AuditLog` faithfully records secrets and PII into a store typically retained longer and replicated wider than application logs. Needs field-level allowlisting, a `@Sensitive` opt-out, and an explicit never-capture list. Also covers the `Validator` wrapper surface and the metrics-aspect fallback contract. Blocks 3.1–3.3. — size: **M** · route: **frontier-reasoning / high**
- [ ] 3.1 `Validator` over Jakarta Bean Validation 3.x (RFC-0002; FR-14) — size: **S** · route: **standard / medium**
- [ ] 3.2 `ExecutionTimeMetricAspect` with Micrometer-or-log fallback; the AspectJ/Spring binding stays in the adapter (RFC-0002; FR-15) — size: **S** · route: **standard / medium**
- [ ] 3.3 `AuditLog` with a field-level redaction allowlist and `@Sensitive` opt-out (RFC-0002; FR-16) — size: **M** · route: **frontier-reasoning / high**

## Milestone 4 — json and jdbc

**Goal:** persistence and serialization capability modules, each owning its own third-party surface.

- [ ] 4.0 **RFC-0003 — jdbc transaction semantics and json hardening.** Must settle FR-06 (`JdbcTxRunner`: isolation levels, nesting/suspension, savepoints, and which exception types trigger rollback — all currently undefined) and FR-21 (`ObjectMapperExtensions`: operation list, null-vs-absent semantics, collection/generic handling). Blocks 4.2 and 4.4. — size: **M** · route: **frontier-reasoning / high**
- [ ] 4.1 `JsonMapper` hardened configuration — JavaTimeModule, `FAIL_ON_UNKNOWN_PROPERTIES=false`, **no default typing** (the polymorphic-deserialization CVE class configured away) (FR-20) — size: **S** · route: **frontier-reasoning / high**
- [ ] 4.2 `ObjectMapperExtensions` once RFC-0003 specifies the operation set (RFC-0003; FR-21) — size: **S** · route: **standard / medium**
- [ ] 4.3 `SimpleJdbcExecutor` — parameterized statements only, by construction; no string-concatenation overload exists (FR-05, NFR-03) — size: **M** · route: **frontier-reasoning / high**
- [ ] 4.4 `JdbcTxRunner` once RFC-0003 specifies isolation and rollback semantics (RFC-0003; FR-06) — size: **M** · route: **standard / high**
- [ ] 4.5 `PageRequest`/`PageResponse<T>` with whitelist-validated sorting (FR-07) — size: **S** · route: **standard / high**

## Milestone 5 — concurrent

**Goal:** pooling and async execution with the concurrency claims proven, not asserted. Spec §1
rule: *a thread-safety claim without a named jcstress test is not a claim.*

- [ ] 5.0 **RFC-0004 — concurrency contracts.** Pool lifecycle and rejection semantics, MDC propagation guarantees across `CompletableFuture` boundaries, and the `DistributedLock` lease/reentrancy contract every implementation must honour. Blocks 5.1–5.3. — size: **M** · route: **frontier-reasoning / high**
- [ ] 5.1 `CustomThreadPoolFactory` plus the rejection/shutdown jcstress harness; graceful shutdown drains within the configured timeout (RFC-0004; FR-08, NFR-05) — size: **M** · route: **standard / high**
- [ ] 5.2 `AsyncExecutor` with MDC propagation and the submission-overhead benchmark (RFC-0004; FR-09, NFR-02) — size: **M** · route: **standard / high**
- [ ] 5.3 `DistributedLock` — interface only in this module, lease time mandatory, no reentrancy promise (RFC-0004; FR-10, ADR-001). The interface *is* the deliverable: it constrains every future implementation — size: **S** · route: **frontier-reasoning / high**

## Milestone 6 — security

**Goal:** JWT and cryptography under the hardened profiles the ADRs pin, with negative tests as
first-class deliverables.

- [ ] 6.0 **RFC-0005 — security module contracts.** Closes the FR-12 gaps (no AAD support; no per-key message cap driving the rotation trigger) and the NFR-11 gap (no CVSS failing threshold and no suppression-file policy, so the scan is a report rather than a gate). Also decides whether `OutputEncoder` may take a compile dependency on OWASP Java Encoder — a new third-party surface in `d4np-security`, legal under ADR-001 but a decision, not a default. Blocks 6.2–6.4. — size: **M** · route: **frontier-reasoning / high**
- [ ] 6.1 `JwtTokenProvider` on Nimbus with the ADR-003 hardened profile — per-key algorithm allowlist, `alg=none` structurally impossible, mandatory `exp` with configurable skew, JWKS caching — plus negative tests (alg-confusion, alg=none, expired, wrong-audience) and RFC 7515/7519 vectors (ADR-003; FR-11) — size: **L** · route: **frontier-reasoning / high**
- [ ] 6.2 `AesEncryptor` AES-256-GCM with the rotation envelope, AAD, and a per-key message cap; IV-uniqueness property test over 10⁷ operations (RFC-0005; FR-12, NFR-06) — size: **L** · route: **frontier-reasoning / high**
- [ ] 6.3 `OutputEncoder` delegating to OWASP Java Encoder rather than reimplementing context-aware escaping (RFC-0005; FR-13) — size: **S** · route: **frontier-reasoning / high**
- [ ] 6.4 OWASP Dependency-Check CVSS threshold and suppression policy turned into a real gate (RFC-0005; NFR-11) — size: **S** · route: **frontier-reasoning / high**

## Milestone 7 — adapters and test support

**Goal:** confine host coupling to the edge; ship the test-scoped helpers without letting production
code reach them.

- [ ] 7.0 **RFC-0006 — adapter boundary contracts.** The normative RFC 7807 exception→status mapping, the AOP/DI binding surface, the Redisson lock's reentrancy and lease behaviour against the FR-10 interface, and the test module's `--add-opens` contract. Blocks 7.1–7.4. — size: **M** · route: **frontier-reasoning / high**
- [ ] 7.1 `spring-adapter`: `GlobalExceptionHandler` with the RFC 7807 mapping table — `BusinessException`→422, validation→400, `StrategyNotFoundException`→500 + alert, fallback→500 (RFC-0006; FR-19) — size: **M** · route: **standard / high**
- [ ] 7.2 `spring-adapter`: AOP/DI binding for the metrics aspect (RFC-0006; FR-15 binding) — size: **S** · route: **standard / medium**
- [ ] 7.3 `lock-redisson`: Redisson `DistributedLock` implementation with container-based integration tests (RFC-0006; FR-10 implementation) — size: **M** · route: **standard / high**
- [ ] 7.4 `test` module: `ReflectionUtils` with the `--add-opens` contract and the CI check that fails production code importing the test module (RFC-0006; FR-25) — size: **M** · route: **standard / high**

## Milestone 8 — release engineering

**Goal:** make the compatibility and supply-chain contracts enforceable, then publish **1.0.0**.
This is the milestone where `0.x` becomes `1.0.0` and binary compatibility starts being promised.

- [ ] 8.1 `japicmp` per-module binary-compatibility gate against the previous release (NFR-09) — size: **M** · route: **frontier-reasoning / high**
- [ ] 8.2 JaCoCo line coverage ≥ 85% and PIT mutation ≥ 60% enforced on `core` and `security` (NFR-10) — size: **S** · route: **standard / medium**
- [ ] 8.3 Perf gates pinned to a stable runner **or** compared against a stored per-runner baseline, replacing the absolute NFR-01/NFR-06 numbers in CI. This changes how a stated NFR is enforced, so it is a design decision, not plumbing: NFR-01's 2 ns/op sits near JMH's noise floor and hosted runners vary by more than 10%. Until this lands, the Milestone 2 perf numbers are tracked on the reference machine and **advisory in CI** — size: **M** · route: **frontier-reasoning / max**
- [ ] 8.4 GPG-signed Maven Central publication via Sonatype, sources + javadoc JARs, reproducible-build plugin (NFR-12) — size: **L** · route: **frontier-reasoning / high**
- [ ] 8.5 Build provenance/attestation (SLSA or Sigstore) and pinned CI action SHAs (NFR-12 gap) — size: **M** · route: **frontier-reasoning / high**
- [ ] 8.6 Threat model under `docs/security/` via `/eados audit` (STRIDE) — size: **M** · route: **frontier-reasoning / high**
