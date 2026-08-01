# Benchmark Report: `StrategyRegistry.find` — the NFR-04 budget

- **Date:** 2026-08-01
- **Version / commit:** v0.1.0 + ROADMAP item 2.3 (`feat/strategy-registry`)
- **Environment:** Intel Core i5-6600K @ 3.50 GHz (4 cores, no SMT), 32 GB RAM, Windows 10 Pro
  19045, Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, `--release 17`
- **Command:** `mvn -B -Pjmh verify -pl d4np-core -Djmh.forks=3 -Djmh.warmup.iterations=3
  -Djmh.iterations=5` — **3 forks x 5 measurement iterations**, not the PR-grade default

> **Report-grade, but still not spec §6's 5x10 on the reference machine.** Item 2.2's report used the
> one-fork CI defaults; this one does not, because the headline finding below is a ~2 ns difference
> that a single iteration cannot resolve. Three forks give confidence intervals, which is what makes
> the comparison honest. **NFR-04 remains advisory in CI** until item 8.3 pins the perf gate to a
> stable runner.

## Scenario

`StrategyRegistryFindBenchmark` (`d4np-core/src/bench/java/it/d4np/utils/`) reproduces NFR-04's shape
literally rather than approximately:

| NFR-04 says | The harness does |
|---|---|
| "at 1k strategies" | fills the registry with **1000** entries in `@Setup(Level.Trial)` |
| "under 8-thread read load" | `@Threads(8)` on both benchmarks |
| "`find` ≤ 50 ns/op" | `AverageTime` mode, nanoseconds |

Each thread **rotates through the key space** from `Scope.Thread` cursor state, so the measurement
includes cache misses across the whole table rather than one hot line that never leaves L1 — which is
what "at 1k strategies" is asking about. A shared counter would have measured contention on the
counter instead of on the map.

Keys are zero-padded to equal length (`strategy-0042`), so the number is not quietly a benchmark of
`String.hashCode` over strings of differing sizes.

Both benchmarks measure a **hit**. `getOrThrow`'s miss path builds an exception carrying a sorted,
rendered copy of every key; it is deliberately expensive and deliberately unbudgeted, because it
happens once, at wiring time, and then the application fails.

## Results

| Metric | Temurin 17 | Temurin 21 | Budget |
|--------|-----------|-----------|--------|
| **`findHit`** — the NFR-04 figure | **17.800 ± 4.293 ns/op** | **12.802 ± 0.997 ns/op** | ≤ 50 ns/op |
| `getOrThrowHit` | 18.018 ± 0.737 ns/op | 14.950 ± 0.537 ns/op | not budgeted |

**NFR-04 is met on both toolchains** — with roughly 4x headroom on JDK 21 and 2.8x on JDK 17, on
hardware slower than the spec's reference machine (Ryzen 7 5800X). Even the top of JDK 17's wide
interval (22.1 ns/op) sits at less than half the budget, which is what makes the conclusion robust
despite the spread.

## Interpretation

### The budget

`find` is a `ConcurrentHashMap.get` plus one `Optional` allocation, and lands well inside the budget
on both toolchains. The `Optional` is accepted rather than overlooked: RFC-0001 states the budget
against `find` at scale precisely so that the number includes it.

### The claim this benchmark refuted — and how far the refutation reaches

`getOrThrow` reads the map directly and allocates **no** `Optional`, so `StrategyRegistry`'s Javadoc
originally said it was the cheaper of the two. **It is not cheaper on either toolchain**, and on JDK
21 it is measurably *slower*:

| Run | `findHit` | `getOrThrowHit` | Separated? |
|---|---|---|---|
| JDK 21, first 3x5 | 12.303 ± 0.837 | 14.557 ± 0.622 | yes |
| JDK 21, after the inlining experiment below | 12.616 ± 0.972 | 14.572 ± 0.549 | yes |
| JDK 21, final code | 12.802 ± 0.997 | 14.950 ± 0.537 | yes |
| **JDK 17, final code** | **17.800 ± 4.293** | **18.018 ± 0.737** | **no — intervals overlap** |

**The ~2 ns/op gap is a JDK 21 result and does not generalise.** On JDK 21 it is stable across three
independent multi-fork runs and the ordering never inverts. On JDK 17 the two are
**indistinguishable**: `find`'s interval is ±4.3 ns, wide enough that this toolchain cannot resolve a
2 ns difference at all, and stating one from it would be reading noise as signal.

That asymmetry is worth more than the gap itself. The honest summary is the weaker, portable one:
**`getOrThrow` is never faster than `find`, and on at least one supported toolchain it is slower** —
which is enough to retract the original claim, and not enough to support a new one about why.

**The obvious explanation was tested and is wrong.** The hypothesis was that constructing
`StrategyNotFoundException` inline inflates `getOrThrow` past the JIT's inlining size threshold — the
same reasoning that makes `Lazy.get()`'s fast/slow split load-bearing under NFR-01
([ADR-0013](../adr/0013-lazy-initialization-by-double-checked-volatile.md)). So the throw was moved
into a private method and the run repeated: **14.57 before, 14.57 after — no change whatsoever.** The
split was therefore **reverted** rather than kept, because a restructuring justified by a disproven
mechanism is ceremony borrowed from a sibling type.

The cause is **narrowed but not settled**, and is deliberately left open at ~2 ns inside a 50 ns
budget. The two honest candidates are the harness itself — the benchmarks return different types
(`Optional<T>` versus `T`), so JMH's blackhole does different work — and an escape-analysis effect on
the returned `Optional`. That the gap appears on JDK 21 and not on JDK 17 is consistent with either,
since both are JIT-dependent, so it narrows nothing further. Distinguishing them needs `-prof perfasm`
or `-XX:+PrintInlining`, which is worth doing when something actually depends on the answer.

**JDK 17 is also the slower toolchain overall here** — 17.8 versus 12.8 ns/op on the same hardware for
the same lookup, a gap far larger than the one this section is about. That is unsurprising for four
years of JIT work and is not investigated, because NFR-07 pins the *published baseline* at 17 while
NFR-04's budget is met with room on both.

**What is not in doubt is the guidance**, and the Javadoc now carries it: *do not choose between the
two lookups on speed.* Choose on what a missing key means. Both sit far enough inside the budget that
the difference is invisible in any real application, and `getOrThrowHit` stays in the suite so that
neither lookup can regress relative to the other unnoticed.

The transferable lesson is the one worth keeping: **avoiding an allocation is not the same as being
faster, and a plausible mechanism is not a measurement.**

## Reproduce

```bash
# From a clean checkout, with JAVA_HOME on Temurin 17 or 21:
mvn -B clean

# PR-grade (what CI runs): 1 fork, 1 iteration — proves the harness executes.
mvn -B -Pjmh verify -pl d4np-core

# Report-grade, as used above: 3 forks x 5 iterations, with confidence intervals.
mvn -B -Pjmh verify -pl d4np-core -Djmh.forks=3 -Djmh.warmup.iterations=3 -Djmh.iterations=5

# Publication-grade, on the reference machine (spec §6: 5 forks x 10 iterations):
mvn -B -Pjmh verify -pl d4np-core -Djmh.forks=5 -Djmh.warmup.iterations=10 -Djmh.iterations=10

cat d4np-core/target/jmh-result.json
```
