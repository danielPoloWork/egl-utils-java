# Benchmarks

Reproducible performance measurements for `egl-utils-java`. Any performance claim in the
spec, README, or a PR must be backed by a benchmark here and by code under
`src/bench/java/it/d4np/utils/`. Numbers without a reproducible method
are not evidence.

## Methodology

- **Harness:** `Maven 3.9+ (multi-module reactor)` builds the bench target; run with `mvn -B -Pjmh verify`.
- **Environment:** record the machine (CPU, RAM, OS), the toolchain version, and the build
  configuration (release/optimized) with every result — a number without its environment is
  not comparable.
- **Discipline:** warm up, run multiple iterations, report a central tendency **and** spread
  (e.g. median + p99), and pin the commit SHA the run was taken at.
- **Regression gate:** the CI `benchmark` job runs the suite; a result is a regression only
  against a recorded baseline on comparable hardware (note when CI hardware is too noisy to
  gate and the run is informational).

## Results

One report per measured scenario, from [`template.md`](template.md). Keep the index newest-first.

| Date | Scenario | Version | Headline result | Report |
|------|----------|---------|-----------------|--------|
| 2026-07-29 | Publication baseline — volatile vs. final field read | v0.0.0 | 0.4–0.6 ns/op on both toolchains, so NFR-01's 2 ns/op budget has 3–4x headroom over its floor (**informational**, 1 fork x 1 iteration) | [report](2026-07-29-publication-baseline.md) |

Harnesses live under `<module>/src/bench/java/` and run via `mvn -B -Pjmh verify`; the jcstress
counterpart for thread-safety claims is `mvn -B -Pjcstress verify` over
`<module>/src/jcstress/java/`. Both are profile-gated test-scope roots — see
[ADR-0007](../adr/0007-nfr-harnesses-as-test-scope-profiles.md) and
[`../development/local-build.md`](../development/local-build.md) for the settings that separate a
PR-grade run from a publication-grade one.
