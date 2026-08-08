# ADR-0028: The FR-05 operation set — three operations, and a single-row query that refuses a second row

- **Status:** Accepted
- **Date:** 2026-08-08
- **Deciders:** tech-lead (implementation of ROADMAP item 4.3), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-05 (*"The operation list stays
  item 4.3's call"*); spec [§2 FR-05](../specs/01_spec_utils.md);
  [ADR-0015](0015-strategy-registry-last-write-wins.md) (an exception on the wrong status code
  misattributes the fault, and a typed payload field breaks serialisability);
  [ADR-0026](0026-rewrite-jacksons-unchecked-conversion-failure.md) (no exception leaves a module
  carrying text the library did not write); compliance controls **C-01** and **C-02**

## Context

RFC-0003 pinned everything about FR-05 except the operations themselves, deliberately: *"Three
shapes are needed at minimum — a list query, a single-row query returning `Optional`, and an update
returning the affected count — and naming more here would be inventing surface the specification
does not ask for."* So the RFC fixed the **shape** of the module — parameterized statements only,
a caller-supplied `RowMapper`, the two connection-ownership factories — and left the surface to the
implementation.

Two questions inside that delegation do not have obvious answers.

**What happens when a "single-row" query matches two rows?** JDBC has no opinion; the `ResultSet`
simply has a second row in it. Returning the first is the cheapest thing to write and reads
correctly at every call site.

**What does the module's exception carry when no driver raised anything?** RFC-0003 defined
`JdbcAccessException` as carrying "the `SQLState` as a `String`, the vendor code as an `int`, and the
original as `getCause()`" — a definition written for translating a `SQLException`. A fault the
library detects itself has none of those three.

## Decision

**Three operations, and no more:**

| Operation | Signature | Answer |
|---|---|---|
| list query | `<T> List<T> query(String sql, RowMapper<T> mapper, Object... params)` | every mapped row, unmodifiable, possibly empty |
| single-row query | `<T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params)` | the row, or empty |
| update | `int update(String sql, Object... params)` | the affected row count |

The mapper precedes the parameters because varargs must come last — the ordering `JdbcTemplate`
settled on for the same reason, so it reads as expected rather than as a quirk.

**`queryOne` refuses a second row with `JdbcAccessException`.** No row is an ordinary answer and is
`Optional.empty()`; two rows is not an answer to the question that was asked.

**A fault this library detected reports no driver codes.** `sqlState()` is `""`, `vendorCode()` is
`0`, and `getCause()` is `null` — three signals that agree, and the combination *is* the signal:
this library decided, no driver reported.

## Alternatives Considered

- **Return the first row and ignore the rest.** Rejected on the failure it produces, which is the
  worst kind: a duplicate in a column the schema was supposed to keep unique becomes an application
  that reads one of two records at random, forever, with nothing in a log and nothing at the call
  site to notice. The library has the information — it is holding the second row — and discarding it
  is choosing to answer a question nobody asked. This is item 4.2's reasoning about the literal
  `null` document applied to a shape rather than a value: a wrong answer that fails later is worse
  than a refusal that fails now.
- **Fabricate SQLState `21000` for the extra row.** Genuinely tempting: `21000` is the SQL standard's
  own *cardinality violation*, PostgreSQL raises it for exactly this condition in a scalar subquery,
  and it would have made every `JdbcAccessException` carry a code. **Rejected because the field means
  "what the driver said".** Writing our own conclusion into it leaves a consumer that branches on
  the code unable to tell a real `21000` from ours, and a diagnostic that cannot be traced to its
  source is worse than an absent one. `JsonConversionException` took the cause-less form in item 4.1
  for the same reason — a refusal this library makes is not a report of what a subsystem said.
- **Throw `IllegalStateException` for the extra row instead.** Rejected: it is not a caller defect.
  The caller wrote a legitimate query; the *data* did not match the caller's expectation of it. FR-19
  maps both to 500, so nothing changes at the boundary, but the type is what a log reader sees first.
  (The distinction cuts the other way one line later: a `RowMapper` that returns `null` **is** a
  caller defect, and that one *does* raise `IllegalStateException` — the shape `Lazy` gives an
  initializer that returns `null`.)
- **Add `queryOneRequired` / `queryForObject` that throws on no row.** Rejected as surface bought for
  a preference: `Optional` already composes with `orElseThrow`, and the caller's own exception is
  better than ours because only they know what "this id must exist" means.
- **Batch operations in the first cut.** Rejected, and RFC-0003 said so first. A batch API is a
  second parameter-binding vocabulary (`List<Object[]>`, or a callback per row) and a second error
  model (`BatchUpdateException` carries per-statement counts). Adding it later is MINOR; getting the
  error model wrong now is not.
- **`long` from `update`, via `executeLargeUpdate`.** Rejected: it is a JDBC optional feature whose
  default implementation throws, so the method would work on some drivers and not others — the same
  argument RFC-0003 used to refuse savepoints. Omitted in the reversible direction.
- **A row cap on `query`.** Rejected: a library-chosen limit silently truncates an answer the caller
  asked for, which is the failure mode this ADR just refused for `queryOne`. Bounding the request is
  FR-07's job (item 4.5), and until then the `limit` belongs in the caller's SQL. The exposure is
  documented on the method rather than left implicit.

## Consequences

- **`JdbcAccessException` has two constructors and the difference is observable**, which is the
  point: `reportsNoDriverCodeForAFaultItDetectedItself` asserts all three signals together, so a
  later change that started fabricating a code would fail rather than pass quietly.
- **The surface is three methods and two factories**, and every one of them takes SQL plus a
  parameter array — asserted structurally by `publishesNoWayToRunUnparameterizedSql`, which walks
  the reflected surface and fails on any public method that takes a `String` first and does not end
  in `Object[]`. A convenience overload added later would have to break that test to exist.
- **C-01 gains its fourth enforced call site.** The message is a fixed operation label plus the two
  codes; the driver's own text — which H2 demonstrably fills with both the statement and the bound
  parameter — survives only as the cause.
- **Batch, `long` counts and a typed-null binding are all named as MINOR additions**, so the next
  person to want one finds the reasoning rather than a silence.

## References

- [RFC-0003 §FR-05](../rfc/0003-jdbc-and-json-contracts.md) — the delegation this answers, and the
  contract rows it does not change.
- [`SimpleJdbcExecutor.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/SimpleJdbcExecutor.java)
  — the three operations and the refusal.
- [`JdbcAccessException.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/JdbcAccessException.java)
  — the two constructors, and why the cause-less one reports no code.
- [ADR-0026](0026-rewrite-jacksons-unchecked-conversion-failure.md) — the restated wrapping rule this
  module inherits, met here as a driver message rather than a Jackson one.
