# 2026-08-01 — the last three core types, and Milestone 2 closes (ROADMAP item 2.5)

**Milestone 2, item 2.5 — and the milestone.** Fourth item closed today. The headline is not the code
but a contradiction found inside the contract that was supposed to make this item mechanical.

## What changed

`d4np-core` gains `StringCaseConverter`, `ObjectUtils`, `ResourceLoaderUtils` and
`ResourceNotFoundException`, plus 66 tests (124 → **190**), **ADR-0018**, and compliance controls
**C-03** and **C-04**. **Milestone 2 is complete: all five items closed.**

No jcstress harness and no benchmark, both correct rather than omissions — all three types are
stateless statics making no thread-safety claim for a harness to prove, and no NFR names any of them.

## Where the project stands

**Milestone 2 is done.** `d4np-core` now holds the full RFC-0001 surface: the error vocabulary,
`Lazy`, `StrategyRegistry`, `GenericFactory`, `FluentBuilder`, and these three utilities. Milestone 3
is next and is **blocked on item 3.0**, the RFC-0002 that must settle the `AuditLog` redaction policy
before any M3 code — and which now also carries two inherited amendments (below).

## What the next session needs to know

- **RFC-0001 contradicts itself on FR-22, and item 3.0 owes the amendment.** The RFC pins FR-22 as a
  table, a prose rule *and* pseudocode. For `URLs` the prose and pseudocode both split `UR|Ls` and
  render `urLs`; the table pins one token rendering `urls`. **The table won** — it is what tests
  transcribe and consumers read — and the rule that reproduces all eight rows is *split only when at
  least two lowercase characters follow*. **RFC-0001 was deliberately not edited**: nothing yet says
  which half of an RFC outranks the other half, and amending an approved RFC to match one's own
  implementation is the wrong direction for a governance system meant to prevent that. Item 3.0 now
  owes **two** RFC amendments — this one and ADR-0012's `Result<Void>` question.
- **The threshold has a visible cost, and it is pinned by a test.** `HTTPId` renders `httpid` because
  `d` is one lowercase character exactly as `s` is in `URLs`; `HTTPIdentity` renders `http_identity`.
  Anyone tempted to "fix" the first case should read ADR-0018 before touching the tokenizer.
- **A test expectation was wrong and the implementation was right.** `toKebab("HTTPId")` was expected
  to give `http-id`, which contradicts the very threshold that makes `URLs` work. Worth remembering as
  the direction that is easy to get backwards under time pressure.
- **`Locale.ROOT` is now a compliance control (C-03) and it is NOT mechanically enforced.** The test
  sets the default locale to `tr-TR` and reproduces the failure, but no gate forbids a future call
  site from using the default-locale overload. **An ErrorProne pattern is the way to close that**, and
  it would be cheap — worth folding into item 8.2 when the jacoco/pit half of the quality job lands.
- **`ObjectUtils`' selection rule is asserted reflectively**, comparing declared method names against
  `java.util.Objects`. That is what stops the class growing a second spelling of `requireNonNull`;
  keep it if the class is extended.
- **Test resources now exist under `d4np-core/src/test/resources/`** and are read through the module
  patch without any `--add-opens` or descriptor change — the anchor rule works as RFC-0001 says. The
  `latin1.txt` fixture holds byte `0xE9` on purpose; do not let a tool "fix" its encoding, because it
  is what proves the UTF-8 default is a decision rather than the platform default.

## Verification

Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, Windows 10 Pro 19045 (i5-6600K, 4 cores):
`clean verify` (`Tests run: 190`) on both; `-Pjmh,jcstress verify` on both (zero failures); CI's real
quality goals `spotless:check -pl '!d4np-bom'`, `checkstyle:check` and `validate` on both; and
`python tools/consistency_lint.py`.
