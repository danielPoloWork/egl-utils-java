# 2026-08-30 — FR-09's async wrapper, and a leak test that passed against the leak (ROADMAP item 5.2)

`AsyncExecutor`, `ContextPropagator` and `ContextSnapshot` (with nested `ContextSnapshot.Scope`) land
in `d4np-concurrent`, with [ADR-0036](../../../adr/0036-carry-context-through-an-spi-that-restores.md)
— mandatory rather than optional, because a cross-task context leak is an information-disclosure
decision and the enterprise posture requires a record for those.

## What changed

Four public types, **17 new tests** (56 in the module), NFR-02's benchmark and its
[report](../../../benchmarks/2026-08-30-async-submission.md). The threat model's
information-disclosure row for FR-09 moves ▢ → ✅ — and its own test description was corrected on the
way, for the reason below.

## The test everyone would write passes against the defect it names

RFC-0004 and the threat-model row both describe the same demonstration: *a single-threaded pool, two
tasks with distinct contexts, assert the second sees nothing of the first.* I wrote it, and **it
passed against a propagator whose `Scope` restores nothing.**

The reason is obvious in hindsight and invisible in advance: the second submission **installs its own
capture over the residue**, so the leak is hidden by the very mechanism under test. What leaks is not
what the next `AsyncExecutor` task sees. It is what **anyone else sharing the pool** sees — which is
the realistic case, because a pool is shared.

Both demonstrations now observe the worker by submitting **directly to the pool**, and they are a
matched pair over an identical sequence:

| Test | `Scope` | Observer sees |
|---|---|---|
| `leavesTheWorkerAsItFoundIt` | restores | `<none>` |
| `theSamePoolLeaksWhenTheScopeRestoresNothing` | no-op | **`tenant-A`** |

A safety test that cannot see the defect it names is worse than none, so the threat model's row was
corrected too rather than left prescribing the version that cannot fail.

## A claim corrected in the unusual direction

The draft Javadoc said `CompletableFuture.supplyAsync` treats a rejecting executor differently across
JDK versions, and that this contract exists to paper over the difference. **Measured: it does not.**
It lets `RejectedExecutionException` escape synchronously on the submitting thread on Temurin
17.0.20.1+1 **and** 21.0.12.1+1 — consistent behaviour.

That is a *stronger* argument for completing the future by hand, not a weaker one: the JDK reliably
gives one operation two failure paths, and this library reliably gives it one. The divergence is now
pinned by a test asserting both halves, so it stays a decision rather than drifting into an accident
if the JDK ever changes.

## The benchmark measured the wrong thing first

Submit to a real pool, `join()`, compare — every arm came out at **~12 µs, including the raw one**.
That is a thread handoff: park, unpark, context switch. NFR-02's quantity was buried inside it, and
at CI's single iteration the arms were not even correctly ordered — the context-carrying executor
measured *faster* than raw.

So the budget is measured **inline** (`Runnable::run`, shared by both arms). Report grade, 5 forks ×
10 iterations, JDK 21:

| Arm | Score | Error |
|---|---|---|
| `inlineRawSupplyAsync` | 0.012 | ± 0.001 µs/op |
| `inlineAsyncExecutor` | **0.013** | ± 0.001 µs/op |
| `inlineAsyncExecutorWithContext` | 0.013 | ± 0.001 µs/op |
| `pooledRawSupplyAsync` | 11.865 | ± 0.511 µs/op |
| `pooledAsyncExecutor` | 11.160 | ± 0.032 µs/op |

**On JDK 17 all three inline arms measure identically at 0.014 µs**, so the overhead there is
literally zero — a cleaner result than 21's, where the wrapper sat one least-significant digit above
the floor. Stated precisely, the 21 difference *equals* the ±1 ns error on each arm, so the honest
claim is "indistinguishable from the JDK's own submission at this granularity", not "exactly 1 ns";
the margin against the whole measured submission path is about **350×**. Two things fall out that the budget did not ask for: an eight-entry context copy
is free at this resolution, which retires RFC-0004's worry that a large capture might eat the
headroom; and through a real pool the wrapper adds nothing measurable, though the report declines to
claim it is *faster* because the error bars overlap.

## Smaller things worth carrying forward

- **`Scope.close()` narrows away `AutoCloseable`'s `throws Exception`.** A restore runs inside
  `AsyncExecutor`'s own try-with-resources, where a throw would replace whatever the body was
  reporting — the same reasoning that keeps `ManagedThreadPool.close()` quiet.
- **An `Error` from a body is delivered through the future rather than left to escape.** A future
  nobody completes is a caller waiting forever, which is worse than an `Error` the caller can see.
- **Two NullAway frictions, both with house answers already:** a literal `null` in a null-rejection
  test needs `@SuppressWarnings("NullAway")` with a stated reason, and JMH's `@Setup` fields need
  `@SuppressWarnings("NullAway.Init")` — item 4.3 reached for the second first.

## Where the project stands

Item **5.3** (`DistributedLock`) is all that remains in Milestone 5, and it is the milestone's most
expensive page: the interface *is* the deliverable, and `d4np-lock-redisson` inherits whatever it
pins.

## What the next session needs to know

- **5.3's fencing-token decision is the one that cannot be deferred.** A lease can expire while the
  holder still runs, giving two writers; without `OptionalLong fencingToken()` in the *interface*, no
  implementation can add it without a MAJOR break.
- **Two more interface constraints RFC-0004 already pinned:** `close()` releases *this acquisition*
  and never a key, and an implementation without reentrancy must **refuse** a nested acquisition
  rather than block — ADR-0031's argument at longer range.
- **`DistributedLockException` will want C-01 treatment.** A lock key is very often an identifier
  (`order:tenant-42:user-7`), so the message names the operation and the failure class, never the
  backend's own text and never the key in full.
