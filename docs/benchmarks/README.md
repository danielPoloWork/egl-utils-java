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
| 2026-08-08 | `SimpleJdbcExecutor` row mapping vs a hand-written `ResultSet` loop, 10k rows, H2 in memory — the NFR-03 budget | v0.1.0 + item 4.3 | **Met, and the first budget here whose verdict depends on the statistic:** ratio **1.071 (JDK 21) / 1.100 (JDK 17)** on the raw mean and **0.952 / 1.051** on the median of per-fork means, against a ≤ 1.10 ceiling. The ~5% steady-state overhead is the one virtual call per row RFC-0003 predicted. Also the evidence behind item **8.8**: at CI's 1-fork settings the same ratio spans **0.56–1.27**, so the gate the RFC promised cannot be wired at that sample size (**report-grade**, 5 forks x 10 iterations) | [report](2026-08-08-jdbc-row-mapping.md) |
| 2026-08-01 | `StrategyRegistry.find` at 1k strategies, 8 threads — the NFR-04 budget | v0.1.0 + item 2.3 | **12.8 ns/op (JDK 21) and 17.8 (JDK 17) against a ≤ 50 ns/op budget** — NFR-04 met with 2.8–4x headroom. Also refuted a documented claim: `getOrThrow` allocates no `Optional` and is nonetheless ~2 ns/op **slower** on JDK 21, while being indistinguishable on 17 — so the gap is one JIT's, not the code's (**report-grade**, 3 forks x 5 iterations) | [report](2026-08-01-strategy-registry-find.md) |
| 2026-08-01 | `Lazy.get()` steady state — the NFR-01 budget | v0.1.0 + item 2.2 | **0.827 ns/op (JDK 17) and 0.945 ns/op (JDK 21) against a ≤ 2 ns/op budget** — NFR-01 met with ~2x headroom, and only 0.2–0.4 ns/op above the bare volatile read, which is what says `get()` inlines (**informational**, 1 fork x 1 iteration) | [report](2026-08-01-lazy-get.md) |
| 2026-07-29 | Publication baseline — volatile vs. final field read | v0.0.0 | 0.4–0.6 ns/op on both toolchains, so NFR-01's 2 ns/op budget has 3–4x headroom over its floor (**informational**, 1 fork x 1 iteration) | [report](2026-07-29-publication-baseline.md) |

Harnesses live under `<module>/src/bench/java/` and run via `mvn -B -Pjmh verify`; the jcstress
counterpart for thread-safety claims is `mvn -B -Pjcstress verify` over
`<module>/src/jcstress/java/`. Both are profile-gated test-scope roots — see
[ADR-0007](../adr/0007-nfr-harnesses-as-test-scope-profiles.md) and
[`../development/local-build.md`](../development/local-build.md) for the settings that separate a
PR-grade run from a publication-grade one.
