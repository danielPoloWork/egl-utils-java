# ADR-0009: Run ErrorProne + NullAway on the JDK 21+ cells, and enforce warnings-as-errors everywhere

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP items 1.11, 1.4 (which deferred this), 1.8 (harness profiles);
  ADR-001 / ADR-0006 (dependency policy); AGENTS.md §9, §10; NFR-07 (JDK 17 baseline);
  RFC-0001 (nullability contracts); `.mvn/README.md`

## Context

`AGENTS.md` §9 and §10, the rendered specification, the PR template and the CI job title
(`quality / spotless + errorprone + checkstyle + jacoco + pit`) have all named **ErrorProne +
NullAway** and **warnings-as-errors** since the scaffold. Neither was configured. The quality bar was
a description of a build that did not exist — the same class of claim-without-a-gate that items 1.3,
1.7, 1.8 and 1.10 each closed in their own area.

Item 1.4 attempted the wiring and removed it again after CI disproved three assumptions in a row.
Those findings framed this item, and re-measuring them changed two:

**(a) ErrorProne cannot run on the Temurin 17 cells — but a version that can does exist.** Reading the
class-file majors out of the published `error_prone_core` jars: 2.31.0 → 55, **2.36.0 / 2.38.0 /
2.40.0 / 2.41.0 / 2.42.0 → 61**, **2.43.0 / 2.44.0 / 2.50.0 → 65**. So `2.42.0` is the last release
that loads on JDK 17, and the choice the roadmap posed ("a JDK-21-only profile, **or** a version built
for 17") is a genuine either/or rather than a constraint. Verified further: 2.42.0 + NullAway 0.13.8
finds the same violations on JDK 17 **with the identical flag set**, and both 2.42.0 and 2.50.0 also
work on JDK 25.

**(b) The javac flags are hard requirements, and there are three of them, not two.** Each was removed
in turn and the build failed naming it: *"The default compilation policy (by-todo) is not supported by
Error Prone, pass `-XDcompilePolicy=simple` instead"*, *"The default `--should-stop=ifError` policy
(INIT) is not supported by Error Prone"*, *"`-XDaddTypeAnnotationsToSymbol=true` is required by Error
Prone on JDK 21"*. Item 1.4 recorded the last two; `compilePolicy` belongs on the list.

**(c) The `ProvisionException` that item 1.4 read as a version conflict is an ambiguous wrapper.**
`ProvisionException: Failed to initialize com.uber.nullaway.NullAway` was reproduced here **from a
compatible pair** simply by omitting `-XepOpt:NullAway:AnnotatedPackages` — its cause then reads
*"NullAway configuration is incorrect. Must either specify annotated packages … or pass
OnlyNullMarked"*. The version conflict is nevertheless real: 2.50.0 + 0.12.7 **with** the option set
fails with the same wrapper but a different cause — `NoClassDefFoundError:
com/google/errorprone/predicates/type/DescendantOf`, a class removed from ErrorProne after 0.12.7 was
built. So item 1.4's conclusion holds and its diagnosis was incomplete: **read the cause chain, not
the wrapper.** NullAway compiles against ErrorProne's internal check API, which is why the pair — not
each latest-stable — is the unit of choice.

## Decision

**ErrorProne 2.50.0 + NullAway 0.13.8, in a profile that self-activates on `<jdk>[21,)</jdk>`.**

- **Self-activating, not `-P`-selected.** A flag every contributor and CI job must remember is a gate
  that eventually does not run. The activation makes the analysis automatic wherever it can execute
  and inert where it physically cannot.
- **NullAway at `ERROR`** (`-Xep:NullAway:ERROR`) with `AnnotatedPackages=it.d4np`. A nullability
  finding that only warns is the review promise this replaces.
- **Generated sources are excluded** (`-XepExcludedPaths:.*/target/generated-(test-)?sources/.*`) —
  required, not prophylactic: see Consequences.
- **`<failOnWarning>true</failOnWarning>` and `<showWarnings>true</showWarnings>` are set globally**,
  in the parent's `pluginManagement`, not inside the profile — a javac warning is a warning on either
  toolchain, so all six build cells enforce AGENTS.md §10's promise, including the three where
  ErrorProne cannot run.
- **No nullability annotation artifact is added.** NullAway needs one only where something *is*
  nullable; core has no production types yet, ADR-001 fixes it at zero third-party dependencies, and
  RFC-0001 states its contracts in Javadoc. The annotation choice arrives with the first nullable
  member (item 2.1) — the same no-speculation discipline items 1.6 and 1.8 applied.
- **Suppressions are narrow or not at all.** `@SuppressWarnings("CheckName")` on the smallest possible
  element, with a comment; never `-XepDisableAllChecks`, never a blanket severity downgrade
  (AGENTS.md §10: "no broad disables").

## Alternatives Considered

- **ErrorProne 2.42.0 with no JDK gate**, so all six cells and every contributor run the analysis.
  Rejected on one durable consequence: 2.42.0 is the *last* release that loads on JDK 17, so this
  choice freezes static analysis at a 2025 release for as long as JDK 17 is the published baseline —
  and NFR-07 makes that the supported floor indefinitely, since raising it is a MAJOR bump. Verified
  working (17, 21 and 25), so the option remains available to a future maintainer who needs analysis
  on 17 specifically; the cost is stated so the trade is visible.
- **Two JDK-ranged profiles**: 2.42.0 for `[17,21)`, 2.50.0 for `[21,)`. Gives analysis everywhere and
  currency where possible. Rejected because it makes "clean" JDK-dependent: a check added after 2.42.0
  fires only on the 21 cells, and a false positive fixed after 2.42.0 fires only on the 17 cells,
  forcing a suppression that the newer analyzer does not need. Divergent analyzers producing
  contradictory verdicts across cells is a worse failure mode than an honest gap.
- **Leaving ErrorProne out and keeping the claims.** Rejected — that is the state item 1.11 exists to
  end.
- **`-Xep:NullAway:WARN` to start, promoting later.** Rejected: with nothing yet annotated the
  promotion has zero cost today and every cost later, once findings accumulate against a warning that
  nobody has to fix.
- **Forking the compiler (`<fork>true</fork>`) with `-J--add-exports` arguments** instead of relying on
  `.mvn/jvm.config`. Not needed — the export set is already there for google-java-format and was
  verified sufficient — and a forked javac per module would pay process startup for every compile.

## Consequences

- **The three JDK-17 build cells compile without static analysis, and a contributor on JDK 17 sees no
  findings locally.** Stated rather than hidden. Nothing ships unanalyzed: the same sources are
  analyzed by the three JDK-21 cells and the `quality` job on every PR, and `release 17` still governs
  the bytecode everywhere. The residual risk is a slower feedback loop for 17-only contributors, which
  `docs/development/local-build.md` now names.
- **The `quality` job's title stops being aspirational.** It runs `mvn -B verify` on JDK 21, so
  "spotless + errorprone + checkstyle" is now what it does — the jacoco and pit halves remain item 8.2.
- **Excluding generated sources is required, and item 1.8 is why.** With the exclusion removed,
  ErrorProne reports **12 `ThreadPriorityCheck` findings in JMH-generated code**
  (`PublicationBaselineBenchmark_*_jmhTest.java`), and because warnings are errors the build fails in
  `default-testCompile`. Generated code is not ours to fix, and suppressing inside it is impossible.
- **`.mvn/jvm.config` is load-bearing for ErrorProne, now verified rather than anticipated.** With the
  file removed, compilation dies with `IllegalAccessError: class
  com.google.errorprone.BaseErrorProneJavaCompiler … cannot access class
  com.sun.tools.javac.api.BasicJavacTask … because module jdk.compiler does not export
  com.sun.tools.javac.api`. Item 1.4's README note predicted this item would need the same export set;
  the existing eight exports plus two opens are sufficient, so no flag was added.
- **The analyzers do not enter any module's dependency graph.** `annotationProcessorPaths` are resolved
  independently of project dependencies — verified with `dependency:tree` on `d4np-core`, whose graph
  is still nothing but test-scoped JUnit and AssertJ. NullAway's own JSpecify and Guava dependencies
  therefore never reach a consumer, and ADR-0006's default-deny allowlists needed no edit.
- **Both harness profiles still work with the analyzer active.** `-Pjmh,jcstress verify` on JDK 21
  runs both harnesses and both annotation processors: item 1.8's `combine.children="append"` on
  `default-testCompile` appends its processor path to this profile's plugin-level list rather than
  replacing it. That merge was the main integration risk and it is now covered by a run.
- **`consistency_lint.py` gains `static-analysis-wired`**, asserting in both directions that the
  manifest's toolchain description and the POM agree, that NullAway is at `ERROR`, that it has
  `AnnotatedPackages` (or `OnlyNullMarked`), and that `failOnWarning` is on while AGENTS.md promises
  warnings-as-errors. It deliberately does **not** check the three javac flags or the generated-sources
  exclusion: dropping any of those fails the build loudly and by name, and a loud failure needs no
  lint. Severity and `failOnWarning` are the opposite — remove either and every finding still prints
  while the build turns green.
- **The lint's first draft was itself vacuous, which is recorded because it is instructive.** It read
  `AnnotatedPackages` out of the POM *comment that explains the option*, so deleting the option left
  the check green. POM text is now matched with comments stripped, mirroring `jpms-congruence`'s
  `_strip_java_comments`.
- **CI logs do not evidence that the analyzer ran, and that is worth knowing.** At `-B` verbosity
  `maven-compiler-plugin` prints the same line on both toolchains (`Compiling 1 source file with javac
  [debug release 17]`) and names no processor path, so a green JDK-21 job is consistent with the profile
  having activated *or* not. Activation here rests on the profile's JDK range, verified locally on the
  exact Temurin builds the matrix resolves (21.0.12+8 activates, 17.0.20+8 does not). To get direct
  CI-side evidence, push a commit containing a deliberate violation and watch the 21 cells fail while
  the 17 cells pass, or add `-X` to one job and look for `-Xplugin:ErrorProne` in the javac invocation.
  Neither was done here to avoid a throwaway pull request, since the workflow triggers only on
  `pull_request` and pushes to `main`.
- **A version bump is a paired bump.** Upgrading ErrorProne without checking NullAway (or the reverse)
  reproduces item 1.4's failure. Dependabot will offer them separately; the reviewer must verify the
  pair, and the cause chain — not the `ProvisionException` wrapper — is what identifies a mismatch.

## References

- Class-file majors read from published jars: `error_prone_core` 2.31.0 (55), 2.36.0–2.42.0 (61),
  2.43.0+ (65). JDK 17 loads major ≤ 61.
- Failure texts quoted above were produced locally on Temurin 21.0.12+8 and 17.0.20+8, Maven 3.9.9.
- `tools/consistency_lint.py` → `check_static_analysis`; `.mvn/README.md` (why the export set exists).
- ErrorProne bug patterns: <https://errorprone.info/bugpatterns>; NullAway configuration:
  <https://github.com/uber/NullAway/wiki/Configuration>.
