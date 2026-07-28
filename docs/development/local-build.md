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

# Benchmark
mvn -B -Pjmh verify

# Cross-artifact congruence (run before drafting any PR)
python tools/consistency_lint.py
```

## Why Spotless skips the BOM

`d4np-bom` deliberately carries **no parent** (ADR-001 / NFR-09: a BOM must not push the reactor's
build configuration into a consumer's dependency resolution). It therefore cannot inherit the
Spotless plugin declaration, and Spotless refuses to run on a project that does not declare it —
`No plugin found for prefix 'spotless'`, or `Spotless plugin absent from the project` if you invoke
it by full coordinates. Excluding the module is also the semantically correct answer: the BOM has no
`src` tree, so a Java formatter has nothing to format there.

Checkstyle needs no exclusion: `maven-checkstyle-plugin` is in the default pluginGroup
`org.apache.maven.plugins`, so its goal prefix resolves without inheritance.

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
