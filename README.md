# egl-utils-java

> A modular set of Java libraries implementing enterprise design patterns for Spring Boot and Jakarta EE systems — a zero-dependency core plus opt-in capability modules (JDBC, concurrency, security, JSON) and framework adapters.

![Status](https://img.shields.io/badge/Status-v0.0.0-blue)

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
| 1 | Project bootstrap & CI | ⏳ in progress |
| 2 | core foundations | ⏳ planned |
| 3 | core cross-cutting | ⏳ planned |
| 4 | json and jdbc | ⏳ planned |
| 5 | concurrent | ⏳ planned |
| 6 | security | ⏳ planned |
| 7 | adapters and test support | ⏳ planned |
| 8 | release engineering | ⏳ planned |


## License

MIT © 2026 Daniel Polo. See [`LICENSE`](LICENSE).
