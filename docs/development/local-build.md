# Local Build & Test

How to build, test, and check `egl-utils-java` on your machine. CI runs the same commands
on Linux / Windows / macOS on Temurin JDK 17 & 21 — **six matrix cells, all three platforms on both
toolchains** (item 1.5) — so reproducing them locally avoids a red round-trip.

Format and lint additionally run on a two-platform `format-lint` matrix (Linux + Windows). That is not
redundancy: see [ADR-0004](../adr/0004-declare-line-endings-and-cross-platform-format-checks.md) — a
formatting verdict can differ by platform, and it did.

## Prerequisites

- **Java 21 (LTS)** toolchain.
- **Build system:** Maven 3.9+ (multi-module reactor).
- **Package manager:** Maven Central via Sonatype; GPG-signed artifacts, sources + javadoc JARs.
- **Formatter / linter:** Spotless (google-java-format), ErrorProne + NullAway + Checkstyle; maven-enforcer for the ADR-001 dependency rules.
- **Docs:** Javadoc (for the API docs build).

## Commands

```bash
# Build
mvn -B clean verify

# Test
mvn -B test

# Format check — the -pl exclusion is REQUIRED, see "Why Spotless skips the BOM" below
mvn -B spotless:check -pl '!d4np-bom'

# Format fix (rewrites the sources; google-java-format's output is not negotiable)
mvn -B spotless:apply -pl '!d4np-bom'

# Lint — no exclusion needed, clean on all ten modules
mvn -B checkstyle:check

# Benchmark (JMH) and concurrency stress (jcstress) — see "The NFR harnesses" below
mvn -B -Pjmh verify
mvn -B -Pjcstress verify

# Cross-artifact congruence (run before drafting any PR)
python tools/consistency_lint.py
```

## Static analysis: ErrorProne + NullAway need JDK 21

Since item 1.11 the analyzers are bound to **compilation itself** — there is no separate command, and
no `-P` flag to remember. Design and alternatives:
[ADR-0009](../adr/0009-errorprone-nullaway-on-jdk-21-cells.md). What you need to know:

- **They run on JDK 21+ only, automatically.** ErrorProne 2.43.0 onward is compiled to class-file 65,
  so it cannot load on JDK 17 at all; the `errorprone` profile self-activates on `<jdk>[21,)</jdk>`.
  **Build on 21 before you push.** On a JDK-17 toolchain the compile succeeds with no analysis, and
  the three JDK-21 CI cells will be the first thing to tell you otherwise.
- **NullAway is an ERROR, not a warning**, over `AnnotatedPackages=it.d4np`. A null dereference or a
  `null` passed where non-null is required fails the build.
- **Warnings are errors on every cell** (`failOnWarning` on `maven-compiler-plugin`, JDK 17 included).
  That is AGENTS.md §10's bar, now enforced rather than promised.
- **Suppress narrowly or not at all.** `@SuppressWarnings("TheCheckName")` on the smallest element that
  needs it, with a comment saying why. Never `-XepDisableAllChecks`, never a severity downgrade in the
  POM — AGENTS.md §10 forbids broad disables, and a downgrade is invisible in review.
- **Upgrade ErrorProne and NullAway as a PAIR.** NullAway compiles against ErrorProne's internal check
  API. A mismatch fails with `ProvisionException: Failed to initialize com.uber.nullaway.NullAway`,
  whose *cause* names the real problem — `NoClassDefFoundError` for a moved ErrorProne class means the
  versions disagree; *"NullAway configuration is incorrect"* means an option is missing. Dependabot
  offers them separately, so check both.
- **Do not delete `.mvn/jvm.config`.** ErrorProne runs inside the compiler's JVM and needs its
  `jdk.compiler` exports; without them compilation dies with `IllegalAccessError … module jdk.compiler
  does not export com.sun.tools.javac.api`. See [`../../.mvn/README.md`](../../.mvn/README.md).
- **Generated code is out of scope** (`-XepExcludedPaths` covers `target/generated-*-sources`), because
  JMH's generated runners trip `ThreadPriorityCheck` and cannot be edited or annotated.

## The NFR harnesses

Two profiles, both real since item 1.8 — before that CI invoked them and Maven only *warned* that
they did not exist, so the performance and concurrency gates passed having measured nothing. The
design and the alternatives are in
[ADR-0007](../adr/0007-nfr-harnesses-as-test-scope-profiles.md); what you need to work with them:

- **Where the code goes.** `<module>/src/bench/java/…` for JMH, `<module>/src/jcstress/java/…` for
  jcstress. Both are compiled as **test** source roots and only when the matching profile is active,
  so `jmh-core` and `jcstress-core` stay out of every published JAR.
- **Opt in, or it never runs.** The parent defaults `jmh.skip`/`jcstress.skip` to `true`. A module
  that owns harness sources sets the switch to `false` in its own `<properties>` — `d4np-core` does.
  `consistency_lint.py`'s `harness-opt-in` fails the build if the sources and the switch disagree
  either way, because both tools exit non-zero on an empty test list and neither says why.
- **CI settings are PR-grade, not publication-grade.** One fork, one warmup and one measurement
  iteration, `-m sanity`. For a number that backs an NFR, use the reference machine and override:

  ```bash
  mvn -B -Pjmh verify -Djmh.forks=5 -Djmh.warmup.iterations=10 -Djmh.iterations=10
  mvn -B -Pjcstress verify -Djcstress.mode=default
  ```

- **Results.** `<module>/target/jmh-result.json` (machine-readable, for item 8.3) and
  `<module>/target/jcstress-results/index.html`. Record anything you intend to cite under
  [`../benchmarks/`](../benchmarks/).
- **Keep jcstress annotation strings ASCII.** The generated `META-INF/TestList` counts string lengths
  in characters and reads them as bytes, so a single em dash desynchronises the file and the run dies
  with `NumberFormatException: For input string: ""` — a message that points at nothing you wrote.
- **A plain `mvn verify` does not compile the harnesses.** Run the profile before pushing; the six
  build cells will not catch a harness that stopped compiling.

## Why Spotless skips the BOM

`d4np-bom` deliberately carries **no parent** (ADR-001 / NFR-09: a BOM must not push the reactor's
build configuration into a consumer's dependency resolution). It therefore cannot inherit the
Spotless plugin declaration, and Spotless refuses to run on a project that does not declare it —
`No plugin found for prefix 'spotless'`, or `Spotless plugin absent from the project` if you invoke
it by full coordinates. Excluding the module is also the semantically correct answer: the BOM has no
`src` tree, so a Java formatter has nothing to format there.

Checkstyle needs no exclusion: `maven-checkstyle-plugin` is in the default pluginGroup
`org.apache.maven.plugins`, so its goal prefix resolves without inheritance.

## The dependency policy runs on every build

`maven-enforcer` is bound to `validate`, so `mvn -B clean verify` already enforces ADR-001 / NFR-08 —
you do not need a separate command. To check only the policy:

```bash
mvn -B validate
```

**Do not use `mvn enforcer:enforce`.** A bare CLI goal runs only the `default-cli` execution, which
sees the parent's universal rules and **silently skips every per-module rule** — it reports success
with a banned dependency present. CI made exactly that mistake until item 1.7.

Each module declares its own policy, and the form differs by contract
([ADR-0006](../adr/0006-enforce-the-dependency-policy-per-module.md)):

- **`core`, `jdbc`, `concurrent`, `test`, `security`, `json` are default-deny.** Adding a compile
  dependency means adding it to that module's `<includes>` allowlist. That is deliberate friction —
  "core has zero third-party dependencies" is a contract, not a habit. Test-scope artifacts are
  exempt via a scope wildcard, so upgrading JUnit or adding a test library needs no change.
- **`spring-adapter` and `lock-redisson` are default-allow**, banning only the frameworks that are
  foreign to them, because an allowlist there would have to track Spring's and Redisson's transitive
  closures.

If enforcer fails, it names the artifact and the path it arrived by. Extending an allowlist is the
right fix when the dependency is legitimate for that module; if it is not, the rule just did its job.

## JPMS: every code module ships a `module-info.java`

Eight modules carry a descriptor; `d4np-bom` cannot (no sources). `d4np-core` owns the family root
`it.d4np.utils`, so **only core may put a type there** — two modules sharing one package is a split
package and the module system rejects it outright. Capability modules use `it.d4np.utils.<capability>`.
Names and the full graph: [ADR-0005](../adr/0005-jpms-module-names-and-export-less-descriptors.md).

Three things to know before editing one:

- **`requires` must mirror the POM.** `consistency_lint.py`'s `jpms-congruence` check compares the
  internal `requires` edges against the module's internal `<dependency>` entries and fails on any
  disagreement — so add both, or neither.
- **You cannot `exports` a package that has no class in it.** `javac` rejects it with *"package is
  empty or does not exist"*; `exports` is not a forward declaration, and a lone `package-info.java`
  does **not** satisfy it. Add the `exports` clause in the same change as the first type.
- **Checkstyle never sees these files.** Checkstyle 10.26.1 cannot parse a module declaration, so
  `**/module-info.java` is excluded in the parent POM. Spotless *does* format them.

Inspect what actually got built — the descriptor in the JAR, not the source:

```bash
# Separator is ':' on Linux/macOS and ';' on Windows — the module path is not a shell path.
MP="$(ls d4np-*/target/*.jar | paste -sd:)"
java --module-path "$MP" --describe-module it.d4np.utils.spring
# Resolve the whole graph at once; a broken `requires` fails here with FindException.
java --module-path "$MP" --add-modules ALL-MODULE-PATH -version
```

## Line endings are declared, not inherited

A root `.gitattributes` normalises text files to **LF**. Do not delete it and do not "fix" your line
endings locally: without it, Spotless falls back to the platform's native ending (it reads
`GIT_ATTRIBUTES` by default), and on Windows `spotless:check` then reports **every line** of a file as
a violation while the same commit passes on Linux. Details and the rejected alternatives are in
[ADR-0004](../adr/0004-declare-line-endings-and-cross-platform-format-checks.md).

If your working tree predates that file, `git add --renormalize .` is the one-time correction.

## A warning about `.mvn/jvm.config`

That file takes **one JVM flag per line and supports no comments** — a `#` line makes every Maven
command fail, including `mvn -v`. See [`../../.mvn/README.md`](../../.mvn/README.md) before editing it.

## Before you open a PR

1. `mvn -B spotless:check -pl '!d4np-bom'` and `mvn -B checkstyle:check` are clean.
2. `mvn -B test` passes; new/changed behavior is covered (≥ 85% line).
3. ErrorProne/NullAway (compile-time soundness), jcstress (concurrency), JFR leak profiling, OWASP Dependency-Check are green where applicable.
4. `python tools/consistency_lint.py` passes.
5. The relevant docs (README, ROADMAP, ADRs, patterns, changelog) are updated in the same
   PR — see [`../workflow/documentation.md`](../workflow/documentation.md).
