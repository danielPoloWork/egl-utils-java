# ADR-0005: JPMS module names, and descriptors that land before the code they describe

- **Status:** Accepted
- **Date:** 2026-07-28
- **Deciders:** Daniel Polo (maintainer — chose the core module name), agent (senior project architect)
- **Related:** ROADMAP item 1.6; ADR-001 (module split); spec §1.1 (JPMS row), §2 item 25 (FR-25),
  §3 (allowed dependencies); NFR-07, NFR-08, NFR-09; items 1.7, 7.4

## Context

Spec §1.1 states the contract flatly: *"all modules ship `module-info`; `d4np-test` requires
`--add-opens` (documented)"*. Item 1.6 delivers it. Two facts shaped how.

**First, a module name is a binary contract.** It appears in consumers' own `module-info` files, so
renaming one later is a breaking change that japicmp (NFR-09) exists to catch. Two records in this
repository disagreed about the most important name:

| Record | Says |
|---|---|
| `orchestrator/project.yaml` (maintainer, dated correction) | *"modules land at `it.d4np.utils.core`, `.jdbc`, `.security`, … (the commons-lang3 convention)"* |
| `AGENTS.md` §5 + the ADR-0003 note + the generated README hint | core's production sources are at `it/d4np/utils/`; *"read that value as **core's package root**"*; consumers `import it.d4np.utils.*` |

That is not a wording nit — it decides whether core is `it.d4np.utils` or `it.d4np.utils.core`, and
every other module's `requires` clause follows it. It was escalated to the maintainer rather than
guessed.

**Second, there is no production code yet.** The entire reactor contains one Java file, the item 1.3
smoke test. This is not a detail that could be worked around, because the module system enforces it:

```
$ javac module-info.java          # module it.d4np.utils.jdbc { exports it.d4np.utils.jdbc; }
error: package is empty or does not exist: it.d4np.utils.jdbc
```

An `exports` clause is **not a forward declaration**. Two attempts to satisfy it were tried and
measured rather than reasoned about, and the second result is the non-obvious one:

- a package containing a real type — satisfies `exports`, but requires inventing types, which would
  become published API that japicmp then guards;
- a package containing **only `package-info.java`** — **still fails** with the same error. A
  `package-info.java` carrying no annotation produces no class file, so the package is "empty". This
  is worth recording because it is the natural first fix and it does not work.

A module declaring no `exports` at all, however, compiles cleanly.

## Decision

**Module names.** Eight code modules ship a descriptor; `d4np-bom` cannot, being `packaging=pom` with
no sources. `d4np-core` **owns the family root `it.d4np.utils`** (the maintainer's call), and every
capability module takes a child of it:

| Artifact | Module | Internal `requires` |
|---|---|---|
| `d4np-core` | `it.d4np.utils` | — |
| `d4np-jdbc` | `it.d4np.utils.jdbc` | `it.d4np.utils` |
| `d4np-concurrent` | `it.d4np.utils.concurrent` | `it.d4np.utils` |
| `d4np-security` | `it.d4np.utils.security` | `it.d4np.utils` |
| `d4np-json` | `it.d4np.utils.json` | `it.d4np.utils` |
| `d4np-spring-adapter` | `it.d4np.utils.spring` | `it.d4np.utils`, `it.d4np.utils.json` |
| `d4np-lock-redisson` | `it.d4np.utils.lock.redisson` | `it.d4np.utils.concurrent` |
| `d4np-test` | `it.d4np.utils.test` | `it.d4np.utils` |

That table is spec §3's dependency graph, and `d4np-lock-redisson` pointing at `concurrent` rather
than at core is deliberate there, not an omission here.

**Descriptors land now, export-less.** Each declares its name and its internal `requires` edges only.
`exports` clauses arrive in the same change as the first types of each module — M2 for core, M4–M7 for
the rest. Third-party and JDK edges (`java.sql`, `com.fasterxml.jackson.databind`, Spring as
`requires static`) likewise arrive with the code that uses them: this project does not pin what
nothing uses yet, the same discipline item 1.1 applied to dependency versions.

**`requires` mirrors the POM, and a lint enforces it.** `consistency_lint.py` gains a
`jpms-congruence` check asserting that every jar module has a descriptor, that names are unique and
under the family root, that the BOM has none, and that the set of internal `requires` edges equals the
set of internal `<dependency>` entries. It derives artifact→module-name from the descriptors
themselves rather than hardcoding the table above, so the table is not restated a third place.

**Checkstyle does not see these files.** `**/module-info.java` is excluded from
`maven-checkstyle-plugin`.

## Alternatives Considered

- **`it.d4np.utils.core` for core** (the manifest's note, commons-lang3 convention). Every module owns
  one subtree symmetrically and no module monopolises the root, which forecloses the split-package
  risk described under Consequences. Rejected by the maintainer in favour of the shorter import for
  the most-used module, consistency with `AGENTS.md` §5, the generated README hint, and the existing
  test's own package. `orchestrator/project.yaml`'s note has been corrected to match the decision, so
  the two records no longer disagree.
- **Defer item 1.6 until modules have code.** Descriptors would then arrive with real `exports` and
  never need a second edit. Rejected because the roadmap sequences 1.6 before M2 deliberately: fixing
  the names *before* consumers exist is the cheap moment to fix them, and the `requires` graph is
  worth having as a compile-time property while the code that must respect it is still being written.
- **Invent a placeholder type per module** so `exports` compiles today. Rejected: those types would be
  public API under japicmp's binary-compatibility gate, and deleting them later would be a breaking
  change. Manufacturing API to satisfy a linter inverts the purpose of the linter.
- **Add `package-info.java` per module** to make the packages exist. Rejected on evidence, not
  principle: verified above, it does not satisfy `exports`.
- **Find a Checkstyle version that parses module declarations**, instead of excluding the files.
  Rejected as unbounded for the value: the exclusion costs nothing real (see Consequences), and
  10.26.1 was pinned deliberately by item 1.4.

## Consequences

- **The family root is now spoken for.** No module other than `d4np-core` may place a type in
  `it.d4np.utils`. Two modules sharing one package is a split package, which the module system
  rejects outright rather than warning about — so the failure mode is a hard build error at the moment
  someone adds a "shared" type in the obvious-looking place. Each capability module's own subpackage
  is the answer. This is the accepted cost of the shorter import.
- **Every descriptor will be edited again**, once per module, when its first types land. That is
  normal JPMS evolution rather than rework, but it means these files are not finished artifacts and
  should not be read as if they were.
- **The eight JARs currently contain nothing but `module-info.class`.** Verified with
  `--describe-module` against the built artifacts and by resolving the whole graph with
  `--add-modules ALL-MODULE-PATH`; that check was also shown to be non-vacuous, since removing
  `d4np-json` from the module path fails with `FindException: Module it.d4np.utils.json not found,
  required by it.d4np.utils.spring`. Nothing is published, so no consumer can be misled by an empty
  module today; publishing before M2 would be.
- **Checkstyle has a blind spot on module descriptors, and it is narrow.** Checkstyle 10.26.1 cannot
  parse a module declaration at all — verified as a tool limitation, not a misconfiguration: the pin
  resolves `checkstyle-10.26.1.jar`, and a minimal `TreeWalker` + `AvoidStarImport` config on a bare
  `module m {}` still fails with `no viable alternative at input 'module'`. Because that is a hard
  failure of the goal rather than a violation, one descriptor would take the whole lint down. Nothing
  in `checkstyle.xml` applies to these files anyway: every rule governs naming, imports, structure or
  Javadoc of *type* declarations, and a descriptor declares no types and has no imports — `requires`
  is not an import, so even the jakarta-only `IllegalImport` rule could not police it. Spotless *does*
  parse module declarations, so formatting stays enforced, and `jpms-congruence` covers the semantics
  Checkstyle cannot see.
- **FR-25's `--add-opens` contract is documented** in `d4np-test`'s descriptor, including the part
  that is easy to get backwards: the *consumer* opens its packages to `it.d4np.utils.test`, so making
  `d4np-test` an `open module` would achieve nothing. Item 7.4 still owns the actionable-error half.
- **ADR numbering.** This takes **0005**, which item 1.10 had sketched for renumbering the four
  `.spec/adr/` decisions; that item now reads 0006–0009. Second occurrence of lesson L-0005 in two
  PRs, which is itself evidence that reserving a range in a roadmap item does not hold.

## References

- Spec §1.1 (JPMS row), §2 item 25 (FR-25), §3 (allowed dependencies).
- ADR-001 (`.spec/adr/d4np_java_adr_001_module_split.md`) — the module split this graph encodes.
- ADR-0004 — the adjacent case of a cross-platform tool assumption disproved by running it.
- `tools/consistency_lint.py` → `check_jpms` (the `jpms-congruence` invariant).
- JLS §7.7 (module declarations); `javac` diagnostic *package is empty or does not exist*.
