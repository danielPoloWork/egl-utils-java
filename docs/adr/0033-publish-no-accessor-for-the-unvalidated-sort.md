# ADR-0033: Publish no accessor for the unvalidated sort, so the allowlist cannot be skipped

- **Status:** Accepted
- **Date:** 2026-08-29
- **Deciders:** tech-lead (implementation of ROADMAP item 4.5), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-07 (the three-row operation
  table this reads as exhaustive); spec [§2 FR-07](../specs/01_spec_utils.md);
  [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) (the rule this applies: a guarantee a
  consumer can switch off is advisory); [ADR-0024](0024-take-a-jackson-type-in-one-signature.md)
  (FR-20's absent `ObjectMapper` getter, the same shape one milestone earlier);
  [ADR-0028](0028-the-fr-05-operation-set-and-what-it-refuses.md) (the residual this closes);
  [threat model](../security/threat-model.md) *Tampering* → *Injection via `ORDER BY`*

## Context

A column name cannot be a bind parameter in any database. Every other statement `d4np-jdbc` runs
goes through a `PreparedStatement` and item 4.3 made that structural — no operation accepts SQL
without a parameter slot, no `java.sql.Statement` is created anywhere in the module — but the
`ORDER BY` clause is the one place a repository still has to build SQL by concatenating a string it
received. Item 4.3 recorded exactly that as its residual and named FR-07 as the row that would close
it.

FR-07 supplies the mechanism: sort properties are validated against a caller-supplied allowlist.
RFC-0003 §FR-07 then established *when* that happens — at validation rather than at construction,
because `PageRequest` is built at the HTTP edge from query parameters and the set of sortable columns
is the **repository's** knowledge — and gave the operation the signature
`PageSort validatedAgainst(Set<String> allowedProperties)`.

What neither document settles is whether `PageRequest` also publishes the sort it is carrying. The
question is not cosmetic. With a `sort()` accessor the control is *advice*: a repository that never
calls `validatedAgainst` compiles, passes its own tests, and renders the client's string straight
into the clause. Without one, the allowlist is the only route to the strings at all.

The measurement that makes this concrete is in `PageRequestTest`: interpolating an unvalidated
property named `note` — a column the allowlist omits and no query selects — reorders the result by
that column's values, so a client who can name a column can read a column, one comparison per
request. Concatenating `sku; drop table orders --` **drops the table**, through
`SimpleJdbcExecutor.query` and a `PreparedStatement`, because a statement string holding a semicolon
is still one string and H2 executes both halves of it.

## Decision

**`PageRequest` publishes no accessor that returns the sort it carries.** `validatedAgainst(Set)` is
the only member that returns a `PageSort`, and it takes the allowlist as its argument, so a
repository cannot reach the client's property strings without having first named the columns it is
prepared to order by. `PageRequest.toString()` renders the page, the size and the *number* of sort
properties, never their names, because a formatting method that rendered them would restore by
another door exactly what the missing accessor removes.

This is [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md)'s rule — a guarantee a consumer
can switch off is advisory — applied to FR-07, and it is the same shape FR-20 already has: hardening
Jackson by never handing out the `ObjectMapper` means there is nothing to call
`activateDefaultTyping` on.

The guarantee is asserted **structurally rather than editorially**:
`publishesNoAccessorThatReturnsTheSort` walks the reflected public surface and fails on any method
returning a `PageSort` that does not take a `Set`, and `publishesNoAccessorThatReturnsASortOrder`
does the same for `PageSort.Order` and for the names a convenience accessor would plausibly be given.
A `sort()` added later would compile and pass every other test in the file.

## Alternatives Considered

- **Publish `sort()` and document the obligation** — the conventional shape, and what every
  pagination API the authors know of does. Rejected on its failure mode rather than on taste: the
  mistake it permits is invisible in review (the missing call is an *absence*, and nothing marks the
  line where it should have been), produces no error, and is discovered by an attacker rather than by
  a test. The threat model's `ORDER BY` row would have had to stay ▢, because the control would be a
  convention rather than a property of the type.

- **Return a rendered `ORDER BY` fragment from `validatedAgainst` instead of a `PageSort`** — the
  strictly strongest option: with no property strings ever handed back, even the validated ones,
  concatenation would be impossible rather than merely uninviting. Rejected because rendering
  requires quoting, and identifier quoting is vendor-specific — the module's standing line is that it
  never chooses a dialect and never changes the meaning of SQL it did not write. It also contradicts
  RFC-0003's signature, which returns `PageSort`. The trade is stated rather than hidden: this
  decision stops at "the repository concatenates a string drawn from its own allowlist", which is one
  step short of "nobody concatenates".

- **Validate inside `PageRequest.of(...)`** — take the allowlist at construction and have no
  unvalidated state at all. Rejected by RFC-0003 §FR-07 before this item started, and for a reason
  that has not moved: the edge that constructs the request does not know the repository's columns,
  so the allowlist would have to travel from the repository to the controller, which is the coupling
  the split exists to avoid.

## Consequences

- **The threat model's *injection via `ORDER BY`* row moves ▢ → ✅**, and it moves on a structural
  claim rather than a documented one. The residual is stated with it: a component that both parses
  the request and builds the SQL still holds the `PageSort` it constructed. What is removed is the
  case where the two are different layers — which is every application with a repository.

- **A convenience is genuinely lost.** Echoing the requested sort back in a "next page" link, or
  logging what a client asked for, now goes through `validatedAgainst` or through `PageSort`'s own
  bounded `toString()`. That is the cost, and it is small precisely because the sort a caller should
  echo is the validated one.

- **Adding `sort()` later is MINOR and would be a silent repeal.** The structural tests exist so that
  it cannot happen without someone deleting an assertion that says why.

- **`PageSort.toString()` renders property names and `PageRequest.toString()` does not**, which reads
  as an inconsistency until the reason is stated: whoever holds a `PageSort` either built it or
  received it validated, whereas `PageRequest` is the type that crosses the layer boundary carrying
  the guarantee.

## References

- [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-07 — the operation table and the
  validation-time argument.
- [ADR-0028](0028-the-fr-05-operation-set-and-what-it-refuses.md) — the residual item 4.3 stated and
  routed here.
- `PageRequestTest.TheSortIsUnreachable`, `PageRequestTest.AgainstARealDatabase` — the structural
  assertions and the two demonstrations that the payloads are not inert.
- [ADR-0034](0034-mint-a-validation-failure-from-outside-core.md) — what the refusal is allowed to
  say.
