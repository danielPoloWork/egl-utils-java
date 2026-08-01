# 2026-08-01 — `Lazy<T>` and safe publication (ROADMAP item 2.2)

**Milestone 2, item 2.2.** Second checkpoint in this journal. The session closed the FR-03 contract
and, in doing so, produced the project's first NFR closed by a *measurement* and its first
documented hole in the gate net.

## What changed

`d4np-core` gains `Lazy<T>` — two factories (`of`, `memoizingFailures`), a fast path of one
`volatile` read and a branch, a private monitor, and `IllegalStateException` for the two defect cases
(a `null` result, a re-entrant initializer). With it: 15 unit tests (42 → **57**), two jcstress
harnesses (`LazyPublicationStress`, `LazyMemoizedFailureStress`), a JMH benchmark
(`LazyGetBenchmark`), **ADR-0013**, and a benchmark report. The pattern catalogue's *Lazy
initialization* row moves Planned → Implemented, and compliance control **C-02** now names `Lazy` as
a second enforcement site.

**NFR-01 is met and measured:** `Lazy.get()` at **0.827 ns/op (JDK 17)** and **0.945 ns/op (JDK 21)**
against a **≤ 2 ns/op** budget, sitting 0.2–0.4 ns/op above item 1.8's bare `volatileRead` floor —
that small delta is what says `get()` is inlining.

## Where the project stands

Milestone 2 is **2 of 5 items closed**; 2.3, 2.4 and 2.5 are unblocked and none is blocked on
anything else. Nothing in this item touched the `Result<Void>` question, deliberately.

## What the next session needs to know

- **One `volatile` keyword in `Lazy` is held by review, not by a gate — and that was measured, not
  assumed.** It was deleted and the full gated suite re-run: **the build stayed green** (56/56
  jcstress on 21, compiler silent). Two independent causes, each confirmed separately. **(a)**
  ErrorProne's `DoubleCheckedLocking` check is enabled and does fire — proven with a probe class using
  the classic single-method idiom, which fails the build under `failOnWarning` — but it matches an
  if/synchronized/if nest **inside one method body**, and `Lazy` splits the two checks across `get()`
  and `initialize()` for inlining. *The optimisation that makes NFR-01 reachable is the same edit that
  removes the static check.* **(b)** x86-64 is TSO and does not reorder the stores that make the
  pre-JSR-133 idiom unsafe, so the defect is unobservable on this hardware at any iteration count.
  **The fix is an aarch64 jcstress cell**, filed against item 8.3 (which already owns runner topology
  for NFR gates). Do not read the green jcstress runs as covering this.
- **README's milestone table under-reported, and the lint cannot catch that direction.** It still read
  Milestone 2 as *planned* after 2.1 shipped; corrected to *in progress* here. `check_milestones` only
  fails when README claims ✅ over unchecked ROADMAP items — the **opposite** skew stays green, which is
  the same one-directional blind spot item 2.1 found in `check_spec_map`. Two glyph tables now have a
  known-stale failure mode; a third instance is probably worth a lint fix rather than a third manual
  correction.
- **`spotless:check` is not bound to `verify`.** A format violation in this branch survived two full
  `clean verify` runs and only surfaced when the standalone goal ran. Run the CI goals, not just
  `verify`.
- **CI's spotless command excludes the BOM: `mvn -B spotless:check -pl '!d4np-bom'`.** `d4np-bom`
  deliberately carries **no parent**, so it does not inherit the plugin and a whole-reactor
  `spotless:check` dies with *"No plugin found for prefix 'spotless'"* — a command mistake that looks
  exactly like a build break. `checkstyle:check` and `validate` take no exclusion.
- **The local Temurin images in the session scratchpad were incomplete** and cost time to diagnose:
  both were missing `conf/security/policy/` and `lib/security/cacerts`, so JCE failed to initialize
  and every Maven HTTPS call died with `trustAnchors parameter must be non-empty` — which surfaces as
  an unrelated-looking reactor failure. Both files were copied in from the system JDK 25; verify
  `java -version` **and** an HTTPS fetch before trusting a fresh toolchain unpack.
- **`memoizingFailures` degrades to retry for a sneaky-thrown checked exception.** `Supplier.get()`
  declares none, so the catch is `RuntimeException | Error`; a probe measured three `get()` calls
  producing three initializer invocations, with the original `IOException` propagating unchanged.
  Nothing wedges. Widening to `Throwable` was rejected, not overlooked — see ADR-0013.
- **`VolatilePublicationStress` was kept, not replaced.** Item 1.8's note predicted 2.2 would swap its
  subject; it is now the **control** instead (both fail → platform; only the `Lazy` ones fail → our
  bug), and that note is corrected in place rather than left predicting something that did not happen.
- **The route was accepted as a mismatch, again.** `route_advice.py` routes 2.2 to
  `frontier-reasoning/high`; the maintainer's catalogue places the session model (Opus 5) at
  `standard`. Same standing decision as item 2.1; recorded in the PR body.

## Verification

Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, Windows 10 Pro 19045 (i5-6600K, 4 cores):
`clean verify` (`Tests run: 57`) on both; `-Pjmh,jcstress verify` on both (**56** jcstress results on
21, **112** on 17 — biased locking doubles the configurations there — zero failures); CI's real
quality goals `spotless:check -pl '!d4np-bom'`, `checkstyle:check` and `validate` on both; and
`python tools/consistency_lint.py`.

**Non-vacuity proven by breaking things, four ways:** both new jcstress harnesses had their single
`ACCEPTABLE` outcome flipped to `FORBIDDEN` and each reported `[FAILED]` with Maven exiting 1; the
`DoubleCheckedLocking` probe fired on the classic idiom; and the `volatile` deletion above is recorded
as the one breakage the suite did **not** catch, which is the finding rather than a gap in the
testing.
