# ADR-0029: Annotate the bind-parameter varargs `@Nullable`, knowing it says the wrong thing

- **Status:** Accepted
- **Date:** 2026-08-08
- **Deciders:** tech-lead (implementation of ROADMAP item 4.3), owner
- **Related:** [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) (the annotation is
  declaration-only, deliberately); [ADR-0009](0009-errorprone-nullaway-on-jdk-21-cells.md) (NullAway runs at
  `ERROR`); [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-21 (the same expressiveness gap,
  met from the other side); spec [§2 FR-05](../specs/01_spec_utils.md)

## Context

`SimpleJdbcExecutor` binds parameters positionally from an `Object...`, and **a `null` parameter is
a value**: it binds SQL `NULL`, which is the whole reason a nullable column exists. So the ordinary
call

```java
executor.update("insert into orders (id, sku, note) values (?, ?, ?)", 1, "A-1", null);
```

has to be writable. It is the shape of every optional column in every schema.

**It does not compile under NullAway**, which this project runs at `ERROR` severity on every
annotated package (ADR-0009). NullAway treats the elements of a varargs parameter in an annotated
package as `@NonNull`, so the literal `null` is a finding — not in this library's tests only, but in
**every consumer** whose own code NullAway checks. Measured, not assumed: the first compile of item
4.3's test suite produced five of them.

The obvious fix is to annotate the parameter, and here the project's own earlier decision bites.
`it.d4np.utils.Nullable` is a **declaration** annotation — ADR-0011 chose that deliberately and
declined to widen its `@Target` to `TYPE_USE`. A declaration annotation attaches to *the parameter*,
and the parameter of a varargs method **is the array**. So `@Nullable Object... params` tells
NullAway "the array may be null", which is:

- **not what this contract says** — the array must not be `null`, and passing one raises
  `NullPointerException`; and
- **exactly what makes the call site compile**, because with the parameter nullable NullAway stops
  analysing the elements.

Both halves were measured. With the annotation, `update(sql, 1, "A-1", null)` compiles and
`params.length` inside the module becomes a NullAway error until an explicit `requireNonNull`
narrows it. Without it, every `null` bind in every consumer is an error the consumer must suppress.

This is the same gap RFC-0003 hit from the other side, six weeks of roadmap earlier: it wanted
`Map<String, @Nullable Object>` for FR-21's null-versus-absent problem and recorded that the type is
*inexpressible* without widening the annotation — which is why `PartialUpdate` carries the
distinction beside the value instead of inside it. One decision, surfacing twice, in two modules.

## Decision

**The bind-parameter varargs is annotated `@Nullable Object... params` on all three operations, and
the array's non-nullness is enforced at run time instead of by the annotation.**

`execute` opens with `Objects.requireNonNull(params, "params must not be null")` and passes the
narrowed value on, so the guarantee holds where it matters — at the boundary, with a named message —
rather than in a type the language cannot spell here.

**The Javadoc states that the annotation says less than it looks like**, in its own section rather
than in a parenthesis, and every `@param params` tag repeats that the array must not be `null`. A
consumer reading the signature will draw the wrong conclusion; the documentation is where that is
corrected, because nothing else can correct it.

**`@Target` is not widened.** ADR-0011 stands.

## Alternatives Considered

- **Leave the varargs unannotated and let consumers suppress.** Rejected as pushing this library's
  problem onto every consumer, at the most common call there is. A `@SuppressWarnings("NullAway")`
  on a method that binds an optional column also suppresses every *real* finding in that method,
  which turns a nullability tool into a liability precisely where a nullable column is in play.
- **Widen `it.d4np.utils.Nullable` to `TYPE_USE`, then write `Object @Nullable ...`.** The technically
  correct spelling, and rejected on cost and blast radius. It changes a **core** type — the one
  every module and every consumer already depends on — to solve a JDBC ergonomics problem; ADR-0011
  weighed exactly this and chose declaration-only with reasons that have not changed; and a
  `TYPE_USE` annotation reads correctly only to people who know where to put the asterisk, which
  makes the signature *less* legible to the reader it is meant to inform. Revisiting it is a
  standalone decision with its own ADR, driven by FR-21 and FR-05 together rather than by whichever
  one is being written this week.
- **Take `List<Object>` instead of varargs**, so the element type can carry the annotation. Rejected:
  it makes every call site allocate and read `List.of(1, "A-1", null)` — and `List.of` **rejects
  `null` elements**, so the one case this whole decision is about would need `Arrays.asList`. Worse
  ergonomics, in service of a nullability model, for a call that would still be unsafe at run time.
- **Overload for the null case — `updateWithNulls(String, Object[])`.** Rejected as inventing a
  second vocabulary for the same operation; a caller cannot know in advance whether a value is null.
- **Bind `null` through a sentinel value the caller passes instead.** Rejected outright: a sentinel
  for `NULL` is a value that means "not a value", which is the class of design ADR-0013 refused when
  it declined to let a `null` result mark an uninitialized `Lazy`.

## Consequences

- **A NullAway-checked consumer can bind SQL `NULL` with no suppression**, which is the point. This
  is the ergonomics half of FR-05 and it is worth more than the analyser's opinion about an array
  reference nobody passes.
- **NullAway no longer catches an explicitly-`null` array.** `executor.update(sql, (Object[]) null)`
  now type-checks and fails at run time instead of at compile time. The cast is the tell: writing it
  takes deliberate effort, whereas passing a null *element* is routine. The runtime check is
  asserted by `rejectsNullArguments`, so the guarantee is tested even though it is no longer typed.
- **The annotation and the contract disagree in the published API**, permanently and visibly. This is
  the real cost, and it is recorded here rather than smoothed over: a reader who trusts the signature
  over the Javadoc will believe a null array is accepted. The mitigation is documentation and a test;
  there is no better one available without ADR-0011 being reopened.
- **ADR-0011 now has two call sites arguing against it**, FR-21's map and FR-05's varargs. Neither is
  strong enough alone and the pair is worth noting: if a third arrives, the `TYPE_USE` question
  should be reopened on its own terms rather than settled again in passing.

## References

- [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) — why the annotation is
  declaration-only, and the decision this one deliberately does not reopen.
- [RFC-0003 §FR-21](../rfc/0003-jdbc-and-json-contracts.md) — *"`Map<String, @Nullable Object>` is
  inexpressible without widening `@Target`"*, the same gap in the JSON module.
- [`SimpleJdbcExecutor.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/SimpleJdbcExecutor.java)
  — the annotation, the section of Javadoc that corrects it, and the `requireNonNull` that enforces
  what it cannot.
