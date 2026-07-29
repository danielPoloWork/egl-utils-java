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

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-adopt-cross-language-source-layout.md) | Adopt the cross-language source layout | Superseded by ADR-0003 |
| [0003](0003-maven-reactor-layout.md) | Adopt the Maven reactor layout, superseding the flat source tree | Accepted |
| [0004](0004-declare-line-endings-and-cross-platform-format-checks.md) | Declare line endings in `.gitattributes`, and run format checks on more than one platform | Accepted |
| [0005](0005-jpms-module-names-and-export-less-descriptors.md) | JPMS module names, and descriptors that land before the code they describe | Accepted |
| [0006](0006-enforce-the-dependency-policy-per-module.md) | Enforce the ADR-001 dependency policy per module, with default-deny where the contract is "clean" | Accepted |
| [0007](0007-nfr-harnesses-as-test-scope-profiles.md) | Run the JMH and jcstress harnesses from profile-activated, test-scope source roots | Accepted |
