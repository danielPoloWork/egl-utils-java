# Software Specification: Java Enterprise Architecture Library (Java 21 (LTS))

> Rendered from the intake interview (Phase 5). Frozen contract: diverging implementation
> updates this spec in the same PR or adds an ADR superseding the relevant section.

## 1. Objective & Business Context

Give enterprise Java teams reusable, production-grade implementations of common enterprise
design patterns — keyed factories, fluent builders, strategy registries, thread-safe lazy
initialization, programmatic JDBC transactions, pooled/async execution, JWT and AES primitives,
a Result-based error model, and conversion helpers — adoptable a la carte WITHOUT inheriting a
framework. The pain removed is the dependency cost of the usual alternative: a monolithic
utility JAR drags Spring MVC, Jackson, AspectJ and Redisson into every consumer, inflating
dependency audits, CVE triage, and version conflicts against the host's own framework versions
(ADR-001 context). Consumers are Spring Boot 3.2+ services and plain Jakarta EE / JDBC hosts on
JDK 17+; `core` carries zero third-party dependencies, so a team can take one pattern without
taking a framework. Framework independence is a build property enforced in CI, not a slogan.

## 2. Functional Requirements

- FR-01 GenericFactory<T,K>: generic keyed factory. [GAP] no contract stated — unknown-key behavior, duplicate registration, and thread safety are undefined (item 4 got this treatment; item 1 did not). [RESOLVED by RFC-0001, docs/rfc/0001-core-contracts.md — that RFC pins the contract and takes precedence over this line.]
- FR-02 FluentBuilder<T>: base for fluent domain-object builders. [GAP] no contract stated — required-field enforcement, build() validation, and reuse-after-build are undefined. [RESOLVED by RFC-0001, docs/rfc/0001-core-contracts.md — that RFC pins the contract and takes precedence over this line.]
- FR-03 Lazy<T>: thread-safe lazy initialization via double-checked volatile; initializer runs at most once; exceptions memoized-or-retried per option; initializer returning null raises IllegalStateException; safe publication jcstress-verified (NFR-01).
- FR-04 StrategyRegistry<K,S>: Optional<S> find(K) for the missing-key case; S getOrThrow(K) throws StrategyNotFoundException carrying the known-keys list; ConcurrentHashMap-backed lock-free reads (NFR-04); register is last-write-wins with a warning log.
- FR-05 SimpleJdbcExecutor: try-with-resources lifecycle, POJO row mapping, PARAMETERIZED STATEMENTS ONLY — no string-concatenation overload exists, making PreparedStatement the enforced SQL-injection defense.
- FR-06 JdbcTxRunner: programmatic transactions over a plain DataSource, scoped to non-Spring hosts; Javadoc directs Spring users to Spring's own TransactionTemplate. [GAP] no contract stated — isolation levels, nesting/suspension, savepoints, and which exception types trigger rollback are all undefined.
- FR-07 PageRequest / PageResponse<T>: page >= 0, 1 <= size <= maxSize (default 200, configurable), violations throw IllegalArgumentException at construction; sort fields validated against a caller-supplied whitelist for injection-safe ordering; immutable.
- FR-08 CustomThreadPoolFactory: named pools, thread priorities, explicit RejectedExecutionHandler; 0 jcstress anomalies for rejection/shutdown races and graceful shutdown drains within the configured timeout (NFR-05).
- FR-09 AsyncExecutor: CompletableFuture-returning async wrapper over a pre-configured executor with MDC context propagation; submission overhead <= 5 us vs raw supplyAsync (NFR-02).
- FR-10 DistributedLock: INTERFACE ONLY in this module — the Redisson implementation ships separately so core never drags a Redis client (ADR-001); lease time mandatory; no reentrancy promise unless an implementation documents it.
- FR-11 JwtTokenProvider: HS256/RS256 sign/parse/validate on Nimbus JOSE+JWT (ADR-003) with a hardened profile — per-key algorithm allowlist (never HS256 and RS256 on one verifier), alg=none structurally impossible, mandatory exp with configurable clock skew (default 60 s), typ/aud/iss checks on by default, JWKS caching with rate-limited refresh, HS256 secrets under 256 bits rejected at construction.
- FR-12 AesEncryptor: AES-256-GCM only, unique random 96-bit IV per operation from SecureRandom, 128-bit auth tag, ECB prohibited; keys via a KeyProvider SPI (env/JCEKS/KMS), never hard-coded; ciphertext envelope v1:{keyId}:{iv}:{ct+tag} enables rotation (decrypt by old key id, re-encrypt with current); CryptoException never leaks javax.crypto internals or partial plaintext; stateless and concurrent. [GAP] no AAD support and no per-key message cap driving the rotation trigger.
- FR-13 OutputEncoder: context-aware OUTPUT encoding for XSS across HTML-body, HTML-attribute, JavaScript, and URL contexts (OWASP Java Encoder semantics). Deliberately NOT an input sanitizer — input filtering for XSS is an anti-pattern that manufactures false security; SQL-injection defense lives in FR-05 and allowlist input validation in FR-14.
- FR-14 Validator: programmatic wrapper over Jakarta Bean Validation 3.x (jakarta.validation), consistent with the compatibility matrix.
- FR-15 ExecutionTimeMetricAspect: AOP timing aspect emitting Micrometer Timers when a registry is present and logging otherwise; the AspectJ/Spring-AOP binding lives in the adapter module, not core.
- FR-16 AuditLog: annotation + service recording state-change audit trails (who / when / before-after values). [GAP] NO REDACTION POLICY — as specified this faithfully records secrets and PII into a store typically retained longer and replicated wider than application logs. Needs field-level allowlisting, a @Sensitive opt-out, and an explicit never-capture list.
- FR-17 Result<T>: sealed Ok/Err wrapper for EXPECTED business outcomes with map/flatMap/recover/orElseThrow(fn); a failure carries a typed ErrorDetail (code, message, cause); Ok(null) is forbidden — use Result<Void>; immutable.
- FR-18 BusinessException: UNCHECKED (extends RuntimeException) base for rule violations that abort the use case, carrying the same ErrorDetail type (ADR-002 records why checked exceptions lose here). Rule of thumb: if the caller is expected to branch on it, it is Result.Err; if only a boundary handler cares, it is thrown.
- FR-19 GlobalExceptionHandler: Spring MVC advice mapping exceptions to RFC 7807 application/problem+json; lives in the Spring adapter module; normative mapping table — BusinessException -> 422, validation -> 400, StrategyNotFoundException -> 500 + alert, fallback -> 500.
- FR-20 JsonMapper: pre-configured Jackson (JavaTimeModule, FAIL_ON_UNKNOWN_PROPERTIES=false, NO default typing — the polymorphic-deserialization CVE class is configured away); isolated in the json module so Jackson stays optional for core consumers.
- FR-21 ObjectMapperExtensions: deep-conversion and partial-mapping helpers. [GAP] operation list, null-vs-absent semantics, and collection/generic handling are unspecified.
- FR-22 StringCaseConverter: camelCase / snake_case / kebab-case conversion. [GAP] edge cases undefined — acronym runs (HTTPServer), embedded digits, and non-ASCII input need pinned table-driven expectations. [RESOLVED by RFC-0001, docs/rfc/0001-core-contracts.md — that RFC pins the contract and takes precedence over this line.]
- FR-23 ObjectUtils: null-safe comparison and validation helpers. [GAP] the helper set is not enumerated, so there is nothing specific to test. [RESOLVED by RFC-0001, docs/rfc/0001-core-contracts.md — that RFC pins the contract and takes precedence over this line.]
- FR-24 ResourceLoaderUtils: classpath resource loading. [GAP] behavior across exploded-directory vs JAR vs JPMS-encapsulated resources, and the missing-resource contract, are unspecified. [RESOLVED by RFC-0001, docs/rfc/0001-core-contracts.md — that RFC pins the contract and takes precedence over this line.]
- FR-25 ReflectionUtils: TEST-SCOPED reflection helpers in the test module; on JDK 9+ setAccessible against a non-opened module throws InaccessibleObjectException, so the module documents required --add-opens test JVM args and the helpers fail with an actionable message when absent; production code importing the test module fails the CI dependency check.


## 3. Non-Functional Requirements

<!-- Scalability / load budgets belong here as NUMBERS, not adjectives (the design "scalability"
     fold): a value per hard NFR axis — throughput / concurrency, p99 latency, memory ceiling,
     target FPS, cold-start budget — each phrased so CI could prove a violation. -->
- NFR-01 performance: Lazy.get() steady state <= 2 ns/op on the volatile-read path, AND 0 jcstress anomalous states for the initialization race (JMH + jcstress).
- NFR-02 performance: AsyncExecutor submission overhead <= 5 us vs raw CompletableFuture.supplyAsync (JMH).
- NFR-03 performance: SimpleJdbcExecutor row-mapping overhead <= 10% vs a hand-written ResultSet loop (H2 in-memory, 10k rows) (JMH).
- NFR-04 performance: StrategyRegistry.find <= 50 ns/op at 1k strategies under 8-thread read load (JMH).
- NFR-05 correctness: CustomThreadPoolFactory pools show 0 jcstress anomalies for rejection/shutdown races; graceful shutdown drains within the configured timeout (jcstress + integration test).
- NFR-06 performance/security: AesEncryptor >= 400 MB/s AES-256-GCM on the reference machine (AES-NI), plus an IV-uniqueness property test over 10^7 operations (JMH + property test).
- NFR-07 compatibility: JDK 17 baseline (--release 17) with 21 exercised in CI; jakarta.* namespace only (Bean Validation 3.x); Spring Boot 3.2+ supported in the adapter module only; JDK 8/11 and javax.* explicitly out of scope; every module ships module-info (JPMS).
- NFR-08 dependency policy: core has ZERO third-party dependencies (jakarta.validation-api as provided); Spring, Jackson, and Redisson types never appear in the public APIs of core/jdbc/concurrent; arrows point toward core only, no cycles. Enforced by maven-enforcer, so a PR leaking com.fasterxml into core fails the build rather than review.
- NFR-09 binary compatibility: japicmp guards binary compatibility per module against the previous release; the BOM is the consumer-facing versioning contract; SemVer with enforcement teeth.
- NFR-10 quality gates: JaCoCo line coverage >= 85%; PIT mutation score >= 60% on the core and security modules.
- NFR-11 security posture: OWASP Dependency-Check runs per PR; RFC 7515/7519 and NIST test vectors plus negative tests (alg-confusion, alg=none, expired, wrong-audience) live in the security suite. [GAP] no CVSS failing threshold or suppression-file policy is stated, so the scan is a report rather than a gate.
- NFR-12 release integrity: Maven Central publication via Sonatype with GPG-signed artifacts, sources and javadoc JARs, and the reproducible-build plugin; release notes generated from conventional commits. [GAP] no build provenance/attestation (SLSA or Sigstore) and no pinned CI action SHAs.


## 4. Logical Architecture & Core Algorithm

<!-- For a non-obvious core algorithm, include a short LANGUAGE-FREE pseudocode sketch (control
     flow + invariants) alongside the prose + diagram (the design "pseudocode" fold); skip it when
     the approach is standard. If the design owns persistent state, capture the data model here —
     entities, relations, normal form, migration policy — within ADR-0004's secondary-SQL frame. -->
Maven multi-module reactor (ADR-001). Framework independence is structural: a zero-dependency
core, capability modules that own their third-party surface, and adapters that isolate hosts.

  core                 [ZERO third-party deps; jakarta.validation-api as provided]
                        FR-01..04, FR-14..18, FR-22..24
  jdbc        -> core  [JDBC API only, no drivers]            FR-05..07
  concurrent  -> core  [no third-party deps]                  FR-08..10
  security    -> core  [compile: nimbus-jose-jwt]             FR-11..13
  json        -> core  [compile: jackson-databind + jsr310]   FR-20..21
  spring-adapter -> core, json  [provided: spring-web, spring-context, aspectj]  FR-19, FR-15 binding
  lock-redisson  -> concurrent  [compile: redisson]           FR-10 implementation
  test           -> core        [test scope only]             FR-25
  bom                           [consumer-facing versioning contract]

Rules, CI-enforced by maven-enforcer + japicmp:
  * arrows point toward core only; no cycles
  * Spring/Jackson/Redisson types never appear in core/jdbc/concurrent public APIs
  * every module ships module-info (JPMS); the test module documents its --add-opens needs

LAYOUT — RESOLVED by ADR-004 (.spec/adr/d4np_java_adr_004_generated_layout.md; status ACCEPTED by
the owner @danielPoloWork on 2026-07-26 — this note previously read "Proposed, awaiting the owner",
which was stale). The 9-artifact reactor cannot be expressed inside EADOS's single flat source
tree, because a Maven module is a directory with its own pom.xml — so src/main/java/it/d4np/utils/core
would be a PACKAGE, not a module, collapsing the reactor into the one JAR ADR-001 exists to prevent.
Decision: scaffold renders the governance layer plus one tree; Milestone 1 item 1.1 then establishes
the reactor and relocates that tree to become the `core` module's source root. Therefore read
src_main as CORE's package root, not the repository's only source root. Item 1.1 is a hard
prerequisite for M2, and NFR-08/NFR-09 enforcement is downstream of it.

## 5. Public Interface

<!-- The API contract (the design "api" fold): each operation with its payload shapes, the error
     model (the failure taxonomy, not just the happy path), and the versioning / SemVer surface.
     A service/web project may keep the written-out contract under docs/api/ (capabilities.api_spec). -->
Consumers import via `import it.d4np.utils.*;`. The public surface:

- Lazy<T>: get() non-null (initializer returning null -> IllegalStateException); initializer-exception policy per option; safe publication guaranteed (jcstress).
- StrategyRegistry<K,S>: find(K) -> Optional<S>, getOrThrow(K) -> S or StrategyNotFoundException listing known keys; never returns null; fully concurrent; register is last-write-wins with a warning log.
- PageRequest.of(page, size, sort) / PageResponse<T>: non-null args; IllegalArgumentException on bounds or sort-whitelist violation at construction; immutable.
- Result<T> (sealed Ok|Err): map, flatMap, recover, orElseThrow(fn); Ok(null) forbidden; failures carry ErrorDetail(code, message, cause); immutable.
- BusinessException (unchecked) + ErrorDetail: the shared error vocabulary across both channels and the problem+json wire format.
- AesEncryptor.encrypt/decrypt: non-null; CryptoException never leaks javax.crypto internals or partial plaintext; stateless and concurrent; envelope v1:{keyId}:{iv}:{ct+tag}.
- JwtTokenProvider: sign/parse/validate under the ADR-003 hardened profile; raw Nimbus types never leak from the API, so an implementation swap stays an internal change.
- SimpleJdbcExecutor / JdbcTxRunner: parameterized statements only; no string-concatenation overload is offered.
- SemVer surface: a MAJOR bump is any binary-incompatible change japicmp flags in a published module, any package-root change, or any raise of the JDK baseline. The BOM is the single import consumers pin.
- [GAP] Only 5 of ~25 public types carry a nullability/error/thread-safety contract row (spec section 5). The remaining types need the same treatment before freeze.


## 6. Verification & Test Strategy

Benchmarks: JMH 1.37+, forked JVMs, 5x10 warmup/measurement iterations, Blackhole to defeat
dead-code elimination; harnesses committed under bench/. Reference machine Ryzen 7 5800X on
JDK 21. Nightly CI tracks results and fails a > 10% regression.

Concurrency: every thread-safety claim is backed by a NAMED jcstress test (harnesses under
jcstress/), reduced iterations per PR and the full suite nightly. A claim without a jcstress
test is not a claim — this is the rule that replaces v1's unverified "no deadlocks" assertion.

Functional: JUnit 5 across the CI matrix (JDK 17 + 21); H2 in-memory for JDBC; RFC 7515/7519
and NIST test vectors for JWT/AES plus negative tests (alg-confusion, alg=none, expired token,
wrong audience); an IV-uniqueness property test over 10^7 operations.

Gates: JaCoCo line coverage >= 85%; PIT mutation >= 60% on core and security; maven-enforcer
for dependency convergence and the banned-dependency rules; japicmp for per-module binary
compatibility; OWASP Dependency-Check per PR; a CI check failing production code that imports
the test module.

Release: SemVer; Maven Central via Sonatype with GPG-signed artifacts, sources + javadoc JARs,
and the reproducible-build plugin; release notes generated from conventional commits.

[GAP] Reproducibility of the absolute budgets: NFR-01 (<= 2 ns/op) and NFR-06 (>= 400 MB/s) are
stated against a named reference machine, but GitHub-hosted runners vary far more than 10% in
both CPU model and steal time. As written these gates will be flaky. Resolve by pinning the
perf gate to a self-hosted runner, or by comparing against a stored per-runner baseline rather
than an absolute number. NFR-01 additionally sits near JMH's measurement noise floor.

Toolchain: built with Maven 3.9+ (multi-module reactor), tested with JUnit 5 + AssertJ; jqwik (property tests); jcstress (concurrency); H2 (JDBC integration), checked with
ErrorProne/NullAway (compile-time soundness), jcstress (concurrency), JFR leak profiling, OWASP Dependency-Check, coverage target ≥ 85% line. Every functional and
non-functional requirement above maps to a CI gate (see [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)).
