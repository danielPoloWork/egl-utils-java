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
- **`JsonMapper` and `JsonConversionException` — the first public API of `d4np-json`, and the first
  code this project has shipped outside `d4np-core`** (ROADMAP item 4.1, FR-20, RFC-0003,
  [ADR-0024](docs/adr/0024-take-a-jackson-type-in-one-signature.md),
  [ADR-0025](docs/adr/0025-render-java-time-as-iso-8601.md)). `JsonMapper.create()` is Jackson
  configured once — `JavaTimeModule` registered and **rendered as ISO-8601**, unknown properties
  tolerated, `INCLUDE_SOURCE_IN_LOCATION` off, and **polymorphic default typing explicitly
  deactivated**. `readValue(String, Class<T>)` and `writeValueAsString(Object)` are the surface;
  `withModules(..)` adds host Jackson modules **additively**, and nothing removes a setting. A
  document that is the literal `null` is refused rather than returned.
- `d4np-json` now **exports** `it.d4np.utils.json`, and takes `jackson-databind` +
  `jackson-datatype-jsr310` at **compile** scope — the first compile-scope third-party dependency in
  the reactor. Every Jackson `requires` on the descriptor is **non-transitive**: a consumer that calls
  `create()`, `readValue` or `writeValueAsString` declares no Jackson edge of its own, verified by
  compiling and running a consumer module that never names Jackson. `d4np-core` still sees none of it.
- **`ObjectMapperExtensions`, `PartialUpdate<T>` and `JsonTypeToken<T>` — FR-21, over the same
  hardened mapper** (ROADMAP item 4.2, RFC-0003,
  [ADR-0026](docs/adr/0026-rewrite-jacksons-unchecked-conversion-failure.md),
  [ADR-0027](docs/adr/0027-a-partial-update-renders-names-not-values.md)).
  `readPartial(mapper, json, Class<T>)` returns the instance **and** the top-level property names the
  document contained, so `{"a": null}` and `{}` stop being the same request — `isPresent("a")` with a
  `null` value is an explicit null, `!isPresent("a")` is an absence. It **refuses an unknown
  property** at every depth while `FAIL_ON_UNKNOWN_PROPERTIES` stays disabled for every other read:
  leniency is for a document you do not own, strictness is for an instruction, and the check is per
  operation rather than per mapper. It refuses a document that is not a JSON object, and the literal
  `null`, on the same C-02 grounds `readValue` does. `convert(mapper, source, Class<T>)` is deep
  conversion between POJO shapes; `convert(mapper, source, JsonTypeToken<T>)` is the same for a
  generic target, through **this library's own token** rather than Jackson's `TypeReference` — so no
  Jackson type reaches a published signature and a Jackson major release cannot force ours. A token
  over a type variable is refused at construction rather than resolved to its bound, and a conversion
  that would answer `null` is refused rather than returned. `PartialUpdate.toString()` renders the
  value's **type** and its property names, never the value.
- **No new module edge and no new dependency.** FR-21's three types join the package `d4np-json`
  already exports; the descriptor is untouched, and a consumer that never names a Jackson type still
  declares no Jackson `requires` of its own.
- **`SimpleJdbcExecutor`, `RowMapper<T>` and `JdbcAccessException` — the first public API of
  `d4np-jdbc`** (ROADMAP item 4.3, FR-05, NFR-03, RFC-0003,
  [ADR-0028](docs/adr/0028-the-fr-05-operation-set-and-what-it-refuses.md),
  [ADR-0029](docs/adr/0029-annotate-the-varargs-so-a-null-parameter-compiles.md)). Three operations
  — `query` returning an unmodifiable `List<T>`, `queryOne` returning `Optional<T>`, and `update`
  returning the affected count — each taking `(String sql, ..., Object... params)` and binding
  through a `PreparedStatement`. **No `java.sql.Statement` is created anywhere in the module**, so a
  parameterless call is still prepared and there is no overload that accepts pre-interpolated SQL.
  `queryOne` **refuses a second row** instead of returning the first. A `RowMapper` that returns
  `null` raises `IllegalStateException`, and a `DataSource` that hands back no connection is refused
  rather than dereferenced (control C-02).
- **Two connection lifecycles, and the difference is who closes it.**
  `SimpleJdbcExecutor.on(DataSource)` takes a connection per operation and closes it — FR-05's
  try-with-resources promise, asserted by asking the driver whether each connection it handed out is
  closed. `on(Connection)` borrows one and never closes it, which is the form FR-06's transaction
  runner will use (item 4.4). The `DataSource` form carries an `@apiNote`: captured into a
  transaction block it takes a *second* connection, so the work commits outside the transaction.
- **`d4np-jdbc` now exports `it.d4np.utils.jdbc` and takes `requires transitive java.sql`** — the
  only transitive edge in the repository, because a consumer *implements* `RowMapper` and cannot
  write that lambda without naming `ResultSet` and `SQLException`. **The module still has no
  third-party dependency at any scope a consumer resolves:** H2 is test scope, absent from the
  descriptor, and reached through `DriverManager` so that no type here — production, test or
  benchmark — names a driver class.
- **`JdbcTxRunner`, `TxIsolation`, `TxCallback<T>` and `TxVoidCallback` — FR-06's programmatic
  transactions** (ROADMAP item 4.4, RFC-0003,
  [ADR-0030](docs/adr/0030-the-two-channels-out-of-a-transaction-body.md),
  [ADR-0031](docs/adr/0031-one-nesting-detector-for-the-whole-jvm.md),
  [ADR-0032](docs/adr/0032-name-the-void-transaction-form-differently.md)). `inTransaction(body)`
  commits on a normal return and rolls back on **any `Throwable`, including an `Error`**;
  `inTransactionWithoutResult(body)` is the same without a value. The transactional `Connection` is
  passed to the body and is never ambient. **A returned `Result.Err` commits** — the exception
  channel demarcates the transaction, the value channel does not — and a returned `null` is refused
  with the transaction rolled back first. Scoped to hosts without a transaction manager; on Spring,
  use Spring's.
- **`TxIsolation` replaces JDBC's `int` constants**, so `TRANSACTION_NONE` and any other `int` cannot
  be passed to a transaction runner. `DEFAULT` means `setTransactionIsolation` is **never called** —
  not "read committed" — and a level that *is* applied is restored, together with `autoCommit`,
  before the connection returns to the pool.
- **Nesting is refused** with `IllegalStateException`, by one detector shared across every runner in
  the JVM: two pools cannot be nested either, because two uncoordinated transactions give no
  atomicity and the nested shape hides that. Savepoints and suspension are deliberately absent.
- **`d4np-jdbc` opts into `jcstress`** — the first module outside core to owe a concurrency harness,
  because `JdbcTxRunner`'s nesting detector is the first real per-thread state here.
- **`PageRequest`, `PageSort` and `PageResponse<T>` — FR-07's paging, with the sort clause closed by
  construction** (ROADMAP item 4.5, RFC-0003,
  [ADR-0033](docs/adr/0033-publish-no-accessor-for-the-unvalidated-sort.md),
  [ADR-0034](docs/adr/0034-mint-a-validation-failure-from-outside-core.md)).
  `PageRequest.of(page, size, sort)` bounds `page ≥ 0` and `1 ≤ size ≤ 200`, with
  `of(page, size, sort, maxSize)` as the configurable ceiling — a parameter, never a system property.
  **`PageRequest` publishes no accessor for the sort it carries:** `validatedAgainst(allowed)` takes
  the repository's allowlist and is the only member that returns the properties, so an `ORDER BY` —
  the one clause a column name can never be a bind parameter for — cannot be built from unchecked
  client input. A rejected property raises `ValidationException` (FR-19's **400**), names what was
  refused and never the allowlist, and reports every rejection rather than the first. Matching is
  exact and case-sensitive; at most 8 properties, and none twice.
- **`PageResponse<T>` derives `totalPages()` and `hasNext()`** rather than storing them, counts in
  `long` end to end, copies its content with `List.copyOf` (which rejects a `null` row), and renders
  the page's shape in `toString()` and never its rows. Neither type is `Serializable`.
- **`ValidationException.of(validated, violations)` — a public mint in `d4np-core`**
  ([ADR-0034](docs/adr/0034-mint-a-validation-failure-from-outside-core.md)). A module that reaches a
  validation verdict without a Bean Validation provider — FR-07's allowlist is the first — can now
  throw the type FR-19 maps to 400. Every string it carries is stripped of ISO control characters and
  truncated, and the message lists at most 20 violations, inside the exception rather than at each
  caller.
- `PageRequest.offset()` returns `(long) page * size`, which is the one derived value a caller would
  otherwise compute with a silent `int` overflow.
- **`CustomThreadPoolFactory`, `ThreadPoolSpec` and `ManagedThreadPool` — FR-08's pools, and the
  first public API of `d4np-concurrent`** (ROADMAP item 5.1, NFR-05, RFC-0004,
  [ADR-0035](docs/adr/0035-declare-autocloseable-so-the-override-is-legal.md)). The module exports
  `it.d4np.utils.concurrent` for the first time and keeps **zero third-party dependencies at every
  scope**, so a consumer takes `d4np-core` and this JAR and nothing else — everything used is in
  `java.base`, so unlike `d4np-jdbc` it adds no `requires` edge at all.
- **A bounded queue and a rejection policy are both mandatory**, with no defaulting overload.
  `Executors.newFixedThreadPool` uses an *unbounded* queue, over which an explicit
  `RejectedExecutionHandler` can never run — so requiring the handler without requiring the bound
  would have been decoration. Pass `Integer.MAX_VALUE` to opt into unbounded buffering explicitly.
- **`ManagedThreadPool` owns the shutdown budget and publishes no way to undo its configuration.**
  `close()` stops accepting, drains for the configured `drainTimeout`, then interrupts; it never
  throws, is idempotent, and logs an incomplete drain as a `WARNING` carrying the **count** of tasks
  that never started rather than the tasks. No setter and no accessor returns the underlying
  executor or its queue.
- **Threads are named `<pool>-<n>`, non-daemon by default, and always carry an
  uncaught-exception handler** — the default logs the failure's type through `System.Logger` and
  never its message. Thread priority is accepted and documented as advisory; no test asserts
  scheduling behaviour, because that claim cannot be made honestly.
- `ThreadPoolSpec.Builder` extends core's `FluentBuilder`, so a spec missing several mandatory
  fields reports all of them in one `BuilderValidationException` instead of one per build attempt.
- **`AsyncExecutor`, `ContextPropagator` and `ContextSnapshot` — FR-09's async wrapper and the
  context SPI that replaces MDC** (ROADMAP item 5.2, NFR-02, RFC-0004,
  [ADR-0036](docs/adr/0036-carry-context-through-an-spi-that-restores.md)). `AsyncExecutor.over(exec)`
  wraps any `Executor` and returns `CompletableFuture`s; `withContext(propagator)` returns a **new**
  instance, so a shared executor cannot change behaviour underneath its users.
- **Context propagation is an SPI, not a dependency.** FR-09 names SLF4J's `MDC`, which
  `d4np-concurrent` may not import at any scope, so the module publishes `ContextPropagator` /
  `ContextSnapshot` / `ContextSnapshot.Scope` and ships no implementation that reads a logging
  framework. Binding it to MDC is four lines in the host, given verbatim in the Javadoc. The default
  carries nothing **explicitly** rather than reaching for `org.slf4j.MDC` reflectively.
- **`Scope.close()` restores the previous context and never clears it**, so a pooled worker thread
  carries nothing of one task into the next — the information-disclosure case where a second
  request's log lines would otherwise show the first request's tenant or user. The restore also runs
  when the task body throws.
- **Two method names, `supply` and `run`, deliberately not one overloaded `submit`.** A
  `Supplier`/`Runnable` overload pair is not ambiguous — it silently binds to `Runnable` and returns
  `CompletableFuture<Void>` when a body gains braces, discarding the result.
- **Every failure arrives through the future, including a rejected submission.** This diverges from
  `CompletableFuture.supplyAsync`, which lets a `RejectedExecutionException` escape on the submitting
  thread, forcing a caller to handle one operation in two places.
- **`DistributedLock`, `LockHandle` and `DistributedLockException` — FR-10's lock contract, interface
  only** (ROADMAP item 5.3, RFC-0004,
  [ADR-0037](docs/adr/0037-a-fencing-token-that-restarts-is-worse-than-none.md),
  [ADR-0038](docs/adr/0038-refuse-the-convenience-form-the-rfc-sanctioned.md)). `d4np-concurrent`
  publishes the interface and no implementation, so a consumer never takes a backend client to get it.
  `tryAcquire(key, lease, wait)` returns `Optional<LockHandle>`: failing to acquire is an outcome, not
  an error.
- **Lease time is mandatory** — there is no overload that omits it — so a crashed or paused holder
  cannot starve everyone else; the backend releases the lock rather than the holder.
- **`LockHandle.fencingToken()` returns `OptionalLong`, and empty is a required answer** for any
  implementation that cannot keep the sequence strictly increasing across its own restart. A
  restarted counter is more dangerous than an absent one: it looks like a guarantee, and a resource
  trusting it accepts a stale writer. The mitigation is the protected resource's to apply, which the
  Javadoc states rather than implies.
- **`close()` releases the acquisition and never the key**, so a holder whose lease expired cannot
  delete the lock a later holder now owns. It is idempotent, never throws, and narrows
  `AutoCloseable`'s `throws Exception` away.
- **A non-reentrant implementation must refuse a nested acquisition rather than block on it**, so the
  failing case is a prompt empty result instead of a wait that ends at the lease with nothing logged.
- **`DistributedLockException` carries the lock key in `key()` and never in `getMessage()`**, which
  names only the operation and the failure's type. The key is bounded and stripped of control
  characters inside the type, because every thrower lives in another module.
- **`JwtTokenProvider`, `JwtVerifier`, `JwtProfile`, `JwtClaims`, `JwksSource` and
  `JwtVerificationException` — FR-11's JWT support, and the first public API of `d4np-security`**
  (ROADMAP item 6.1, ADR-003, RFC-0005,
  [ADR-0039](docs/adr/0039-detect-nimbus-shaded-gson-jpms-failure-at-construction.md)). One compile
  dependency, `nimbus-jose-jwt`, and no Nimbus type in any published signature.
- **One verifier accepts one algorithm**, fixed at construction. The token's `alg` is compared
  against it and never used to select a verifier, so `alg=none` and algorithm confusion are refused
  before key material is resolved.
- **Verifying and signing are separate types.** `JwtVerifier` verifies; `JwtTokenProvider` extends it
  and signs. A service that only consumes tokens has no `sign` method available to it.
- **`exp` is mandatory in both directions** — a token without one is rejected, and a token this
  library signs cannot lack one, because the provider sets `iss`, `aud`, `iat` and `exp` itself and
  `JwtClaims.with` refuses those names. Clock skew defaults to 60 s and is capped at five minutes.
- **HS256 secrets under 256 bits are rejected at construction**, with the measured bit count in the
  message.
- **`JwksSource` pins the JWKS trust posture**: the URL is fixed at construction from an origin
  allowlist, `jku`/`x5u` are never consulted, non-HTTPS is refused rather than upgraded, a redirect
  is refused rather than followed, and the fetch is bounded in time and size with a cached,
  rate-limited refresh.
- **Modular consumers must add `--add-reads com.nimbusds.jose.jwt=java.sql`.** Nimbus's shaded Gson
  reads `java.sql.Date` without declaring the edge, and its `ClassNotFoundException` guard does not
  catch the `IllegalAccessError` JPMS raises instead. The provider detects this at construction and
  names the flag rather than failing at the first token. Class-path consumers are unaffected.

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
- **The polymorphic-deserialization gadget chain (CWE-502) is closed for `JsonMapper`** — default
  typing is explicitly deactivated *and* the configured `ObjectMapper` is unreachable, so there is
  nothing for a consumer to re-enable it on. The threat-model row moves ▢ → ✅ (item 4.1). A
  `@JsonTypeInfo` annotation on a host's own type is the host's reviewed decision and is deliberately
  not overridden; that residual is pinned by a named test.
- **Control C-01 gains its second enforced call site, and one of its two defences is narrowed.** No
  message `d4np-json` produces carries any part of the document — it is assembled from the target type
  and Jackson's structural path — and `INCLUDE_SOURCE_IN_LOCATION` is disabled so the source snippet
  stays out of the `cause` too. **But `InvalidFormatException` quotes the rejected value in its own
  message and no Jackson setting governs that**, so the cause chain is not safe to render: FR-19's
  handler (item 7.1) must never put a cause's `getMessage()` into an RFC 7807 body. Document-supplied
  property names are additionally stripped of ISO control characters, so a crafted `Map` key cannot
  fold a log line in two.
- **Control C-01 gains a third call site and a leak channel the rule as written did not reach**
  (item 4.2, [ADR-0026](docs/adr/0026-rewrite-jacksons-unchecked-conversion-failure.md)). RFC-0003
  phrased the wrapping rule against the *checked* `JsonProcessingException`; FR-21's `convert` never
  raises one, because `ObjectMapper.convertValue` rethrows Jackson's failure as an **unchecked**
  `IllegalArgumentException` carrying Jackson's own message — which quotes the rejected value.
  Nothing in the language or in any gate here would have flagged letting it through, and it would
  have landed on FR-19's **500** fallback rather than 400. It is caught and rewritten from the target
  type and the structural path. The rule is restated in the form that holds: **no exception leaves
  this module carrying text this library did not write**, whatever its checked-ness.
- **The control stops being only about exceptions.** `PartialUpdate.toString()` names the value's
  type and lists its property names rather than rendering the value, and those names are bounded
  through the same routine every message uses — because a `toString()` reaches a log far more
  casually than an exception reaches a client, and with a `Map` target every document key is a known
  property ([ADR-0027](docs/adr/0027-a-partial-update-renders-names-not-values.md)).
- **SQL injection via string concatenation is closed for `SimpleJdbcExecutor`** — the threat-model
  row moves ▢ → ✅ (item 4.3). Defended by construction: every operation binds through a
  `PreparedStatement`, no overload omits the parameter slot, and no `java.sql.Statement` is created
  anywhere in the module. Asserted by running rather than reading — an injection payload is stored
  as a string with the table intact, and every operation runs through a `Connection` proxy that
  fails the test if `createStatement` is ever called. **Residual, stated:** a caller that
  concatenates the SQL string before passing it in is beyond what any Java API can stop.
- **Control C-01 gains its fourth call site, and its second independent provider.** A
  `JdbcAccessException` message is a fixed operation label plus the driver's SQLState and vendor
  code; the driver's own message — which H2 demonstrably fills with **both the bound parameter and
  the whole `insert` statement** — survives only as the cause. Two providers, two message channels,
  one rule, which is what makes item 7.1's "never render a cause's `getMessage()`" a property of the
  boundary handler rather than a workaround for one library.
- **C-01 reaches the library's own log lines for the first time** (item 4.4). FR-06's two lines — a
  rollback at `DEBUG`, a failed rollback at `WARNING` — name the failure's **type** and an SQLState,
  never its message, never SQL and never a parameter. Asserted against a body whose exception message
  carries a credential. This is the weaker of the two boundaries precisely because nobody reviews a
  log line for disclosure.

---

## Released versions

| Version | Date | Milestone | Entries |
|---------|------|-----------|---------|
| v0.1.0 | 2026-07-29 | M1 — Project bootstrap & CI | [docs/changelog/v0/v0.1.0.md](docs/changelog/v0/v0.1.0.md) |

