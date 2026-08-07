# egl-utils-java

> A modular set of Java libraries implementing enterprise design patterns for Spring Boot and Jakarta EE systems — a zero-dependency core plus opt-in capability modules (JDBC, concurrency, security, JSON) and framework adapters.

![Status](https://img.shields.io/badge/Status-v0.1.0-blue)

Part of the **Enterprise-Grade Libraries** series. A
library written in **Java 21 (LTS)**, built and governed to an enterprise quality
bar: full CI matrix, static analysis, sanitizers, documented design decisions, and SemVer
releases.

## What it is

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

The frozen specification is in
[`docs/specs/01_spec_utils.md`](docs/specs/01_spec_utils.md).

## Build, test, run

```bash
mvn -B clean verify
mvn -B test

# NFR harnesses — the numbers behind a performance or thread-safety claim (ADR-0007)
mvn -B -Pjmh verify        # JMH benchmarks under <module>/src/bench/java
mvn -B -Pjcstress verify   # jcstress stress tests under <module>/src/jcstress/java
```

- **Toolchain:** Maven 3.9+ (multi-module reactor), JUnit 5 + AssertJ; jqwik (property tests); jcstress (concurrency); H2 (JDBC integration), Spotless (google-java-format), ErrorProne + NullAway + Checkstyle; maven-enforcer for the ADR-001 dependency rules.
- **Supported platforms:** Linux / Windows / macOS on Temurin JDK 17 & 21.
- Consumers import the public surface via: `import it.d4np.utils.*;`.
- **Optional at runtime:** `Validator` (FR-14) wraps Jakarta Bean Validation, which `core` declares
  at `provided` scope and `requires static` — so nothing arrives in your graph unless you use it. A
  consumer that calls `Validator.create()` supplies `jakarta.validation:jakarta.validation-api` and a
  provider (Spring Boot 3.2+ already does); one that never validates carries neither, and the
  zero-third-party-dependency claim is unchanged.
- **Metrics are opt-in the same way:** `ExecutionTimeMetricAspect` (FR-15) times a call and reports to
  an `ExecutionTimeRecorder` chosen once at construction. `create()` installs the dependency-free
  fallback, which logs through `System.Logger` at **`DEBUG`** — so a host that has enabled nothing sees
  nothing, deliberately ([ADR-0021](docs/adr/0021-time-through-an-advice-body-core-can-own.md)). The
  Micrometer recorder and the AspectJ/Spring binding live in the adapter module; core never names
  either.
- **Audit trails redact at capture, not at write:** `AuditLog` (FR-16) turns a before/after pair into
  an `AuditEvent` that holds `[REDACTED]` where a value was blocked and **no API that returns a raw
  one** — a sink cannot leak what it never receives. Mark what may be captured with `@Audited` (on a
  component or on the type), opt a component out with `@Sensitive`, and note that `AuditPolicy`'s
  never-capture list — `password`, `api_key`, `card_number`, … — outranks both and can be added to but
  never removed from. A blocked component still records **that it changed**, so *"the password was
  changed at 14:02 by alice"* survives without the password
  ([ADR-0022](docs/adr/0022-redact-at-capture-behind-a-typed-event.md)). Records land in a host's
  `AuditSink`; `create()` falls back to `System.Logger` at `INFO`, which is a development convenience
  rather than a compliance store.
- **Jackson is hardened by not being reachable:** `JsonMapper` (FR-20, `d4np-json`) is Jackson
  configured once — `JavaTimeModule` registered and rendered as ISO-8601, unknown properties
  tolerated, `INCLUDE_SOURCE_IN_LOCATION` off, and **polymorphic default typing explicitly
  deactivated**. The configured `ObjectMapper` is never handed out, which is the guarantee: there is
  nothing to call `activateDefaultTyping` on, so the hardening is a property of the type rather than
  of how you got it. Customise additively with `JsonMapper.withModules(..)`; read and write with
  `readValue`/`writeValueAsString`, which raise the unchecked `JsonConversionException` whose message
  carries the target type and the property path and **never any part of the document**
  ([ADR-0024](docs/adr/0024-take-a-jackson-type-in-one-signature.md),
  [ADR-0025](docs/adr/0025-render-java-time-as-iso-8601.md)). `d4np-core` still sees no Jackson at
  all — that separation is what this module exists for.

See [`docs/development/local-build.md`](docs/development/local-build.md) for the full local
setup.

## How this project is run

| Document | Purpose |
|---|---|
| [`AGENTS.md`](AGENTS.md) | How AI agents (and humans) work in this repo — the contract. |
| [`ROADMAP.md`](ROADMAP.md) | The numbered plan and what is done. |
| [`docs/adr/`](docs/adr/) | Why it is built the way it is (Architecture Decision Records). |
| [`docs/patterns/`](docs/patterns/) | Design patterns adopted, rejected, or considered. |
| [`docs/workflow/`](docs/workflow/) | Git, documentation, release, and maintenance conventions. |
| [`CHANGELOG.md`](CHANGELOG.md) | User-visible changes per release. |
| [`SECURITY.md`](SECURITY.md) | How to report a vulnerability. |

## Milestones

| # | Title | Status |
|---|---|---|
| 1 | Project bootstrap & CI | ✅ done |
| 2 | core foundations | ✅ done |
| 3 | core cross-cutting | ✅ done |
| 4 | json and jdbc | 🚧 in progress |
| 5 | concurrent | ⏳ planned |
| 6 | security | ⏳ planned |
| 7 | adapters and test support | ⏳ planned |
| 8 | release engineering | ⏳ planned |


## License

MIT © 2026 Daniel Polo. See [`LICENSE`](LICENSE).
