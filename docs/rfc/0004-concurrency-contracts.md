# RFC-0004: Concurrency contracts — pool lifecycle, context propagation and the distributed lock

- **Status:** Accepted (2026-08-30, owner authority — no peer-review round; **approval followed the
  merge**, unlike RFC-0001–0003; see [Approval](#approval))
- **Author:** tech-lead · **Reviewers:** reviewer, enterprise-architect (a third module surface and
  the interface every future lock implementation inherits), security-auditor (FR-09's cross-task
  context leak and the C-01 rules on lock keys) · **Approver:** owner (@danielPoloWork)
- **Date:** 2026-08-30
- **Related:** spec [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) §2 FR-08, FR-09, FR-10
  · §3 (the zero-third-party row this RFC's hardest decision follows from) · §5 (three types with no
  contract row) · §6 · NFR-02, NFR-05 ·
  [RFC-0001](0001-core-contracts.md) (the error model, nullability and versioning rules this one
  inherits; and the *"known risk, carried not hidden"* treatment of an unholdable budget) ·
  [RFC-0002](0002-cross-cutting-contracts.md) (the SPI-plus-default shape FR-15 established, which
  FR-09 reuses; and `Unit`) ·
  [RFC-0003](0003-jdbc-and-json-contracts.md) (ambient state as detector rather than transport; the
  naming-consequence rule; the rule that no exception leaves a module carrying text this library did
  not write) ·
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (the dependency policy that makes
  FR-09's stated mechanism unavailable) ·
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) ·
  [ADR-0005](../adr/0005-jpms-module-names-and-export-less-descriptors.md) (the descriptor this
  module's first `exports` completes) ·
  [ADR-0010](../adr/0010-single-specification-authority.md) (rung 1 — an RFC outranks the spec for
  every section it pins; the rung FR-09 stands on) ·
  [ADR-0011](../adr/0011-declare-the-nullability-annotation-in-core.md) ·
  [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) ·
  [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) (**which named this RFC as its own
  revisit trigger**) ·
  [ADR-0015](../adr/0015-strategy-registry-last-write-wins.md) ·
  [ADR-0017](../adr/0017-fluent-builder-accumulated-validation.md) (the builder FR-08's spec uses) ·
  [ADR-0019](../adr/0019-mint-unit-for-the-void-success.md) ·
  [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) (a guarantee a consumer can
  switch off is advisory — applied twice below) ·
  [ADR-0023](../adr/0023-the-owner-approves-this-projects-rfcs.md) (the approver role) ·
  [ADR-0028](../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md) (refusing the plausible
  wrong answer rather than returning it) ·
  [ADR-0030](../adr/0030-the-two-channels-out-of-a-transaction-body.md) ·
  [ADR-0031](../adr/0031-one-nesting-detector-for-the-whole-jvm.md) (nesting refused because the
  permissive reading offers no atomicity — FR-10 inherits the argument) ·
  [ADR-0032](../adr/0032-name-the-void-transaction-form-differently.md) (**the precedent FR-09's
  naming decision applies, with a measurement that makes the case stronger**) ·
  [threat model](../security/threat-model.md) §1 boundaries **B1** and **B3**, §2's two ▢ rows for
  FR-08 and FR-10, and the information-disclosure row this RFC finds missing.

---

## Context

Milestone 5 opens `d4np-concurrent`, the fourth module to carry production code and the third to
publish an API. Three requirements: a thread-pool factory (FR-08), an async wrapper (FR-09), and the
`DistributedLock` **interface** (FR-10), whose implementation ships separately in
`d4np-lock-redisson` so that no consumer of the concurrency utilities drags a Redis client.

Two things make this milestone different from the three before it.

**The module's dependency budget is zero, and that is a build gate rather than a convention.**
`d4np-concurrent/pom.xml`'s `enforce-adr-001` execution is default-deny with exactly **two** allowed
patterns — `it.d4np:*` and `*:*:*:*:test`. `d4np-core`'s equivalent has a **third**, for
`jakarta.validation-api` at `provided`; this module has no such entry, so there is no provided-scope
escape hatch here. The POM says why in its own comment: `DistributedLock` is an interface here and
Redisson implements it elsewhere, so *"the moment Redisson could be added here, that separation
would be a convention instead of a structure."* Everything below is written inside that budget.

**The interface for FR-10 is the deliverable, not a step toward one.** `d4np-lock-redisson` and every
future implementation inherit whatever this document pins, and an interface is the one artifact in
this project where a later correction is necessarily a MAJOR break. The roadmap flags it; this RFC
treats FR-10's section as the most expensive page in it.

Three findings were established **before** drafting rather than during implementation, because
RFC-0003 pinned a surface for FR-06 that did not compile and item 4.4 paid for it. Each was measured
on Temurin 17.0.20.1+1 and 21.0.12.1+1; the probes are described where they are used.

---

## Decision

### The shared rules this RFC inherits rather than re-argues

| Rule | Source | Consequence here |
|---|---|---|
| No published method declares a checked exception | RFC-0001 §Error model | `AsyncExecutor`'s bodies are `Supplier`/`Runnable`, not `Callable` — see FR-09 |
| Expected outcome → `Result`; defect or infrastructure fault → unchecked | RFC-0001 §Error model, RFC-0003 §Error model | Lock acquisition timeout is `Optional.empty()`; a backend failure is unchecked |
| No method returns `null`; `Optional` for absence | RFC-0001 | Every surface below |
| No exception or log line leaves a module carrying text this library did not write | RFC-0003 §FR-06, control **C-01** | Lock keys and pool task lists, below |
| A guarantee a consumer can switch off is advisory | [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) | What FR-08's factory returns; why FR-09 has no reflective fallback |
| Rename where a wrong choice **compiles and diverges**; keep the name where it cannot compile | ADR-001's naming-consequence rule, applied in RFC-0003 | FR-09's two submission methods |
| A thread-safety claim without a named jcstress test is not a claim | spec §6 | FR-08 owes one; FR-09 and FR-10 do not, for reasons stated |
| Ambient thread state is safe as a **detector** and unsafe as **transport** | RFC-0003 §FR-06, [ADR-0031](../adr/0031-one-nesting-detector-for-the-whole-jvm.md) | FR-09 is the case where it is transport, and is treated accordingly |

---

### FR-08 `CustomThreadPoolFactory` — the pool contract

#### The rejection handler the requirement mandates is unreachable in the JDK's default pool shape

FR-08 requires an **explicit** `RejectedExecutionHandler`. `Executors.newFixedThreadPool` and
`newCachedThreadPool` — the two shapes a reader will have in mind — hand `ThreadPoolExecutor` an
**unbounded** `LinkedBlockingQueue`. An unbounded queue never refuses a task, so the rejection
handler is never invoked, and configuring one is decoration. The requirement's own threat-model row
(*"Thread-pool exhaustion or a rejection storm"*) is not mitigated by a handler that cannot fire; it
is mitigated by the queue that makes it fire.

**Therefore the queue capacity is a mandatory, bounded parameter.** There is no overload that omits
it and no default. This is FR-05's structural move applied to a second requirement: `SimpleJdbcExecutor`
makes SQL injection unavailable by *not offering* a concatenating overload, and `ThreadPoolSpec`
makes an unbounded queue unavailable the same way. A consumer who genuinely wants unbounded
buffering can still say so — `Integer.MAX_VALUE` is accepted — but it is then a decision in their
source rather than a default they inherited.

The same reasoning makes the **rejection policy mandatory**. Two of the JDK's four standard policies,
`DiscardPolicy` and `DiscardOldestPolicy`, drop work and report nothing; they are legal here, because
refusing them would be both paternalistic and unenforceable (a caller can write a discarding handler
in four lines), but the parameter's Javadoc **names them as the two that lose work silently**. That
is ADR-0028's shape: `queryOne` refuses a second row rather than returning one of them at random,
and where refusing is not available, saying so is.

#### What the factory returns, and who owns the drain

NFR-05 requires that *"graceful shutdown drains within the configured timeout"*. A **factory** has no
lifecycle, so something must own that timeout, and FR-08 does not say what. Three candidates:

| Returned | Why it loses / wins |
|---|---|
| `ThreadPoolExecutor` | **Rejected.** It publishes `setCorePoolSize`, `setRejectedExecutionHandler`, `setThreadFactory` and `getQueue`. Every guarantee the factory just established becomes one the consumer can switch off, and `getQueue()` hands out the live queue to `clear()`. ADR-0022's rule, and the same reasoning that removed `JsonMapper`'s `ObjectMapper` getter |
| `ExecutorService` | **Rejected.** It is correctly narrow — it has none of those setters — but it carries no configured timeout, so `shutdown()` followed by the caller's own `awaitTermination` is not a drain the *library* configured. NFR-05 would become advice |
| **`ManagedThreadPool`** | **Adopted.** A `final class` implementing `ExecutorService` and `AutoCloseable`, wrapping a `ThreadPoolExecutor` it never publishes. It carries the configured drain timeout, and `close()` applies it |

`final class` rather than an interface, deliberately: an interface anyone can implement makes the
drain guarantee per-implementation, which is the advisory outcome the row above rejects.

#### `close()`, and the JDK 19 default that silently replaces it — measured

`ExecutorService` became `AutoCloseable` in **Java 19**, with a default `close()` that calls
`shutdown()` and then `awaitTermination(1, DAYS)` **in a loop until terminated**. This project
compiles at `--release 17`, where that method does not exist, and ships to consumers running 17
**and** 21. That skew is not theoretical, and it was probed rather than reasoned about — a wrapper
compiled at `--release 17` (javac from JDK 21, exactly as this project builds) and exercised from a
consumer compiled on 21:

| Probe | Result |
|---|---|
| Wrapper **without** `close()`, consumer's try-with-resources, 3 s task | `shutdown()` then **`awaitTermination(1, DAYS)`** — the interface default. Elapsed **3017 ms**; a configured budget would have been ignored entirely |
| Wrapper **with** `close()` declared, 500 ms budget, 3 s task | our method runs; `awaitTermination(500, MILLISECONDS)` then `shutdownNow()`. Elapsed **510 ms** |
| Same object through the **`ExecutorService`** static type | our method still runs — it is a genuine override at run time. Elapsed **513 ms** |
| `@Override` on that `close()` at `--release 17` | **compile error** — *"method does not override or implement a method from a supertype"* |
| A **JDK 17 consumer** using try-with-resources over an `ExecutorService` subtype | **compile error** — *"NoClose cannot be converted to AutoCloseable"* |

Four contract lines follow, and none of them is obvious from the source:

1. **`ManagedThreadPool` declares `close()` explicitly.** Inheriting the default is not "the same
   thing by another route": it discards the configured timeout and waits essentially forever.
2. **It declares `implements AutoCloseable` explicitly**, because a JDK 17 consumer otherwise cannot
   write try-with-resources at all — the interface it would need does not exist on that runtime.
3. **The `@Override` annotation cannot be written**, so the one marker that tells a reader the method
   is an override is unavailable, and the method looks like removable convenience. The Javadoc says
   so at the declaration, in the imperative, naming the JDK version.
4. **Item 5.1 owes a test that drives `close()` through the `ExecutorService` static type on a 21
   runtime**, because that is the exact call that regresses silently if anyone deletes the method.
   A test through the concrete type would keep passing.

`close()` never throws. A drain that did not finish is reported as a `WARNING` log line carrying the
**count** of tasks that never started — never the tasks themselves, because a `Runnable` is a
caller-supplied object whose `toString()` this library does not control (**C-01**).

#### Threads: names, daemon status, priority, uncaught exceptions

| Concern | Contract | Why it is a decision |
|---|---|---|
| Name | Mandatory. Threads are named `<pool-name>-<n>`, `n` from 1 | The whole operational value of FR-08's "named pools" is a readable thread dump; `pool-3-thread-7` is the state it exists to replace |
| Daemon | **Non-daemon by default**, overridable | A daemon pool lets the JVM exit with work in flight — silent loss, and the direct contradiction of NFR-05's drain. Non-daemon turns a forgotten `close()` into a hang, which a thread dump diagnoses in seconds. It is also `Executors`' own default, so a consumer's mental model is unchanged |
| Priority | Optional; applied via the `ThreadFactory`; **documented as a hint the OS may ignore** | `Thread.setPriority` is advisory and on common Linux configurations has no effect at all. FR-08 names the feature, so it is offered — but **no test asserts scheduling behaviour**, because such a test is flaky by construction, and spec §6's rule cuts both ways: a claim without a test is not a claim, so the claim is not made |
| Uncaught exceptions | Mandatory `Thread.UncaughtExceptionHandler`, defaulting to a `System.Logger` `ERROR` line | A pool thread dying silently is the classic defect. The default logs the throwable's **type** and message-free identity, per C-01 |

#### Surface

```java
public final class CustomThreadPoolFactory {            // static factory; not instantiable
  public static ManagedThreadPool create(ThreadPoolSpec spec);
}

public final class ThreadPoolSpec {
  public static Builder named(String name);
  public static final class Builder extends FluentBuilder<ThreadPoolSpec> { … }
}

public final class ManagedThreadPool implements ExecutorService, AutoCloseable {
  public String name();
  public Duration drainTimeout();
  public void close();                    // declared, never inherited — see the probe above
}
```

`Builder` extends core's **`FluentBuilder<ThreadPoolSpec>`** ([ADR-0017](../adr/0017-fluent-builder-accumulated-validation.md))
rather than hand-rolling a builder. This is the case FR-02 was written for: a spec with nine
parameters, four of them mandatory, where **accumulated** validation reports "queue capacity is
missing *and* no rejection policy was set" in one exception instead of one per build attempt. The
failure type is core's existing `BuilderValidationException`; this RFC mints no new exception for
FR-08.

#### Thread safety, logging and the harness

`ManagedThreadPool` is thread-safe — it is a thin final wrapper whose only state is an immutable
delegate reference plus the immutable spec. `ThreadPoolSpec` and its builder are **not**: a builder
is a single-threaded construction idiom, and the Javadoc says so rather than leaving it inferred.

**NFR-05 is the first non-functional requirement in this project that mandates a jcstress harness by
name**, and item 5.1 owes it: rejection and shutdown races, zero anomalies. Item 4.4's rule is
carried forward and is not optional — **the harness must be shown to fail before it is trusted**, by
sabotaging the state it watches and confirming it goes red. A harness that cannot fail satisfies spec
§6's letter and proves nothing.

---

### FR-09 `AsyncExecutor` — the async wrapper

#### The requirement names a type this module may not depend on

FR-09 asks for *"`CompletableFuture`-returning async wrapper over a pre-configured executor **with
MDC context propagation**"*. MDC is SLF4J's `org.slf4j.MDC`. It is not reachable from here:

- `d4np-concurrent`'s enforcer allowlist has no third-party entry **at any scope**, so an SLF4J
  dependency fails `mvn validate` rather than review.
- This library does not log through SLF4J at all. It logs through `java.lang.System.Logger`
  ([ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md)), which **has no MDC** — no markers,
  no structured context, by design.

**ADR-0014 predicted this exact moment and named the trigger**, which is why this is a revisit rather
than a discovery. Its Consequences read: *"`System.Logger`'s API is deliberately small — no fluent
builders, no markers, no MDC. For one warning this is a fair trade; a module that later needs
structured context will find `System.Logger` thin, and that is the moment to revisit, not now."*
FR-09 is a module that needs structured context. The revisit happens here, and the answer is **not**
to add a logging dependency.

This is FR-15's shape one milestone later. There the spec named a type after an aspect, core could
not see `aspectjrt` or Micrometer, and the resolution was: core owns the behaviour and defines the
sink as an SPI; the binding lives where the dependency is legal. FR-09 takes the same shape, and this
RFC pins the contract under [ADR-0010](../adr/0010-single-specification-authority.md) rung 1 — *an
RFC outranks the spec for every section it pins* — so FR-09's spec sentence is superseded by this
section rather than by an edit to the manifest.

#### What is delivered instead

`d4np-concurrent` owns a **context SPI** and ships **no** implementation that reads a logging
framework:

```java
public interface ContextPropagator {
  ContextSnapshot capture();              // called on the SUBMITTING thread
}

public interface ContextSnapshot {
  Scope install();                        // called on the WORKER thread, before the task
  interface Scope extends AutoCloseable { @Override void close(); }   // restores; never throws
}
```

**The default is a no-op, and explicitly not a reflective lookup of `org.slf4j.MDC`.** A reflective
fallback would work when SLF4J happened to be on the classpath and silently do nothing otherwise —
implicit configuration inside the one type whose value is that its behaviour is explicit, which is
exactly the argument that rejected `findAndRegisterModules()` for `JsonMapper` in item 4.1. It is
also JPMS-hostile: reflective access into a named module needs an `opens` this library cannot
require.

**The MDC binding is the host's, and it is four lines** — `capture()` reads `MDC.getCopyOfContextMap()`,
`install()` calls `MDC.setContextMap` and returns a `Scope` that restores the previous map. The
Javadoc carries that code verbatim so it is copied rather than reinvented.

**It is deliberately not shipped in `d4np-spring-adapter`**, even though that module's allowlist would
permit SLF4J (it bans only `org.redisson:*`). Two reasons, and the first is the project's thesis:
**MDC is SLF4J, not Spring**, so putting the binding there would make a Jakarta EE host — which spec
§1 names as a first-class target — take a Spring dependency to get context propagation. The second is
structural: `spring-adapter` does not depend on `d4np-concurrent` today, and spec §3's module graph
would gain an arrow for a class with no Spring in it.

#### The pooled-thread leak is the actual defect, and the threat model has no row for it

The interesting failure of context propagation is not the missing context. It is the **surviving**
context: a worker thread is pooled and reused, so a task that installs a context and does not restore
what was there leaves it visible to **the next task on that thread** — a different request, often a
different user or tenant, whose log lines then carry someone else's identifiers.

That is why `install()` returns a `Scope` that **restores the previous value** rather than a method
that clears. Clearing is wrong in both directions: it wipes context a caller had already established
(nested submissions), and it does nothing about the ordering that actually leaks. The contract is
therefore stated as an invariant rather than a suggestion:

> Every installed snapshot is closed on the same thread, in the reverse order of installation,
> including when the task throws. A worker thread's context after a task is exactly what it was
> before.

Item 5.2's contract test is a **single-threaded pool**, two tasks, distinct contexts, asserting the
second sees nothing of the first — a sequential property that a jcstress harness would not test, so
this is not one of the harnesses spec §6 asks for.

**This is an information-disclosure threat with no row in the threat model**, which today has eight
rows under that heading and none about context on a pooled thread. **This RFC adds one** (B1 ·
`AsyncExecutor` (FR-09)), which makes it the first RFC in this project to add a threat-model row
rather than only move existing ones — RFC-0003 explicitly added none. Under the enterprise posture
(AGENTS.md §7) this is a security-relevant decision, so **item 5.2 carries an ADR**; recording it
here is not a substitute for that record.

#### Two names, not two overloads — measured, and the measurement changed the argument

The obvious surface is one overloaded `submit`, taking a `Supplier<T>` or a `Runnable`. Item 4.4 met
an ambiguity in exactly this shape and renamed the void form
([ADR-0032](../adr/0032-name-the-void-transaction-form-differently.md)), so the pair was compiled
before it was written down. **It is not ambiguous — and what it does instead is worse.** All four
shapes compile, and the overload chosen depends on the *syntax* of the body:

| Call | Binds to | Future returned |
|---|---|---|
| `submit(() -> returnsInt())` | `Supplier` | `CompletableFuture<Integer>` |
| `submit(() -> returnsVoid())` | `Runnable` | `CompletableFuture<Void>` |
| **`submit(() -> { returnsInt(); })`** | **`Runnable`** | **`CompletableFuture<Void>`** |
| `submit(Probe::returnsInt)` | `Supplier` | `CompletableFuture<Integer>` |

Rows 1 and 3 are the **same call** with a pair of braces added. Adding them — to insert a log line,
say — silently changes the returned type and discards the task's result. Nothing warns.

ADR-001's naming-consequence rule decides it: rename where a wrong choice **compiles and diverges**.
An ambiguity is a compile error the author must resolve; this is a silent semantic change, so the
case for distinct names is *stronger* here than in item 4.4, not weaker. **The methods are `supply`
and `run`** — which is the decision the JDK already made in the very class FR-09 wraps:
`CompletableFuture.supplyAsync(Supplier)` and `runAsync(Runnable)` are two names and have never been
an overloaded pair. Item 4.4 borrowed Spring's `executeWithoutResult`; this borrows the JDK's own.

#### Errors: one failure channel, and rejection travels through it

`CompletableFuture` is already a two-channel type, so the body's failure completes the future
exceptionally. It is **not** wrapped in `Result`: two error channels in one signature would force
every `thenApply`/`exceptionally` in a caller's chain to know which one carried the failure, and
RFC-0003 settled the general form of this question when it ruled that the exception channel
demarcates a transaction and the value channel does not — one meaning per channel.

**A rejected submission is delivered as a failed future, not thrown.** `Executor.execute` on a
saturated pool under `AbortPolicy` throws `RejectedExecutionException` synchronously on the
submitting thread, which would give one operation two failure paths — a `try`/`catch` *and* an
`exceptionally` — and a caller's chain would silently never run. The cost is stated rather than
hidden: a caller who discards the returned future discards the rejection with it, which is the
general hazard of ignoring a future rather than a new one.

#### Surface

```java
public final class AsyncExecutor {
  public static AsyncExecutor over(Executor delegate);
  public AsyncExecutor withContext(ContextPropagator propagator);   // returns a new instance
  public <T> CompletableFuture<T> supply(Supplier<T> body);
  public CompletableFuture<Void> run(Runnable body);
}
```

`withContext` returns a new instance rather than mutating, so an `AsyncExecutor` is immutable and
therefore safely shareable — which is what lets the thread-safety row below be a claim about the type
instead of about its users. It takes an `Executor`, not an `ExecutorService`: `supply`/`run` need
only `execute`, and the narrower parameter accepts `ManagedThreadPool`, a raw `ExecutorService`, a
`ForkJoinPool` or a virtual-thread executor without this module naming any of them.

---

### FR-10 `DistributedLock` — the interface every implementation inherits

#### The lease can expire while you hold it, and no implementation can fix that

FR-10 makes lease time mandatory, which bounds the *starvation* threat the threat model records. It
does not address the converse, which is the one that corrupts data: **a lease can expire while the
holder is still running.** A stop-the-world pause, a slow disk, or a network partition is enough. The
lock is then handed to a second holder while the first still believes it is exclusive, and both write.

No lease-based lock can prevent this, and it is not a Redis defect — it is a property of leases. The
only structural mitigation is a **fencing token**: a value that strictly increases per lock key, which
the holder passes to the resource it is protecting, and which the resource uses to reject a write
from a stale holder. Without one in the *interface*, no implementation can offer it later without a
MAJOR break, and the roadmap's own note — *the interface is the deliverable* — is precisely about
this class of omission.

**`LockHandle.fencingToken()` returns `OptionalLong`.** Mandatory would be dishonest, because the
planned implementation cannot supply one that survives a Redis failover; absent would silently
foreclose it for every future implementation. Empty is a documented statement with teeth:

> An empty token means this implementation cannot keep mutual exclusion across a lease expiry. Do not
> use it to guard a non-idempotent write to an external system.

This is the same move as `JdbcAccessException` refusing to fabricate SQLState 21000 when no driver
raised one ([ADR-0028](../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md)): a field means
what the backend said, so writing our own conclusion into it leaves a consumer unable to tell the two
apart.

#### Release affects an acquisition, never a key

The classic distributed-lock defect is releasing by key: the lease expires, another holder acquires,
the original finishes and deletes *their* lock. The interface forbids it in the contract rather than
leaving it to each implementation to notice:

> `close()` releases **this acquisition**. If the lease has already expired, it releases nothing. An
> implementation must never release a lock it cannot prove is the one it acquired.

`close()` is idempotent and never throws — a release failure is a `WARNING` log line, because a lock
whose release failed will expire on its own, and throwing from `close()` inside try-with-resources
would suppress the body's exception in the one place a caller is least able to react.

#### Reentrancy: refuse, do not block

FR-10 promises no reentrancy *"unless an implementation documents it"*, which leaves the failing case
unspecified — and the unspecified behaviour is the harmful one. A non-reentrant lock re-acquired from
the same holder waits for a lock that holder already owns, so it blocks until the `wait` timeout at
best and until the lease expires at worst, with nothing in a log to read.

**The interface requires the fast failure**: an implementation that does not support reentrancy must
**refuse** a nested acquisition from the same holder rather than block on it. This is
[ADR-0031](../adr/0031-one-nesting-detector-for-the-whole-jvm.md)'s argument arriving one milestone
later and at a longer range — there, a nested transaction would take a second connection and wait on
locks the outer transaction held, hanging a modest pool with no error to read. The remedy is the
same: refuse, and say so.

A capability method (`boolean isReentrant()`) was considered and **rejected**: it invites
`if (lock.isReentrant())` branching whose false arm is untestable against a real backend, and it turns
a documentation contract into a runtime one that every implementation must then keep honest.

#### Errors, and the lock key that must not travel

| Condition | Shape |
|---|---|
| Not acquired within `wait` | `Optional.empty()` — an expected outcome the caller branches on (RFC-0001) |
| Backend unreachable, or a protocol failure | `DistributedLockException`, unchecked, extending `RuntimeException` — an infrastructure fault, so **not** `BusinessException` (RFC-0001's table; `JdbcAccessException`'s shape) |
| `null` key, non-positive lease, negative wait | `IllegalArgumentException` — a defect in the calling code, not something a client can send |

**A lock key is very often an identifier** — `order:tenant-42:user-7` is the shape every tutorial
uses — so it is exactly the input C-01 governs. `DistributedLockException`'s message names the
**operation** and the backend's failure **class**, never the backend's own message and never the key
in full. This is `JdbcAccessException`'s rule ported to a second backend, and it matters more here
than there: FR-19 maps an unchecked non-business exception to a **500 with no body**, so the client
sees nothing — but item 4.4 established that C-01 covers the library's own log lines too, and a log
is where a lock key would otherwise land in clear.

#### Surface

```java
public interface DistributedLock {
  Optional<LockHandle> tryAcquire(String key, Duration lease, Duration wait);
}

public interface LockHandle extends AutoCloseable {
  String key();
  Instant leaseExpiry();
  OptionalLong fencingToken();
  boolean isHeld();            // best-effort and local: true until the lease expiry passes or close()
  @Override void close();      // release; idempotent; never throws; this acquisition only
}

public final class DistributedLockException extends RuntimeException { }
```

One method on `DistributedLock`, deliberately. Every method here is one that
`d4np-lock-redisson` and every later implementation must honour, and a convenience form is a
default method item 5.3 may add without widening what an implementer owes.

**`isHeld()` is documented as best-effort and local** — it compares the lease expiry against the
clock and does not consult the backend. A method that round-trips would be a different and more
expensive contract, and one that *looked* authoritative while racing the network would be worse than
one that admits what it is.

---

### The error model — no amendment, and the `Unit` question does not arise

RFC-0002 minted `Unit` and RFC-0003 recorded that ADR-0012's prediction about item 4.4 was wrong.
Neither question re-opens here: `run` returns `CompletableFuture<Void>` because that is
`CompletableFuture`'s own vocabulary and the future is the value, and no operation in this RFC
returns `Result`. **This RFC amends neither RFC-0001 nor RFC-0002 nor RFC-0003**, stated explicitly
because RFC-0002 did amend RFC-0001 and a reader will check.

### Data & schema

Not applicable. `d4np-concurrent` owns no persistent state. A `DistributedLock` implementation holds
state in an external store, but the store's schema is the implementation's and is out of this
module's scope by construction — which is the point of FR-10 being interface-only.

### Scalability budgets

| Axis | Metric | Target | Tool | Item |
|---|---|---|---|---|
| performance | `AsyncExecutor` submission overhead vs raw `CompletableFuture.supplyAsync` | **≤ 5 µs** | JMH | 5.2 (NFR-02) |
| correctness | rejection / shutdown races | **0 anomalies** | jcstress | 5.1 (NFR-05) |

**NFR-02 cannot be a CI gate today, and it is worth being precise about why, because the reason is
not the one that applies to NFR-03.** RFC-0003 could recommend NFR-03 as a real gate because it is a
*relative* comparison of two arms inside one JMH invocation: a slow runner slows both and the machine
cancels out. NFR-02 is phrased relatively but **bounded absolutely** — 5 µs — so a loaded runner moves
the difference and not just the scale. It belongs with NFR-01's 2 ns/op and NFR-06's 400 MB/s in
**item 8.3**'s stable-runner problem, and explicitly *not* with item 8.8's fork-count problem, which
item 4.3 opened for the relative case.

**A second observation, recorded now so item 5.2 does not have to rediscover it: the budget is very
loose.** `supplyAsync` submission is on the order of a hundred nanoseconds; 5 µs is roughly two
orders of magnitude of headroom, so a regression of 4 µs — which a context capture copying a large
map could plausibly reach — would pass. Item 5.2 should therefore **report the measured overhead as a
number** rather than only a pass/fail against the ceiling, and the audit phase is the place to decide
whether the ceiling should be tightened. Following RFC-0001's precedent for NFR-01, the budget is
**tracked on the reference machine and advisory in CI** until 8.3 lands.

### Versioning

`d4np-concurrent` publishes its first API here, so nothing in it is a compatibility change yet. For
the future, and because FR-10's interface is the reason this section exists: **adding a method to
`DistributedLock` or `LockHandle` without a `default` implementation is MAJOR**, since every
implementation outside this repository breaks. Adding a `default` method is MINOR. Everything else
follows RFC-0001 §Versioning unchanged. `d4np-core` gains **no** new type and no new method from this
RFC.

---

## Alternatives

1. **Reflective `org.slf4j.MDC` lookup inside `AsyncExecutor`.** The only option that keeps FR-09's
   sentence literally true. Rejected: it is implicit configuration in the type whose value is
   explicitness (item 4.1's argument against `findAndRegisterModules`), it needs reflective access a
   JPMS consumer must grant, and it fails **silently** — a consumer without SLF4J gets a working
   executor that propagates nothing, discovered in production when a log line lacks a correlation id.
2. **Ship the MDC propagator in `d4np-spring-adapter`.** Legal — that module's allowlist bans only
   Redisson. Rejected because MDC is SLF4J and not Spring: it would make a Jakarta EE host take a
   Spring dependency to get context propagation, in a project whose stated objective is framework
   independence, and it would add an arrow to spec §3's graph for a class containing no Spring type.
3. **A tenth module, `d4np-context-slf4j`.** Rejected on cost: a published artifact, a BOM entry, a
   japicmp baseline and a release note, for roughly twenty lines a host can write from the Javadoc.
   Revisit if a second propagator (OpenTelemetry, Micrometer) is ever wanted — two would change the
   arithmetic, one does not.
4. **`CustomThreadPoolFactory` returns `ExecutorService` and NFR-05's drain is a static helper**
   (`ConcurrentUtils.shutdownGracefully(pool, timeout)`). Rejected: the timeout then travels
   separately from the pool it configures, so two call sites can drain the same pool with different
   budgets, and the guarantee is advisory again.
5. **`submit` as an overloaded pair.** Rejected on the measurement above — not because it fails to
   compile, but because it compiles and silently changes the result type when a body gains braces.
6. **`DistributedLock` as a callback API only** (`runExclusively(key, lease, body)`), which would make
   the leaked-handle mistake unavailable, as item 4.4 did for transactions. Rejected as the *sole*
   form: it cannot express "try, and do something else if the lock is held", which is the case a
   distributed lock exists for. The callback form is available later as a `default` method costing
   implementers nothing.
7. **A mandatory fencing token.** Rejected: the one planned implementation cannot honour it, so the
   interface would be unimplementable by `d4np-lock-redisson` on day one — an interface no one can
   implement is not a stronger guarantee, it is a stalled milestone.

## Consequences

- **`d4np-concurrent` gains its first `exports` clause** and its first production types, completing
  the descriptor ADR-0005 left open. The module keeps **zero** third-party dependencies at every
  scope, so a consumer takes exactly one JAR plus `d4np-core` — the same property item 4.3 achieved
  for `d4np-jdbc`, and here without even a `java.sql` edge, since everything used is in `java.base`.
- **Nine new top-level public types and two nested**, for **eleven** in total: FR-08 contributes
  `CustomThreadPoolFactory`, `ThreadPoolSpec`, `ManagedThreadPool` and nested `ThreadPoolSpec.Builder`;
  FR-09 contributes `AsyncExecutor`, `ContextPropagator`, `ContextSnapshot` and nested
  `ContextSnapshot.Scope`; FR-10 contributes `DistributedLock`, `LockHandle` and
  `DistributedLockException`. **Both numbers are stated because RFC-0003 stated one and was wrong by
  exactly the nested types it named in prose and did not count** — item 4.5 found the discrepancy at
  the end of the milestone, and nested types are types to `japicmp`.
- **Three ▢ rows become implementable and one row is added.** The threat model's *"Thread-pool
  exhaustion or a rejection storm"* (FR-08) and *"Distributed lock held forever"* (FR-10) are the two
  existing rows; the new one is **information disclosure — a pooled worker thread carrying one task's
  context into the next** (B1 · FR-09), which has no row today. **No new trust boundary is needed** —
  B1 covers the library API and B3 already names `lock-redisson → Redis` — stated explicitly because
  RFC-0002 routed a missing boundary to item 8.6 and a reader will look for the same here.
- **Control C-01 gains two prospective call sites** (FR-08's undrained-task log line, FR-10's lock
  key and backend message) and the compliance register's C-01 row is updated when each lands, not
  now.
- **Item 5.2 carries an ADR** for the context SPI, because a cross-task context leak is a
  security-relevant decision and the enterprise posture requires one (AGENTS.md §7). Item 5.1 and
  item 5.3 are expected to carry one each — the `close()`/`@Override` skew, and the fencing-token
  contract — but this RFC pins the contracts and does not pre-write those records, which is how items
  4.1–4.5 were sequenced.
- **`ExecutorService`, `CompletableFuture`, `Executor`, `RejectedExecutionHandler` and `Supplier`
  appear in published signatures**, all from `java.base`. No `requires` edge is added, which is the
  contrast with `d4np-jdbc`'s `requires transitive java.sql`: there the JDBC API is a separate module
  a consumer must read; here it is not.
- **Spec §5's `[GAP]`** — *"only 5 of ~25 public types carry a nullability/error/thread-safety
  contract row"* — is narrowed by three more requirements' worth of types. RFC-0002 pinned three,
  RFC-0003 pinned six, this pins eleven.
- **FR-09's spec sentence is superseded rather than edited.** ADR-0010 rung 1 makes an RFC outrank the
  spec for every section it pins, which is the same mechanism the compliance register already cites
  for RFC-0001 §Cross-cutting. No manifest amendment and no spec re-render is required, and none is
  performed.

## Approval

The approval encodes a **human decision** — no RFC self-approves (`AGENTS.md` §6). The record below
was **authorized by the owner (@danielPoloWork) in session on 2026-08-30** and transcribed by the
agent. The agent drafted this RFC and did not judge its soundness; the decision is the owner's.

```
approved-by: owner @danielPoloWork (2026-08-30)
```

**This document was drafted `Proposed` with an empty `approved-by:` and flipped only on the owner's
word**, in a change separate from the drafting, so the two acts are visible as two acts in the
history.

**The sequence differs from RFC-0001, RFC-0002 and RFC-0003, and the difference is recorded rather
than smoothed over.** Each of those was approved **before** its pull request, so RFC-0003 could say
that *"merging publishes an already-accepted document rather than performing the acceptance."* That
sentence is not available here. This RFC was drafted, opened as PR #49 and **merged while still
`Proposed`**; the owner approved it afterwards, in session, and this change is the flip. So for the
window between those two events, `main` carried an unapproved RFC — which is visible in the history
rather than hidden by it, and is exactly why the status field is a field rather than an assumption.
Nothing was implemented against the RFC in that window: item 5.1 is the first item to consume it and
had not started.

**One mechanical consequence worth naming, because it is evidence rather than noise:** `rfc_check.py`
failed against this file before the flip *and* after it, with **different messages** — `no approval
record` before, `approved-by 'owner' but the rfc-approved gate requires 'tech-lead'` after. The
second is the same failure RFC-0003 produces today. The gate's verdict does not change; what changes
is which sentence it prints, and that is how a reader can tell the record was written.

**On the approver role:** this project's RFCs are approved by the **owner**, not by the `tech-lead`
that `.eados-core`'s RFC protocol names — satisfying that gate literally would be self-approval,
since `tech-lead` is the authoring role.
[ADR-0023](../adr/0023-the-owner-approves-this-projects-rfcs.md) records the deviation, and
`rfc_check.py` reporting a failure against RFC-0001's form is **expected output** rather than a
defect to be worked around.

**Review provenance — stated, not implied.** No independent `reviewer`, `enterprise-architect` or
`security-auditor` round has run. FR-09's cross-task context leak is an information-disclosure
decision whose reviewer role owns the threat model it touches, and FR-10's fencing-token contract
binds every future implementation, so the absence is material here. It is recorded so a later reader
knows which assurance this RFC does and does not carry.

Reviewers (structured findings addressed): reviewer — **not run** ; enterprise-architect — **not run**
; security-auditor — **not run**.

## References

- FR-08, FR-09, FR-10 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) §2; §3 for the
  module's zero-dependency row and the `lock-redisson → concurrent` arrow; §5 for the contract-row
  gap; §6 for the rule that an unproven thread-safety claim is not a claim; NFR-02 and NFR-05 for the
  two budgets that bind this milestone; FR-19 for the mapping table item 7.1 owns.
- [RFC-0001](0001-core-contracts.md) §Error model, §Versioning, §Scalability budgets — including its
  *"known risk, carried not hidden"* treatment of NFR-01, which §Scalability budgets above applies to
  NFR-02.
- [RFC-0002](0002-cross-cutting-contracts.md) §FR-15 — the SPI-with-a-default shape this RFC reuses
  for context propagation, and the reason core could not see Micrometer either.
- [RFC-0003](0003-jdbc-and-json-contracts.md) §FR-06 — ambient state as detector rather than
  transport; the one-meaning-per-channel rule; and the surface it pinned that did not compile, which
  is why the three probes above ran before this document was written.
- [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) — the dependency policy and the
  naming-consequence rule; [ADR-0006](../adr/0006-enforce-the-dependency-policy-per-module.md) — the
  default-deny allowlists that make this module's budget a build gate.
- [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) §Consequences — the paragraph naming
  *"a module that later needs structured context"* as the revisit trigger, which FR-09 is.
- [ADR-0017](../adr/0017-fluent-builder-accumulated-validation.md) — the builder `ThreadPoolSpec`
  uses, and why accumulated validation is the right shape for a multi-parameter spec.
- [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) — a guarantee a consumer can
  switch off is advisory; [ADR-0028](../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md) —
  refusing rather than returning a plausible wrong answer, and not fabricating a backend's field.
- [ADR-0031](../adr/0031-one-nesting-detector-for-the-whole-jvm.md) — nesting refused because the
  permissive reading offers no atomicity, the argument FR-10's reentrancy rule inherits;
  [ADR-0032](../adr/0032-name-the-void-transaction-form-differently.md) — the naming precedent FR-09
  applies with a stronger measurement behind it.
- [threat model](../security/threat-model.md) §1 **B1**/**B3**, §2 *Denial of service* rows for FR-08
  and FR-10, and §2 *Information disclosure*, which gains a row for FR-09.
- JEP 428 / JDK 19 — `ExecutorService extends AutoCloseable` and the default `close()` whose
  one-day drain the probe above measured.
- Martin Kleppmann, *How to do distributed locking* (2016) — the fencing-token argument FR-10's
  `OptionalLong` exists to keep available.
- OWASP *Denial of Service Cheat Sheet* — bounded queues and explicit rejection as the mitigation
  FR-08 makes mandatory rather than optional.
