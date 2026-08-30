# 2026-08-29 — `PageRequest`, a type that will not tell you what it is carrying (ROADMAP item 4.5)

**Milestone 4, item 4.5 — and Milestone 4 closes.** FR-07 looked like the easy one: no I/O, no third-party
surface, three value types and a set-membership check. What it turned out to hold is the half of the
injection story a `PreparedStatement` cannot reach, one decision RFC-0003 left open, and one
specified throw that did not compile.

## What changed

`PageRequest`, `PageSort` (with `PageSort.Order` and `PageSort.Direction`) and `PageResponse<T>` land
in `d4np-jdbc`; `ValidationException` gains a public mint in `d4np-core`. **72 new tests** in
`d4np-jdbc` (123 in the module) and **7** in core (340, the first change to that number since item
3.3). Two records:
[ADR-0033](../../../adr/0033-publish-no-accessor-for-the-unvalidated-sort.md) (no accessor for the
unvalidated sort) and
[ADR-0034](../../../adr/0034-mint-a-validation-failure-from-outside-core.md) (the second door into
`ValidationException`, and what it may say). Two threat-model rows move ▢ → ✅ and the compliance
register gains **C-06**.

## The specified throw did not compile, and the reason was in core

RFC-0003 §FR-07 is careful about which exception a whitelist violation raises. It must be
`ValidationException` and not `IllegalArgumentException`, because FR-19 maps validation to **400**
and has no row for the latter, which would land on the **500** fallback and report a client's typo as
a server fault. The RFC closes with *"`d4np-jdbc → d4np-core` makes the type available"*.

It does not. The constructor is package-private, and the class says why:

> *"`Validator` is the only thing that may decide an object is invalid."*

That was true while a Bean Validation provider was the only way to reach the verdict. FR-07 is the
first place here that reaches it without one — the allowed set is the repository's, supplied per
call, and is not expressible as an annotation on the type being validated.

ADR-0034 opens `ValidationException.of(...)` and keeps `fromProvider(...)` package-private, so the
provider's sentence stays the provider's. Both doors funnel through one private constructor that
**bounds every string it is given** — control characters stripped, 200 characters per violation, at
most 20 listed in the message — because a violation about client input necessarily quotes some of it,
and a property name holding `\r\n` folds one log line into two.

**This is the second time in two milestones that a monopoly claim has not survived a second module.**
ADR-0024 corrected `d4np-json`'s *"no Jackson type appears in a signature"* the same way. A claim of
that shape, written by the first implementation, is a prediction rather than a contract.

## The decision the RFC's table left open

RFC-0003 gives FR-07 three operations: two constructors and
`PageSort validatedAgainst(Set<String> allowedProperties)`. It does not say whether `PageRequest`
also publishes the sort it carries, and the question is not cosmetic.

With a `sort()` accessor the whitelist is *advice*. The mistake it permits — a repository that never
calls `validatedAgainst` — compiles, passes its own tests, and is invisible in review, because the
missing call is an **absence** and nothing marks the line where it belonged.

So there is no accessor. `validatedAgainst` is the only member that returns a `PageSort`, and it
takes the allowlist as its argument, so a repository that has not named its sortable columns has
nothing to interpolate. This is FR-20's shape one milestone later — `JsonMapper` hardens Jackson by
never handing out the `ObjectMapper` — and it is asserted structurally rather than editorially:
`publishesNoAccessorThatReturnsTheSort` walks the reflected surface, so a convenience accessor added
later fails wherever it is written.

`toString()` is where the type would otherwise contradict itself, so `PageRequest` renders the sort
as a **count**. `PageSort`'s own rendering does show the names, bounded — whoever holds that object
built it or received it validated.

## A test that nearly shipped as a false negative

The loud demonstration is `sku; drop table orders --` concatenated into the clause. Run through
`update`, H2 refuses (`executeUpdate` on a query) and reports:

```
SQL statement: select id from orders order by sku
```

Read at face value, that says the semicolon was discarded and the payload is inert. This journal's
first draft said exactly that, and started rewriting the test around a subtler payload.

It is wrong. Through `query` the same string **drops the table** — a statement string holding a
semicolon is still one string, and a `PreparedStatement` does not make it two. The assertion caught
what the message hid, which is C-01's lesson arriving from the other direction: *a driver's text is
evidence about the driver, not about what happened.*

The quieter demonstration is the one that matters more. `theSamePropertyLeaksAHiddenColumnWhenNobody
ValidatedIt` orders by `note` — a column the allowlist omits and no query here selects — and the rows
come back in that column's order. A client who can name a column can read one, one comparison per
request, leaving an ordinary-looking query in the log.

## The exact-match rule has a second reason

RFC-0003 refused normalisation because identifier folding is vendor-specific: PostgreSQL folds to
lower, Oracle to upper, MySQL depends on the filesystem, so a library that normalises picks a vendor.

Building it surfaced the security half. Because the comparison is exact, the string that survives
validation is `equals` to the one the repository listed — so interpolating the client's string is
indistinguishable from interpolating our own. Under case folding it would **not** be: `"ORDERDATE"`
would pass a check against `"orderDate"` and then be the text that reaches the SQL. Two arguments,
one direction, and worth knowing if anyone is ever tempted to fold.

## One judge for the sort, and the half of FR-07 that keeps the wrong one

FR-07's *"violations throw `IllegalArgumentException` at construction"* is right for a programmatic
caller and wrong for `?sort=,asc` from a browser. So **nothing about a property is judged at
construction** — `PageSort` accepts any non-`null` string, blank or enormous — and `validatedAgainst`
is the only thing that refuses, always as a 400. The same reasoning bounds two inputs the requirement
never named, both at validation time: at most **8** properties, and none named twice.

The page/size bounds keep `IllegalArgumentException`, because spec §2 states it normatively. That
leaves the identical misattribution on the other half of the same requirement — `?page=-1` reaches
the 500 fallback — which RFC-0003 caught on one side and not the other. It is an `@apiNote` on the
factory and a **fifth obligation on item 7.1**, not an implementation quietly disagreeing with an
approved RFC.

## Smaller things worth carrying forward

- **Two silent overflows removed by writing the arithmetic.** `page * size` in `int` returns a
  *negative* offset at ten million pages of 200; `(total + size - 1) / size` overflows to a negative
  page count near `Long.MAX_VALUE`. `Math.ceilDiv` would say it plainly and arrived in Java 18 —
  proven out of reach by ErrorProne's `InvalidLink`, which resolves `{@link}` against the
  `--release 17` classpath rather than the compiling JDK's.
- **`PageResponse` is a class and `PageSort.Order` is a record**, and the test is what the generated
  `toString()` would say: a page's components are up to `size` rows of the caller's data, an order's
  are both things a reader needs to see.
- **`@apiNote` is legal on a method and rejected on a type here** — Checkstyle's `JavadocType` says
  `Unknown tag`, `JavadocMethod` does not — so item 4.3's tag declaration covers half the places the
  JDK allows. The note moved rather than the ruleset.
- **RFC-0003's "15 new public types" is 17.** Five in `d4np-json`, ten top-level in `d4np-jdbc`, plus
  `PageSort.Order` and `PageSort.Direction` — both named in the RFC's prose, neither counted. Nested
  types are types to `japicmp`, so it is item 8.1's number.
- **The fourth "no jcstress harness, and here is why" in this project**, which is itself worth
  noticing: three immutable types with no shared mutable state, and no NFR names FR-07.

## One inherited claim that did not survive being run

Items 4.1–4.4 each closed with `-Xdoclint:all -Xwerror` **clean on both toolchains**. On this host it
is clean on 21 and **fails on 17**, with eight `warning: no comment` findings on the record components
of `AuditEvent` and `ErrorDetail` — item 3.3's noise, which item 4.1 recorded as not recurring.

The temptation is to copy the sentence forward, and the alternative temptation is to fix two
one-line Javadocs and move on. Neither happened. `origin/main` was checked out into a separate
worktree and produces the **identical eight**, so this item introduces none and the files are ones it
does not touch — which makes it item 8.7's, and repairing another item's shipped file inside an
unrelated PR is the quiet exception item 3.1 declined to make.

What is *not* settled is why the earlier claim held. This host runs Temurin **17.0.20.1+1** where
those items ran **17.0.20+8**, so either the patch release changed doclint's treatment of an
annotated record component or the earlier run did not report it. Recorded as two candidates rather
than resolved into one, and scoped into 8.7 with the warning that a gate turned on before it is
answered goes red on half the matrix immediately.

## Where the project stands

**Milestone 4 is complete.** M3, M4, M5 and M6 were mutually independent once M2 landed, so the next
milestone is the owner's priority call rather than a technical constraint — M5 (`concurrent`) and M6
(`security`) both begin with an RFC item (5.0, 6.0), and item 8.3 remains pullable forward at any
time.

## What the next session needs to know

- **`d4np-core` is no longer closed to the capability modules.** Item 4.5 is the first time a module
  outside core needed to *mint* a core type rather than consume one, and it will not be the last —
  FR-12's `CryptoException` and FR-11's JWT failures are the same question one milestone ahead.
  ADR-0034's shape (a public factory carrying the obligation, a package-private one for the
  privileged path, bounding inside the type) is the precedent to copy or to argue with.
- **Item 7.1 now carries five obligations**, and the new one is different in kind from the other four:
  they are all *"do not render X"*, and this one asks the mapping table to decide what happens to
  `IllegalArgumentException` — which today is nothing, which is why FR-07's own page bound
  misattributes.
- **The `ORDER BY` row is ✅ with a residual that no Java API can remove**, and it is stated in three
  places so it cannot be read as closed: a component that both parses the request and builds the SQL
  still holds the `PageSort` it constructed.
