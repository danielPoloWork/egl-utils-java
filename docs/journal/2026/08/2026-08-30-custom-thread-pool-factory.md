# 2026-08-30 — FR-08's pools, and an RFC conclusion corrected by its own second requirement (ROADMAP item 5.1)

**Milestone 5's first implementation item.** `CustomThreadPoolFactory`, `ThreadPoolSpec` (with
`ThreadPoolSpec.Builder`) and `ManagedThreadPool` open `d4np-concurrent` — the first module here to
publish an API and add **no `requires` edge at all**, since everything FR-08 needs is in `java.base`
where `d4np-jdbc` needed `requires transitive java.sql`.

## What changed

Four production types in a new exported package, **39 new tests**, NFR-05's named jcstress harness,
and [ADR-0035](../../../adr/0035-declare-autocloseable-so-the-override-is-legal.md). The threat
model's *thread-pool exhaustion* row moves ▢ → ✅ with its own mitigation text corrected; C-01 gains
two call sites, both on log lines.

## The RFC's conclusion was wrong, and its own second requirement is what made it wrong

RFC-0004 §FR-08 said `@Override` on `close()` is *"a compile error at `--release 17`"*, so the method
would carry no marker and read as removable convenience. **ErrorProne disagreed on the first
compile:**

```
[MissingOverride] close implements method in AutoCloseable; expected @Override
```

The RFC's probe measured a class implementing `ExecutorService` **alone**. That is not the shape the
RFC specifies — its *second* conclusion requires `AutoCloseable` to be declared as well, for the JDK
17 consumer's try-with-resources. Once it is, the supertype method exists at every release level.
Measured both ways at `--release 17`:

| Shape | `@Override` | Deleting `close()` |
|---|---|---|
| `implements ExecutorService` | **compile error** | compiles; inherits the one-day drain |
| `implements ExecutorService, AutoCloseable` | **compiles**, and `failOnWarning` makes it mandatory | **compile error** |

So the interface declaration does three jobs where the RFC credited it with one — and the third
*removes the hazard the RFC was warning about*. Deleting `close()` fails the build.

**The guard has an expiry date, and that is the part worth carrying forward.** Compiled natively on
21 the same deletion compiles cleanly, because `ExecutorService` supplies the default that satisfies
`AutoCloseable`. Both guards lapse together the moment the `--release` baseline moves past 18 —
already a MAJOR bump, now with one more thing to re-check. The failure mode is silent: a pool that
drains for a day instead of its budget looks like a hang, not a regression.

The RFC is **not** amended, on item 2.5's precedent. ADR-0035 narrows it and the RFC keeps its
measurement, which is what lets a reader see that the document was checked rather than edited.

## The same skew bit a second time, in the test the RFC asked for by name

RFC-0004 asks for a test driving `close()` through an `ExecutorService` variable in
try-with-resources. **That cannot be written here**, because this project's *test* sources also
compile at `--release 17`:

```
incompatible types: try-with-resources not applicable to variable type
    (ExecutorService cannot be converted to AutoCloseable)
```

Reflection is not a workaround for that — it is the faithful version. What regresses is
`invokeinterface` on `ExecutorService.close()`, and `ExecutorService.class.getMethod("close").invoke(pool)`
performs exactly that dispatch. On a 17 runtime the lookup throws `NoSuchMethodException`, and the
test asserts the runtime is below 19 rather than skipping quietly. A companion test covers
try-with-resources over `AutoCloseable`, which does compile at the baseline.

## FR-08 mandates a control that cannot fire in the shape everyone pictures

`Executors.newFixedThreadPool` hands `ThreadPoolExecutor` an **unbounded** queue. An unbounded queue
never refuses a task, so the *explicit* `RejectedExecutionHandler` FR-08 requires can never run, and
the threat model's *rejection storm* row would be mitigated by decoration.

So the queue capacity is a mandatory parameter with no defaulting overload — FR-05's structural move
applied to a second requirement — and `firesWhenTheQueueIsFull` is an assertion that could not pass
over an unbounded queue. `Integer.MAX_VALUE` keeps unbounded buffering available as a decision in the
caller's source rather than a default they inherited.

`DiscardPolicy` and `DiscardOldestPolicy` stay legal — refusing them is unenforceable, since a
discarding handler is four lines of a caller's own code — but are named in the Javadoc as the two
that lose work silently.

## The harness was made to fail before its green run was believed

NFR-05 is the **first requirement in this project to name a jcstress harness** rather than leave one
to judgement, which is what makes items 3.1, 4.1, 4.3 and 4.5's opposite conclusions legible as
decisions rather than omissions.

`ThreadPoolRejectionShutdownStress` races a submit against a `close()` on one pool:
`Interesting tests: No matches`. Then, per item 4.4's rule, `close()` was made to throw — and the
harness went red with the named forbidden outcome **`close threw`, 4 results, 100%** on every fork.
Sabotage removed, green run repeated.

Two things that took a moment to get right: the first sabotage sat behind `!drained`, a branch a
no-op task with a 1 ms budget almost never reaches, so it changed nothing; and `-Djcstress.args` is
not wired at all — the exec plugin passes a fixed argument list — so a property-based sabotage never
reached the forked JVMs either. Editing the code directly, as item 4.4 described, is the method that
works.

## Two findings from running rather than reading

- **`FluentBuilder`'s validate-then-construct ordering is invisible to NullAway.** It lives in
  another class, so every subclass with a mandatory *reference* field pays an
  `Objects.requireNonNull` in `construct()`. That is an assertion of ADR-0017's contract, not
  defensive padding — it fires only if `construct()` is ever reached without `validate()`, which the
  final `build()` exists to prevent.
- **The default uncaught-exception handler logs during the worker thread's death sequence.**
  `Thread.dispatchUncaughtException` runs after `run()` returns, which can be after the pool already
  reports termination — so `awaitTermination` is not a synchronisation point for that line. The first
  run of that assertion saw an empty recorder. Polling is the honest fix, and the comment says why.

## Smaller things worth carrying forward

- **A `submit`ted task that throws does not reach the uncaught handler**; `FutureTask` captures it
  for `get()`. That is `ThreadPoolExecutor`'s contract, and it is asserted rather than assumed,
  because a reader who thought the handler was universal would stop checking futures.
- **The pool name is bounded at construction and it is *not* a fourth C-01 call site.** Truncate-and-
  strip appears here for the fourth time in this repository, but a pool name is a developer-supplied
  constant rather than client input. Item 4.5 deferred the extraction question to a fourth call site;
  this is a fourth appearance of the *shape* and not of the *control*, which is the distinction that
  deferral did not draw — so the question stays open.

## Where the project stands

Items **5.2** (`AsyncExecutor`) and **5.3** (`DistributedLock`) remain. RFC-0004 is Accepted, so both
are unblocked.

## What the next session needs to know

- **Item 5.2 carries a mandatory ADR** — RFC-0004 routes the context SPI there, and a cross-task
  context leak is security-relevant under the enterprise posture (AGENTS.md §7). The threat-model row
  for it already exists, added by item 5.0, and is still ▢.
- **5.2's benchmark reports a number, not a verdict.** NFR-02's 5 µs is loose by roughly two orders
  of magnitude against `supplyAsync`'s ~100 ns submission, and the *gate* is item 8.3's, not 8.8's,
  because the bound is absolute rather than relative.
- **`submit` is not the name to use for 5.2's two methods.** RFC-0004 measured that the
  `Supplier`/`Runnable` overload pair is not ambiguous but silently changes the returned type when a
  body gains braces; the methods are `supply` and `run`.
