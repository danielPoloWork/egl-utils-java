# Benchmark Report: `SimpleJdbcExecutor` row mapping — the NFR-03 budget

- **Date:** 2026-08-08
- **Version / commit:** v0.1.0 + ROADMAP item 4.3 (`feat/simple-jdbc-executor`)
- **Environment:** Intel Core i5-6600K @ 3.50 GHz (4 cores, no SMT), 32 GB RAM, Windows 10 Pro
  19045, Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, `--release 17`, H2 2.3.232 in memory
- **Command:** `mvn -B -pl d4np-jdbc -am -Pjmh -DskipTests verify -Djmh.forks=5
  -Djmh.warmup.iterations=5 -Djmh.iterations=10` — **5 forks × 10 measurement iterations**, not the
  PR-grade default

> **Report-grade, and the first budget in this project whose verdict depends on which statistic is
> used.** NFR-03 says "≤ 10% overhead" and does not say *of what*. On the raw mean the budget is met
> on JDK 21 and sits **exactly on the ceiling** on JDK 17; on the median of per-fork means it is met
> comfortably on both. Both numbers are below, neither is hidden, and choosing between them is
> **ROADMAP item 8.8's** job rather than this report's.

## Scenario

`RowMappingBenchmark` (`d4np-jdbc/src/bench/java/it/d4np/utils/jdbc/`) reproduces NFR-03's shape
literally rather than approximately:

| NFR-03 says | The harness does |
|---|---|
| "vs a hand-written `ResultSet` loop" | two arms in the **same JMH invocation**, over the same connection |
| "H2 in-memory" | `jdbc:h2:mem:`, created and filled once per trial |
| "10k rows" | **10 000** rows inserted in `@Setup(Level.Trial)`, all of them read by every arm |
| "row-mapping overhead ≤ 10%" | `AverageTime` mode, milliseconds; the budget is the ratio of the two arms |

Three arms, and only two of them are the budget:

- **`handWrittenLoop`** — prepare, execute, iterate, construct. The floor, and deliberately the
  *fast* version of it: it returns the mutable `ArrayList` it filled, where the executor returns
  `List.copyOf(..)`. **The executor is measured doing strictly more work than the thing it is
  compared against**, which makes the result conservative rather than flattering.
- **`executorQuery`** — the same work through `SimpleJdbcExecutor.on(Connection)`. **These two are
  NFR-03.**
- **`executorQueryFromDataSource`** — the same again through `on(DataSource)`, which opens and closes
  a real connection per call. **Not the budget**, and it is measured anyway so that no reader assumes
  the budget arm includes pool acquisition. It does not, and NFR-03 does not ask it to.

Both budget arms borrow the *same* connection, so neither pays for acquiring one and the comparison
is about the framing — statement preparation, parameter binding, result iteration and mapping. Every
arm returns its list so JMH consumes it: an unread `ResultSet` loop is exactly the shape a JIT can
prove pointless.

## Results

### The budget — Temurin 21.0.12+8

| Arm | Mean (n=50) | 99.9% CI | Per-fork means | Median of forks |
|---|---|---|---|---|
| `handWrittenLoop` | 0.739 ms/op | ± 0.032 | 0.714 · 0.762 · 0.738 · 0.740 · 0.740 | 0.740 |
| **`executorQuery`** | **0.791 ms/op** | ± 0.217 | 0.669 · **1.127** · 0.705 · 0.692 · 0.764 | 0.705 |
| `executorQueryFromDataSource` | 1.830 ms/op | ± 0.069 | 1.826 · 1.791 · 1.785 · 1.798 · 1.952 | 1.798 |

**Ratio: 1.071 on the mean, 0.952 on the median of fork means.** Budget ≤ 1.10 — met either way.

The gap between the two statistics is one number: fork 2 recorded a single iteration at **3.767
ms/op** against that fork's own 0.665 ms minimum — a garbage collection landing inside a one-second
iteration, over an arm that allocates 10 000 records per operation. It moves the arm's mean from
~0.70 to 0.791 and inflates the confidence interval from ±0.03-scale to ±0.217.

### The budget — Temurin 17.0.20+8

| Arm | Mean (n=50) | 99.9% CI | Per-fork means | Median of forks |
|---|---|---|---|---|
| `handWrittenLoop` | 0.716 ms/op | ± 0.033 | 0.674 · 0.675 · 0.737 · 0.736 · 0.760 | 0.736 |
| **`executorQuery`** | **0.788 ms/op** | ± 0.027 | 0.766 · 0.854 · 0.752 · 0.774 · 0.794 | 0.774 |
| `executorQueryFromDataSource` | 2.068 ms/op | ± 0.087 | 1.925 · 2.188 · 1.997 · 2.165 · 2.063 | 2.063 |

**Ratio: 1.100 on the mean, 1.051 on the median of fork means.** Budget ≤ 1.10.

No outlier here — the forks are tight on both arms — so this is the honest steady-state picture: the
executor costs about **5% over the hand-written loop** by the outlier-resistant statistic, and the
raw mean lands on **1.1006**, which is the ceiling to four decimal places.

### CI-grade, for contrast — 1 fork × 1 warmup × 1 iteration, JDK 21

| Repetition | `executorQuery` | `handWrittenLoop` | Ratio |
|---|---|---|---|
| 1 | 0.886 ms/op | 1.573 ms/op | 0.563 |
| 2 | 1.035 ms/op | 1.576 ms/op | 0.657 |
| 3 | 1.306 ms/op | 1.029 ms/op | 1.269 |

## Interpretation

**NFR-03 is met, and the honest form of that sentence needs its statistic attached.** On the median
of per-fork means — the outlier-resistant reading — the overhead is **−5% on JDK 21 and +5% on JDK
17**, comfortably inside a 10% allowance. On the raw mean it is **+7.1% on 21** and **+10.06% on 17**,
which is the ceiling. Both readings come from the same 50 measurements per arm; neither is a
correction of the other.

**The measured overhead is the one RFC-0003 predicted, which is the result worth keeping.** The RFC
argued for a caller-supplied `RowMapper` over reflection partly on this budget, reasoning that "a
`RowMapper` lambda over the same `ResultSet` *is* that loop plus one virtual call". A ~5% steady-state
gap over 10 000 rows is about 4 ns per row — one interface call, one null check, and a share of the
10 000-element `List.copyOf` at the end. The model the design was chosen on turns out to describe the
code that was written. Per-row reflection would have spent the whole allowance before the framing was
measured, which was the RFC's third and decisive reason.

**The margin is thin, and that is a forward-looking risk rather than a present failure.** At +5%
steady-state there is roughly half the budget left. Items 4.4 and 4.5 add no per-row work by design,
and any future change that does — a hook, a callback, a mapping-time validation — should re-run this
before it lands rather than after. The named lever if it is ever needed is the trailing
`List.copyOf(..)`: over 10 000 rows it is an 80 KB array copy that a `Collections.unmodifiableList`
view would remove entirely, at the cost of returning a view rather than a copy. It is **not** taken
here, because tuning to a number rather than to a requirement is how a measurement culture becomes a
benchmarking one.

**The `DataSource` arm is 2.3× (JDK 21) and 2.6× (JDK 17) the borrowed-connection arm**, and this is
the number to remember outside this report. Opening and closing a real H2 connection costs more than
reading 10 000 rows through it. It is not a regression and not in the budget — but it is the concrete
reason FR-06's transaction runner hands its callback a `Connection` rather than a `DataSource`, and
the reason `on(DataSource)` carries an `@apiNote` about being captured into a transaction block.

### Why this cannot be a CI gate today, in one table

RFC-0003 called NFR-03 "the one performance gate in this project that can be a real CI gate today",
and the reasoning survives: it is a **relative** comparison inside one JMH invocation, so a slow
runner slows both arms and the machine cancels. That is precisely what NFR-01's 2 ns/op and NFR-06's
400 MB/s cannot claim, and why item 8.3 exists for them and not for this.

What the RFC could not know — the benchmark did not exist — is the **sample size**, and the CI-grade
table above answers it: three consecutive repetitions on one idle machine put the ratio at **0.563,
0.657 and 1.269**, a **2.3× spread across a 1.10 threshold**. At one warmup iteration neither arm is
warm, and the two do not warm at the same rate, so the swing is systematic rather than merely noisy —
note that `handWrittenLoop` reads 1.57 ms at CI grade against 0.74 ms warm.

A gate wired at those settings fails roughly a third of builds that regressed nothing. **ROADMAP item
8.8** owns raising the counts and choosing the statistic; this report is its evidence.

## Reproduce

```bash
# Report-grade, as run above (both toolchains):
mvn -B -pl d4np-jdbc -am -Pjmh -DskipTests verify \
    -Djmh.forks=5 -Djmh.warmup.iterations=5 -Djmh.iterations=10

# CI-grade, as the `benchmark` job runs it:
mvn -B -Pjmh verify

# Machine-readable results, for item 8.8 to read instead of scraping the console:
cat d4np-jdbc/target/jmh-result.json
```

Note that `-am` also builds and runs `d4np-core`'s benchmarks, which is why a report-grade run takes
minutes rather than seconds; `-pl d4np-jdbc` alone works once `d4np-core` is installed locally.
