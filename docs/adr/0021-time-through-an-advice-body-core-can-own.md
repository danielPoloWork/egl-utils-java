# ADR-0021: Ship FR-15 as an advice body core can own, and bound what instrumentation is allowed to break

- **Status:** Accepted
- **Date:** 2026-08-04
- **Deciders:** tech-lead (implementation of ROADMAP item 3.2), owner
- **Related:** [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §FR-15; spec
  [§2 FR-15](../specs/01_spec_utils.md), §3 (module graph),
  [NFR-08](../specs/01_spec_utils.md) (dependency policy);
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (module split, zero-dependency core);
  [ADR-0014](0014-log-through-the-jdk-system-logger.md) (the logger and the injection seam);
  [ADR-0020](0020-render-violations-from-the-message-template.md) (the sibling item's decision);
  [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) (`@Nullable`)

## Context

Spec §2 names the type: **`ExecutionTimeMetricAspect`** — *"AOP timing aspect emitting Micrometer
Timers when a registry is present and logging otherwise; the AspectJ/Spring-AOP binding lives in the
adapter module, not core"*. RFC-0002 §FR-15 pins the sink signature, one-shot sink resolution, the
never-propagate rule and the both-completions rule.

Three things the spec and the RFC do **not** decide are unavoidable at the keyboard, and each has a
wrong answer that would look perfectly reasonable in review:

1. **What the type actually is.** Core may not depend on `aspectjrt` or on Micrometer (ADR-001; spec
   §3 puts both in `spring-adapter`'s `provided` set). So the class the spec names after an aspect
   cannot *be* an aspect here. Either the name changes, or the shape does.
2. **How the measured call is passed in.** An `@Around` advice holds a `ProceedingJoinPoint` whose
   `proceed()` throws `Throwable`. Whatever core exposes has to accept that without changing what the
   measured method's own caller sees.
3. **How far "instrumentation must not break the measured call" goes.** The RFC says a recorder that
   throws must not propagate. `Throwable` includes `OutOfMemoryError`, and "must not propagate" read
   literally means swallowing the news that the VM is dying.

There is also a smaller decision with a disproportionate footprint: the fallback recorder's **log
level**, which is on the execution path of every measured method in every host that installs no
metrics backend.

## Decision

**1. The name stays and the shape becomes an advice body.** `ExecutionTimeMetricAspect` lives in core
holding the measurement, the sink selection and the failure policy; `spring-adapter` contributes the
`@Aspect`, the pointcut and the annotation, and its advice is one line. The Javadoc opens by saying it
is not an AspectJ aspect, so the name never has to be reverse-engineered from the imports.

**2. The sink is an SPI with one core implementation.** `ExecutionTimeRecorder` is the functional
interface from the RFC's table; `LoggingExecutionTimeRecorder` is the dependency-free default, writing
through `System.Logger` (ADR-0014). The recorder is resolved **once, in the constructor** —
`create()` installs the logging one, `using(recorder)` takes the host's.

**3. Three entry points, split by exception shape.**

| Entry point | Signature | For |
|---|---|---|
| `time` | `<T> T time(String, Invocation<T>) throws Throwable` | `@Around` advice — `Invocation.proceed()` mirrors `ProceedingJoinPoint.proceed()` exactly |
| `call` | `<T> T call(String, Supplier<T>)` | ordinary code returning a value |
| `run` | `void run(String, Runnable)` | ordinary code returning nothing |

`call` and `run` **repeat** the eight-line measurement instead of delegating through `time`.

**4. The swallow is bounded at `RuntimeException | LinkageError`, and warns once.** A recorder failure
is caught, the measured call's outcome is untouched, and the first failure per aspect instance emits
one `WARNING` naming the recorder class; later failures are silent, guarded by an `AtomicBoolean`.
Everything else that an `Error` can be — an exhausted heap, a dying VM — propagates.

**5. The fallback logs at `DEBUG`,** and the level is package-private, not configurable.

**6. Timing is `System.nanoTime()`**, and `name` is rejected when `null` or blank.

**7. The Checkstyle ruleset gains `SuppressWarningsFilter` + `SuppressWarningsHolder`,** because
`throws Throwable` trips `IllegalThrows` and there was previously no way to make an exception to a
rule other than deleting it or silencing it for a whole file. `AGENTS.md` §9 already prescribes the
mechanism — *"suppress narrowly (`@SuppressWarnings("CheckName")` with a reason)"* — and without the
filter that annotation is inert for Checkstyle. Two methods carry it, each with the reason inline.

## Alternatives Considered

- **Rename the type to what it is (`ExecutionTimer`, `TimingAdvice`).** Honest, and rejected: the spec
  is the frozen contract (ADR-0010), so renaming means a spec change to gain a word, and every future
  reader tracing FR-15 by name would land on nothing. Keeping the name costs one paragraph of Javadoc.
- **Put the whole aspect in `spring-adapter` and leave core out of FR-15.** Rejected because it moves
  the *policy* — swallow, warn once, time failures too — into the module that may see Micrometer, where
  it becomes untestable without a Spring context and unavailable to a non-Spring host. Spec §3 assigns
  core FR-15 and the adapter only the *binding*.
- **One entry point taking `Callable<T>`.** Rejected: `Callable.call()` throws `Exception`, not
  `Throwable`, so an advice would have to wrap an `Error` from the measured method — changing the
  exception its caller catches, which is the one thing instrumentation must not do.
- **One entry point (`time`) only.** Rejected on the caller's cost: every non-advice call site would
  catch `Throwable` to time a lambda, and code that catches `Throwable` because a library made it is
  how real errors get swallowed three layers away from here.
- **`call`/`run` delegating to `time` and laundering the `Throwable` back.** The DRY option, and it was
  written first. Rejected because the laundering needs a final `catch (Throwable t) { throw new
  AssertionError(t); }` branch that **no test can reach** — a `Supplier` cannot throw a checked
  exception — so it would be permanently uncovered code, in a project with an 85% coverage gate, whose
  only purpose is to satisfy the compiler. Two duplicated `finally` blocks are cheaper and each is
  directly tested.
- **Swallow every `Throwable` from the recorder.** The literal reading of the RFC, and rejected on the
  failure mode it creates: an `OutOfMemoryError` raised inside a recorder would be absorbed, the
  measured method would look fine, and the diagnosis of a dying VM would begin at the wrong end. The
  narrow set is `RuntimeException | LinkageError` for the reason `Validator.fromProvider` catches the
  same pair (ADR-0020's sibling in item 3.1): a `provided`-scope backend absent from the runtime image
  fails with `NoClassDefFoundError`, and a missing metrics jar must not end a business call.
- **Warn on every recorder failure.** Rejected as a denial of service the library would inflict on its
  host: a sink that fails on every call would log per invocation, and the flood costs more than the
  metrics were worth. The accepted cost is stated rather than hidden — a recorder that starts failing
  *after* the one warning was already spent fails silently, and a host that needs to know a sink is
  down needs a sink health check, not a log line.
- **A plain `boolean` for the once-flag.** Rejected because "at most once" is a claim about a race:
  two threads would both read `false` and both warn. `ExecutionTimeRecorderFailureStress` forbids the
  two-warning outcome by name.
- **`INFO` for the fallback.** Rejected: it would be on by default in exactly the hosts that
  configured nothing, and a per-invocation log line is how instrumentation becomes the dominant cost
  of the method it measures. `TRACE` was rejected in the other direction — it is where most backends
  put wire-level dumps, and a timing is not that. The accepted cost of `DEBUG` is that a host
  expecting timings out of the box sees nothing; the Javadoc states it.
- **A configurable level, or a `Level` parameter on the recorder.** Rejected as a knob whose
  combinations this library would then have to support; a caller who needs a different level writes a
  recorder, which is a three-line lambda.
- **`Instant.now()`/`System.currentTimeMillis()` for the clock.** Rejected: a wall clock can step
  backwards under NTP, and a negative latency is not a value any backend can interpret.
- **Accept a blank `name`.** Rejected: it produces a metric nobody can attribute to a method, and the
  cost of finding that in a dashboard weeks later is far higher than an `IllegalArgumentException` at
  the call site.

## Consequences

- **Core gains three public types and no dependency edge.** `ExecutionTimeRecorder`,
  `LoggingExecutionTimeRecorder`, `ExecutionTimeMetricAspect` (plus the nested `Invocation`). The
  module descriptor is unchanged — `System.Logger` is in `java.base` — so `d4np-core` still requires
  nothing but `java.base` and `static jakarta.validation`.
- **Item 7.x inherits a one-line advice and a Micrometer recorder.** The adapter's work is the
  `@Aspect`, the pointcut, the annotation, and an `ExecutionTimeRecorder` over `MeterRegistry` —
  including the decision of how `failed` maps onto a `Timer` tag, which is Micrometer's vocabulary and
  therefore the adapter's decision, not this one's.
- **`time` returns `@Nullable`, and that is deliberate.** A measured method may legitimately return
  `null`; this class reports on a call, it does not judge its result. It is the one place in core where
  a `null` return is contractual, which is why ADR-0012's boundary is cited here rather than silently
  bent.
- **The fallback is invisible until a host enables `DEBUG`.** Anyone who reports "timings do not
  appear" should be pointed here. This is the accepted cost of not logging per invocation by default.
- **A recorder failure is reported once and then never again** — see the alternative above; the gap is
  real, named, and the wrong thing to close with more logging.
- **No performance claim is made, because no NFR states one.** FR-15 carries no numeric budget (unlike
  NFR-01's `Lazy` or NFR-04's `StrategyRegistry`), so there is no benchmark under `src/bench/` for it
  and none is implied. The only performance statement made anywhere here is negative and structural:
  nothing on the measured path synchronises, and the fallback tests the log level before rendering.
- **`IllegalThrows` now has a legal, narrow escape hatch, and item 7.x will need it.** An AspectJ
  `@Around` advice *must* declare `throws Throwable`, so the adapter's binding would have failed the
  same rule; the alternative — a path-keyed suppressions file — would have silenced the check for
  entire files including code written years later. The risk of the filter is that it makes every rule
  suppressible by annotation; the mitigation is that an annotation is visible in the diff and a
  suppressions file is not, which is why this is the shape `AGENTS.md` §9 asked for in the first place.
- **`ExecutionTimeRecorder` is the precedent for FR-12's `KeyProvider`** — a functional SPI in core
  with one dependency-free implementation and the third-party one in the module that may name it.

## References

- FR-15 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md); §3 for the module graph that
  forbids Micrometer in core.
- [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §FR-15 — the contract table this implements.
- `d4np-core/src/main/java/it/d4np/utils/ExecutionTimeMetricAspect.java`,
  `ExecutionTimeRecorder.java`, `LoggingExecutionTimeRecorder.java`.
- `d4np-core/src/test/java/it/d4np/utils/ExecutionTimeMetricAspectTest.java` and
  `LoggingExecutionTimeRecorderTest.java`;
  `d4np-core/src/jcstress/java/it/d4np/utils/ExecutionTimeRecorderFailureStress.java` for the
  at-most-once warning under a race.
