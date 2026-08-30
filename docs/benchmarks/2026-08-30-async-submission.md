# Benchmark Report: `AsyncExecutor` submission overhead — the NFR-02 budget

- **Date:** 2026-08-30
- **Version / commit:** v0.1.0 + ROADMAP item 5.2 (`feat/async-executor`)
- **Environment:** Intel Core i5-6600K @ 3.50 GHz (4 cores, no SMT), 32 GB RAM, Windows 10 Pro
  19045, Temurin **17.0.20.1+1** and **21.0.12.1+1**, Maven 3.9.9, `--release 17`
- **Command:** `mvn -B -Pjmh -pl d4np-concurrent verify -Djmh.forks=5 -Djmh.warmup.iterations=10
  -Djmh.iterations=10` — **5 forks × 10 measurement iterations**, not the PR-grade default

> **Verdict: met, by roughly three orders of magnitude.** NFR-02 allows ≤ 5 µs of submission
> overhead; the measured overhead is **at or below this harness's resolution** — one
> least-significant digit (1 ns) on JDK 21 and **exactly zero** on JDK 17, against a ±1–2 ns error on
> each arm. That is not a close call, and the report says so plainly rather than dressing the margin
> as a pass.

## The harness had to be rewritten before it measured the right thing

The obvious shape — submit to a real pool, `join()`, compare — was written first and is **wrong for
this requirement**. Every arm came out at **~12 µs, including the raw one**. That number is a thread
handoff: a park, an unpark and a context switch. The quantity NFR-02 names is buried inside it.

At CI's single iteration the arms were not even correctly ordered — the executor *with* a context
propagator measured faster than raw `supplyAsync`, which is noise reporting itself as a result. Any
budget read off that harness would have been meaningless in both directions.

So the budget is measured against an **inline executor** (`Runnable::run`) shared by both arms: no
handoff, no scheduling, no parking. The difference is this library's capture, wrap and complete, and
nothing else. **That is what "submission overhead" means.** The pooled pair is kept in the harness
and reported below, because deleting it would invite the reader to assume the budget includes the
handoff — it does not.

## Scenario

`AsyncSubmissionBenchmark` (`d4np-concurrent/src/bench/java/it/d4np/utils/concurrent/`), five arms:

| Arm | Executor | In the budget? |
|---|---|---|
| `inlineRawSupplyAsync` | `Runnable::run` | **yes — the floor** |
| `inlineAsyncExecutor` | `Runnable::run` | **yes — the budget is this minus the floor** |
| `inlineAsyncExecutorWithContext` | `Runnable::run`, 8-entry context copied per submission | no — what propagation costs |
| `pooledRawSupplyAsync` | `ManagedThreadPool`, 1 thread | no — the end-to-end floor |
| `pooledAsyncExecutor` | `ManagedThreadPool`, 1 thread | no — the end-to-end cost |

Every body is `() -> Boolean.TRUE`, so no arm measures work. `AverageTime` mode, microseconds.

## Results — Temurin 21.0.12.1+1

| Benchmark | Mode | Cnt | Score | Error | Units |
|---|---|---|---|---|---|
| `inlineRawSupplyAsync` | avgt | 50 | **0.012** | ± 0.001 | µs/op |
| `inlineAsyncExecutor` | avgt | 50 | **0.013** | ± 0.001 | µs/op |
| `inlineAsyncExecutorWithContext` | avgt | 50 | 0.013 | ± 0.001 | µs/op |
| `pooledRawSupplyAsync` | avgt | 50 | 11.865 | ± 0.511 | µs/op |
| `pooledAsyncExecutor` | avgt | 50 | 11.160 | ± 0.032 | µs/op |

**NFR-02 = `inlineAsyncExecutor` − `inlineRawSupplyAsync` = 0.013 − 0.012 = 0.001 µs against a
5 µs ceiling.** The margin is about **5000×**.

**Stated precisely, because the honest number is smaller than the interesting one:** the difference
is 1 ns and the reported error on each arm is ±1 ns, so the overhead is **at or below the harness's
resolution**. The correct claim is *"indistinguishable from the JDK's own submission at this
granularity"*, not *"exactly 1 ns"*. Either way the budget is met with room that no plausible change
to this code would consume.

### Two observations the budget does not ask for

- **Propagating an eight-entry context is free at this granularity, on both toolchains.**
  `inlineAsyncExecutorWithContext` measures the same as the plain arm — 0.013 µs on 21, 0.014 on 17 —
  so a `Map.copyOf` of eight entries does not move a 13 ns number. A reader deciding whether to enable propagation can do so on correctness grounds
  alone; the RFC's worry that a large-map capture might consume the 5 µs headroom is not reachable
  from anything MDC-shaped.
- **Through a real pool, `AsyncExecutor` is not slower than `supplyAsync` and is markedly steadier**
  — 11.160 ± 0.032 against 11.865 ± 0.511. The means overlap once the raw arm's error is taken into
  account, so this report does **not** claim ours is faster; what it does claim, because the error
  bars support it, is that the wrapper adds nothing measurable to an end-to-end async call. The
  sixteen-fold difference in spread is worth noticing and is not explained here.

## Results — Temurin 17.0.20.1+1

| Benchmark | Mode | Cnt | Score | Error | Units |
|---|---|---|---|---|---|
| `inlineRawSupplyAsync` | avgt | 50 | **0.014** | ± 0.001 | µs/op |
| `inlineAsyncExecutor` | avgt | 50 | **0.014** | ± 0.002 | µs/op |
| `inlineAsyncExecutorWithContext` | avgt | 50 | 0.014 | ± 0.002 | µs/op |
| `pooledRawSupplyAsync` | avgt | 50 | 12.143 | ± 0.482 | µs/op |
| `pooledAsyncExecutor` | avgt | 50 | 11.960 | ± 0.549 | µs/op |

**On JDK 17 the overhead is 0.000 µs — all three inline arms measure identically.** That is a
cleaner result than JDK 21's, where the wrapper was one least-significant digit above the floor, and
it says the same thing: at this granularity `AsyncExecutor`'s submission is indistinguishable from
the JDK's own. Both toolchains meet a 5 µs budget with a margin of roughly **350×** against the whole
measured submission path, never mind against the difference.

The pooled pair behaves the same way on 17 as on 21 — the means are within each other's error, so
neither arm is claimed faster.

## Why this is not a CI gate, and why the reason differs from NFR-03's

NFR-03 is a **relative** ratio between two arms in one JMH invocation: a slow runner slows both and
the ratio holds, which is why item 4.3 could recommend it as a real gate and route the remaining work
— fork and iteration counts — to **item 8.8**.

NFR-02 is phrased relatively but **bounded absolutely** at 5 µs, so a loaded runner moves the
difference itself and not merely the scale. It belongs with NFR-01's 2 ns/op and NFR-06's 400 MB/s in
**item 8.3**'s stable-runner problem. Until 8.3 lands the numbers here are tracked on the reference
machine and advisory in CI (RFC-0004 §Scalability budgets).

**The budget is also extremely loose against what it measures**, and that is worth recording rather
than enjoying: 5 µs is roughly 400× the whole measured submission path. A regression that made
`AsyncExecutor` a hundred times slower would still pass. Whether the ceiling should be tightened is
the audit phase's question, and RFC-0004 routes it there rather than answering it here — but the
number a future reader needs in order to answer it is in the table above.

## Reproducing

```bash
mvn -B -Pjmh -pl d4np-concurrent verify \
    -Djmh.forks=5 -Djmh.warmup.iterations=10 -Djmh.iterations=10
```

PR-grade (one fork, one iteration) is what `mvn -B -Pjmh verify` runs and is enough to prove the
harness executes. It is **not** enough to read the budget off: at that sample size the two inline
arms were measured at 0.019 and 0.013 µs in the wrong order relative to their report-grade values.
Machine-readable output lands in `d4np-concurrent/target/jmh-result.json` for item 8.3.
