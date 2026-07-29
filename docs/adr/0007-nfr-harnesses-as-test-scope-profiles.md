# ADR-0007: Run the JMH and jcstress harnesses from profile-activated, test-scope source roots

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 1.8; ADR-001 (module split), ADR-0006 (per-module dependency policy);
  spec §6 (verification & test strategy); NFR-01..NFR-06, NFR-08; items 2.2, 5.1, 5.2, 8.2, 8.3

## Context

Spec §6 makes measurement part of the contract, not a follow-up:

> Benchmarks: JMH 1.37+, forked JVMs, 5x10 warmup/measurement iterations, Blackhole to defeat
> dead-code elimination; harnesses committed under `bench/`. […] Concurrency: every thread-safety
> claim is backed by a **NAMED jcstress test** (harnesses under `jcstress/`) […] A claim without a
> jcstress test is not a claim.

`.github/workflows/ci.yml` has been invoking `mvn -B -Pjmh verify` (job `benchmark`) and
`mvn -B -Pjcstress verify` (job `jcstress`) since the scaffold render, and **neither profile
existed**. Maven only *warns* on an unmatched profile — `The requested profile "jmh" could not be
activated because it does not exist` — so both jobs ran an ordinary `verify` and reported a green
performance gate and a green concurrency gate having measured neither. That is the third instance of
one failure class in this repository: the unpinned surefire of item 1.3 (tests silently not run), the
vacuous `version-lockstep` of item 1.1, and `enforcer:enforce` skipping every per-module rule in item
1.7. The gate reports success *because* it does nothing.

Three constraints shaped the design.

**The harness dependencies are third-party, and four modules are default-deny.** ADR-0006 gives
`core`, `jdbc`, `concurrent` and `test` an allowlist, not a ban list, so `jmh-core` and
`jcstress-core` cannot simply be added at compile scope — each would have to be written into the
allowlist of every module that measures anything, weakening "zero third-party dependencies" to
accommodate measurement tooling, and `jmh-core` would appear in the consumer-visible graph.

**There is nothing to measure yet.** The reactor holds one test and no production types. Item 1.6
established the response to exactly this situation: land the machinery, not speculative API.

**The runners are all-or-nothing per module.** JMH and jcstress both exit non-zero when handed an
empty test list, so a runner bound unconditionally in the parent fails the eight modules that own no
harness.

## Decision

**`src/bench/java` and `src/jcstress/java` are added as TEST-compile source roots** by
`build-helper-maven-plugin`, inside the `jmh` and `jcstress` profiles respectively, with `jmh-core`
and `jcstress-core` declared at **`test` scope**. ADR-0006's per-module allowlists already permit
`*:*:*:*:test` unconditionally, so no dependency policy is touched, nothing reaches a published JAR,
and nothing is transitive to a consumer. Harness code is verification code; test scope is where
verification code belongs. The directory names follow AGENTS.md §5 (`src/bench/java/…`), with
`src/jcstress/java` its sibling — spec §6's bare `bench/` and `jcstress/` read as module-relative,
since ADR-0003 relocated the flat tree into the reactor.

**Code generation runs as an annotation processor on the `default-testCompile` execution**, declared
with `combine.children="append"` so that `-Pjmh,jcstress` activates both processors instead of the
second profile replacing the first's list.

**The runners are launched with `exec-maven-plugin`'s `exec` goal, not `java`.** Both tools fork JVMs
and construct that command line from `java.class.path`; under `exec:java` the harness classes live in
the plugin's isolated classloader, so the fork would start a JVM that cannot see them. `exec` starts a
real `${java.home}/bin/java` whose classpath is the module's test classpath.

**Opt-in is a per-module property.** The parent defaults `jmh.skip` and `jcstress.skip` to `true`; a
module that owns harness sources overrides the switch to `false` beside them. `d4np-core` opts into
both today. `consistency_lint.py` gains `harness-opt-in`, which asserts the parent declares both
profiles and that, per module, owning harness sources and opting the runner in are the same fact —
in both directions.

**PR-grade settings are the default, publication-grade is a command-line override.** One fork, one
warmup iteration, one measurement iteration, `-m sanity` for jcstress. Spec §6's 5x10 belongs to the
reference machine, and a CI number would not be evidence for an NFR either way (the manifest's own
risk note records that GitHub runners vary by far more than the budgets do).

## Alternatives Considered

- **Compile the harnesses as MAIN sources.** Rejected: `jmh-core` would need compile or `provided`
  scope in a default-deny module, i.e. the ADR-0006 allowlist would be edited to admit measurement
  tooling, and benchmark classes would ship inside the module's JAR and its JPMS descriptor.
- **A separate reactor module (`d4np-bench`) holding all harnesses.** Rejected: it puts the benchmark
  a module away from the code it measures, needs a compile dependency on every module it benchmarks
  (so it can never measure package-private internals), and contradicts AGENTS.md §5, which places
  `src/bench/` inside the module. It would also appear in the BOM's reactor, which is consumer-facing.
- **The jcstress Maven archetype's shaded uber-JAR.** The upstream archetype builds `jcstress.jar`
  with `maven-shade-plugin` and runs that. Rejected: shading is there so a *standalone* suite can be
  copied to another machine; inside a reactor it adds a packaging step, a second artifact per module,
  and a shade configuration to maintain, to reach a runner that `exec` already reaches.
- **Binding the runners unconditionally and filtering with `-t <regexp>` / `-e`.** Rejected: with no
  matching tests both tools still exit non-zero, so every harness-less module would fail. The skip
  property makes the module's intent explicit and greppable, and the lint keeps it honest.
- **`-Djmh.forks=0` (JMH's embedded mode) to allow `exec:java`.** Rejected: fork isolation is not
  incidental to JMH, it is how a benchmark avoids inheriting the JIT profile of everything Maven ran
  before it. Trading measurement validity for a simpler plugin binding is the wrong direction for a
  gate that exists to produce numbers.
- **Leaving the two CI jobs pointed at profiles that do not exist, and documenting them as pending.**
  Rejected on principle: a green check nobody can distinguish from a real one is worse than a missing
  check, for the reason ADR-0006 already records — it is believed.

## Consequences

- **The two CI jobs now measure something, and the roadmap's warning about them is retired.** Both
  were verified end to end on the toolchain CI uses (Temurin 21) and on the published baseline
  (Temurin 17), on Windows: `-Pjmh verify` runs two benchmarks and writes
  `target/jmh-result.json`; `-Pjcstress verify` runs the stress test across every VM configuration
  jcstress can construct (28 results on 17, 14 on 21 — biased locking is gone in 21) with zero
  failures.
- **The jcstress gate fails the build on a forbidden observation** — verified by marking an outcome
  the run *does* observe as `FORBIDDEN`: `AssertionError: TEST FAILURES: … Observed forbidden state`,
  exit 1, `BUILD FAILURE`. The harness is an assertion, not a smoke test.
- **A non-ASCII character in a jcstress annotation string breaks the run.** Found by running it: the
  generated `META-INF/TestList` is a length-prefixed format whose lengths are counted in characters
  while the reader consumes bytes, so one em dash desynchronises the whole file and jcstress dies in
  `TestList.getTests` with `NumberFormatException: For input string: ""` — a failure that names
  nothing you wrote. **Keep jcstress `desc`/`@Description` strings ASCII-only.** This repository's
  house style uses em dashes everywhere, so the trap is easy to walk into twice.
- **jcstress writes its result blob to the working directory and offers no option to move it.** `-r`
  governs only the HTML report, so every run dropped an untracked
  `jcstress-results-<timestamp>.bin.gz` next to the module's `pom.xml`, one `git add -A` from being
  committed — the same class of near-miss item 1.4 hit with `.gitignore`. Fixed by pointing the exec
  process at `${project.build.directory}`.
- **Plugin-level `<configuration>` could not be used for the runners.** Both profiles declare
  `exec-maven-plugin`; with `-Pjmh,jcstress` the two plugin-level blocks merge, one set of
  `<arguments>` wins, and both executions run the same command. Measured, not reasoned: **jcstress
  ran twice, JMH never ran, and the build reported success.** The configuration therefore lives in
  each `<execution>`, and the combined activation is now part of the verification set.
- **An opt-in without sources fails obscurely, which is why the lint check exists.** Verified:
  `jcstress.skip=false` in a module with no harness dies in `TestList.getTests` with a
  `NullPointerException` on an absent resource. Nothing in that message mentions the missing
  directory.
- **Format and lint now cover the harness roots, and did not before.** Spotless's default include set
  is `src/main/java` + `src/test/java` only, and Checkstyle's default source roots are the *active*
  compile roots — which `src/bench/java` becomes only under `-Pjmh`. Both were extended explicitly,
  and both were verified to read the new files (Spotless reformatted the benchmark; a deliberate star
  import failed `checkstyle:check` in each root). Without that, `spotless:apply` would not have been
  able to format harness code at all.
- **The harnesses are NOT compiled by a plain `mvn verify`.** A compile error in a benchmark is caught
  by the `benchmark` / `jcstress` jobs (Linux + JDK 21), not by the six build cells. That is the cost
  of profile activation; the alternative is paying JMH's code generation on every build in every cell.
- **The skeleton measures the budget's floor, not a placeholder.** NFR-01 budgets `Lazy.get()` at
  ≤ 2 ns/op, which is a volatile read plus a branch, so the benchmark measures a volatile field read
  against a final field read. On the development machine (Windows, 4 CPUs, single fork/iteration) both
  land at 0.4–0.6 ns/op, so **NFR-01 has roughly 3-4x headroom over its own floor** — informational
  only, at this iteration count, but it is the first evidence the budget is reachable at all. Item 2.2
  replaces the subject with the real `Lazy<T>` and keeps the harness.
- **Every later NFR item now has somewhere to land, and one property to flip.** Items 2.2, 5.1 and
  5.2 add sources under the module's harness root and set the switch; nothing in the build changes.
  Item 8.3 (nightly perf tracking) has `target/jmh-result.json` to read instead of console output.

## References

- Spec §6 (verification & test strategy); NFR-01 (`Lazy.get()` ≤ 2 ns/op + jcstress publication),
  NFR-02, NFR-03, NFR-04, NFR-05, NFR-06.
- ADR-0006 (`*:*:*:*:test` scope exemption, which is what makes test scope viable here).
- `tools/consistency_lint.py` → `check_harness_opt_in`.
- JMH 1.37, jcstress 0.16, build-helper-maven-plugin 3.6.0, exec-maven-plugin 3.6.3 — latest stable
  of each; jcstress offers no option to relocate its `.bin.gz` result blob (`-h` output checked).
