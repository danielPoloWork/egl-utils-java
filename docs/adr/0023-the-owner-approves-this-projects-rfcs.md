# ADR-0023: The owner approves this project's RFCs, not the `tech-lead` the protocol names

- **Status:** Accepted
- **Date:** 2026-08-05
- **Deciders:** Daniel Polo (owner / maintainer), agent (senior project architect)
- **Related:** ROADMAP item 4.0; `AGENTS.md` §6 (the agent/human boundary, and "no RFC self-approves");
  RFC-0001, [RFC-0002](../rfc/0002-cross-cutting-contracts.md),
  [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §Approval;
  `.eados-core/orchestrator/os/rfc/rfc.yaml`; `.eados-core/tools/rfc_check.py`;
  `orchestrator/project.yaml` → `delivery_state.checkpoints` (the `rfc-approved` entry)

## Context

The RFC review protocol this repository was generated from names one role as both the author and the
approver of an RFC. `.eados-core/orchestrator/os/rfc/rfc.yaml` reads:

```yaml
author_roles: [tech-lead, product-manager]
reviewer_roles: [reviewer, enterprise-architect]
approver_role: tech-lead
approval:
  marker: "approved-by:"            # line format: approved-by: <approver_role> (<YYYY-MM-DD>)
gate: rfc-approved
```

and `.eados-core/tools/rfc_check.py` enforces the token literally:

```python
m = re.search(re.escape(marker) + r"\s*([A-Za-z][\w-]*)", text)
elif approver and m.group(1) != approver:
    problems.append(f"approved-by '{m.group(1)}' but the rfc-approved gate requires '{approver}'")
```

**This project has drifted from that in practice, twice, in opposite directions, and nothing recorded
it.** Measured on 2026-08-05 at commit `5310d70`:

| RFC | Record | `rfc_check.py` |
|---|---|---|
| RFC-0001 | `approved-by: tech-lead (2026-07-27)` | **OK** |
| RFC-0002 | `approved-by: owner @danielPoloWork (2026-08-02)` | **FAIL** — *"approved-by 'owner' but the rfc-approved gate requires 'tech-lead'"* |

RFC-0002's own header states its approver explicitly — *"**Approver:** owner (@danielPoloWork)"* — and
its Approval section records that the owner authorised it in session. So the FAIL is not a mistake in
the record; the record is right and the gate's expectation is the thing that does not fit this project.

**The reason it does not fit is a contradiction, not a preference.** `AGENTS.md` §6 states that **no
RFC self-approves**. The `design` phase assigns authoring to `tech-lead`. The gate requires approval by
`tech-lead`. With a capacity of one person — `ownership.owner`, `maintainer` and `author` are all Daniel
Polo, and the manifest's `governance` block notes that reviewers *"stay deferred until there is a second
collaborator"* — satisfying the gate literally means the same role writes the document and declares it
accepted, which is precisely what §6 forbids. Two governing documents cannot both be honoured with one
token.

Three facts bound how this can be fixed:

1. **`.eados-core/` is not a repository artifact.** `.gitignore:74` excludes `/.eados-core/`; it is the
   factory bundle copied in to regenerate this repo. An edit there does not survive a fresh clone.
2. **There is no project-local override.** `rfc_check.py` resolves its protocol unconditionally from its
   own directory — `RFC_SPEC = os.path.join(ROOT, "orchestrator", "os", "rfc", "rfc.yaml")` — so no
   file in this repository can redirect it.
3. **Nothing in the build depends on the verdict.** Grepped this session: `rfc_check` appears in no
   `.github/workflows/*.yml` and in no check of `tools/consistency_lint.py`. The gate is a manual
   command whose result is transcribed into `delivery_state`, not a CI step.

## Decision

**This project's RFC approver role is `owner`.** Every RFC records its approval as

```
approved-by: owner @danielPoloWork (YYYY-MM-DD)
```

and `rfc_check.py` reporting `FAIL — approved-by 'owner' but the rfc-approved gate requires 'tech-lead'`
against that form is **expected output, not a defect to fix.** This record is what a reader consults
when they meet that line.

Three clauses make the decision usable rather than merely stated:

1. **The approver is named, not just roled.** The record carries the GitHub handle, so the trail says
   *who* decided and not only *which role* was nominally responsible. That is the property the gate's
   role-token form cannot express and the reason the honest attribution wins over the green verdict.
2. **`approved-by: tech-lead` is not written again.** RFC-0001's record is left exactly as shipped —
   see Consequences — but it is the last of its kind.
3. **The mechanical verdict is transcribed, never suppressed.** Every PR that lands an RFC quotes
   `rfc_check.py`'s actual output, including the FAIL, so the deviation stays visible in the place a
   reviewer is already reading. A deviation that has to be remembered is a deviation that gets lost,
   which is how RFC-0002's went unrecorded for two milestones.

## Alternatives Considered

- **Write `approved-by: tech-lead` and take the green gate.** The cheapest option, and RFC-0001's
  form. Rejected on two counts. It names a role where the project has a person, so the audit trail loses
  the one fact an approval exists to hold; and because `tech-lead` is *also* the authoring role here, the
  record would assert exactly the self-approval `AGENTS.md` §6 prohibits. **A gate that turns green by
  writing a misattribution is worse than a red gate with a record beside it** — the same reasoning
  `orchestrator/project.yaml` already applies to risk R-04, where back-filling invented RFC ids onto two
  PRs would have turned `traceability-lint` green *"by writing a falsehood into the evidence trail."*
- **Set `approver_role: owner` in `.eados-core/orchestrator/os/rfc/rfc.yaml`.** Rejected on fact 1: the
  directory is gitignored factory tooling, so the change would vanish on a fresh clone and the gate would
  pass, on some machines, for a reason no committed file explains. This is the same reason ADR-0006 put
  the dependency rules in the module POMs rather than anywhere upstream — a rule that is not in the
  repository is not a rule the repository has.
- **Add a project-local protocol override.** Rejected on fact 2: nothing reads one. Proposing the
  mechanism upstream is a legitimate follow-up and is not this decision's business.
- **Retrofit RFC-0002's record to `tech-lead`.** Rejected: it is another item's shipped approval record,
  and the owner's decision on 2026-08-02 was taken as the owner. Editing a landed approval to satisfy a
  tool falsifies the one line in the document that exists to be trustworthy.
- **Rewrite RFC-0001's record to `owner` for consistency.** Rejected for the mirror of that reason. The
  2026-07-27 record is what was written and transcribed at the time, and the `rfc-approved: OK` in
  `delivery_state.checkpoints` was computed against it. Rewriting it would make a recorded gate result
  refer to text that no longer exists.
- **Leave it undocumented, as RFC-0002 did.** Rejected because RFC-0003 is the third RFC: at one
  occurrence "nobody noticed" is plausible, at three it is "nobody wrote it down." Under the enterprise
  posture a governance deviation is exactly the class of thing that carries a record.

## Consequences

- **`rfc_check.py` reports FAIL for RFC-0002, RFC-0003 and every RFC after them.** That is now a known,
  documented verdict rather than an unexplained red. Any future automation that gates on the tool must
  either accept `owner` as the approver token or be pointed here; wiring it into CI as-is would make the
  pipeline red for a reason the pipeline cannot explain.
- **RFC-0001 keeps the only approval record in the repository that does not name its approver.** That
  inconsistency is chosen over rewriting history, and it is written down here so a reader who compares
  the three RFCs finds an explanation instead of a puzzle.
- **The recorded `rfc-approved: OK` checkpoint stays accurate.** `delivery_state.checkpoints` transcribes
  a verdict computed on 2026-07-27 against RFC-0001, whose form passes. Nothing about this decision makes
  that entry retroactively wrong, and the manifest's existing caveat — that the gate is *structural*, and
  that the independent-reviewer step never ran — remains the more important limitation of it.
- **No tooling and no CI change.** By fact 3 nothing in the build turns red or green either way, so the
  entire cost of the deviation is one manual verdict a human has to interpret — which is what this
  record makes interpretable, and the only thing it buys.
- **The peer-review half is untouched and still absent.** This decision settles *who approves*, not
  *whether anyone reviewed*. `reviewer_roles: [reviewer, enterprise-architect]` has never run for any RFC
  in this project, each RFC's Approval section says so, and this record does not improve that. It stays
  a one-person project until there is a second collaborator, and an approval by the owner is the terminal
  gate `AGENTS.md` §12.4 calls precedence layer 1 — not a substitute for review.

## References

- Measured on 2026-08-05 at commit `5310d70`: `rfc_check.py docs/rfc/0001-core-contracts.md` → OK;
  `rfc_check.py docs/rfc/0002-cross-cutting-contracts.md` → FAIL, *"approved-by 'owner' but the
  rfc-approved gate requires 'tech-lead'"*; `grep -rn rfc_check .github/ tools/` → no hits;
  `.gitignore:74` → `/.eados-core/`.
- `.eados-core/orchestrator/os/rfc/rfc.yaml` — `approver_role`, `author_roles`, `approval.marker`.
- `.eados-core/tools/rfc_check.py` — `check_rfc()`, and `RFC_SPEC` resolved from the tool's own
  directory.
- `AGENTS.md` §6 (agent/human boundary; "no RFC self-approves"), §7 (a governance decision under the
  enterprise posture carries a record), §12.4 (the maintainer decision as precedence layer 1).
- [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §Approval and
  [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §Approval — the two records this decision governs.
- `orchestrator/project.yaml` → the R-04 note, for the precedent that a gate must not be turned green by
  writing something untrue.
