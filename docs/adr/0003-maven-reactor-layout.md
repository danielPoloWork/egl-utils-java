# ADR-0003: Adopt the Maven reactor layout, superseding the flat source tree

- **Status:** Accepted
- **Date:** 2026-07-27
- **Deciders:** Maintainer
- **Supersedes:** [ADR-0002](0002-adopt-cross-language-source-layout.md)
- **Related:** ADR-0001 · `.spec/adr/d4np_java_adr_001_module_split.md` (module split) ·
  `.spec/adr/d4np_java_adr_004_generated_layout.md` (generated layout) · AGENTS.md §5 ·
  ROADMAP item 1.1

## Context

[ADR-0002](0002-adopt-cross-language-source-layout.md) adopted a **single flat source tree** —
`src/main/java/it/d4np/utils/` — as normative, and closed with an explicit condition: *"changing the
shape requires superseding this ADR."* This ADR is that supersession. It exists because ADR-0002 was
written by the generator, before the project's own module policy was applied to the tree.

The project's specification requires a **nine-artifact Maven reactor**
(`.spec/adr/d4np_java_adr_001_module_split.md`, Accepted): a zero-dependency `core`, capability
modules that own their third-party surface, adapters that isolate hosts, and a BOM. That policy is
what makes "framework independence" a build property rather than a slogan — `maven-enforcer` can only
ban `com.fasterxml` from `core` if `core` is a module.

The two cannot both hold. Maven's contract is that a module is a **directory with its own `pom.xml`
and its own `src/main/java`**. Inside one flat tree, `src/main/java/it/d4np/utils/core` is a
**package**, not a module — so the reactor collapses into a single JAR whose packages are merely
*named* after the intended modules. That is structurally identical to the monolith the entire
specification exists to correct: a consumer wanting only `StrategyRegistry` would again inherit
Spring, Jackson, AspectJ and Redisson.

`.spec/adr/d4np_java_adr_004_generated_layout.md` (Accepted 2026-07-26) already decided the
resolution and scheduled it as ROADMAP item 1.1. What it could not do is discharge ADR-0002's
supersession requirement, because it lives outside the generated `docs/adr/` record. This ADR closes
that gap.

## Decision

**The Maven reactor is the normative layout.** The repository root holds the parent POM; each of the
nine modules of ADR-001 is a directory with its own `pom.xml` and its own source root:

```text
pom.xml                                          # parent, packaging: pom
d4np-core/src/main/java/it/d4np/utils/           # relocated from the flat tree
d4np-core/src/test/java/it/d4np/utils/
d4np-core/src/bench/java/it/d4np/utils/
d4np-jdbc/          d4np-concurrent/    d4np-security/    d4np-json/
d4np-spring-adapter/  d4np-lock-redisson/  d4np-test/     d4np-bom/
```

Three points ADR-0002's text now reads wrongly against, corrected here:

- **The package root is unchanged.** `it.d4np.utils` still mirrors the path *within each module*, and
  consumers still `import it.d4np.utils.*;`. Subdivision inside `utils/` remains by component. What
  changes is that there are now nine source roots instead of one, not the namespace.
- **The manifest's derived `src_main` is narrower than it appears.** `orchestrator/project.yaml`
  derives `src_main = src/main/java/it/d4np/utils`; after this ADR that value denotes **the `core`
  module's package root**, not the repository's only source root. The manifest is not wrong; its
  scope is narrower than its name suggests.
- **Cross-language consistency is preserved at the level that matters.** ADR-0002's goal was that
  sibling projects share one shape an agent can navigate identically. That still holds: the shape is
  `<module>/src/<phase>/<lang>/<group>/<slug>/`, with the flat tree as its degenerate one-module case.
  The series convention survives; only the module dimension is added.

## Alternatives Considered

- **Keep the flat tree and express modules as packages.** Rejected on a mechanism, not a preference:
  `maven-enforcer` and `japicmp` are inherently per-module, so NFR-08 (dependency policy) and NFR-09
  (binary compatibility) would be unenforceable. The central claim of the specification would become
  untestable.
- **Teach the generator multi-artifact rendering first, then re-render.** Rejected for this project:
  it blocks all delivery on an upstream feature. Raised separately as an upstream RFC; if it lands,
  this project may migrate to the generated form and this ADR is superseded rather than amended.
- **Separate repositories per module.** Rejected in ADR-001 — overkill for one maintainer, and
  cross-cutting changes become multi-repo choreography.

## Consequences

- **The generator and the tree now diverge permanently.** `render.py` will always emit the flat tree,
  because the manifest has no field for a module layout. Anyone re-rendering will reintroduce
  `src/main/java/it/d4np/utils/` at the root and rewrite the documentation that describes the reactor.
  This is the accepted cost of ADR-004's decision, and it is the same failure class as the audit's
  **R-07** — recorded so that a future re-render is a deliberate act, not a surprise.
- **Generated documents were hand-corrected inside item 1.1.** `AGENTS.md` §5 and
  `tools/consistency_lint.py`'s `CONFIG` describe the tree; both now describe the reactor. ADR-004
  authorises this explicitly: restructuring after hand-off is ordinary development, not forbidden
  hand-editing, because control passed to this repository's own `AGENTS.md` at the bootstrap PR.
- **`version-lockstep` becomes meaningful.** `CONFIG["version_file"]` previously rendered as
  `<src_main>/pom.xml` — a path that could never exist — so the check silently compared the README
  badge to itself. It now points at the real parent `pom.xml`.
- **Item 1.1 is a hard prerequisite for Milestone 2.** Component code committed before the reactor
  existed would land in the wrong artifact. Items 1.6 (`module-info`) and 1.7 (`maven-enforcer`), and
  through them NFR-08/NFR-09 and item 8.1, are all downstream of this.
- **CI changes shape.** The `bootstrap` guard probes for `pom.xml`; creating it flips the six
  toolchain jobs from *skipped* to *running*, and they will fail until items 1.2 (buildable) and 1.4
  (formatter/linter configs) land. Required status checks are deliberately limited to
  `consistency / lint` and `bootstrap`, so merges are not blocked in the interim.

## References

- `.spec/adr/d4np_java_adr_001_module_split.md` — the module split and dependency policy.
- `.spec/adr/d4np_java_adr_004_generated_layout.md` — the flat-tree/reactor reconciliation.
- `.spec/d4np-java.md` §3 — the C4 component view and the allowed dependency arrows.
- ROADMAP item 1.1; item 1.10 tracks reconciling the four `.spec/adr/` records into `docs/adr/`.
