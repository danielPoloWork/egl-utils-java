# ADR-001: Multi-module split & dependency policy — framework independence made structural

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-14 |
| **Related spec** | [d4np-java.md](../d4np-java.md) (§1, §2 module headers, §3) |

## Context
v1 declared "maximum decoupling" while specifying a **single JAR** whose 25 items imply Spring MVC (item 19), Jackson (items 20–21), AspectJ/Spring AOP (item 15), and Redisson (item 10). A consumer wanting only `StrategyRegistry` would inherit that entire transitive graph — the opposite of the stated principle, and a real cost in enterprise settings (dependency audits, CVE triage, version conflicts with the host's own Spring/Jackson). v1 also named item 6 `TransactionTemplate`, colliding with Spring's identically named class that already solves programmatic transactions — a naming decision inseparable from the framework-independence policy.

## Options considered

**A. Maven multi-module: zero-dependency core + capability modules + adapters** *(chosen)*
- ✅ `d4np-core` has zero third-party dependencies; Spring, Jackson, and Redisson exist only in the modules whose job they are (`d4np-spring-adapter`, `d4np-json`, `d4np-lock-redisson`).
- ✅ The decoupling principle becomes CI-enforceable: maven-enforcer bans framework types from core/jdbc/concurrent public APIs; violations fail the build, not the code review.
- ✅ Hosts pin their own Spring/Jackson versions; adapter modules declare them `provided`.
- ❌ More artifacts to version and publish. Accepted: a BOM (`d4np-bom`) keeps consumer POMs to one import.

**B. Single JAR with `optional` dependencies**
- ✅ One artifact.
- ❌ Optional deps hide the real graph (classpath errors surface at runtime, not resolution time); the "core is clean" claim remains unverifiable; JPMS `module-info` for one JAR spanning eight domains becomes a requires-static thicket.

**C. Separate repositories per concern**
- ✅ Hard boundaries.
- ❌ Overkill for one team; cross-cutting refactors (error model touching core + adapter) become multi-repo choreography.

## Decision
**Option A.** Modules: `d4np-core`, `d4np-jdbc`, `d4np-concurrent`, `d4np-security`, `d4np-json`, `d4np-spring-adapter`, `d4np-lock-redisson`, `d4np-test`, plus `d4np-bom`. Dependency rules as drawn in spec §3, enforced by maven-enforcer; japicmp guards binary compatibility per module.

**Naming consequence:** item 6 is renamed `JdbcTxRunner`. It targets non-Spring hosts explicitly; its Javadoc directs Spring users to Spring's own `TransactionTemplate`. Shipping a same-named competitor would guarantee import confusion in exactly the codebases most likely to adopt this library.

## Consequences
- "Framework independence" is a build property, not a slogan; a PR leaking `com.fasterxml` into core fails CI.
- Consumers adopt capabilities à la carte; security teams audit only the modules they use.
- The BOM becomes the versioning contract; individual module SemVer is tracked by japicmp per module.
