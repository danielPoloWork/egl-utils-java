# ADR-0010: One specification authority — the manifest's `spec` block, published as `docs/specs/01_spec_utils.md`

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP items 1.12, 1.10; ADR-0008 (`.spec/` is the intake area); RFC-0001 follow-ups;
  `tools/consistency_lint.py` → `check_spec_authority`; risk **R-07** (render drift)

## Context

Two documents described the same product and neither was subordinate to the other:

- **`.spec/d4np-java.md`** — the imported v2.0 "Reviewed draft" of 2026-07-14, nine sections, requirements
  numbered as *items 1–25*.
- **`docs/specs/01_spec_utils.md`** — the six-section rendered specification, labelled *"Frozen
  contract: diverging implementation updates this spec in the same PR or adds an ADR superseding the
  relevant section."*

`ROADMAP.md`'s Traceability section claimed the FR/NFR ids come from the imported draft ("every item
cites its FR/NFR id from `.spec/d4np-java.md` §2/§6, mirrored in the manifest"). RFC-0001's follow-ups
recorded the same ambiguity from the other side and explicitly owed the owner a call on which document
is authoritative. Item 1.10 fixed the ADR half of this two-homes problem and deliberately left the
specification half open; this is that half.

**Three measurements collapsed the choice the roadmap posed.**

1. **The imported draft cannot be the id source — it has no `FR-` ids at all.** Scanning it returns
   **zero** `FR-` matches and only **`NFR-01`…`NFR-06`** (its §6 performance table). The
   `FR-01`…`FR-25` / `NFR-01`…`NFR-12` vocabulary that the ROADMAP, the POMs, the RFC, the threat model
   and the compliance register all cite was **minted during Phase-5 intake**, not imported. The numbering
   coincides with the draft's items 1–25, which is why nobody noticed: `item 17` and `FR-17` are the same
   requirement under two names.
2. **The manifest and the rendered spec already agree exactly.** Both carry 25 FR ids and 12 NFR ids,
   the sets are identical in both directions, and all 25 requirement lines match character for
   character.
3. **The "frozen contract" is a generated file.** `docs/specs/01_spec_utils.md` is byte-identical to a
   fresh `render.py` run of `orchestrator/project.yaml`. It is a *view*, so it cannot be the source: a
   hand-edit there is reverted by the next re-render, which is risk **R-07** and has already cost this
   project once (item 1.5, where a re-render was proven to revert item 1.4).

## Decision

**A three-rung precedence ladder, written down and gated:**

| Rung | Artifact | Role |
|---|---|---|
| 1 | `docs/rfc/NNNN-*.md` | An RFC **outranks the spec** for every section it pins. RFC-0001 pins the contracts of FR-01, FR-02, FR-22, FR-23, FR-24 and the public-interface table. |
| 2 | `orchestrator/project.yaml` → `spec.*` | **The source of record** for the FR/NFR set. Requirement text is edited *here*. |
| 2′ | `docs/specs/01_spec_utils.md` | The **authoritative published view** of rung 2 — the document to read and to cite. Never hand-edited; regenerated from the manifest. |
| 3 | `.spec/d4np-java.md` | **Superseded provenance.** The record of what was reviewed on 2026-07-14. Not a requirement source. |

Concretely:

1. **The imported draft carries a `SUPERSEDED` banner** naming its successor and stating that it holds
   no `FR-` ids, so a reader who lands on it directly cannot mistake it for the live spec. Its body is
   untouched below the banner — this is a narrow, additive exception to ADR-0008's "do not edit the
   intake records", justified because a reader arriving by search has no other signal, and because the
   banner adds provenance rather than rewriting it.
2. **The five `[GAP] no contract stated` requirement lines that RFC-0001 has in fact pinned now say
   so**, with a pointer rather than a copy: `[RESOLVED by RFC-0001 … that RFC pins the contract and
   takes precedence over this line.]` Copying the RFC's tables into the manifest would create exactly
   the second home this item exists to remove.
3. **`ROADMAP.md`'s Traceability section is corrected** to name the manifest as the id source and the
   rendered spec as its published view.
4. **`consistency_lint.py` gains `spec-authority`**, which asserts the ladder mechanically: the manifest
   and the rendered spec carry the same FR/NFR ids with the same text, every FR/NFR id cited anywhere in
   `ROADMAP.md` exists in the manifest, and the imported draft still carries its superseded banner.

## Alternatives Considered

- **Keep the imported draft authoritative and back-port RFC-0001's contracts into it** — the roadmap's
  other branch. Rejected on measurement 1: the draft has no `FR-` ids, so "back-porting" would first
  require *minting* the FR vocabulary into an archived 2026-07-14 document, then maintaining two
  normative specs whose section shapes differ (nine sections versus six). It would also make the
  authoritative spec a file outside `docs/`, which `docs/README.md` does not even index.
- **Declare `docs/specs/01_spec_utils.md` the source of truth, full stop**, without naming the manifest.
  Rejected because it is generated: the first hand-edit would be silently reverted by the next
  re-render, and the project would learn R-07 a third time. Naming rung 2 and rung 2′ separately is what
  makes "where do I edit this?" answerable.
- **Delete `.spec/`** now that its content is superseded. Rejected: the four ADRs it holds are binding
  and indexed (ADR-0008), and the draft is the evidence of what was reviewed and accepted — provenance
  a governed repository does not throw away. Git history is not a substitute for a file a reader can
  open.
- **Back-port RFC-0001's contract text into the manifest** instead of a pointer. Rejected as duplication
  under a different name: the contract would then exist in two places and drift, and RFC-0001 is already
  the citable home. The pointer removes the *contradiction* (spec says "undefined", RFC defines it)
  without creating a copy.
- **A `docs/specs/README.md` index page** describing the ladder. Not adopted — one more page to keep in
  step, when the facts belong in the ADR (durable) and the lint (enforced).

## Consequences

- **"Where do I change a requirement?" now has one answer:** `orchestrator/project.yaml` → `spec.*`,
  then re-render the spec view. Anyone who edits `docs/specs/01_spec_utils.md` directly will see it
  reverted by the next render, and the new lint check fails the moment the two disagree — so the
  mistake is caught in the same PR instead of a future re-render.
- **The `[GAP]` markers are now honest.** Five of them said "no contract stated" while RFC-0001 stated
  it. The remaining gaps (FR-06 transaction semantics, FR-12 AAD, NFR-11 CVSS threshold, NFR-12
  provenance) are real and still owned by later RFCs, exactly as RFC-0001 records.
- **The traceability claim in `ROADMAP.md` was wrong and is corrected.** It named a document that
  contains none of the ids it claimed to source. Nothing enforced that sentence, which is why it
  survived seven merged PRs; `spec-authority` now enforces the corrected version.
- **A dangling FR/NFR id in the roadmap is now a build failure.** `FR-26` or `NFR-13` in an item — a
  typo, or a requirement invented in a roadmap line rather than in the spec — fails the lint. That is
  the direction the spec-coverage map cannot check, since it maps sections, not ids.
- **`.spec/` is now unambiguously an archive**, and ADR-0008's framing of it as the intake area is
  complete: superseded spec draft plus four binding, indexed ADRs. The next question a reader asks
  ("why are the ADRs still live if the spec is superseded?") is answered in the banner itself.
- **Cost accepted: the authoritative spec is a rendered artifact.** That is inherent to an
  EADOS-generated repository and is the same trade ADR-0003 recorded for the source layout. The
  mitigation is that the render is verified — a fresh render of the manifest reproduces the committed
  spec byte for byte, checked in this PR and asserted by the lint from now on.

## References

- Measured on 2026-07-29 at commit `4d3a310`: `.spec/d4np-java.md` → 0 `FR-` ids, 6 `NFR-` ids;
  `orchestrator/project.yaml` and `docs/specs/01_spec_utils.md` → 25 FR + 12 NFR each, sets equal, all
  25 requirement texts equal; `render.py` output for the spec identical to the committed file.
- RFC-0001 (`docs/rfc/0001-core-contracts.md`) §Follow-ups — the open question this ADR answers.
- ADR-0008 (`.spec/` as intake area, and the "do not edit the archive" stance this narrowly excepts).
- `tools/consistency_lint.py` → `check_spec_authority`.
