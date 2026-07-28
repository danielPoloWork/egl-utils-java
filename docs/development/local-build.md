# Local Build & Test

How to build, test, and check `egl-utils-java` on your machine. CI runs the same commands
on Linux / Windows / macOS on Temurin JDK 17 & 21; reproducing them locally avoids a red round-trip.

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
