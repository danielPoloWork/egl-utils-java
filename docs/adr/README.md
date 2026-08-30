# Architecture Decision Records

One numbered Markdown file per decision, in the lightweight
[Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
format. Numbering is sequential and never reused or renumbered. Template:
[`template.md`](template.md).

Open an ADR when a choice affects the public surface or compatibility, when two reasonable
options exist and the rationale is non-obvious, when a **design pattern** is adopted, or
when superseding a prior decision. Do **not** open one for routine implementation details
or trivially reversible choices.

Status transitions: `Proposed` → `Accepted` → (`Superseded by ADR-XXXX` | `Deprecated`).

## Two numbering schemes, and why

This project's decisions were taken under two regimes, so ids come in two shapes — and **the shape is
load-bearing, not cosmetic**:

| Id shape | What it means | Where the record lives |
|---|---|---|
| `ADR-0001`, four digits | a decision taken under this docs system (2026-07-26 onward) | `docs/adr/NNNN-kebab.md` |
| `ADR-001`, three digits | one of the four **pre-governance** product decisions, taken 2026-07-14 during the specification's v2.0 design review | `.spec/adr/d4np_java_adr_00N_*.md` |

**`ADR-001` and `ADR-0001` are different decisions, one zero apart.** Read the digit count before you
follow a reference: three digits means the module split, four means "record architecture decisions".

ROADMAP item 1.10 settled the divergence by **indexing the imported four here instead of renumbering
them** — the reasoning, the alternatives and the measurements are in
[ADR-0008](0008-index-the-pre-governance-adrs-in-place.md). In short: at the time of the decision
**183 references to the three-digit ids existed across 45 files**, including every module POM and
every `module-info.java`, and the merged git history cites them verbatim
(`build: enforce the ADR-001 dependency policy per module`). An ADR id is a stable identifier, not an
address; renumbering would have traded that stability for directory tidiness. The imported four keep
their ids and their files, and this page becomes the one complete index.

**The three-digit series is closed.** Every new decision takes the next four-digit number, claimed at
the moment it lands — reserving a number range in advance was tried four times and missed four times
(ADR-0004, ADR-0005, ADR-0006, ADR-0007 each took a number some earlier note had promised item 1.10).

Both indexes below are complete: **43 decisions, no third home.** `consistency_lint.py`'s `adr-index`
check asserts the bijection and the status of every row in both, so a record cannot drift out of this
page — including if a re-render of the generated docs ever drops the rows. (This count read **24**
until item 4.0, having missed ADR-0021 and ADR-0022: both added their rows below without incrementing
the total, because the `adr-index` check verifies the bijection and every status but **not** this
number. Corrected there rather than left for a reader to recount, and noted so the drift is visible
instead of silently absorbed. Item 4.1 took it from 27 to **29**, item 4.2 to **31**, item 4.3 to
**33**, item 4.4 to **36**, item 4.5 to **38**, item 5.1 to **39**, item 5.2 to **40**, item 5.3 to **42** and item 6.1 to **43** — the count is maintained by
hand on every pass, which is the standing reason it drifts.)

## Index — decisions taken under this docs system

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-adopt-cross-language-source-layout.md) | Adopt the cross-language source layout | Superseded by ADR-0003 |
| [0003](0003-maven-reactor-layout.md) | Adopt the Maven reactor layout, superseding the flat source tree | Accepted |
| [0004](0004-declare-line-endings-and-cross-platform-format-checks.md) | Declare line endings in `.gitattributes`, and run format checks on more than one platform | Accepted |
| [0005](0005-jpms-module-names-and-export-less-descriptors.md) | JPMS module names, and descriptors that land before the code they describe | Accepted |
| [0006](0006-enforce-the-dependency-policy-per-module.md) | Enforce the ADR-001 dependency policy per module, with default-deny where the contract is "clean" | Accepted |
| [0007](0007-nfr-harnesses-as-test-scope-profiles.md) | Run the JMH and jcstress harnesses from profile-activated, test-scope source roots | Accepted |
| [0008](0008-index-the-pre-governance-adrs-in-place.md) | Index the pre-governance ADRs in place, keeping their ids | Accepted |
| [0009](0009-errorprone-nullaway-on-jdk-21-cells.md) | Run ErrorProne + NullAway on the JDK 21+ cells, and enforce warnings-as-errors everywhere | Accepted |
| [0010](0010-single-specification-authority.md) | One specification authority — the manifest's `spec` block, published as `docs/specs/01_spec_utils.md` | Accepted |
| [0011](0011-declare-the-nullability-annotation-in-core.md) | Declare the nullability annotation in core instead of depending on one | Accepted |
| [0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) | Enforce `Ok(null)` mechanically, and leave a successful `Result<Void>` unconstructible | Accepted |
| [0013](0013-lazy-initialization-by-double-checked-volatile.md) | Publish `Lazy<T>` through double-checked `volatile`, behind a private monitor | Accepted |
| [0014](0014-log-through-the-jdk-system-logger.md) | Log through `java.lang.System.Logger`, and make the call testable by injection | Accepted |
| [0015](0015-strategy-registry-last-write-wins.md) | Keep `StrategyNotFoundException` outside the `BusinessException` hierarchy, and carry its keys as text | Accepted |
| [0016](0016-generic-factory-atomic-duplicate-rejection.md) | Make `GenericFactory` thread-safe, and reject duplicates atomically | Accepted |
| [0017](0017-fluent-builder-accumulated-validation.md) | Give `FluentBuilder` a second accumulator, and keep the defensive-copy rule a documented obligation | Accepted |
| [0018](0018-tokenizer-word-threshold-and-utf8-default.md) | Resolve FR-22's table-versus-prose contradiction with a two-character word threshold | Accepted |
| [0019](0019-mint-unit-for-the-void-success.md) | Mint `Unit` so a `Result` can succeed without a payload | Accepted |
| [0020](0020-render-violations-from-the-message-template.md) | Render constraint violations from the message template, never the interpolated message | Accepted |
| [0021](0021-time-through-an-advice-body-core-can-own.md) | Ship FR-15 as an advice body core can own, and bound what instrumentation is allowed to break | Accepted |
| [0022](0022-redact-at-capture-behind-a-typed-event.md) | Redact at capture, behind an event no caller can mint | Accepted |
| [0023](0023-the-owner-approves-this-projects-rfcs.md) | The owner approves this project's RFCs, not the `tech-lead` the protocol names | Accepted |
| [0024](0024-take-a-jackson-type-in-one-signature.md) | Take a Jackson type in one signature, and keep the module edge non-transitive | Accepted |
| [0025](0025-render-java-time-as-iso-8601.md) | Render `java.time` as ISO-8601, a fifth setting FR-20 does not name | Accepted |
| [0026](0026-rewrite-jacksons-unchecked-conversion-failure.md) | Catch and rewrite Jackson's *unchecked* conversion failure, which the wrapping rule does not reach | Accepted |
| [0027](0027-a-partial-update-renders-names-not-values.md) | `PartialUpdate` renders its property names and never its value — which is why it is a class and not a record | Accepted |
| [0028](0028-the-fr-05-operation-set-and-what-it-refuses.md) | The FR-05 operation set — three operations, and a single-row query that refuses a second row | Accepted |
| [0029](0029-annotate-the-varargs-so-a-null-parameter-compiles.md) | Annotate the bind-parameter varargs `@Nullable`, knowing it says the wrong thing | Accepted |
| [0030](0030-the-two-channels-out-of-a-transaction-body.md) | The two channels out of a transaction body — what propagates, and why `null` is not a value | Accepted |
| [0031](0031-one-nesting-detector-for-the-whole-jvm.md) | One nesting detector for the whole JVM, so two pools cannot be nested either | Accepted |
| [0032](0032-name-the-void-transaction-form-differently.md) | Name the void transaction form differently, because the overload pair does not compile | Accepted |
| [0033](0033-publish-no-accessor-for-the-unvalidated-sort.md) | Publish no accessor for the unvalidated sort, so the allowlist cannot be skipped | Accepted |
| [0034](0034-mint-a-validation-failure-from-outside-core.md) | Mint a validation failure from outside core, and bound what it is allowed to say | Accepted |
| [0035](0035-declare-autocloseable-so-the-override-is-legal.md) | Declare `AutoCloseable` explicitly, because it is the guard rather than the method | Accepted |
| [0036](0036-carry-context-through-an-spi-that-restores.md) | Carry context through an SPI whose scope restores, never clears | Accepted |
| [0037](0037-a-fencing-token-that-restarts-is-worse-than-none.md) | A fencing token that restarts is worse than none, so empty is a required answer | Accepted |
| [0038](0038-refuse-the-convenience-form-the-rfc-sanctioned.md) | Refuse the convenience form RFC-0004 sanctioned, because its return type cannot say what happened | Accepted |
| [0039](0039-detect-nimbus-shaded-gson-jpms-failure-at-construction.md) | Keep Nimbus at the latest, and turn its JPMS failure into a startup error | Accepted |

## Index — imported decisions (pre-governance, 2026-07-14)

These are this project's own architecture decisions, not third-party ones. They predate the docs
system by twelve days, were reviewed and accepted with the specification, and are **binding**: every
`ADR-001` reference in a POM, a module descriptor or the manifest points here.

| ADR | Title | Status | Record | Carried forward by |
|-----|-------|--------|--------|--------------------|
| ADR-001 | Multi-module split & dependency policy — framework independence made structural | Accepted | [`d4np_java_adr_001_module_split.md`](../../.spec/adr/d4np_java_adr_001_module_split.md) | [ADR-0003](0003-maven-reactor-layout.md) built the reactor; [ADR-0006](0006-enforce-the-dependency-policy-per-module.md) made the policy a build gate; spec §3 |
| ADR-002 | Error model — `Result<T>` for expected outcomes, unchecked `BusinessException` | Accepted | [`d4np_java_adr_002_error_model.md`](../../.spec/adr/d4np_java_adr_002_error_model.md) | [RFC-0001](../rfc/0001-core-contracts.md) pins the contracts; ROADMAP item 2.1 implemented them, with [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) settling the null boundary this record left implicit |
| ADR-003 | JWT — Nimbus JOSE+JWT selected for `JwtTokenProvider` | Accepted | [`d4np_java_adr_003_jwt_library.md`](../../.spec/adr/d4np_java_adr_003_jwt_library.md) | [threat model](../security/threat-model.md); risk **R-06** (JWKS trust posture) is still open; ROADMAP item 6.1 |
| ADR-004 | Generated repository layout — reconciling the EADOS flat source tree with the Maven reactor | Accepted | [`d4np_java_adr_004_generated_layout.md`](../../.spec/adr/d4np_java_adr_004_generated_layout.md) | [ADR-0003](0003-maven-reactor-layout.md) executed it in ROADMAP item 1.1; its closing file-naming note is superseded by [ADR-0008](0008-index-the-pre-governance-adrs-in-place.md) |

`.spec/` is the **intake area**: the imported specification draft (`.spec/d4np-java.md`, v2.0) and
these four records. It is provenance, not a second docs system — and which document is authoritative
when the imported draft and [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) disagree is
**no longer open**: ROADMAP item 1.12 settled it in
[ADR-0010](0010-single-specification-authority.md), which makes the draft superseded provenance and
the manifest's `spec.*` block the source of record. (This paragraph described it as an open question
until item 2.1; 1.12 added its ADR row above without correcting the prose.)
