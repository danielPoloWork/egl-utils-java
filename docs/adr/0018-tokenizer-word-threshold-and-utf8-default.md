# ADR-0018: Resolve FR-22's table-versus-prose contradiction with a two-character word threshold

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.5; [RFC-0001](../rfc/0001-core-contracts.md) §FR-22 (the table, the
  prose rule and the pseudocode that disagree) and §Cross-cutting (why `Locale.ROOT` is
  security-load-bearing); [ADR-0010](0010-single-specification-authority.md) (the precedence ladder
  this record applies *inside* one document); [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md)
  (the precedent for recording a contract that cannot be implemented as written); FR-22, FR-24,
  NFR-07

## Context

RFC-0001 pins FR-22 three times over, and the three do not agree.

1. **A table** of eight inputs with their expected tokens and all three renderings.
2. **A prose rule**: *"an uppercase run of length ≥ 2 followed by a lowercase letter splits **before
   the final uppercase**"*.
3. **Pseudocode**: `else if buf is upper-run and next c is lower: flush(buf); buf.append(c)`.

Seven rows are consistent with all three. **`URLs` is not.** Its uppercase run is `URL` — length 3,
followed by the lowercase letter `s` — so both the prose and the pseudocode split before the final
uppercase, producing `UR` + `Ls` and rendering `urLs`. **The table pins `URLs` as a single token
rendering `urls`.**

This is not a typo that can be read past: the tokens column and all three rendering columns of that
row agree with each other and disagree with the rule stated below them. One of the two has to be
implemented, and whichever is chosen the other is wrong.

Two further FR-22/FR-24 decisions are recorded here because they are the same kind — a contract
sentence whose *reason* has to survive into the code or someone will "simplify" it away.

## Decision

**The table wins, and the tokenizer splits an uppercase run only when at least two lowercase
characters follow.** One character is not a word; it is a suffix.

That threshold is the unique rule that reproduces **all eight rows**, including `HTTPServer` →
`HTTP|Server` (five lowercase follow) and `URLs` → `URLs` (one lowercase follows). The constant is
named `LOWERCASE_RUN_THAT_MAKES_A_WORD` in the source rather than inlined as `2`, so the next reader
finds this record instead of a magic number.

Supporting decisions, restated so they are not re-litigated in review:

- **All case mapping uses `Locale.ROOT`**, never the default-locale `toLowerCase()` / `toUpperCase()`
  overloads.
- **`ResourceLoaderUtils`' charset default is `StandardCharsets.UTF_8`, written out**, never
  `Charset.defaultCharset()`.
- **A resource name containing `..` is rejected** rather than normalized away.

## Alternatives Considered

- **Implement the prose rule and let `URLs` render `urLs`.** The literal reading of the sentence, and
  defensible on the grounds that a rule is more general than a table. Rejected because **the table is
  the testable artifact**: it is what a test transcribes, what a reviewer checks against, and what a
  consumer reads to predict output. A rule that contradicts its own worked examples is a rule nobody
  will trust, and `urLs` is visibly wrong to any reader — which is presumably why the row was written
  that way.
- **Implement both: a rule with `URLs` special-cased.** Rejected as the worst of the two. A special
  case for one input is an acronym dictionary with one entry, and RFC-0001 explicitly refuses the
  acronym-dictionary direction ("the alternative is an acronym dictionary in a zero-dependency
  module"). The threshold generalises; a hard-coded string does not.
- **Amend RFC-0001's prose in this PR to match the table.** Deferred, not rejected, and this is the
  same shape ADR-0012 handled: under ADR-0010 an RFC outranks the spec, but nothing yet says which
  half of an RFC outranks the other half. Editing an approved RFC to match one's implementation is
  the wrong direction of travel for a governance system that exists to stop exactly that. The record
  here states the resolution; **RFC-0002 (item 3.0) should carry the one-line amendment**, since it is
  already amending contracts and has an owner.
- **Split on a single lowercase but only when it is not at end-of-string.** This also produces `URLs`
  correctly, by treating a trailing letter as a suffix. Rejected as narrower for no benefit: it gets
  `HTTPId` (mid-string, one lowercase, then nothing) wrong in the other direction and needs a second
  clause for it, whereas the length threshold handles both with one number.
- **`Charset.defaultCharset()` for `readString`.** Rejected on NFR-07's own baseline: JEP 400 makes
  the default UTF-8 only from **JDK 18**, and this project publishes against **17**, so a packaged
  file would decode one way on a developer's machine and another in a container. A test asserts the
  UTF-8 and ISO-8859-1 decodings of the same fixture differ, so a regression to the platform default
  fails rather than passing on whichever machine ran it.

## Consequences

- **Every row of RFC-0001's table is a transcribed test**, not a paraphrase, and the tokenizer is
  asserted separately from the three renderings — otherwise a tokenizer bug and a renderer bug can
  cancel out and leave every end-to-end conversion green.
- **The threshold has a visible cost, and it is pinned by a test rather than left to be discovered:**
  `HTTPId` tokenizes as one word and renders `httpid`, because `d` is a single lowercase character
  exactly as `s` is in `URLs`. `HTTPIdentity` renders `http_identity`. That asymmetry is the price of
  satisfying the table without a dictionary, and a reader debugging a converted identifier should find
  it stated rather than infer it.
- **The acronym round trip stays a documented non-guarantee.** `HTTPServer → http_server → httpServer`
  loses the original capitalisation, and a test asserts the *non*-guarantee so nobody promotes it to a
  promise by accident.
- **The `Locale.ROOT` rule is tested by changing the default locale**, not by reading the source. The
  test sets `Locale.TR` and asserts `IDToken → id_token`; under the default-locale overload it would
  be `ıd_token` with a dotless ı, and the identifier would stop matching the key it was derived from —
  silently, and only on hosts with that locale. RFC-0001 §Cross-cutting calls this
  security-load-bearing, and the compliance register now carries it as a control.
- **`..` rejection is tested on all three entry points**, not just `open`. Rejecting rather than
  normalizing is the cheaper thing to reason about where a name is built from caller-supplied input:
  a normalizer has to be proven airtight, a refusal does not.
- **Resource resolution is anchored on a caller-supplied class**, per RFC-0001, and the fixtures in
  this item live beside the test class on purpose — a fixture elsewhere would exercise a different
  arrangement than the one the Javadoc tells callers to use.
- **The specification and the RFC still disagree on paper.** Nothing in this PR edits RFC-0001, so a
  reader who finds the prose rule before this record will still be misled for one hop. That is
  deliberate, and item 3.0 owns closing it.

## References

- FR-22, FR-23, FR-24 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) — all three are
  `[GAP]` lines resolved by RFC-0001.
- [RFC-0001](../rfc/0001-core-contracts.md) §FR-22 (table, prose, and the `tokenize` pseudocode),
  §FR-24, §Cross-cutting.
- JEP 400 (UTF-8 by Default) — why the charset default is written out at a JDK 17 baseline.
- `d4np-core/src/main/java/it/d4np/utils/{StringCaseConverter,ObjectUtils,ResourceLoaderUtils,ResourceNotFoundException}.java`
  and their tests.
