# ADR-0004: Declare line endings in `.gitattributes`, and run format checks on more than one platform

- **Status:** Accepted
- **Date:** 2026-07-28
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 1.5; ROADMAP item 1.4 (Spotless/Checkstyle); spec §1.1 / NFR-07
  (`tier1_platforms`); `orchestrator/project.yaml` → `ci`; `.mvn/README.md`

## Context

`tier1_platforms` promises "Linux / Windows / macOS on Temurin JDK 17 & 21", and `README.md`
repeats it. Until item 1.5 the CI matrix carried **four** cells, testing Windows and macOS on JDK 21
only — so **17, the published baseline, was untested on two of the three platforms**. Nothing
recorded that as a decision; a profile default had simply gone unexamined.

Formatting was checked on **one** platform (`ubuntu-24.04`). That looked sufficient because
google-java-format's output is deterministic — a property of the formatter version, not the OS.

That reasoning is wrong, and the first cross-platform format job proved it within one run. Spotless
resolves its `lineEndings` setting from `GIT_ATTRIBUTES` by default. **This repository had no
`.gitattributes`**, so the lookup fell through to the *platform's* native ending: on Windows Spotless
expected CRLF, while `core.autocrlf=input` had written LF into the working tree. Every line of the
file mismatched. `spotless:check` reported **all 77 lines** of `BuildContractSmokeTest.java` as format
violations on Windows while passing on Linux.

Two forces make this more than a nuisance:

1. **The failure was environment-dependent, not repository-determined.** The outcome varied with each
   contributor's `core.autocrlf` — `input` (LF on disk), `true` (CRLF on disk), and `false` (whatever
   was committed) each produce a different verdict from the same commit. A build gate whose result
   depends on a local git setting is not a gate.
2. **It was invisible by construction.** Format checks only ever ran on Linux, where the accident is
   benign, so no amount of static review would have surfaced it.

## Decision

Line endings are a declared property of this repository, not of a contributor's environment. A
root **`.gitattributes`** normalises text files to **LF** in both the repository and the working
tree (`* text=auto eol=lf`, with explicit rules per extension), excepting `*.bat`/`*.cmd`, which
require CRLF because `cmd.exe` mis-parses LF-terminated batch files, and binaries, which are marked
`binary` so they are never diffed or converted.

Format and lint checks run on **more than one platform** — a `format-lint` job matrixed over
`ubuntu-24.04` and `windows-2022` — so a platform-dependent formatting verdict fails a gate instead
of ambushing a contributor. The `build` matrix is completed to the **six** cells `tier1_platforms`
already promised.

## Alternatives Considered

- **Set `<lineEndings>UNIX</lineEndings>` on the Spotless plugin instead of adding
  `.gitattributes`.** Rejected because it fixes only the tool that happened to report the symptom.
  Git would still check out whatever each contributor's `core.autocrlf` dictated, and Checkstyle's
  `NewlineAtEndOfFile`, IDE "mixed line endings" warnings, and every future text-processing tool
  would keep resolving the question independently. `.gitattributes` is the single declaration that
  git *and* Spotless (via `GIT_ATTRIBUTES`) *and* editors all read, so it answers the question once.
  This alternative remains available as a belt-and-braces addition if a tool ever ignores git
  attributes; it was not needed to make the gate deterministic.
- **Keep format checks on Linux only and leave line endings undeclared.** Rejected: it is the status
  quo that hid the defect. It also silently penalises Windows contributors, who would hit an
  unfixable whole-file `spotless:check` failure with no cross-platform signal to explain it.
- **Run `format-lint` on all three platforms, or across both JDKs.** Rejected as cost without
  signal. The divergence class being hunted is CRLF-vs-LF and path separators, so Windows is the
  informative case while macOS and Linux are both POSIX/LF; macOS also bills at the highest runner
  multiplier. google-java-format and Checkstyle are libraries whose verdicts do not depend on the
  JDK executing them, so a second toolchain would double the cost and prove nothing.
- **Trim `tier1_platforms` to match the four-cell matrix** rather than growing the matrix. A
  legitimate option — it is cheaper and honest. Rejected because 17 is the *published* baseline
  (NFR-07): the toolchain consumers actually receive is the one that most needs testing. Recorded
  explicitly because the reverse call is the maintainer's to make, and if it is ever made,
  `tier1_platforms`, `README.md` and spec §1.1 must be trimmed in the same commit so the contract
  never over-promises what CI exercises.

## Consequences

- **Determinism.** `spotless:check` now returns the same verdict on Windows and Linux, verified by
  running it on both: the Windows run failed on all 77 lines before this change and passes after it.
- **No churn.** `git add --renormalize .` produced no modifications, so the repository was already
  uniformly LF; this ADR codifies the existing reality rather than rewriting history. Contributors
  with `core.autocrlf=true` will see their working tree converted to LF on next checkout — the
  intended effect.
- **Cost.** The build matrix grows from four cells to six, and one of the new cells is macOS, the
  highest-multiplier runner. Two more `format-lint` cells are added. This is the price of the
  compatibility claim already published in `README.md`.
- **`*.bat`/`*.cmd` keep CRLF.** No such file exists in the tree today; the rule is pre-declared so
  the first one added cannot break `cmd.exe` parsing.
- **Numbering.** This ADR takes **0004**, which ROADMAP item 1.10 had sketched as the start of the
  range for renumbering the four `.spec/adr/` decisions. That is the L-0005 pattern — a reserved
  number is taken by whichever work lands first — and item 1.10 has been updated to say so, rather
  than leaving the collision to be discovered during 1.10.
- **Limitation.** `.gitattributes` governs files git manages. It does not constrain what a generator
  writes into an untracked file, so a tool emitting CRLF into generated output remains possible;
  the `format-lint` matrix is what would catch it.

## References

- ROADMAP items 1.4 (formatter/linter configs) and 1.5 (CI matrix).
- `orchestrator/project.yaml` → `ci.matrix`, `ci.tier1_platforms`, `ci.extra_jobs`.
- Spotless `lineEndings` default (`GIT_ATTRIBUTES`) — diffplug/spotless documentation.
- `.mvn/README.md` — the adjacent case of a config format whose rules are not self-evident.
- Lesson L-0005 (a reserved ADR number can be taken by work that lands first).
