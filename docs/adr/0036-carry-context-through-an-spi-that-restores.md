# ADR-0036: Carry context through an SPI whose scope restores, never clears

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** tech-lead (implementation of ROADMAP item 5.2), owner, security-auditor (the
  information-disclosure row this closes)
- **Related:** [RFC-0004](../rfc/0004-concurrency-contracts.md) §FR-09 (the contract this implements
  and narrows in one place); spec [§2 FR-09, §3, NFR-02](../specs/01_spec_utils.md);
  [ADR-0014](0014-log-through-the-jdk-system-logger.md) (**which named this revisit and whose
  prediction this closes**);
  [ADR-0010](0010-single-specification-authority.md) rung 1 (why FR-09's spec sentence is superseded
  rather than edited);
  [ADR-0024](0024-take-a-jackson-type-in-one-signature.md) (the rejection of implicit,
  classpath-dependent configuration);
  [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) (a guarantee a consumer can forget is
  advisory);
  [ADR-0032](0032-name-the-void-transaction-form-differently.md) / ADR-001's naming-consequence rule
  (why the methods are `supply` and `run`);
  [threat model](../security/threat-model.md) §2 *Information disclosure* — the row added by item 5.0
  and closed here

## Context

**This decision is security-relevant, so under the enterprise posture it requires an ADR rather than
a judgement call** (AGENTS.md §7). The threat it addresses was not in the threat model until item 5.0
put it there: *a pooled worker thread carrying one task's context into the next*.

FR-09 asks for *"MDC context propagation"*. `MDC` is SLF4J's, and `d4np-concurrent` may not depend on
SLF4J at any scope — its `maven-enforcer` allowlist has exactly two entries, with no `provided`
exemption of the kind `d4np-core` holds for Bean Validation, so the dependency fails `mvn validate`
rather than review. This library does not log through SLF4J either; it uses `java.lang.System.Logger`,
which has **no MDC at all**.

ADR-0014 predicted precisely this when it chose the platform logger:

> *"`System.Logger`'s API is deliberately small — no fluent builders, no markers, no MDC. For one
> warning this is a fair trade; **a module that later needs structured context will find
> `System.Logger` thin, and that is the moment to revisit, not now.**"*

FR-09 is that module. This ADR is that revisit.

## Decision

**`d4np-concurrent` owns a three-type context SPI and ships no implementation that reads a logging
framework.** `ContextPropagator.capture()` runs on the submitting thread and returns a
`ContextSnapshot`; `ContextSnapshot.install()` runs on the worker thread and returns a
`ContextSnapshot.Scope`; `Scope.close()` **restores what was on the thread before**.

The invariant is normative:

> Every installed snapshot is closed on the same thread, in the reverse order of installation,
> including when the task body throws. A worker thread's context after a task is exactly what it was
> before.

### Restore, not clear — the decision the whole design turns on

Clearing after a task is the obvious implementation and it is wrong in both directions:

| Behaviour after the task | What breaks |
|---|---|
| **Clear** | Destroys context that was already on the thread — the nested-submission case, where an async task submits another |
| **Nothing** | Leaves this task's context visible to the next task on the same pooled worker: a different request, often a different user or tenant, whose log lines then carry someone else's identifiers |
| **Restore** | Correct in both |

The second row is the actual defect, and the reason this is an information-disclosure decision rather
than an ergonomics one. **It is demonstrated rather than asserted**, in the paired shape item 4.1
established: `leavesTheWorkerAsItFoundIt` and `theSamePoolLeaksWhenTheScopeRestoresNothing` run the
identical sequence over the identical pool, differing only in whether the `Scope` restores — and the
second observes `tenant-A` surviving on the worker.

**Both observations go straight to the pool rather than through a second `AsyncExecutor` call, and
that detail is the finding.** The first draft of the leak test submitted a second task through the
executor and *passed even against the leaky propagator*, because that second submission installs its
own capture over the residue and hides it. The leak is not visible to the next task through the same
executor; it is visible to **anyone else sharing the pool** — which is the realistic case, since a
pool is shared. A safety test that cannot see the defect it names is worse than none.

### The default is a real no-op, not a reflective MDC lookup

`ContextPropagator.none()` propagates nothing. Reaching for `org.slf4j.MDC` reflectively would work
when SLF4J happened to be on the classpath and silently do nothing otherwise — implicit configuration
inside the one type whose value is that its behaviour is explicit, which is exactly the argument that
refused `findAndRegisterModules()` for `JsonMapper` in item 4.1 (ADR-0024). It is also JPMS-hostile:
reflective access into a named module needs an `opens` this library cannot require. A propagator that
silently does nothing is discovered in production by a log line missing a correlation id.

**The MDC binding is the host's, and the Javadoc carries the code verbatim** so it is copied rather
than reinvented. It is deliberately **not** shipped in `d4np-spring-adapter`, whose allowlist would
permit SLF4J: MDC is SLF4J, not Spring, so putting it there would make a Jakarta EE host — which spec
§1 names as a first-class target — take a Spring dependency to get context propagation, in a library
whose stated objective is framework independence.

### `Scope.close()` narrows `AutoCloseable` and must not throw

`AutoCloseable.close()` declares `throws Exception`; `Scope.close()` declares nothing. A restore runs
inside `AsyncExecutor`'s own try-with-resources, where a thrown exception would replace whatever the
body was reporting — the same reasoning that keeps `ManagedThreadPool.close()` quiet and that kept
FR-06's transaction runner from holding its connection in a try-with-resources.

## One narrowing of RFC-0004, measured

RFC-0004 §FR-09 says *"a `supplyAsync` submission is on the order of a hundred nanoseconds, so 5 µs
is roughly two orders of magnitude of headroom"*, and routes NFR-02's gate to item 8.3 because the
bound is absolute. Both hold. What the RFC does not say, and what building the benchmark forced, is
**which submission**:

- Measured through a real pool with a `join()`, every arm costs **~12 µs**, including the raw one.
  That number is a thread handoff — park, unpark, context switch — and the wrapper under test is
  buried inside it. At CI's single iteration the arms were not even correctly ordered.
- Measured **inline** (`Runnable::run`, shared by both arms), raw `supplyAsync` is **~19 ns** and
  `AsyncExecutor` is **~13 ns**.

So NFR-02 is judged on the inline pair, where "submission overhead" is the only thing that differs.
The pooled pair is kept in the harness because deleting it would let a reader assume the budget
includes the handoff.

## Consequences

- **The threat model's information-disclosure row for FR-09 moves ▢ → ✅**, closed by a structural
  property of the SPI rather than by caller discipline.
- **`AsyncExecutor` overhead is negative against the JDK's own form** — ~13 ns versus ~19 ns inline —
  so NFR-02's 5 µs ceiling is met by roughly two to three orders of magnitude. Reported as a number
  rather than a verdict, per RFC-0004, and the audit phase owns whether to tighten it.
- **Four new public types**: `AsyncExecutor`, `ContextPropagator`, `ContextSnapshot` and nested
  `ContextSnapshot.Scope`. `NoContext` is package-private — publishing it would add a public type
  whose only purpose is to be the absence of another.
- **No new module dependency, at any scope.** `d4np-concurrent` still resolves to `d4np-core` alone.
- **Rejection travels through the future**, and that is a deliberate divergence from the JDK measured
  on both toolchains: `CompletableFuture.supplyAsync` lets a `RejectedExecutionException` escape on
  the submitting thread on Temurin 17.0.20.1+1 **and** 21.0.12.1+1 — consistent behaviour, not a
  version quirk — which would give one operation two failure paths. `AsyncExecutor` completes the
  future by hand so "every failure arrives through the future" is true of rejection as well as of the
  body. Pinned by a test that asserts both halves, so the divergence stays a decision.
- **An `Error` from a body is delivered through the future rather than left to escape**, because a
  future nobody completes is a caller waiting forever — a worse outcome than an `Error` the caller
  can see. It is delivered, not swallowed.

## Alternatives

1. **Reflective `org.slf4j.MDC`.** The only option keeping FR-09's sentence literally true. Rejected
   on its failure mode: silent, classpath-dependent, JPMS-hostile.
2. **Ship the MDC propagator in `d4np-spring-adapter`.** Legal — that allowlist bans only Redisson.
   Rejected because MDC is SLF4J and not Spring, and because `spring-adapter` does not depend on
   `d4np-concurrent` today, so spec §3's module graph would gain an arrow for a class containing no
   Spring type.
3. **A tenth module, `d4np-context-slf4j`.** Rejected on cost: a published artifact, a BOM entry, a
   japicmp baseline and a release note, for roughly twenty lines a host writes from the Javadoc.
   Revisit if a second propagator (OpenTelemetry, Micrometer) is ever wanted.
4. **A two-type SPI** — `interface ContextPropagator<C> { C capture(); Scope install(C); }`. Fewer
   types, and it forces `AsyncExecutor` to hold a `ContextPropagator<?>` whose wildcard cannot be
   bound without an unchecked helper. The three-type shape is null-free and needs no cast.
5. **Clear instead of restore.** Rejected above; it is the reading that breaks nested submissions
   while not fixing the leak.
6. **`CompletableFuture.supplyAsync(body, delegate)` internally.** Rejected on the measurement above:
   it lets rejection escape synchronously, which is the two-channel shape this contract removes.
