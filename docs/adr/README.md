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

Both indexes below are complete: **16 decisions, no third home.** `consistency_lint.py`'s `adr-index`
check asserts the bijection and the status of every row in both, so a record cannot drift out of this
page — including if a re-render of the generated docs ever drops the rows.

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
