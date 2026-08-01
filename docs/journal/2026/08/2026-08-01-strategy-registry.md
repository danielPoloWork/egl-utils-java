# 2026-08-01 — `StrategyRegistry` and the library's first logging (ROADMAP item 2.3)

**Milestone 2, item 2.3.** Second item closed today and the third checkpoint in this journal. Two
ADRs, one refuted performance claim, and two testing approaches that were built before either was
known not to work.

## What changed

`d4np-core` gains `StrategyRegistry<K,S>` — `find`/`getOrThrow`/`register`, `ConcurrentHashMap`-backed
with lock-free reads — and `StrategyNotFoundException`, which carries **every key that is
registered** because that list is usually what ends the investigation at the log line. With them: 24
unit tests (57 → **81**), two jcstress harnesses, two JMH benchmarks, **ADR-0014** (logging) and
**ADR-0015** (the registry decisions). The pattern catalogue's *Strategy + Registry* row moves
Planned → Implemented; compliance control **C-02** gains a third enforcement site.

**NFR-04 is met on both toolchains:** `find` at **12.8 ns/op (JDK 21)** and **17.8 (JDK 17)** against
a ≤ 50 ns/op budget, at the budgeted shape rather than a convenient one — 1000 strategies,
`@Threads(8)`, keys rotating per thread so the number includes cache misses across the table.

## Where the project stands

Milestone 2 is **3 of 5 items closed**. Items 2.4 (`GenericFactory` + `FluentBuilder`) and 2.5
(`StringCaseConverter`, `ObjectUtils`, `ResourceLoaderUtils`) remain, and neither is blocked.

## What the next session needs to know

- **A documented performance claim was refuted by its own benchmark, and both explanations were
  wrong.** `getOrThrow` allocates no `Optional`, so it was written up as the cheaper call; on JDK 21 it
  measures **~2 ns/op slower** than `find`, across three multi-fork runs. The inlining hypothesis —
  exception construction inflating the method past the size threshold — was tested by moving the throw
  into a private method and **changed nothing at all**, so the split was reverted rather than kept.
- **That gap is JDK-21-only, and finding that out is why both toolchains got a report-grade run.** On
  JDK 17 the two lookups are **indistinguishable** (17.8 ± 4.3 versus 18.0 ± 0.7 — overlapping, and
  `find`'s spread there is too wide to resolve 2 ns at all). Had only JDK 21 been measured, the docs
  would now assert a property of the code that is really a property of one JIT. **Standing lesson:
  avoiding an allocation is not the same as being faster, a plausible mechanism is not a measurement,
  and a measurement on one toolchain is not a property of the code.**
- **`System.Logger` is now the logging mechanism for the whole library (ADR-0014).** Items 3.3
  (`AuditLog`), 4.4 (`JdbcTxRunner`) and 5.1 (pool logging) should follow it rather than re-deciding.
  Zero dependencies, and no new `requires` edge — core still requires only `java.base`.
- **Do not try to install a `System.LoggerFinder` in a test.** It cannot win under surefire: the JDK
  resolves the finder **once per VM, on the first `System.getLogger` call, and caches it forever**, and
  something in the fork has already triggered platform logging by then. The identical
  `META-INF/services` file works in a plain `java` launch, which is exactly what makes the failure
  confusing — the mechanism is right and the timing is not. This cost a full build-and-diagnose cycle.
- **`java.util.logging` is not reachable from these tests either.** They compile *inside* module
  `it.d4np.utils` (patch-module), which requires only `java.base`, so a JUL `Handler` needs either a
  `requires` edge added to the production descriptor for a test's benefit or `--add-reads` threaded
  through test compilation *and* the surefire fork. The way out was injecting a `System.Logger`
  through a package-private constructor — `System.Logger` being in `java.base`.
- **`MessageFormat` is the format dialect of `System.Logger`, and a single quote escapes the next
  placeholder.** This repo's house style uses apostrophes freely in prose, so log format strings are
  the one place they are banned. A test asserts no unrendered `{0}` survives.
- **A jcstress harness that exercises a logged event must silence the logger.** Every iteration of
  `StrategyRegistryRegistrationStress` is a deliberate key collision — precisely what `register` logs
  at `WARNING` — so on the platform logger a run would emit millions of lines and measure the console.
  It reuses the ADR-0014 seam.
- **`StrategyNotFoundException` must stay outside the `BusinessException` hierarchy.** FR-19 maps them
  to different statuses (500 + alert versus 422), and `StrategyNotFoundExceptionTest` asserts the
  *negative* so a later refactor cannot quietly make it a subclass.
- **The route matched this time.** Item 2.3 is `standard / high` and this host's model sits at
  `standard`, so there is no ROUTE-MISMATCH to record — the first item since Milestone 1 without one.
  Items 2.4 and 2.5 are also `standard`.

## Verification

Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, Windows 10 Pro 19045 (i5-6600K, 4 cores):
`clean verify` (`Tests run: 81`) on both; `-Pjmh,jcstress verify` on both (**84** jcstress results on
21, **168** on 17 — biased locking doubles the configurations there — zero failures); a report-grade
JMH run at 3 forks x 5 iterations for the numbers quoted above; CI's real quality goals
`spotless:check -pl '!d4np-bom'`, `checkstyle:check` and `validate` on both; and
`python tools/consistency_lint.py`.

Both new jcstress harnesses were proven non-vacuous by flipping an `ACCEPTABLE` outcome to
`FORBIDDEN` and confirming the build turns red.
