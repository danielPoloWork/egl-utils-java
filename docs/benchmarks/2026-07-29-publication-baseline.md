# Benchmark Report: publication baseline — volatile vs. final field read

- **Date:** 2026-07-29
- **Version / commit:** v0.0.0 @ ROADMAP item 1.8 (`build/jmh-jcstress-harness`)
- **Environment:** Intel Core i5-6600K @ 3.50 GHz (4 cores, no SMT), 32 GB RAM, Windows 10 Pro
  19045, Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, `--release 17`
- **Command:** `mvn -B -Pjmh verify` (PR-grade defaults: 1 fork, 1 warmup iteration, 1 measurement
  iteration of 1 s)

> **Informational, not an NFR result.** One fork and one iteration on a developer workstation is
> enough to prove the harness executes; it is not the 5x10 run on the named reference machine that
> spec §6 requires, and the two numbers below are close enough together to sit inside that run's
> noise. Cited here because the *magnitude* is what item 1.8 needed to know.

## Scenario

`PublicationBaselineBenchmark` (`d4np-core/src/bench/java/it/d4np/utils/`) times two single field
reads on the same object in `AverageTime` mode, nanoseconds:

- `volatileRead` — a `volatile int` read, the memory-barrier path a safely published value costs;
- `finalRead` — a `final int` read, the same access with no publication guarantee.

NFR-01 budgets `Lazy.get()` at **≤ 2 ns/op** in steady state, and that call is a volatile read of the
memoized value plus a branch. `Lazy<T>` does not exist yet (item 2.2), so this measures the *floor* of
that budget: if a bare volatile read already exceeded 2 ns/op on this class of hardware, the NFR would
be unreachable regardless of how `Lazy` is written.

## Results

| Metric | Value | Spread |
|--------|-------|--------|
| `volatileRead`, Temurin 17 | 0.548 ns/op | n/a — single iteration, no error estimate |
| `finalRead`, Temurin 17 | 0.422 ns/op | n/a |
| `volatileRead`, Temurin 21 | 0.606 ns/op | n/a |
| `finalRead`, Temurin 21 | 0.626 ns/op | n/a |

## Interpretation

Both reads cost **0.4–0.6 ns/op**, so NFR-01's 2 ns/op budget has roughly **3–4x headroom over its
own floor** on hardware slower than the spec's reference machine (Ryzen 7 5800X). The budget is
reachable; whether `Lazy.get()` reaches it depends on item 2.2's implementation, not on the barrier.

The volatile/final delta is **not** resolved at this iteration count — on JDK 21 the ordering even
inverts (0.606 vs 0.626), which is the expected shape of a measurement taken from a single 1-second
iteration near the noise floor. Read that inversion as "the two are indistinguishable here", not as
"volatile is free". On x86-64 a volatile *read* needs no fence, so a small delta is also the
theoretically expected result; a platform with weaker ordering would separate them.

## Reproduce

```bash
# From a clean checkout, with JAVA_HOME on Temurin 17 or 21:
mvn -B clean
mvn -B -Pjmh verify -pl d4np-core
cat d4np-core/target/jmh-result.json

# Publication-grade, on the reference machine (spec §6: 5 forks x 10 iterations):
mvn -B -Pjmh verify -pl d4np-core -Djmh.forks=5 -Djmh.warmup.iterations=10 -Djmh.iterations=10
```
