# ADR-0006: Enforce the ADR-001 dependency policy per module, with default-deny where the contract is "clean"

- **Status:** Accepted
- **Date:** 2026-07-28
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 1.7; ADR-001 (module split); spec §3 (allowed dependencies); NFR-07,
  NFR-08, NFR-12; FR-25; items 8.1 (japicmp), 6.1, 4.1, 5.3, 7.1

## Context

NFR-08 is unusually explicit about *how* it wants to be satisfied:

> core has ZERO third-party dependencies (jakarta.validation-api as provided); Spring, Jackson, and
> Redisson types never appear in the public APIs of core/jdbc/concurrent; arrows point toward core
> only, no cycles. **Enforced by maven-enforcer, so a PR leaking com.fasterxml into core fails the
> build rather than review.**

Three properties of the problem shaped the design.

**The policy is per-module, and irreducibly so.** `d4np-core` may not see Jackson; `d4np-json` exists
*in order to* see it. No single central rule can express that, so the framework-isolation half cannot
live in the parent POM.

**Enforcer cannot see API signatures.** It reads the dependency graph, not method signatures, so
"Spring types never appear in the public APIs of core" is enforced by proxy: if the artifact is not
on the module's compile path, no type from it can appear in that module's API. That proxy is exact in
the direction that matters and it is worth stating, because no tool in this repository — not
Checkstyle, not japicmp — actually inspects signatures for foreign types.

**Transitive closure cuts both ways.** A ban must be transitive to catch a leak arriving through
something else. But an *allowlist* must then enumerate the full closure of everything permitted,
which for Spring Boot or Redisson means re-enumerating a large graph on every upgrade.

## Decision

**The parent carries only the rules that hold everywhere**, in the plugin-level `<configuration>` and
a `validate`-bound `enforce-universal` execution: `requireJavaVersion [17,)`,
`requireMavenVersion [3.3.1,)`, `requireReleaseDeps`, `banDuplicatePomDependencyVersions`,
`dependencyConvergence`, and a `bannedDependencies` covering FR-25 (`it.d4np:d4np-test` outside test
scope) and the javax EE artifacts spec §1.1 puts out of scope.

**Each module carries its own `enforce-adr-001` execution**, and the *form* of that rule follows the
module's contract:

| Modules | Form | Allowlist / ban |
|---|---|---|
| `core`, `jdbc`, `concurrent`, `test` | **default-deny** | internal + test scope (core also `jakarta.validation-api` as `provided`) |
| `security`, `json` | **default-deny** | as above + `com.nimbusds:*` / `com.fasterxml.jackson*:*` |
| `spring-adapter`, `lock-redisson` | **default-allow** | ban `org.redisson:*` / ban `org.springframework*:*` + `org.aspectj:*` |

Default-deny wherever the contract is a statement about *everything* ("zero third-party", "JDBC API
only", "no third-party deps"), because a ban list cannot express it — a list naming Spring, Jackson
and Redisson would have admitted `commons-lang3` into core without complaint. Default-allow for the
two modules whose job is to carry a framework, because there an allowlist would have to track the
framework's transitive closure and would fail for reasons unrelated to the policy.

**Enforcer is bound to `validate`**, unlike Spotless and Checkstyle, which item 1.4 deliberately left
unbound. A dependency-policy gate that runs only when someone remembers to invoke it is exactly the
review promise NFR-08 exists to replace; bound at `validate` it costs no compilation and every
ordinary `mvn verify` — including all six CI build cells — enforces it.

**CI invokes the phase, not the goal.** See Consequences: this is not cosmetic.

## Alternatives Considered

- **A single central ban list in the parent naming Spring, Jackson and Redisson.** Rejected on two
  counts. It cannot express "zero third-party" (see above), and it would be *wrong* for
  `lock-redisson`: Redisson itself depends on Jackson, so banning Jackson family-wide would break the
  module the moment item 5.3 adds Redisson. A naive reading of "Jackson lives only in d4np-json"
  leads straight into that trap, which is why it is recorded here rather than left to be discovered.
- **Default-deny everywhere, including the adapter modules.** Rejected: the allowlist for
  `spring-adapter` and `lock-redisson` would be the transitive closure of Spring Boot and of
  Redisson/Netty, re-enumerated on every upgrade. The gate would then fail most often for reasons
  that are not policy violations, which is how a gate gets disabled.
- **Enumerating the allowed test stack in core's allowlist** instead of using a scope wildcard. Tried
  and rejected on evidence: it required listing JUnit's and AssertJ's transitive artifacts
  (`opentest4j`, `apiguardian-api`, `byte-buddy`, …), so any test-library upgrade would break core's
  build for no policy reason. `*:*:*:*:test` was verified to exempt test scope generally, including a
  test library that did not exist when the rule was written.
- **A `consistency_lint.py` check instead of enforcer**, reading core's POM for third-party entries.
  Rejected as the primary mechanism: it would see only *declared* dependencies, and the leak NFR-08
  names ("a PR leaking com.fasterxml into core") can arrive transitively. A lint check is kept, but
  for a different question — that every module *has* a policy at all.
- **`requirePluginVersions`** among the universal rules, to encode item 1.3's unpinned-surefire
  lesson. Not adopted here: the rule fails on plugins Maven itself injects with default versions
  during a lifecycle run, so it needs an exclusion list that is a maintenance surface of its own. The
  lesson is currently held by explicit `<version>` pins in `pluginManagement`, which is checked by
  reading the POM rather than by a rule.

## Consequences

- **`mvn enforcer:enforce` is the wrong command, and CI was running it.** A bare CLI goal executes
  only the `default-cli` execution, which sees the plugin-level `<configuration>` and nothing else —
  so it runs the universal rules and **silently skips every per-module `enforce-adr-001`**.
  Demonstrated rather than deduced: with a banned `commons-lang3` added to `d4np-core`,
  `mvn enforcer:enforce` reported *all rules passed*, while `mvn validate` failed with
  `org.apache.commons:commons-lang3:jar:3.17.0 <--- banned via the exclude/include list`. The
  `compatibility` job now invokes the phase. A gate that reports success while skipping the rules it
  exists to run is worse than no gate, because it is believed.
- **The rules are verified in both directions, not asserted.** A 28-case matrix drives one injected
  dependency into one module at a time and asserts banned-vs-allowed: `commons-lang3` and Jackson and
  Spring and Redisson each banned from core; Jackson allowed in `json` and banned in `security`;
  Spring Boot with its full closure allowed in `spring-adapter` but Redisson banned; Redisson with
  Netty and Jackson allowed in `lock-redisson` but Spring banned; `d4np-test` banned at compile scope
  and allowed at test scope; a javax EE artifact banned; `jakarta.validation-api` allowed at
  `provided`. All 28 pass. The matrix is a development instrument, not a committed test — it mutates
  POMs — so its results live here.
- **The gate is dormant but not vacuous.** No module declares a third-party dependency yet, so every
  rule passes trivially today. What the matrix establishes is that they will not pass trivially when
  M2–M7 add real dependencies, which is the only claim worth making now.
- **Adding a dependency to a default-deny module is a deliberate act.** It requires editing that
  module's allowlist, which is the intent: the zero-dependency claim becomes a reviewed decision
  rather than a habit. Expect this to bite first at item 6.1 (Nimbus) or 4.1 (Jackson) if either
  drags an artifact outside the allowed prefix — the failure names the artifact, and extending the
  list is the correct response.
- **The `compatibility` CI job stays red**, and item 1.7 does not change that. It also runs
  `japicmp:cmp`, which fails with `No plugin found for prefix 'japicmp'` because japicmp is not
  configured at all; **item 8.1 owns that half**. Item 1.7's own half is nevertheless live in all six
  build cells, since enforcer is bound to `validate`. The ROADMAP note that implied 1.7 would turn
  this job green has been corrected.
- **`d4np-bom` is not policed.** It carries no parent by design (ADR-001/NFR-09), so it cannot inherit
  the plugin, and it declares no dependencies — only `dependencyManagement`, which enforcer does not
  treat as a dependency. A banned coordinate could therefore be *managed* there unnoticed. Recorded
  as a real gap; the BOM's correctness is the versioning contract's concern (NFR-09).
- **A new module could ship with no policy at all** and still build green, since the parent's
  universal rules would pass. `consistency_lint.py` gains `module-dependency-policy`, asserting every
  jar module declares an `enforce-adr-001` execution and that the parent declares
  `enforce-universal`. It checks presence, not contents — judging contents would re-encode ADR-001 in
  a second place, and a policy stated twice drifts.

## References

- ADR-001 (`.spec/adr/d4np_java_adr_001_module_split.md`); spec §3 (allowed dependencies), §1.1.
- NFR-08 (dependency policy), NFR-07 (jakarta-only), NFR-12 (no SNAPSHOT deps), FR-25 (test module).
- `tools/consistency_lint.py` → `check_enforcer`.
- maven-enforcer `bannedDependencies` pattern is `groupId:artifactId:version:type:scope`; a groupId
  prefix wildcard (`org.springframework*`) was verified to match subordinate groups such as
  `org.springframework.boot`.
