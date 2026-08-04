# 2026-08-04 — `ExecutionTimeMetricAspect`, and the aspect that cannot be one (ROADMAP item 3.2)

**Milestone 3, item 3.2 — the second item written against RFC-0002.** The RFC pinned five rows of
contract; the work was in the three things it left open, each of which has a reasonable-looking wrong
answer.

## What changed

`ExecutionTimeMetricAspect`, `ExecutionTimeRecorder` and `LoggingExecutionTimeRecorder` land in
`d4np-core` (216 → **239** tests), plus one jcstress harness. No new dependency and no new `requires`
edge: the fallback writes through `System.Logger` (ADR-0014), so the descriptor is untouched.
[ADR-0021](../../../adr/0021-time-through-an-advice-body-core-can-own.md) records the decisions; the
patterns catalogue's **Decorator (aspect)** row moves from `Planned` to `Implemented`.

## The decision that mattered

Spec §2 names the type `ExecutionTimeMetricAspect`; spec §3 forbids core from seeing `aspectjrt` or
Micrometer. **So the class the spec names after an aspect cannot be an aspect here** — either the name
changes or the shape does.

The shape changed. What lives in core is the **advice body**: the measurement, the sink selection and
the failure policy, all testable with no weaving at all. `spring-adapter` will contribute the
`@Aspect`, the pointcut and the annotation, and its advice is one line —
`timing.time(signature, joinPoint::proceed)`. Renaming to `ExecutionTimer` was the honest alternative
and lost on traceability: the spec is the frozen contract (ADR-0010), so a rename means a spec change
to gain a word while every future reader tracing FR-15 by name lands on nothing. The Javadoc's first
paragraph says it is not an AspectJ aspect, so nobody has to infer that from the imports.

**`Invocation.proceed()` throws `Throwable` for one reason: `ProceedingJoinPoint.proceed()` does.**
`Callable<T>` was the obvious signature and fails precisely there — it throws `Exception`, so an
`Error` from the measured method would have to be wrapped, and changing the exception a caller catches
is the one thing instrumentation must not do.

## Two things the RFC did not decide, and had to be

- **"A recorder that throws must not propagate" is bounded, not literal.** `Throwable` includes
  `OutOfMemoryError`, and swallowing that would absorb the news that the VM is dying while the measured
  method looked fine. The catch is `RuntimeException | LinkageError` — the same pair
  `Validator.fromProvider` catches, for the same reason: a `provided`-scope backend absent from the
  runtime image fails with `NoClassDefFoundError`, and a missing metrics jar must not end a business
  call.
- **"At most once per recorder" is a claim about a race, so it is proven as one.** The flag is an
  `AtomicBoolean` set with `compareAndSet`, and `ExecutionTimeRecorderFailureStress` runs two threads
  through a recorder that fails on every call and forbids the two-warning outcome **by name**. On a
  plain `boolean` both threads read `false`, both warn, and the first step of the log flood the rule
  exists to prevent ships looking green. Observed: `1, both ran`, only.

## Two smaller things worth carrying forward

- **`call`/`run` duplicate the measurement instead of delegating to `time`, and that is deliberate.**
  Laundering a `Throwable` back to unchecked form needs a `catch (Throwable t) { throw new
  AssertionError(t); }` branch **no test can reach** — a `Supplier` cannot throw a checked exception —
  so DRY here buys permanently uncovered code whose only job is to satisfy the compiler, under an 85%
  coverage gate. Two tested `finally` blocks beat one untestable branch.
- **A locale trap sits in the fallback, and it is the C-03 hazard in a new costume.**
  `System.Logger` substitutes with `MessageFormat`, which renders a `Long` through the *default*
  locale: a raw duration prints `1.234.567` on this machine (it_IT) and `1,234,567` on a US host — the
  same measurement in two log lines no single expression can parse. Every parameter therefore reaches
  the logger pre-rendered, and `rendersTheDurationIdenticallyOnEveryLocale` sets both locales and
  asserts one string. **Anyone adding a log line in this repository should assume the number is the
  bug.**

## The gate that `clean verify` does not run

`throws Throwable` trips Checkstyle's **`IllegalThrows`** — and `mvn clean verify` was **green**,
because `checkstyle:check` is a separate CI goal. Running CI's real command list locally is the only
reason this was found before a red round-trip.

The ruleset had no escape hatch at all: `@SuppressWarnings` is inert for Checkstyle unless
`SuppressWarningsFilter` + `SuppressWarningsHolder` are configured, so the only ways past a rule were
a path-keyed suppressions file — which silences it for a whole file, including code written later — or
deleting the rule. Both filter and holder are now in `config/checkstyle/checkstyle.xml`, which is what
makes the mechanism `AGENTS.md` §9 already prescribes actually function. **Item 7.x would have hit the
same wall**: an AspectJ `@Around` advice has no choice but to declare `throws Throwable`.

## Where the project stands

Milestone 3 has **3 of 4 items closed**. Only 3.3 (`AuditLog`, the redaction policy) remains, and it
is the largest of the four — RFC-0002 spends most of its length on it.

## What the next session needs to know

- **`ExecutionTimeRecorder` is the precedent for FR-12's `KeyProvider`**: a functional SPI in core, one
  dependency-free implementation here, the third-party one in the module allowed to name it.
- **Item 7.x inherits a one-line advice plus one open question this item deliberately did not answer:**
  how `failed` maps onto a Micrometer `Timer` — a tag, a second timer, or an error-counter — is
  Micrometer's vocabulary and therefore the adapter's decision.
- **The compliance register was left untouched on purpose.** Nothing on the timing path carries an
  argument value (`name` comes from a method signature), so C-01 gains no call site here; adding a row
  for a property the API cannot violate would dilute a register whose value is that every row is real.
- **`Unit` still has no call site**, as RFC-0002 predicted: `time` returns whatever the measured call
  returned, and `run` returns nothing at all. Item 3.3's `AuditLog.record` throws, so it will not
  produce one either.
- **The two unreproducible CI commands are unchanged** from item 3.1: `japicmp:cmp` resolves no plugin
  (item 8.1) and `-Pcoverage` matches no profile, so Maven warns and runs an ordinary `verify` (item
  8.2). A green local `-Pcoverage` is still a vacuous green.
