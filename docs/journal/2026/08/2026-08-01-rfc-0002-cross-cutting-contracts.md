# 2026-08-01 — RFC-0002, and the audit trail that would have leaked (ROADMAP item 3.0)

**Milestone 3, item 3.0 — the first design-only item since RFC-0001.** Fifth checkpoint today. The
deliverable is a contract, not a feature. It was drafted unapproved and **approved by the owner in
session on 2026-08-02**, in a separate commit from the drafting.

## What changed

[RFC-0002](../../../rfc/0002-cross-cutting-contracts.md) pins the FR-16 audit redaction policy, the
FR-14 `Validator` surface and the FR-15 metrics fallback contract; **ADR-0019** settles the
`Result<Void>` question ADR-0012 deferred here by name, minting `Unit`; `Result.ok()` and `Unit` land
in core with 6 tests (190 → **196**). Manifest FR-16 gains a `[RESOLVED by RFC-0002]` pointer, FR-17's
text is amended and the spec re-rendered. RFC-0001 gains a header note recording two amendments.

## Where the project stands

Milestone 2 is released-ready; Milestone 3 has **1 of 4 items closed**, and **3.1–3.3 are unblocked** —
RFC-0002 is `Accepted` on the owner's authority (2026-08-02).

## What the next session needs to know

- **RFC-0002 is `Accepted`, but on owner authority alone — no peer-review round ran.** That is the
  assurance it carries and the assurance it does not, and FR-16 is a security control whose threat
  model the security-auditor owns. The draft-then-flip sequence is worth repeating for the next RFC:
  author it `Proposed` with an empty `approved-by:` and flip only on the owner's word, in a separate
  commit, so the two acts are visible as two acts in the history.
- **The audit sink has no trust boundary in the threat model.** B1–B6 cover consumer→library,
  library→host framework, library→external services, key material, supply chain and test scope.
  "Library → host-supplied audit store" is none of them, and it is now the widest-retention sink in
  the system. **Filed for item 8.6**; RFC-0002 deliberately does not edit `threat-model.md`, which the
  security-auditor role owns.
- **Two compliance controls are owed but not yet registered** — the redaction policy (item 3.3) and
  the validation-message rule against C-01 (item 3.1). The register's evidence column takes tests, and
  there are none until that code lands, so adding rows now would have been a claim without a gate.
- **Item 3.3 inherits a fail-fast obligation that is easy to under-build.** A component that is
  neither a simple value nor itself `@Audited` must fail at first capture, naming the type and the
  component. Silently skipping it looks identical to a correctly-configured type in every test.
- **`StringCaseConverter` is now a security dependency, not just a utility.** FR-16 normalises field
  names through it before matching the never-capture list, so C-03's `Locale.ROOT` rule is what stops
  `API_KEY` becoming `apı_key` on a Turkish-locale JVM, missing the match, and writing the key to the
  audit store in clear. **That is the strongest argument yet for closing C-03's stated gap with an
  ErrorProne pattern forbidding the default-locale overloads** — worth folding into item 8.2.
- **A re-render must be applied file-by-file.** `render.py` regenerates 44 templates, and a wholesale
  copy would have overwritten **16** hand-maintained files (`README.md`, `ROADMAP.md`, `AGENTS.md`,
  `docs/adr/README.md`, the threat model, …) with pristine generated versions. That is risk **R-07**,
  exactly as ADR-0008 predicted. Only `docs/specs/01_spec_utils.md` was copied, and its diff was
  checked to be the two intended lines before applying.
- **ADR-0012's deferral mechanism worked, and is worth reusing.** It recorded an unimplementable spec
  sentence, costed three options, refused to guess, and named the item that would decide. Four
  milestones later that item existed and the question was answered with evidence — including the
  honest finding that **neither named call site actually forced it**, so the decision rests on the
  error model's completeness instead. Defer with a named owner; do not defer twice.

## Verification

Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, Windows 10 Pro 19045: `clean verify`
(`Tests run: 196`) on both; `-Pjmh,jcstress verify` on both; CI's real quality goals
`spotless:check -pl '!d4np-bom'`, `checkstyle:check` and `validate` on both; and
`python tools/consistency_lint.py`. The spec re-render was verified idempotent against the current
file *before* the manifest was touched, so the two-line delta afterwards was known to be the edit and
nothing else.
