# ADR-0014: Log through `java.lang.System.Logger`, and make the call testable by injection

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.3; [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (zero
  third-party dependencies in core); [RFC-0001](../rfc/0001-core-contracts.md) §FR-04 (which states
  the warning); [ADR-0015](0015-strategy-registry-last-write-wins.md) (the registry that needed it);
  [ADR-0005](0005-jpms-module-names-and-export-less-descriptors.md) (the module descriptor this
  decision keeps clean); FR-04, FR-16, NFR-08

## Context

FR-04 states a logging requirement inside a functional one: *"`register` is last-write-wins **with a
warning log**"*. Until item 2.3 nothing in this library logged at all, so the first line of logging
had to answer a question the specification never asks — **through what?**

The constraint that decides it is ADR-001/NFR-08: **`d4np-core` carries zero third-party
dependencies**, and logging is the single most reflexive place for a Java library to break that rule.
SLF4J is the conventional answer and is unavailable here — not on taste, on a rule the build enforces
with a default-deny `maven-enforcer` allowlist (ADR-0006).

There is a second, quieter constraint. `d4np-core`'s `module-info.java` requires **nothing but
`java.base`**, and its own Javadoc says so as a selling point. Any logging choice that adds a
`requires` edge spends that.

And the decision is not local to one class. FR-16's `AuditLog` (item 3.3), NFR-05's pool
rejection/shutdown logging in `d4np-concurrent` (item 5.1) and FR-06's transaction logging (item 4.4)
all need the same answer, so the first one sets the precedent for the library — the same shape as
ADR-0011, where the first nullable member decided the annotation for everything after it.

## Decision

**Core logs through `java.lang.System.Logger`, obtained from `System.getLogger(String)`, and adds no
logging dependency and no `requires` edge.** `System.Logger` lives in `java.base`, so the module
descriptor is unchanged, and the JDK routes it through whatever backend the consuming application
already installed: SLF4J, Log4j 2 and `java.util.logging` all publish a `System.LoggerFinder`. A
consumer therefore gets our warning in the log they already read, configured by the configuration
they already wrote, without this library naming any of them.

**Second half of the decision, and the part that cost the time: the logger is injected through a
package-private constructor** so the logging contract can be asserted by a test. `StrategyRegistry`
holds a `System.Logger` field defaulting to a shared static one; a test hands it a recording
implementation. No public surface changes.

## Alternatives Considered

- **SLF4J API at compile scope.** The industry default, and what most reviewers expect. Rejected on a
  rule, not a preference: ADR-001 fixes core at zero third-party dependencies, and a logging facade is
  exactly the "harmless" dependency that makes framework independence untrue — it lands in every
  consumer's graph and drags a binding decision with it. Available to *capability* modules, whose
  allowlists already admit their own third-party surface; not to core.
- **`java.util.logging` directly.** Zero dependencies too, and works. Rejected because it is a
  *backend*, not a facade: an application on SLF4J or Log4j 2 would get this library's warnings in a
  second, differently configured pipe unless it installs a bridge — the failure mode being that the
  one warning FR-04 exists to surface goes to a log nobody is watching. It also costs a `requires
  java.logging` edge that `System.Logger` does not.
- **No logging: throw on a duplicate registration instead.** Rejected because it contradicts FR-04 —
  last-write-wins *is* the contract, and re-registration is a supported operation. See ADR-0015 for
  why this registry does not reject duplicates the way FR-01's factory does.
- **No logging: return the displaced strategy and let the caller decide.** Tempting, and it would make
  the collision impossible to ignore. Rejected as a silent contract change: FR-04 says "with a warning
  log", and a return value is not a warning — a caller who ignores it (most will, since `register` is
  called for effect) gets no signal at all. Returning the previous value is additive and stays
  available later as a MINOR change.
- **A pluggable listener/callback interface on the registry.** Rejected as speculative generality: it
  invents public API and a lifecycle to avoid one platform call, with no requirement asking for it.

## Consequences

- **The module descriptor is unchanged.** `d4np-core` still requires only `java.base`, which is a
  property its own Javadoc claims and now keeps under a logging requirement.
- **Output lands wherever the consumer already sends logs**, with no bridge and no configuration from
  us. The cost is that we do not control the format, and `System.Logger`'s API is deliberately small —
  no fluent builders, no markers, no MDC. For one warning this is a fair trade; a module that later
  needs structured context will find `System.Logger` thin, and that is the moment to revisit, not now.
- **Format strings are `MessageFormat`, and that has a trap worth naming.** `System.Logger`'s
  parameterised `log` methods substitute with `java.text.MessageFormat`, where a **single quote
  escapes the following placeholder** — so `"key '{0}' replaced"` prints `{0}` verbatim. This library's
  house style uses apostrophes freely in prose, so log format strings are the one place they are
  banned. A unit test asserts the rendered output contains no unrendered `{0}`, which turns the trap
  into a failing test rather than a puzzling log line.
- **A `System.LoggerFinder` cannot be installed by a test under surefire, and this was established by
  building it first.** The JDK resolves the finder **once per VM, on the first `System.getLogger`
  call, and caches it forever**; inside a surefire fork something has already triggered platform
  logging by then, so a provider on the test classpath is never consulted and the finder stays at
  `sun.util.logging.internal.LoggingProviderImpl`. The identical `META-INF/services` file works in a
  plain `java` launch — which is what makes the failure confusing rather than obvious. **Anyone testing
  logging in this repository should skip that approach.**
- **Attaching a `java.util.logging.Handler` was the next idea and is blocked by the module graph.**
  These tests compile *inside* `it.d4np.utils` (patched into the module), so `java.util.logging` is
  not readable: *"package java.util.logging is declared in module java.logging, but module
  it.d4np.utils does not read it"*. Making it work would mean either adding a `requires` edge to the
  production descriptor for a test's benefit, or threading `--add-reads` through both test compilation
  and the surefire fork.
- **Hence the injection seam, which is a real cost stated plainly.** There is a package-private
  constructor that exists for testability, and a reviewer is right to notice it. It is preferred over
  the two alternatives above because it changes no public API, needs no build configuration to stay in
  sync across two phases, and tests *the call this library actually makes* rather than whichever
  backend the test JVM happened to install. It also turned out to earn its keep twice: the jcstress
  registration harness uses the same seam to silence a warning that would otherwise be emitted
  millions of times and measure the console instead of the registry.
- **This is the precedent for items 3.3, 4.4 and 5.1.** They should use `System.Logger` and the same
  injection seam where the log line is part of a stated contract. `AuditLog` (FR-16) is the one to
  watch: RFC-0002 owns its redaction policy, and "which logger" is settled here while "what may
  legally be written" is emphatically not.

## References

- FR-04 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md); NFR-08 for framework
  independence.
- [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) — the zero-dependency rule for core.
- `d4np-core/src/main/java/it/d4np/utils/StrategyRegistry.java` — the logger field and the seam.
- `d4np-core/src/test/java/it/d4np/utils/LogRecorder.java` — the recording logger, and the two
  rejected approaches written out.
- JEP 264 (Platform Logging API and Service) — what `System.Logger` is and how a backend supplies one.
