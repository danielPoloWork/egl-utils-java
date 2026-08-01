# 2026-08-01 — the two creational patterns (ROADMAP item 2.4)

**Milestone 2, item 2.4.** Third item closed today. Two patterns, two new exceptions, two ADRs — and
two places where the pinned contract could not deliver its own promise.

## What changed

`d4np-core` gains `GenericFactory<T,K>` (register / replace / create / tryCreate / keys) with
`FactoryKeyNotFoundException`, and `FluentBuilder<T>` with `BuilderValidationException`. Plus a
package-private `KeyDiagnostics` extracted from item 2.3's exception, 43 new tests (81 → **124**),
one jcstress harness, and **ADR-0016** and **ADR-0017**. The pattern catalogue gains two Implemented
rows (Abstract Factory, Builder + Template method), taking it to five.

No benchmark: no NFR names either type, and inventing a budget the specification does not have would
be worse than leaving them unmeasured.

## Where the project stands

Milestone 2 is **4 of 5 items closed**. Only 2.5 remains (`StringCaseConverter`, `ObjectUtils`,
`ResourceLoaderUtils`), and it is unblocked.

## What the next session needs to know

- **FR-01 had no thread-safety row, and that gap had teeth.** Duplicate rejection is check-then-act:
  as `containsKey`-then-`put`, two threads can both see a key absent and both believe they registered
  it — and that implementation **passes every sequential test**. It is now a single `putIfAbsent`, with
  `GenericFactoryRegistrationStress` forbidding "both accepted" and "both rejected" by name. The spec's
  §5 `[GAP]` line predicts more of these: only 5 of ~25 public types carry a contract row.
- **FR-02's member list could not deliver FR-02's promise.** "Collect every violation" is stated, but
  the only accumulator sketched is a null check, so cross-field rules could only throw. `reject(String)`
  was added and recorded as an addition — **this is the decision in this PR most worth vetoing**, since
  removing a `protected` method later is MAJOR.
- **Do not copy item 2.3's key-diagnostic code again.** It now lives in package-private
  `KeyDiagnostics`, shared by both key-not-found exceptions. Copying would drift silently, because each
  exception tests its own copy. If FR-06 or FR-12 ever needs a third, extend that class.
- **Both new exceptions stay outside `BusinessException`, for different reasons.**
  `FactoryKeyNotFoundException` is a wiring defect (FR-19's 500 fallback), same as item 2.3's.
  `BuilderValidationException` is the subtle one: FR-19 maps "validation" to 400, but that is FR-14's
  `Validator` checking *client* data — a builder violation is our own code forgetting a field, and 400
  would misattribute the fault. Item 7.1 builds the FR-19 handler and should not collapse these.
- **NullAway makes `build()`'s null check unreachable from an annotated subclass.** The test asserting
  it needs `@SuppressWarnings("NullAway")`. The check stays because ADR-0009 runs NullAway only on this
  build's JDK 21+ cells, and no consumer runs it at all by default. Expect the same shape wherever
  caller-supplied code is defensively null-checked.
- **`keys()` is a copy, not an unmodifiable view.** RFC-0001 says "snapshot", and a view would keep
  changing under a caller who read that word literally. A test asserts a later registration does not
  appear in an already-issued set.
- **An `@`-token at the start of a Javadoc line is a block tag even inside `{@code}`.** A usage sample
  in `FluentBuilder` showed `@Override` on its own line and Checkstyle failed both toolchains with
  *"Unknown tag 'Override'"* — `{@code}` suppresses HTML, not tag parsing, and `&#64;` does not help
  inside it either. The overrides are now trailing comments. **This is the second gate in two items
  that a plain `verify` does not run**: `spotless:check` (item 2.2) and now `checkstyle:check` both
  need their standalone CI goals, so a green `clean verify` proves less than it looks.
- **The route matched again** — 2.4 is `standard / high` against this host's `standard` tier, so no
  ROUTE-MISMATCH. Item 2.5 is `standard / medium`.

## Verification

Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, Windows 10 Pro 19045 (i5-6600K, 4 cores):
`clean verify` (`Tests run: 124`) on both; `-Pjmh,jcstress verify` on both (**98** jcstress results on
21, **196** on 17 — biased locking doubles the configurations there — zero failures); CI's real
quality goals `spotless:check -pl '!d4np-bom'`, `checkstyle:check` and `validate` on both; and
`python tools/consistency_lint.py`.

`GenericFactoryRegistrationStress` proven non-vacuous by flipping its `ACCEPTABLE` outcome to
`FORBIDDEN` and confirming the build turns red.
