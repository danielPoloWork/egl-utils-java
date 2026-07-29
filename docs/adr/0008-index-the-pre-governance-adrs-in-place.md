# ADR-0008: Index the pre-governance ADRs in place, keeping their ids

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** Daniel Polo (maintainer — settled the route on 2026-07-29), agent (senior project
  architect)
- **Related:** ROADMAP items 1.10, 1.12; ADR-001, ADR-002, ADR-003, ADR-004 (the imported four);
  ADR-0003 (reactor layout), ADR-0006 (per-module policy); RFC-0001 follow-ups;
  `tools/consistency_lint.py` → `check_adr_index`

## Context

Eleven architecture decisions had been made and the index listed seven. The four missing ones —
module split, error model, JWT library, generated layout — were taken on **2026-07-14** during the
specification's v2.0 design review, twelve days before the EADOS governance layer generated
`docs/adr/`. They live in `.spec/adr/` as `d4np_java_adr_00N_*.md`, carry three-digit ids
(`ADR-001`…`ADR-004`), and were reviewed and accepted with the spec. They are this project's own
decisions, not inherited third-party ones, and they are binding today: `ADR-001` is cited by every
module POM and every `module-info.java` as the authority for the dependency graph.

So a reader of `docs/adr/` saw a partial record, and the repository carried two id shapes one zero
apart — `ADR-001` (module split) versus `ADR-0001` (record architecture decisions).

Three constraints, all measured rather than estimated, shaped the answer.

**The three-digit ids are load-bearing in 45 files.** A scan of the tracked tree found **183
references** to `ADR-001`…`ADR-004`: 124 to `ADR-001` alone, across 10 POMs, 8 module descriptors,
`orchestrator/project.yaml` (28), `ROADMAP.md` (22), the imported spec (16), `docs/rfc/0001` (15),
`docs/specs/01_spec_utils.md` (8) and four already-Accepted docs ADRs. A further **~53 path
citations** point at `.spec/adr/…`, about 30 of them *inside* Accepted ADRs (0003, 0005, 0006). The
manifest's `linter:` string literally contains "maven-enforcer for the ADR-001 dependency rules",
which renders into `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `README.md`, the PR template and
`docs/development/local-build.md` — so a renumbering is a manifest change plus a re-render, not a
find-and-replace.

**The merged history cites the old ids and cannot be edited.** `main` carries
`a76ea4c build: enforce the ADR-001 dependency policy per module (item 1.7)`, and PR #21's title says
the same. Renumbering would leave that string pointing at a *dead id that collides with a live one*:
a reader who resolves "ADR-001" to `ADR-0001` lands on "Record architecture decisions" and is silently
wrong. Tidying the directory would therefore have made the historical record less navigable, not more.

**The plan recorded in ADR-004 had already become impossible.** Its closing line — accepted by the
owner on 2026-07-26 — reads: *"All four normalise to `docs/adr/000N-*.md` when scaffold moves them
into the generated repository."* Scaffold did not move them, and the generated docs system immediately
claimed `0001` and `0002` for its own bootstrap records. `ADR-001` can therefore never become
`ADR-0001`; the only migration still available is renumbering to *different* numbers, which is exactly
the identifier break the note was trying to avoid. This is also the fifth occurrence of lesson
**L-0005** (a number range reserved in advance does not hold): ADR-0004, 0005, 0006 and 0007 each took
a number some earlier note had promised to item 1.10.

## Decision

**The imported four stay where they are, keep their ids and their filenames, and are indexed from
`docs/adr/README.md`.** The maintainer settled the route on 2026-07-29, after the three options were
costed.

Concretely:

1. `docs/adr/README.md` carries **two indexes** — governed (four-digit) and imported (three-digit) —
   and is the single complete index of all twelve decisions. The imported rows link into `.spec/adr/`
   and name what carried each decision forward, so a reader can navigate between the schemes.
2. A **"Two numbering schemes, and why"** section states that the digit count is semantic, that
   `ADR-001 ≠ ADR-0001`, and that **the three-digit series is closed**: every new decision takes the
   next four-digit number, claimed at the moment it lands.
3. `.spec/` is documented as the **intake area** — provenance, not a second docs system. It is listed
   in `docs/README.md`'s layout table, which had never mentioned it.
4. `consistency_lint.py`'s `adr-index` check is extended to make the index a gate rather than a
   promise: it now also asserts the imported set's bijection with the index, id-to-filename agreement,
   sequential three-digit numbering, and — for **both** schemes — that the status in the index matches
   the status inside the record.
5. AGENTS.md §7 states the citation convention, since it is the contract every agent reads before
   touching a record.

**What this decision does not do:** it does not renumber, move, or edit the imported records, and it
does not resolve which *specification* document is authoritative when `.spec/d4np-java.md` and
`docs/specs/01_spec_utils.md` disagree. That question is the same two-homes shape but a different
document, is owed the owner's call by RFC-0001's follow-ups, and is now tracked as **ROADMAP item
1.12** rather than being answered here by implication.

## Alternatives Considered

- **Renumber into the docs convention as `docs/adr/0008`–`0011`.** The tidiest end state: one scheme,
  one directory, covered by the existing sequential-numbering check. Rejected on cost and on truth.
  Cost: 183 id references in 45 files, ~53 path citations, citations rewritten inside four Accepted
  ADRs, a manifest string change plus a re-render of six generated files. Truth: the immutable git
  history and merged PR titles would cite a dead id that collides with a live one, so the change needs
  a permanent alias table and stub files to stay navigable — machinery whose only purpose is to undo
  the confusion the change introduced. (This ADR would also have taken `0008` either way, since the
  decision itself needs a record.)
- **Move the files into `docs/adr/imported/` while keeping the ids 001–004.** One home inside the
  governed tree at a third of the cost, and no id reference breaks. Rejected because it invalidates
  the ~53 path citations — about 30 of them inside Accepted ADRs (0003 cites `.spec/adr/…` thirteen
  times) — and breaks the imported spec's own internal links, which would need either edits to an
  archived draft or stub files left behind at the old paths. Both outcomes reintroduce the second home
  the move was meant to remove, and every citation that is correct today would have to be rewritten to
  stay correct.
- **Leave it as it was and note the gap in the ROADMAP.** Rejected: the defect is not the file
  location, it is that the index a reader consults was incomplete and nothing prevented that. Prose
  noting an incomplete index does not complete it.
- **A third scheme (e.g. `SPEC-ADR-001`) to disambiguate.** Rejected as strictly worse: it invents a
  second name for records that already have one, so every citation becomes ambiguous between three
  forms instead of two.
- **Renumber the *docs* ADRs to free `0001`–`0004` for the imported set.** Not seriously entertained,
  recorded so it is not re-proposed: it breaks a larger, newer set of references to satisfy the older
  one, and `docs/adr/0002` is already cited as superseded by `0003`.

## Consequences

- **Two id shapes coexist in live documentation, permanently.** That is the accepted cost. The
  mitigation is that the distinction is now stated where a reader meets it (the index), enforced where
  it can be (the lint), and repeated in the contract (AGENTS.md §7). Anyone writing a new reference
  has one rule: three digits is the closed imported series; new work is four digits.
- **The index is now a gate.** `adr-index` fails if an imported record is missing from the index, if a
  row links to a file that does not exist, if an id disagrees with its filename, if the three-digit
  series gains a gap, or if a status in the index disagrees with the status inside the record.
  Verified against five deliberate breakages (see below), so the check is not vacuous.
- **Status drift is now caught in both schemes, which it was not before.** The previous check verified
  presence and sequential numbering only, so an ADR marked `Superseded` in its own header could sit in
  the index as `Accepted` indefinitely. `ADR-0002` is the live case that would have hidden it.
- **A re-render can no longer silently shrink the record.** `docs/adr/README.md` is a generated file
  and the renderer emits only the `0001`/`0002` rows, so a re-render deletes every row added since —
  risk **R-07**. That was already true for five rows before this change; what is new is that the loss
  now fails `consistency_lint.py` immediately instead of being noticed by a reader months later.
- **Zero references changed.** No POM, module descriptor, manifest entry, spec section, RFC or
  Accepted ADR was edited to accommodate this decision, and nothing in git history became stale. That
  property is the reason the route was chosen.
- **ADR-004's file-naming note is superseded, its decision is not.** Only the closing sentence about
  normalising filenames is void; Option A of ADR-004 (scaffold flat, then item 1.1 establishes the
  reactor) stands and was executed. The note is left in place rather than edited — an Accepted record
  is superseded, not rewritten — and the index row says so.
- **If the imported set ever grows, the series is still closed.** A fifth pre-governance decision
  cannot appear (the review that produced them is over), so `ADR-005` must never be issued; the lint's
  sequential check would accept `005`, but the convention section forbids it. That is the one rule here
  held by prose rather than by a gate, stated explicitly so the gap is known.

## References

- `.spec/adr/d4np_java_adr_001_module_split.md`, `…_002_error_model.md`, `…_003_jwt_library.md`,
  `…_004_generated_layout.md` — the four imported records, unchanged by this decision.
- ADR-004 §Consequences, final bullet — the superseded file-naming expectation.
- `tools/consistency_lint.py` → `check_adr_index` (both schemes, status congruence).
- ROADMAP item 1.10 (this item), item 1.12 (the specification-authority question), lesson L-0005.
- Reference counts produced by scanning `git ls-files` on 2026-07-29 at commit `4e2bf62`:
  183 three-digit id references in 45 files; ~53 `.spec/adr` path citations.
