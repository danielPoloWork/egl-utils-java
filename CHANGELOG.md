# Changelog

All notable changes to `egl-utils-java` are documented here, following
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning 2.0.0](https://semver.org/).

Every PR that introduces a user-visible change adds a line to `[Unreleased]` in the same
PR. A release PR moves the `[Unreleased]` entries into a new per-version file under
`docs/changelog/v<MAJOR>/v<X.Y.Z>.md` and adds an index row below.

## [Unreleased]

### Added

- **The core error vocabulary — the first public API of `d4np-core`** (ROADMAP item 2.1, FR-17, FR-18,
  ADR-002, RFC-0001): `Result<T>` sealed over `Ok<T>` and `Err<T>` with `map`, `flatMap`, `recover`
  and `orElseThrow`; `ErrorDetail(String code, String message, Throwable cause)`; and the unchecked
  `BusinessException` that carries the same detail. `Ok` rejects a `null` payload, and no operation
  accepts or returns `null`.
- `it.d4np.utils.Nullable` — the library's own nullability marker, so `d4np-core` keeps zero
  third-party dependencies while NullAway still checks it ([ADR-0011](docs/adr/0011-declare-the-nullability-annotation-in-core.md)).
- `d4np-core` now **exports** `it.d4np.utils` from its module descriptor; consumers can
  `import it.d4np.utils.*;` for the first time.
- **`Lazy<T>`** — thread-safe, compute-once initialization (ROADMAP item 2.2, FR-03, NFR-01,
  RFC-0001, [ADR-0013](docs/adr/0013-lazy-initialization-by-double-checked-volatile.md)).
  `Lazy.of(supplier)` **retries** after a failed initialization; `Lazy.memoizingFailures(supplier)`
  remembers the first failure and rethrows it unchanged. An initializer that returns `null` or calls
  `get()` on the `Lazy` it is initializing raises `IllegalStateException`. **The first NFR in this
  project backed by a measurement rather than an assertion:** NFR-01 budgets `get()` at ≤ 2 ns/op in
  steady state, and `LazyGetBenchmark` measures **0.827 ns/op on JDK 17 and 0.945 ns/op on JDK 21** —
  0.2–0.4 ns/op above the raw volatile read the budget is made of, which is the delta that says the
  call inlines ([report](docs/benchmarks/2026-08-01-lazy-get.md)). Safe publication and at-most-once
  initialization are proven by two named jcstress harnesses, not claimed.
- **`StrategyRegistry<K,S>` and `StrategyNotFoundException`** — keyed strategy lookup with lock-free
  reads (ROADMAP item 2.3, FR-04, NFR-04, RFC-0001,
  [ADR-0015](docs/adr/0015-strategy-registry-last-write-wins.md)). `find(key)` returns an
  `Optional` for the case where absence is an ordinary answer; `getOrThrow(key)` raises
  `StrategyNotFoundException` **carrying every key that is registered**, which is usually what ends
  the investigation at the log line. `register` is last-write-wins and never silent — a replacement
  emits a `WARNING` naming the key and both strategy classes. **NFR-04 is met on both toolchains:**
  `find` measures **12.8 ns/op on JDK 21 and 17.8 ns/op on JDK 17** at the budgeted shape of 1000
  strategies under 8-thread read load, against a ≤ 50 ns/op budget
  ([report](docs/benchmarks/2026-08-01-strategy-registry-find.md)). `StrategyNotFoundException`
  deliberately does **not** extend `BusinessException`: FR-19 maps the two to different HTTP statuses
  (500 + alert versus 422), and a test asserts the negative.
- **`GenericFactory<T,K>` and `FactoryKeyNotFoundException`** — keyed construction without naming a
  concrete type (ROADMAP item 2.4, FR-01, RFC-0001,
  [ADR-0016](docs/adr/0016-generic-factory-atomic-duplicate-rejection.md)). `register` **rejects a
  duplicate key**, `replace` is the explicit override, `create` throws with every bound key listed,
  `tryCreate` returns an `Optional`, and `keys()` is an unmodifiable snapshot. The deliberate
  opposite of `StrategyRegistry`, which is last-write-wins: a factory is wired once at startup, where
  a duplicate usually means two modules claiming one discriminator. **The factory is thread-safe — a
  contract neither the spec nor RFC-0001 stated** — and duplicate rejection is a single
  `putIfAbsent`, so two threads racing on one key cannot both believe they won; a jcstress harness
  forbids that outcome by name.
- **`FluentBuilder<T>` and `BuilderValidationException`** — the template-method base for fluent
  builders (ROADMAP item 2.4, FR-02, RFC-0001,
  [ADR-0017](docs/adr/0017-fluent-builder-accumulated-validation.md)). `build()` runs the subclass's
  whole `validate()` and reports **every** violation at once rather than the first, is repeatable,
  and returns a distinct instance per call over a builder that is not reset. Beyond the four members
  RFC-0001 sketched it adds **`reject(String)`**, without which cross-field invariants could only
  throw — and so could not participate in the accumulate-everything contract the RFC states.
- **`StringCaseConverter`, `ObjectUtils`, `ResourceLoaderUtils` and `ResourceNotFoundException`** —
  the last three types of Milestone 2 (ROADMAP item 2.5, FR-22/23/24, RFC-0001,
  [ADR-0018](docs/adr/0018-tokenizer-word-threshold-and-utf8-default.md)). `StringCaseConverter`
  renders `camelCase` / `snake_case` / `kebab-case` from one shared tokenizer, mapping case with
  **`Locale.ROOT`** so a Turkish-locale JVM cannot silently corrupt an identifier; conversions are
  idempotent and total, and the acronym round trip is an explicit **non**-guarantee.
  `ObjectUtils` holds **only what `java.util.Objects` does not** — `anyNull`, `allNonNull`,
  `requireNonBlank`, `compareNullsFirst`/`Last`, and typed `isEmpty`/`isNotEmpty` overloads with no
  `isEmpty(Object)` to bind to by accident. `ResourceLoaderUtils` resolves through a caller-supplied
  `Class<?>` **anchor** — the only rule that works across exploded, JAR and named-module layouts —
  normalizes a leading `/`, **rejects any name containing `..`**, and defaults to UTF-8 written out
  rather than the platform charset.
- **`Unit`, and `Result.ok()`** — a successful outcome with nothing to return (ROADMAP item 3.0,
  FR-17, [ADR-0019](docs/adr/0019-mint-unit-for-the-void-success.md)). `Result<Void>` **cannot be
  constructed** — `Void` is uninhabited and `Ok` rejects `null` — so the construction FR-17 used to
  recommend was impossible for any caller, including this library
  ([ADR-0012](docs/adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) recorded the proof).
  `Unit` closes it without making `null` sayable: `Result.ok()` passes `Unit.INSTANCE` through the
  same canonical constructor as every other payload.
- **`Validator` and `ValidationException`** — Jakarta Bean Validation 3.x behind this library's own
  error model (ROADMAP item 3.1, FR-14, RFC-0002,
  [ADR-0020](docs/adr/0020-render-violations-from-the-message-template.md)). `validate` answers with
  a `Result<T>`, `requireValid` returns the candidate or throws, and `violations` hands back the
  rendered list; all three take optional validation groups. **A violation never carries the rejected
  value** — every one renders as `property path: message template`, never Jakarta's interpolated
  `getMessage()` (which resolves `${validatedValue}`) and never `getInvalidValue()`, so a credential
  cannot ride an error message into an RFC 7807 body (compliance control C-01). The list is sorted,
  because the provider's `Set` is not. **The dependency stays optional:** `core` declares
  `jakarta.validation-api` at `provided` scope and `requires static jakarta.validation`, so a
  consumer that never validates carries nothing, and one that does gets an `IllegalStateException`
  at `Validator.create()` naming both missing artifacts rather than a `NoClassDefFoundError` from
  the first validated call. `ValidationException` stays outside the `BusinessException` hierarchy:
  FR-19 maps validation to **400** and `BusinessException` to **422**.
- **`ExecutionTimeMetricAspect`, `ExecutionTimeRecorder` and `LoggingExecutionTimeRecorder`** —
  execution timing with a pluggable sink (ROADMAP item 3.2, FR-15, RFC-0002,
  [ADR-0021](docs/adr/0021-time-through-an-advice-body-core-can-own.md)). `create()` times through the
  dependency-free fallback; `using(recorder)` takes a host's sink, and it is **resolved once, at
  construction**, so two measurements of one method stay comparable. Three entry points: `time` carries
  `throws Throwable` for an `@Around` advice — its `Invocation` mirrors `ProceedingJoinPoint.proceed()`
  so the adapter wraps nothing — while `call(Supplier)` and `run(Runnable)` serve ordinary code.
  **Instrumentation never breaks the measured call:** a recorder that throws a `RuntimeException` or a
  `LinkageError` is absorbed and the measured outcome is unchanged, warned about **at most once per
  aspect** (an unbounded warning on a failing sink is its own denial of service, proven under a race by
  `ExecutionTimeRecorderFailureStress`); a dying-VM `Error` still propagates. **Failed invocations are
  timed too**, distinguished by `failed`, because timing only successes biases every latency number.
  **Despite its name it is not an AspectJ aspect** — core may see neither `aspectjrt` nor Micrometer
  (ADR-001), so this is the advice body and the `@Aspect`, the pointcut and the Micrometer recorder
  arrive with the Spring adapter. The fallback logs at **`DEBUG`**: a host that has enabled nothing sees
  nothing, deliberately.
- **`AuditLog`, `AuditEvent`, `AuditPolicy`, `AuditSink`, `LoggingAuditSink`, `@Audited`,
  `@Sensitive`, `AuditWriteException` and `AuditCaptureException`** — state-change audit trails that
  cannot carry a secret, and the last item of Milestone 3 (ROADMAP item 3.3, FR-16, RFC-0002,
  [ADR-0022](docs/adr/0022-redact-at-capture-behind-a-typed-event.md)). **Redaction happens at
  capture:** `capture(actor, action, before, after)` returns an `AuditEvent` that already holds
  `[REDACTED]` where a value was blocked and offers **no API that returns a raw value**, because an
  event passes through interceptors, queues, heap dumps and `toString()` calls on its way to a sink —
  and a sink cannot leak what it never receives. Four layers decide each component, first match wins:
  `AuditPolicy`'s never-capture list (`password`, `api_key`, `card_number`, …, matched on **whole
  tokens** so `pin` never redacts `shipping`), then `@Sensitive`, then `@Audited` on the component,
  then `@Audited` on the type, and otherwise the component is **omitted entirely** — a different
  outcome from redacted, deliberately. **A blocked component still records that it changed**, which is
  how *"the password was changed at 14:02 by alice"* reaches the trail without the password. Only
  simple values are captured directly; a composite must carry `@Audited` on its own type and is
  otherwise **refused**, because `String.valueOf` on a record prints every component and would publish
  a nested `@Sensitive` field with its marker present and bypassed. Recursion is bounded at three
  levels, cycles are refused by identity, and five misconfiguration shapes fail loudly at first
  capture rather than producing a plausible record. `record(event)` **throws** `AuditWriteException`
  when the sink fails — the deliberate opposite of FR-15's swallow, because a silent audit trail is a
  compliance hole. A host may **add** never-capture entries and can never remove one.
- **`d4np-core` logs, for the first time, and does it through `java.lang.System.Logger`**
  ([ADR-0014](docs/adr/0014-log-through-the-jdk-system-logger.md)). No logging dependency and no new
  `requires` edge — the module still requires nothing but `java.base` — and the output routes through
  whatever backend the consuming application already installed. This is the precedent for every later
  module that needs to log.

### Changed

- The Checkstyle ruleset gains `SuppressWarningsFilter` and `SuppressWarningsHolder`, so a narrow
  `@SuppressWarnings("CheckName")` with a stated reason suppresses a Checkstyle violation as
  `AGENTS.md` §9 prescribes. Without them the annotation was inert and the only exit from a rule was a
  path-keyed suppressions file that silences it for an entire file. First needed by FR-15's
  `throws Throwable` (item 3.2, [ADR-0021](docs/adr/0021-time-through-an-advice-body-core-can-own.md)).
- The `jcstress` profile passes `-jvmArgsPrepend "-XX:+UnlockDiagnosticVMOptions
  -XX:-RestrictContended"`, restoring `@Contended` padding in the forked test VMs. jcstress's own
  capability probe cannot survive a classpath directory containing a class named `*Result*`, and
  without this the concurrency harness silently loses sensitivity (see item 2.1).

### Deprecated

### Removed

### Fixed

- `StrategyNotFoundException`'s Javadoc referenced `{@value #MAX_KEYS_IN_MESSAGE}`, a constant that
  moved to the package-private `KeyDiagnostics` when item 2.4 extracted it. The reference has been
  dangling since, and `javadoc -Xdoclint:all` fails on it — found by running the Javadoc gate item 8.4
  will own, not by reading the file.

### Security

- **Compliance control C-05 is added** — an audit record carries no secret, credential or PII value —
  and it is a property of the type rather than a discipline: `AuditEvent` exposes no raw value and
  only `AuditLog.capture` can mint one (item 3.3,
  [ADR-0022](docs/adr/0022-redact-at-capture-behind-a-typed-event.md)).
- **Control C-03 moves from partial to enforced.** Its row said no gate forbade the default-locale
  `String.toLowerCase()`; reintroducing one shows ErrorProne's `StringCaseLocaleUsage` fires and
  `failOnWarning` fails the build. Under FR-16 that overload is what would let `API_KEY` miss the
  never-capture list on a Turkish-locale JVM and reach the audit store in clear.

---

## Released versions

| Version | Date | Milestone | Entries |
|---------|------|-----------|---------|
| v0.1.0 | 2026-07-29 | M1 — Project bootstrap & CI | [docs/changelog/v0/v0.1.0.md](docs/changelog/v0/v0.1.0.md) |

