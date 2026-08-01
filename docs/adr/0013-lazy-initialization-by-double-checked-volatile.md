# ADR-0013: Publish `Lazy<T>` through double-checked `volatile`, behind a private monitor

- **Status:** Accepted
- **Date:** 2026-08-01
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.2; [RFC-0001](../rfc/0001-core-contracts.md) §FR-03 (which pins the
  contract this record implements); [ADR-0007](0007-nfr-harnesses-as-test-scope-profiles.md) (the
  harness roots that make the claim testable);
  [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) (the marker on the value field);
  [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) (the sibling null decision);
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (zero third-party dependencies in
  core); FR-03, NFR-01, NFR-07

## Context

RFC-0001 pinned FR-03 down to five rows — retry by default, `memoizingFailures()` opt-in, a
re-entrant initializer throws, a `null` result throws, and *"double-checked `volatile`;
jcstress-verified"*. Four of those are behaviour a test can name. The fifth is a **mechanism named in
a table cell**, and a table cell is not a rationale: it says what to build without recording why it
beats the four other ways to publish a lazily computed value, or what the rest of the class has to
look like for it to be correct.

That "rest of the class" is where the real decisions are, and they are not independent of each other:

- **The steady-state path is budgeted.** NFR-01 puts `Lazy.get()` at **≤ 2 ns/op**, and item 1.8
  already measured the floor that budget is made of — a bare `volatile` field read at 0.4–0.6 ns/op.
  Any mechanism that puts a lock, an allocation or a megamorphic call on the *hot* path spends the
  whole budget before doing anything useful.
- **The baseline is JDK 17.** NFR-07 pins the published bytecode at class-file major 61, which rules
  out the mechanism a 2026 greenfield would reach for first (see the alternatives).
- **Core carries zero third-party dependencies** (ADR-001/NFR-08), so Guava's `Suppliers.memoize`
  and every other library answer are unavailable by construction, not by preference.
- **The two defect cases interact with the publication mechanism.** A `null` result and a re-entrant
  initializer both have to be detected *inside* the critical section, and how they are detected
  determines whether the fast path can stay a single read.

Double-checked locking also has a specific history worth naming, because it is the reason this
record exists at all rather than being an implementation detail: **the idiom was broken in Java
before JSR-133**. The pre-2004 version — a non-`volatile` field, checked twice around a lock — is the
canonical example of unsafe publication, and it is still copied. Writing it correctly is not
folklore-following; it is depending on one specific guarantee, and this record names the guarantee so
a future editor cannot "optimise" the `volatile` away.

## Decision

**`Lazy<T>` holds the computed value in a single `volatile` reference field, read exactly once on the
fast path and re-checked under a *private* monitor on the slow path — the post-JSR-133
double-checked-locking idiom — with `null` itself as the uninitialized marker.** Everything else
follows from keeping that fast path to one read and one branch:

- **`null` is the marker, and no sentinel object exists.** This is sound only because an initializer
  that returns `null` is rejected as a defect (RFC-0001, spec §5): the two rules hold each other up,
  so neither can be relaxed alone. A sentinel would cost a second field or a cast on every read.
- **The monitor is a private `Object`, not `this`.** A type that synchronizes on itself publishes its
  lock: any caller holding a reference can stall or deadlock it from the outside, and monitor
  ownership is not part of this class's contract. The cost is one object per instance.
- **Re-entrancy is detected by a plain `boolean` guarded by that same monitor** — not a `ThreadLocal`,
  not an owner-`Thread` field. The monitor *already* establishes thread identity: only the lock holder
  can observe the flag, and a Java monitor is reentrant, so a nested `get()` from the initializing
  thread walks straight back in and sees `true` while every other thread is still blocked outside.
  A `ThreadLocal` would re-derive information the lock already has, and pay a map lookup for it.
- **The re-entrancy check runs *before* the flag is set**, so the re-entrant invocation throws without
  entering the `try`, and therefore never reaches the `finally` that clears the outer invocation's
  flag. That ordering is load-bearing, not stylistic: inverting the two lines makes one rejected
  re-entrant call wedge the instance permanently, and `LazyTest.reEntrancyLeavesNoStuckState` is the
  regression that catches it.
- **Both `RuntimeException` and `Error` are remembered** under the opt-in memoizing policy, and the
  remembered failure is rethrown as the *same instance*, by contract.
- **Initialization lives in a separate private method.** `get()` is a volatile read, a null check and
  a return; the lock, the exception paths and the defect checks are in `initialize()`. Inlining is
  budgeted in bytecode size, so folding the slow path into `get()` would push a monitor and three
  throw sites into every call site's inlining decision to save one method that never executes twice.

## Alternatives Considered

- **`StableValue` (JEP 502).** The mechanism a greenfield 2026 library would use: JVM-recognised
  at-most-once initialization with constant-folding after the first set, i.e. *below* the volatile-read
  floor this budget is measured against. Rejected on a hard constraint — it is a **preview** API in JDK
  25, and NFR-07 pins the published baseline at **17**. A preview API cannot appear in a published
  signature at all, and cannot be compiled without `--enable-preview`, which changes the class-file
  version and would break every consumer on a non-matching JDK. Revisit if and when NFR-07's baseline
  moves past the release that finalises it; the fast path is private, so that swap is invisible to
  callers.
- **`synchronized` on every `get()`.** Correct, three lines shorter, and the honest default before
  anyone measures. Rejected on NFR-01: an uncontended lock is roughly an order of magnitude past a
  volatile read, and a *contended* one is unbounded — for a value that is written once and read
  forever, that cost is paid on every read for the benefit of the first one.
- **`AtomicReference<T>` with `compareAndSet`, or a `VarHandle` with acquire/release.** Both correct
  and both faster to *write* than DCL. `AtomicReference` was rejected because the CAS makes
  initialization racy-but-idempotent — several threads may run the initializer and all but one discard
  the result, which silently breaks the "at most once" half of FR-03; that is the right trade for a
  pure cache and the wrong one for a supplier that may open a file or a socket. A `VarHandle` with
  `getAcquire`/`setRelease` is a real optimisation of the *same* design — plain-read-with-acquire is
  weaker than a full volatile read — and was rejected as unmeasurable here: the floor is already
  0.4–0.6 ns/op against a 2 ns/op budget, so it would buy fractions of a nanosecond in exchange for
  the one memory-ordering argument in this library that a reviewer cannot check by eye.
- **The initialization-on-demand holder idiom** (a nested class the JVM initialises lazily under its
  own class-initialization lock). The fastest correct answer available at JDK 17, with zero
  synchronization on the read path. Rejected on applicability, not speed: it works only for a
  **static** value known at compile time. `Lazy<T>` is an instance holding a *caller-supplied*
  `Supplier`, and there is no per-instance class to hang the holder on. This is why it appears here as
  a rejected alternative rather than as the implementation.
- **Guava's `Suppliers.memoize`.** Rejected by ADR-001: `d4np-core` carries zero third-party
  dependencies. Named anyway because it is what a reviewer will ask about, and because its
  double-checked implementation is the same shape as this one — which is corroboration, not
  coincidence.
- **Memoizing failures by default.** Rejected by RFC-0001 before this item began; restated here only
  because the implementation makes the cost concrete. See the Consequences note on the lock-taking
  failure path.
- **Implementing `java.util.function.Supplier<T>`.** Tempting — it would let a `Lazy` pass anywhere a
  `Supplier` is expected. Deferred rather than rejected, on the reversibility rule this project used
  in ADR-0012: adding an interface to an existing class later is a source- and binary-compatible
  **MINOR** change, while removing one is **MAJOR**. It also invites a real confusion — `Supplier` is
  the type `Lazy` *takes*, so a `Lazy` that is also a `Supplier` can be handed to `Lazy.of`, and the
  resulting double lazy is nobody's intent. No call site needs it yet.
- **Exposing `isInitialized()` or a peeking accessor.** Rejected: every answer it can give is stale
  before the caller reads it, so its only honest use is diagnostics, and its likely use is a
  caller-written check-then-act race — reintroducing outside the class exactly the interleaving this
  design exists to prevent.

## Consequences

- **The fast path is one volatile read and one branch, with no lock and no allocation**, which is what
  makes NFR-01 reachable rather than aspirational. Measured by `LazyGetBenchmark` in the same JMH
  invocation as item 1.8's `PublicationBaselineBenchmark`, so the delta over the bare volatile read is
  readable directly; the numbers and their caveats are in
  [`docs/benchmarks/2026-08-01-lazy-get.md`](../benchmarks/2026-08-01-lazy-get.md).
- **The thread-safety claim is asserted, not stated.** Spec §6's rule — a thread-safety claim without
  a named jcstress test is not a claim — is met by two harnesses: `LazyPublicationStress` (two threads
  race on first `get()`; the only acceptable outcome is a fully published payload seen by both and an
  initializer count of exactly 1) and `LazyMemoizedFailureStress` (both racers receive the *identical*
  `Throwable`). Item 1.8's `VolatilePublicationStress` is **kept, not replaced**, as the control: if
  both fail the platform is the suspect, if only the `Lazy` ones fail the fault is ours. That file's
  note predicted its own replacement and has been corrected in place.
- **Known gap, measured rather than argued: on x86-64 nothing in this build catches the removal of
  that one `volatile` keyword.** The experiment was run, not reasoned about — `volatile` was deleted
  from the value field and the whole gated suite re-run on Temurin 21.0.12+8, and **the build stayed
  green**: 56/56 jcstress results passed, and the compiler said nothing. Both halves of that have a
  specific cause, and each was confirmed separately.
  - **ErrorProne's `DoubleCheckedLocking` check is enabled and does work** — a probe class with the
    classic single-method idiom was compiled in this reactor and produced
    `[DoubleCheckedLocking] Double-checked locking on non-volatile fields is unsafe`, which under item
    1.11's `failOnWarning` fails the build. It does **not** fire on `Lazy`, because the check matches an
    if/synchronized/if nest inside **one method body**, and this design deliberately splits the two
    checks across `get()` and `initialize()`. **The optimisation that makes NFR-01 reachable is the
    same edit that removes the static-analysis net for the defect it enables** — a real trade, recorded
    rather than discovered later.
  - **jcstress cannot surface it on this hardware either.** x86-64 is a TSO machine: it does not
    reorder the stores whose reordering is what makes the pre-JSR-133 idiom unsafe. The harness is not
    weak — the defect is genuinely unobservable here, and would need a weakly-ordered CPU (aarch64,
    ppc64le) to appear.
  So the `volatile` on that field is currently held by this record, the field's Javadoc and a code
  comment — **by review, not by a gate**. That is the honest status, and it is the argument for adding
  an **aarch64 jcstress cell** when the CI matrix next changes (GitHub-hosted arm64 runners exist);
  filed against item 8.3, which already owns the runner-topology question for NFR gates. The two
  outcome-flip experiments below are what *is* mechanically enforced.
- **The outcome tables are wired to the build's exit status, proven by breaking them.** Both `Lazy`
  harnesses had their single `ACCEPTABLE` outcome flipped to `FORBIDDEN`; the run reported
  `[FAILED] it.d4np.utils.LazyPublicationStress` and `[FAILED] it.d4np.utils.LazyMemoizedFailureStress`
  and Maven exited 1. This is the same non-vacuity discipline items 1.8 and 2.1 applied to their own
  gates, and it is what separates "a harness ran" from "a harness would have told us".
- **Under the memoizing policy, every `get()` after a failure takes the lock.** The remembered failure
  lives in a lock-guarded field rather than a second `volatile`, because putting it on the fast path
  would cost a second read on the *success* path — which is the path with the budget — to speed up a
  path whose defining property is that it is already broken. Stated so nobody reads the slow failure
  path as an oversight.
- **A `null` result and a re-entrant call are retried, not remembered, under the default policy** — they
  are ordinary failures. `LazyTest` asserts both, because the alternative (a defect that wedges the
  instance forever) is the failure mode a caller cannot diagnose from the second exception onward.
- **The `null`-as-marker choice is load-bearing and now has a named dependency.** If a future item ever
  wants `Lazy` to hold a legitimately absent value, it cannot relax the null rejection alone — it needs
  a sentinel or an extra field, and that is a re-opening of this record, not a tweak.
- **Known limitation, measured rather than assumed: a *sneaky-thrown* checked exception is not
  memoized.** `Supplier.get()` declares no checked exceptions, so the catch clause is
  `RuntimeException | Error` — but a caller can still smuggle a checked exception out of a lambda
  through an unchecked cast. Measured with a probe rather than reasoned about: three `get()` calls on a
  `memoizingFailures` instance whose initializer sneaky-throws an `IOException` produce **three**
  initializer invocations, not one, and the original `IOException` propagates unchanged each time. So
  the exception surfaces correctly and the `finally` still clears the re-entrancy flag — nothing wedges
  — and the only casualty is that `memoizingFailures` degrades to retry for that one exotic case. Widening the catch to `Throwable` was rejected: it would put the remembered
  failure outside the type the rethrow can dispatch on, to serve a caller who has already defeated the
  compiler on purpose.
- **`Lazy` is deliberately not `Serializable`.** It holds a caller-supplied `Supplier`, which usually
  is not, and a serialized form would have to choose between shipping the computed value and shipping
  the recipe. No requirement asks for it.
- **This is the first ADR in the project whose subject is a memory-model argument**, so it is also the
  precedent for how those get recorded: name the guarantee being depended on, name the harness that
  would catch its loss, and keep a control experiment that separates "our bug" from "the platform".

## References

- FR-03 and NFR-01 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md); NFR-07 for the
  JDK baseline that rules out `StableValue`.
- [RFC-0001](../rfc/0001-core-contracts.md) §FR-03 — the five-row contract, and §Algorithm sketch,
  whose pseudocode this implementation follows line for line.
- JSR-133 (Java Memory Model) — the `volatile` guarantee the idiom depends on; the pre-JSR-133 form of
  double-checked locking is unsafe and is what this record exists to keep out.
- `d4np-core/src/main/java/it/d4np/utils/Lazy.java`, `.../src/test/java/it/d4np/utils/LazyTest.java`,
  `.../src/jcstress/java/it/d4np/utils/{LazyPublicationStress,LazyMemoizedFailureStress}.java`,
  `.../src/bench/java/it/d4np/utils/LazyGetBenchmark.java`.
- [`docs/benchmarks/2026-08-01-lazy-get.md`](../benchmarks/2026-08-01-lazy-get.md) — the NFR-01
  measurement and its reproduction recipe.
