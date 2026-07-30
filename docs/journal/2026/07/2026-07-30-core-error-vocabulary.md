# 2026-07-30 — the core error vocabulary (ROADMAP item 2.1)

**Milestone 2, item 2.1.** First checkpoint in this journal, which is itself worth stating: the
journal was scaffolded at intake and stayed empty through all twelve Milestone 1 items, so the dated
trail `AGENTS.md` §7 asks for starts here rather than being back-filled from git history.

## What changed

`d4np-core` has a public API for the first time — `Result<T>` (sealed over `Ok`/`Err`, with `map`,
`flatMap`, `recover`, `orElseThrow`), `ErrorDetail(code, message, cause)`, the unchecked
`BusinessException`, and the `Nullable` marker — plus `exports it.d4np.utils;` in the module
descriptor, 39 new unit tests, and a jcstress harness for the immutability claim. Two ADRs
(**0011** nullability annotation, **0012** the null boundary) and one lint widening
(`consistency_lint.py` `patterns`).

## Where the project stands

Milestone 1 is released as v0.1.0; Milestone 2 is **1 of 5 items closed**. The shared error
vocabulary that M3, M4 and M5 all depend on now exists, so items 2.2–2.5 are unblocked and none of
them is blocked on anything else.

## What the next session needs to know

- **Item 2.2 (`Lazy<T>`) is next in sequence** and inherits two things from here: the
  `VolatilePublicationStress` harness item 1.8 left in place says its subject is to be replaced by
  the real `Lazy`, and the `@Nullable` marker now exists, so a nullable member no longer needs a
  decision first.
- **`Result<Void>` is an open question with a named owner.** A successful `Result<Void>` cannot be
  constructed (ADR-0012); item 3.0's scope was extended to settle it in RFC-0002, where `Validator`
  and `AuditLog` provide the first real call sites. Do not settle it in 2.2–2.5 by accident.
- **jcstress in this repo now needs `-jvmArgsPrepend`.** Its `@Contended` probe cannot survive a
  classpath *directory* holding a class named `*Result*`, which this repo permanently has. The parent
  POM carries the workaround and a long comment; if a future JDK removes `RestrictContended`, every
  forked VM will fail to start by name and those two arguments should be dropped.
- **Item 8.4 must build the Javadoc JAR on JDK 21.** JDK 17's doclint emits three spurious warnings
  for the components of a `Serializable` record; measured here, not guessed.
- **The route was accepted as a mismatch.** `route_advice.py` routes 2.1 to `frontier-reasoning/high`
  and the maintainer's catalogue places the session model (Opus 5) at `standard`. The maintainer chose
  to proceed; recorded in the PR body. Every remaining `frontier-reasoning` item will report the same
  mismatch from this host.

## Verification

Temurin 17.0.20+8 and 21.0.12+8, Maven 3.9.9, on Windows: `clean verify` (`Tests run: 42`) on both,
`spotless:check`, `checkstyle:check` (all ten modules), `validate`, `-Pjmh,jcstress verify` on both
(28 jcstress results on 21, 56 on 17, zero failures), Javadoc at `-Xdoclint:all -Xwerror`, and
`python tools/consistency_lint.py`. Every new gate was proven non-vacuous against a deliberate
breakage — the NullAway marker, the two test suppressions, the jcstress forbidden outcome, and all
six branches of the widened `patterns` check.
