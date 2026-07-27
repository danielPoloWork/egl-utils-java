# Software Specification: d4np-java (Java Enterprise Architecture Library)

| | |
|---|---|
| **Version** | 2.0 (addresses spec-review issue #9) |
| **Date** | 2026-07-14 |
| **Status** | Reviewed draft |
| **ADRs** | [ADR-001: Module split & dependency policy](adr/d4np_java_adr_001_module_split.md) · [ADR-002: Error model](adr/d4np_java_adr_002_error_model.md) · [ADR-003: JWT library](adr/d4np_java_adr_003_jwt_library.md) · [ADR-004: Generated repository layout](adr/d4np_java_adr_004_generated_layout.md) |

## 1. Description & Design Philosophy
`d4np-java` is a set of Java libraries implementing enterprise design patterns for Spring Boot and Jakarta EE systems.

Design principles — each now tied to a verification mechanism (§6):
* **Maximum decoupling:** interfaces + DI for substitutability (SOLID). Made *structural* rather than aspirational by the multi-module split and per-module dependency policy in [ADR-001](adr/d4np_java_adr_001_module_split.md) — v1 packed 25 components with Spring MVC, Jackson, AOP, and Redisson implications into one JAR, contradicting this very principle.
* **Thread robustness:** `java.util.concurrent` primitives; **every thread-safety claim is backed by a named jcstress test** (§6) — v1 claimed "no deadlocks or race conditions" with no verification story.
* **Type safety:** advanced generics and wildcards.

### 1.1 Compatibility matrix (the library's most important published contract — absent in v1)
| Dimension | Supported | Explicitly out of scope |
|---|---|---|
| JDK | **17 baseline** (LTS), 21 tested in CI | 8/11 (would forbid records, sealed types, and the jakarta namespace) |
| Namespace | **`jakarta.*`** (Bean Validation 3.x — `jakarta.validation`, corrected from v1's JSR 380/`javax` citation) | `javax.*` |
| Spring Boot | 3.2+ (adapter module only) | 2.x |
| JPMS | all modules ship `module-info`; `d4np-test` requires `--add-opens` (documented, §2 item 25) | — |

---

## 2. Functional Specification (25 items, organized by module — ADR-001)

### `d4np-core` (zero third-party dependencies) — Creational & Structural
1. **`GenericFactory<T, K>`** — generic keyed factory.
2. **`FluentBuilder<T>`** — base for fluent domain-object builders.
3. **`Lazy<T>`** — thread-safe lazy initialization (double-checked volatile; contract: initializer runs at most once, exceptions memoized-or-retried per option — jcstress-verified, NFR-01).
4. **`StrategyRegistry<K, S>`** — dynamic strategy registry. **Method contract (v1 left `get` undefined):** `Optional<S> find(K key)` for the missing-key case; `S getOrThrow(K key)` throws `StrategyNotFoundException` with the known-keys list in the message; backed by `ConcurrentHashMap` (lock-free reads, NFR-04). The §7 example uses this contract.

### `d4np-jdbc` — Persistence
5. **`SimpleJdbcExecutor`** — light JDBC wrapper: try-with-resources lifecycle, POJO row mapping, and **parameterized statements only** — the API offers no string-concatenation overload, making `PreparedStatement` the enforced (not suggested) SQL-injection defense (§4).
6. **`JdbcTxRunner`** — programmatic transactions over a plain `DataSource` *(renamed from v1's `TransactionTemplate`, which collided with Spring's own `org.springframework.transaction.support.TransactionTemplate` — the class that already solves this in Spring contexts)*. Differentiation stated: this runner is for **non-Spring** hosts (plain JDBC/Jakarta); Spring users should use Spring's template, and the Javadoc says so.
7. **`PageRequest` / `PageResponse<T>`** — pagination models. **Contract (v1 unspecified):** `page ≥ 0`, `1 ≤ size ≤ maxSize` (default 200, configurable), violations throw `IllegalArgumentException` at construction; sort fields validated against a caller-supplied whitelist (injection-safe ordering).

### `d4np-concurrent` — Concurrency
8. **`CustomThreadPoolFactory`** — named pools, priorities, explicit `RejectedExecutionHandler`.
9. **`AsyncExecutor`** — `CompletableFuture`-returning async wrapper with pre-configured executor and MDC context propagation.
10. **`DistributedLock`** — **interface only** in this module; the Redisson implementation lives in optional `d4np-lock-redisson` so the core never drags Redis clients (ADR-001). Contract: lease time mandatory, no reentrancy promise unless the implementation documents it.

### `d4np-security` — Security & Authentication
11. **`JwtTokenProvider`** — JWT sign/parse/validate (HS256/RS256) on **Nimbus JOSE+JWT** ([ADR-003](adr/d4np_java_adr_003_jwt_library.md)); `alg` allowlist enforced (`none` and algorithm-confusion rejected), clock-skew tolerance configurable.
12. **`AesEncryptor`** — **symmetric** AES encryption *(v1 said "asymmetric AES" — AES is a symmetric cipher; corrected)*. Pinned parameters: **AES-256-GCM**, unique random **96-bit IV per operation** (`SecureRandom`), 128-bit auth tag; **ECB prohibited**. Key management: keys come from a `KeyProvider` SPI (env var, JCEKS, or KMS adapter) — never hard-coded; ciphertext envelope `v1:{keyId}:{iv}:{ct+tag}` enables **key rotation** (decrypt with old key by id, re-encrypt with current).
13. **`OutputEncoder`** — *(rescoped from v1's `Sanitizer`, whose "input filtering against SQL injection/XSS" claim was an anti-pattern that would create false security)*: context-aware **output encoding** for XSS (HTML body, HTML attribute, JavaScript, URL contexts — OWASP Java Encoder semantics). SQL-injection defense lives where it belongs: parameterized queries in item 5. Input *validation* (allowlists) remains item 14's job.

### `d4np-core` — Validation & Monitoring
14. **`Validator`** — programmatic wrapper over **Jakarta Bean Validation 3.x** (`jakarta.validation`, consistent with §1.1).
15. **`ExecutionTimeMetricAspect`** — AOP timing aspect; emits **Micrometer** `Timer`s when a registry is present, logs otherwise (observability beyond log lines — v1 had none); AspectJ/Spring-AOP binding lives in the adapter module.
16. **`AuditLog`** — annotation + service for state-change audit trails (who/when/before-after values).

### `d4np-core` — Error Handling ([ADR-002](adr/d4np_java_adr_002_error_model.md))
17. **`Result<T>`** — success/failure wrapper for **expected business outcomes**. Contract: `map`, `flatMap`, `recover`, `orElseThrow(fn)`; a failure carries a typed `ErrorDetail` (code, message, cause); sealed — exactly `Ok`/`Err`.
18. **`BusinessException`** — **unchecked** base exception *(v1 made it checked; ADR-002 records why checked exceptions lose in this design)* for rule violations that abort the use case.
19. **`GlobalExceptionHandler`** — Spring MVC advice mapping exceptions to RFC 7807 `application/problem+json`; lives in `d4np-spring-adapter`; the exception→status mapping table is part of the spec (`BusinessException`→422, validation→400, `StrategyNotFoundException`→500 + alert, fallback→500).

### `d4np-json` / `d4np-core` — Utilities & Conversion
20. **`JsonMapper`** — pre-configured Jackson (JavaTimeModule, `FAIL_ON_UNKNOWN_PROPERTIES=false`, no default typing — the polymorphic-deserialization CVE class is configured away); lives in `d4np-json` (Jackson is optional for core consumers).
21. **`ObjectMapperExtensions`** — deep-conversion/partial-mapping helpers (same module).
22. **`StringCaseConverter`** — camelCase/snake_case/kebab-case conversions.
23. **`ObjectUtils`** — null-safe comparison/validation helpers.
24. **`ResourceLoaderUtils`** — classpath resource loading.
25. **`ReflectionUtils`** — test-scoped reflection helpers, in `d4np-test`. **JPMS constraint documented (v1 ignored it):** on JDK 9+, `setAccessible` on non-opened modules throws `InaccessibleObjectException`; the module's docs require `--add-opens` in test JVM args and the helpers fail with an actionable message when it is missing. Production code importing `d4np-test` fails the CI dependency check.

---

## 3. Architecture (C4 Component View — modules & allowed dependencies)
```
 ┌─ d4np-java (Maven multi-module — ADR-001) ─────────────────────────────────┐
 │                                                                            │
 │  d4np-spring-adapter ──► d4np-core, d4np-json     [provided: spring-web,   │
 │   (GlobalExceptionHandler, AOP binding, DI glue)   spring-context, aspectj]│
 │  d4np-lock-redisson  ──► d4np-concurrent          [compile: redisson]      │
 │  d4np-test           ──► d4np-core                [test scope only]        │
 │                                                                            │
 │  d4np-jdbc ──► d4np-core            [no driver deps — JDBC API only]       │
 │  d4np-security ──► d4np-core        [compile: nimbus-jose-jwt]             │
 │  d4np-concurrent ──► d4np-core      [no third-party deps]                  │
 │  d4np-json ──► d4np-core            [compile: jackson-databind + jsr310]   │
 │                                                                            │
 │  d4np-core                          [ZERO third-party dependencies;        │
 │   (items 1-4, 14-18, 22-24)          jakarta.validation-api as provided]   │
 │                                                                            │
 │  Rules (CI-enforced with maven-enforcer + japicmp):                        │
 │   • arrows point toward core only; no cycles                               │
 │   • Spring/Jackson/Redisson types never appear in core/jdbc/concurrent APIs│
 └────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Security Considerations
* **SQL injection:** defended by construction — `SimpleJdbcExecutor` accepts only parameterized statements (item 5); no sanitizer pretends otherwise (item 13 rescope).
* **XSS:** context-aware output encoding at render time (item 13); input validation is allowlist-based (item 14), never "cleaning".
* **Cryptography:** AES-256-GCM with per-op random IVs and rotating keyed envelope (item 12); JWT `alg` allowlist and signature verification before any claim access (item 11).
* **Deserialization:** `JsonMapper` ships with polymorphic default typing disabled (item 20).
* **Dependency hygiene:** OWASP Dependency-Check in CI (§8); modules keep third-party surface minimal per ADR-001.

---

## 5. Method-Level Contract Summary (per acceptance criteria — items 4, 7, 17 normative)
| API | Nullability | Error semantics | Thread safety |
|---|---|---|---|
| `StrategyRegistry.find/getOrThrow` | never returns null (`Optional`/throw) | `StrategyNotFoundException` lists known keys | fully concurrent; `register` is last-write-wins with a warning log |
| `PageRequest.of(page, size, sort)` | non-null args | `IllegalArgumentException` on bounds/whitelist violation at construction | immutable |
| `Result<T>` | `Ok(null)` forbidden (use `Result<Void>`) | failures carry `ErrorDetail`; `orElseThrow` maps to caller-chosen exception | immutable |
| `Lazy.get()` | non-null (initializer returning null → `IllegalStateException`) | initializer exception policy per option (memoize/retry) | safe publication guaranteed (jcstress) |
| `AesEncryptor.encrypt/decrypt` | non-null | `CryptoException` (never leaks `javax.crypto` internals or partial plaintext) | stateless, concurrent |

---

## 6. Non-Functional Requirements & Verification Methodology
**Methodology:** JMH 1.37+ (forked JVMs, 5×10 warmup/measurement, `Blackhole`), reference machine Ryzen 7 5800X, JDK 21; jcstress for every concurrency claim; harnesses committed under `bench/` and `jcstress/`; nightly CI tracks results, > 10% regression fails.

| ID | Target | Tooling |
|---|---|---|
| NFR-01 | `Lazy.get()` steady state ≤ 2 ns/op (volatile-read path), and 0 jcstress anomalous states for the initialization race | JMH + jcstress |
| NFR-02 | `AsyncExecutor` submission overhead ≤ 5 µs vs raw `CompletableFuture.supplyAsync` | JMH |
| NFR-03 | `SimpleJdbcExecutor` row-mapping overhead ≤ 10% vs hand-written `ResultSet` loop (H2 in-memory, 10k rows) | JMH |
| NFR-04 | `StrategyRegistry.find` ≤ 50 ns/op at 1k strategies under 8-thread read load | JMH |
| NFR-05 | `CustomThreadPoolFactory` pools: 0 jcstress anomalies for rejection/shutdown races; graceful shutdown drains within configured timeout | jcstress + integration test |
| NFR-06 | `AesEncryptor` ≥ 400 MB/s AES-256-GCM on the reference machine (AES-NI); IV uniqueness property test over 10⁷ ops | JMH + property test |

---

## 7. API Example (StrategyRegistry — contract-correct)
```java
import d4np.core.patterns.StrategyRegistry;

public class Main {
    public static void main(String[] args) {
        var registry = new StrategyRegistry<String, PaymentStrategy>();
        registry.register("CREDIT_CARD", new CreditCardPayment());
        registry.register("PAYPAL", new PaypalPayment());

        // Missing-key contract is explicit (§2 item 4): Optional, or getOrThrow.
        registry.find("PAYPAL")
                .orElseThrow(() -> new IllegalStateException("PAYPAL strategy not registered"))
                .processPayment(150.00);
    }
}
```

---

## 8. Verification, CI & Release Engineering
* **CI matrix (GitHub Actions):** JDK 17 + 21; full test suite; jcstress suite (reduced iterations per PR, full nightly); JaCoCo coverage gate ≥ 85% line; **PIT mutation testing ≥ 60%** on core/security modules.
* **Compatibility gates:** `maven-enforcer` (dependency convergence, banned deps per §3 rules); **japicmp** binary-compatibility check against the previous release — SemVer enforcement with teeth.
* **Security:** OWASP Dependency-Check per PR; JWT/AES test vectors (RFC 7515/NIST) in the suite.
* **Release:** SemVer; Maven Central via Sonatype with **GPG-signed artifacts**, sources + javadoc JARs, reproducible-build plugin; release notes generated from conventional commits.

---

## 9. Decision Log
* [ADR-001 — Multi-module split & dependency policy (framework independence made structural)](adr/d4np_java_adr_001_module_split.md)
* [ADR-002 — Error model: `Result<T>` for expected outcomes, unchecked `BusinessException`](adr/d4np_java_adr_002_error_model.md)
* [ADR-003 — JWT: Nimbus JOSE+JWT selected](adr/d4np_java_adr_003_jwt_library.md)
* [ADR-004 — Generated repository layout: reconciling the EADOS flat source tree with the Maven reactor](adr/d4np_java_adr_004_generated_layout.md) *(Accepted 2026-07-26 — after this document's 2026-07-14 date)*
