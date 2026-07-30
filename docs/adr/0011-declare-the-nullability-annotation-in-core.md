# ADR-0011: Declare the nullability annotation in core instead of depending on one

- **Status:** Accepted
- **Date:** 2026-07-30
- **Deciders:** Daniel Polo (maintainer), agent (senior project architect)
- **Related:** ROADMAP item 2.1; [ADR-0009](0009-errorprone-nullaway-on-jdk-21-cells.md) (which
  deferred this decision by name); [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) and
  [ADR-0006](0006-enforce-the-dependency-policy-per-module.md) (dependency policy);
  [RFC-0001](../rfc/0001-core-contracts.md) (which pins `cause` as nullable); NFR-07, NFR-08, NFR-09;
  AGENTS.md §9

## Context

ADR-0009 wired NullAway at `ERROR` severity with `-XepOpt:NullAway:AnnotatedPackages=it.d4np`, and
closed by deferring one thing explicitly:

> **No nullability annotation artifact is added.** NullAway needs one only where something *is*
> nullable; core has no production types yet […] The annotation choice arrives with the first nullable
> member (item 2.1).

That member has now arrived, and it is the only one in the item: RFC-0001 pins
`record ErrorDetail(String code, String message, Throwable cause)` and states "`cause` is nullable,
`code` and `message` are not."

**This is not a stylistic choice — without an annotation the code does not compile.** Measured, by
deleting the marker and building on Temurin 21.0.12+8:

```
ErrorDetail.java:[69,25] [NullAway] passing @Nullable parameter 'null' where @NonNull is required
```

Line 69 is the two-argument convenience constructor's `this(code, message, null)`. With the marker
restored, `clean verify` is green. So the annotation is load-bearing in both directions, which is the
property ADR-0009's `static-analysis-wired` check exists to protect.

Three constraints shape the choice, and they pull against each other:

1. **ADR-001 fixes `d4np-core` at zero third-party dependencies** (NFR-08), enforced by a
   default-deny `maven-enforcer` allowlist (ADR-0006) that today admits only `it.d4np:*`, anything at
   `test` scope, and `jakarta.validation-api` as `provided` — the one third-party artifact spec §3
   names.
2. **An annotation is the one dependency a library cannot keep to itself.** Unlike an implementation
   dependency, it appears in the *published signatures*: `japicmp` (NFR-09) gates it, Javadoc renders
   it, and a consumer's own static analysis reads it out of our class files.
3. **NullAway identifies a nullability annotation by name**, not by artifact — any annotation whose
   fully-qualified name ends in `.Nullable`. So a locally declared marker is not a workaround; it is
   the mechanism the tool is built around.

## Decision

**Declare `it.d4np.utils.Nullable` in `d4np-core` and depend on no annotation artifact.**

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({METHOD, PARAMETER, FIELD, RECORD_COMPONENT})
public @interface Nullable {}
```

- **`RUNTIME` retention, not `CLASS`.** The frameworks this library targets — Spring, Jakarta Bean
  Validation — inspect signatures reflectively. `CLASS` would be invisible to them and costs the same
  bytes.
- **Those four targets, because a record component propagates to exactly those declaration sites.**
  Annotating the component reaches the accessor, the field and the canonical constructor parameter only
  if each is in the target set, and the accessor is the site NullAway reads at a call site. Asserted by
  `NullableTest`, not assumed.
- **No `@NonNull` counterpart.** Non-null is the default; an annotation restating the default would
  eventually be applied inconsistently, and from that point its absence would mean nothing.
- **`TYPE_USE` deliberately omitted for now.** No core signature yet needs `List<@Nullable String>`.
  Widening a `@Target` set is source- and binary-compatible; narrowing one is not, so the reversible
  direction is to start narrow.
- **Where a test must pass `null` to assert a rejection contract, NullAway is suppressed on the
  smallest enclosing element with a stated reason** (AGENTS.md §9) — never file-wide, never by severity
  demotion.

## Alternatives Considered

- **JSpecify (`org.jspecify:jspecify`) at compile scope.** The modern consensus annotation, and the one
  NullAway itself uses. Rejected on NFR-08 as written: core's contract is "**ZERO** third-party
  dependencies", not "zero except a small one", and satisfying it would mean editing the default-deny
  allowlist — which ADR-0006 created precisely so that admitting an artifact is a reviewed decision
  rather than a habit. Every consumer of the smallest module in the family would then resolve an extra
  artifact to read three lines of metadata.
- **JSpecify at `provided` scope.** Tempting, because `CLASS`-retained annotations are not needed at
  run time. Rejected on a mechanism: `provided` is not transitive, so a consumer compiling against our
  API would see `@Nullable` in our class files with nothing on their classpath to resolve it — the
  annotation is silently dropped, which is worse than not having one, because the *contract looks*
  documented. It also still needs the allowlist edit.
- **JSR-305 (`javax.annotation.Nullable`).** Rejected twice over: the `javax.*` namespace is
  explicitly out of scope (NFR-07), and both Checkstyle's `IllegalImport` rule and the parent's
  `bannedDependencies` would need an exception carved for it. Independently, JSR-305 is dormant and its
  artifact splits the `javax.annotation` package, which JPMS rejects — and spec §1.1 requires every
  module to ship `module-info`.
- **Checker Framework annotations.** Same dependency objection, a larger artifact, and a stricter
  dialect than NullAway consumes.
- **Avoid nullability entirely: make `cause` non-null and expose `Optional<Throwable>`.** Rejected on
  two grounds. RFC-0001 pins the record's component list, and an RFC outranks an implementation
  convenience (ADR-0010); and `Optional` as a field or record component is a known anti-pattern — it is
  not `Serializable`, which this record has to be (see ADR-0012's sibling reasoning on
  `BusinessException`), and it allocates on a path that exists only to say "nothing went wrong
  underneath".
- **Suppress NullAway on `ErrorDetail` instead of annotating it.** Rejected: AGENTS.md §10 forbids
  broad disables, and it would remove the checker from the one type in the module where nullability is
  actually interesting.

## Consequences

- **`it.d4np.utils.Nullable` is public API from the first release** and `japicmp` will hold it.
  Deleting it later is a MAJOR change. That is the price of the zero-dependency contract, and it is a
  three-line type with no behaviour.
- **A consumer running NullAway gets our contract for free**, because the FQN ends in `.Nullable`. A
  consumer running the Checker Framework or IntelliJ's inspector does too, for the same reason.
- **The propagation is tested, not trusted.** `NullableTest` asserts the retention policy, the exact
  target set, and that the marker reaches the record component, the accessor, the field *and* the
  canonical constructor parameter — plus that it is **absent** from `code()` and `message()`, so its
  presence carries information. A target set missing one site would leave the marker invisible exactly
  where NullAway reads it, and the build would stay green because nothing else in core is nullable yet.
- **Deliberate nulls in tests cost suppressions.** There are **three** `@SuppressWarnings("NullAway")`
  sites — two nested classes whose entire purpose is passing null, and one single-assertion method — and
  removing all three produces **34 findings** (measured; removing only the two class-level ones produces
  26). Each is the narrowest scope that still lets the rejection contract be asserted, which is what
  AGENTS.md §9 asks for: a suppression on the smallest possible element, with a reason.
- **Nothing enters any module's dependency graph.** `dependency:tree` on `d4np-core` is still nothing
  but test-scoped JUnit and AssertJ, unchanged from ADR-0009's measurement.
- **Migration to JSpecify stays open.** If the ecosystem consolidates and NFR-08 is revisited, the
  cheap path is to keep this annotation as the published marker and meta-annotate or deprecate it —
  not to remove it, which `japicmp` would flag.
- **Known limitation, stated:** `@Nullable` here is a *declaration* annotation, so it cannot express
  nullability inside a generic type argument. The first core signature that needs
  `List<@Nullable T>` must widen the target set — an additive change, but one that should be made
  deliberately rather than discovered.

## References

- NullAway configuration (annotation recognised by FQN suffix):
  <https://github.com/uber/NullAway/wiki/Configuration>
- JLS §8.10.3 — propagation of record-component annotations to the field, accessor and constructor
  parameter, subject to each site being in the annotation's `@Target`.
- Failure text quoted above reproduced locally on Temurin 21.0.12+8, Maven 3.9.9, ErrorProne 2.50.0 +
  NullAway 0.13.8.
- `d4np-core/src/test/java/it/d4np/utils/NullableTest.java` — the propagation assertions.
