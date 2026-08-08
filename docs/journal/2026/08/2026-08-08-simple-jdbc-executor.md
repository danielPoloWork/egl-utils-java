# 2026-08-08 — `SimpleJdbcExecutor`, and a gate that measured its way out of existing (ROADMAP item 4.3)

**Milestone 4, item 4.3 — the first code in `d4np-jdbc`, and the first module in this repository to
ship a public API with no third-party dependency at any scope a consumer resolves.** `d4np-core`
comes close but has one `provided` edge behind `requires static`; here the JDBC API ships inside the
JDK, so a consumer that wants parameterized statements takes exactly one JAR.

## What changed

`SimpleJdbcExecutor`, `RowMapper<T>` and `JdbcAccessException` land in `d4np-jdbc` (**26 new
tests**, 333 in core unchanged), with `exports it.d4np.utils.jdbc` and `requires transitive
java.sql` on a descriptor that until today exported nothing. Two records:
[ADR-0028](../../../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md) (the operation set and
the second row it refuses) and
[ADR-0029](../../../adr/0029-annotate-the-varargs-so-a-null-parameter-compiles.md) (an annotation
that has to say the wrong thing). The threat model's **SQL-concatenation row moves ▢ → ✅**; C-01
gains a fourth call site; the patterns catalogue's long-standing *Template method / callback* entry
becomes **Implemented** after seven milestones as `Planned`.

## The gate RFC-0003 promised, and the measurement that filed it instead

RFC-0003 singled NFR-03 out: *"the one performance gate in this project that can be a real CI gate
today"*, because it is a **relative** comparison — two arms, one JMH invocation, one machine — where
NFR-01's 2 ns/op and NFR-06's 400 MB/s are absolute numbers a slow runner fails while measuring
nothing wrong. That reasoning is correct and it survives.

What it does not cover is sample size, and this item's job was to land the benchmark, so the
question became answerable. Three consecutive repetitions at CI's own settings — one fork, one
warmup iteration, one measurement iteration — on one idle machine:

| Repetition | `executorQuery` | `handWrittenLoop` | ratio |
|---|---|---|---|
| 1 | 0.886 ms/op | 1.573 ms/op | **0.56** |
| 2 | 1.035 ms/op | 1.576 ms/op | **0.66** |
| 3 | 1.306 ms/op | 1.029 ms/op | **1.27** |

A **2.3× spread across a 1.10 threshold.** A gate wired today would fail roughly a third of builds
that had regressed nothing, which is the specific way a project teaches its team that red CI is
normal — the failure mode the CI workflow's own bootstrap guard was written to avoid.

So the gate is **filed as item 8.8 rather than shipped**, and the filing carries the numbers instead
of an intuition. What 8.8 needs is fork and iteration counts and the wall-clock they cost; what it
does **not** need is item 8.3's stable runner, and keeping the two items separate is the point —
merging them would bury the distinction RFC-0003 drew.

**Filing it moved a traceability edge**, which is the kind of thing worth noticing rather than
discovering later: `traceability.py` now reports **RFC-0003 → M4, M8** where it reported M4 alone,
because 8.8 cites the RFC that asked for the gate. The RFC's reach outlived the milestone that
implemented it.

## The measured result, and the sentence it does not support

**NFR-03 is met — and "met with room" would be the wrong way to say it**, which is worth writing down
because it was the first draft of this entry.

At 5 forks × 10 iterations the ratio is **1.071 on JDK 21 and 1.100 on JDK 17** against a ≤ 1.10
ceiling on the raw mean, and **0.952 / 1.051** on the median of per-fork means. Both readings come
from the same 50 measurements per arm; neither corrects the other. On JDK 17 the raw mean lands on
**1.1006** — the ceiling to four decimal places.

So this is the first budget in the project whose **verdict depends on which statistic is used**, and
NFR-03 says "≤ 10% overhead" without saying of what. That is not a problem to resolve here; it is
item 8.8's, and it is now filed with the numbers rather than as a preference.

**The overhead is the one RFC-0003 predicted, which is the part worth keeping.** The RFC argued for a
caller-supplied `RowMapper` over reflection partly on this budget, reasoning that a mapper lambda
over the same `ResultSet` *is* the hand-written loop plus one virtual call. ~5% over 10 000 rows is
about 4 ns a row — one interface call, one null check, a share of the trailing `List.copyOf`. The
model the design was chosen on describes the code that got written.

**The margin is thin, and that is a forward risk rather than a present failure.** Items 4.4 and 4.5
add no per-row work by design; anything that later does should re-run this before it lands. The named
lever, if it is ever needed, is that trailing `List.copyOf` — an 80 KB array copy an
`unmodifiableList` view would remove. It is deliberately **not** taken now: tuning to a number rather
than to a requirement is how a measurement culture becomes a benchmarking one.

The third arm is the one worth carrying outside this item: a `DataSource`-backed executor, which
opens and closes a real connection per call, runs **2.3× (JDK 21) and 2.6× (JDK 17)** the
borrowed-connection arm. Opening an H2 connection costs more than reading 10 000 rows through it.
Not a regression, not in the budget — but it is the concrete reason FR-06 hands its callback a
`Connection` rather than a `DataSource`, and why the `@apiNote` about capturing an executor into a
transaction block matters at all.

Full data, both toolchains, in the
[benchmark report](../../../benchmarks/2026-08-08-jdbc-row-mapping.md).

## The annotation that has to say the wrong thing

A `null` bind parameter is a **value**: it is SQL `NULL`, which is the entire reason a nullable
column exists. So `update(sql, 1, "A-1", null)` has to be writable — and under NullAway it does not
compile, in this library's tests and in every consumer whose own code NullAway checks. The first
compile of the test suite produced five findings.

Annotating fixes the call site and creates a smaller, permanent problem. `it.d4np.utils.Nullable` is
a **declaration** annotation by ADR-0011's deliberate choice, and a declaration annotation on a
varargs parameter attaches to *the array*. So `@Nullable Object... params` tells NullAway the array
may be null — which is not what the contract says, and is exactly what makes the element case
compile. Both halves measured: with the annotation, `params.length` inside the module became a
NullAway error until an explicit `requireNonNull` narrowed it.

**This is the same gap RFC-0003 met from the other side one item ago.** FR-21 wanted
`Map<String, @Nullable Object>` and recorded that it is inexpressible without widening `@Target`,
which is why `PartialUpdate` carries the null-versus-absent distinction beside the value instead of
inside it. One decision, surfacing twice, in two modules, within a week. ADR-0029 keeps ADR-0011
standing and says so explicitly: **if a third call site arrives, the `TYPE_USE` question should be
reopened on its own terms** rather than settled in passing by whichever item is being written.

## Two guarantees asserted by running rather than by reading

**"The connection is closed"** is not a property of a `finally` block existing. A real pool would
hide both halves of it — it hands back connections it already had, and `close()` on a pooled
connection closes nothing. So the tests use a hand-written `DataSource` that opens a genuinely new
connection per call, keeps every reference, and asks *the driver* whether each one is closed.

**"No plain `Statement` is ever created"** is not a property of the source not containing
`createStatement`. Every operation runs through a `java.lang.reflect.Proxy` over `Connection` that
fails the test the moment `createStatement` is called and records that `prepareStatement` was — so a
concatenating overload added later cannot pass, wherever it was written. Beside it,
`treatsAnInjectionPayloadAsData` binds `'; drop table orders; --`, finds it stored as a string, and
finds the table intact.

And C-01 keeps its companion discipline: `theDriversOwnMessageStillCarriesBoth` asserts that H2's
own exception quotes **both** the bound parameter and the entire `insert` statement. That is the
second independent provider to demonstrate the same thing — Jackson was the first, in item 4.1 —
which is what turns item 7.1's *"never render a cause's `getMessage()`"* into a property of the
boundary handler rather than a workaround for one library.

## Smaller decisions, and where each one is recorded

- **`queryOne` refuses a second row** rather than returning the first. A duplicate in a
  supposedly-unique column would otherwise become an application reading one of two records at
  random, forever, with nothing in a log (ADR-0028).
- **It reports no SQLState for that refusal.** SQLState `21000` is the standard's own *cardinality
  violation* and would have fitted perfectly — and writing our own conclusion into a field that
  means *what the driver said* makes a consumer unable to tell the two apart. Empty state, zero
  vendor code, `null` cause: three signals that agree.
- **A `RowMapper` returning `null` is `IllegalStateException`, not `JdbcAccessException`**, because
  nothing about the database failed. Same shape `Lazy` gives an initializer that returns `null`.
- **No jcstress harness**, and spec §6 is why that had to be said out loud: there is no shared
  mutable state here to stress. Item 4.4 does owe one, for FR-06's nesting detector.
- **H2 is reached only through `DriverManager`**, so no type in this module — production, test or
  benchmark — names a driver class. That is what makes "JDBC API only, no drivers" checkable rather
  than aspirational.

## Where the project stands

**Milestone 4 is two-thirds done** — 4.0, 4.1, 4.2 and 4.3 complete; **4.4 (`JdbcTxRunner`) and 4.5
(`PageRequest`) remain**, both in this module, both starting from a contract RFC-0003 already pinned
in full.

## What the next session needs to know

- **Item 4.4 inherits three things from today**: `SimpleJdbcExecutor.on(Connection)` is the form its
  callback will use and is already tested for never closing what it borrows; `JdbcAccessException`
  already exists with the shape RFC-0003 specified for it, including the cause-less form for a fault
  the library detects itself; and ADR-0026's restated wrapping rule now has a JDBC demonstration.
- **Item 4.4 owes a jcstress harness** — RFC-0003 §FR-06 names it, for the thread-scoped depth
  counter that refuses nesting. Today's item established that a *stateless* type owes none, which is
  the contrast that makes 4.4's obligation clear rather than optional.
- **Item 4.5 owes the other half of the injection story.** A column or table name cannot be a bind
  parameter in any database, so `SimpleJdbcExecutor` cannot defend an `ORDER BY`; FR-07's allowlist
  is the control for that row, and the threat model's two rows now sit next to each other with one
  green and one still ▢.
- **The javadoc scoping caveat shrinks by one module and does not close** — the first draft of this
  entry said it closed, and running it said otherwise. `d4np-jdbc` stops being an empty module today,
  so the scope goes from `-pl d4np-core,d4np-json` to `-pl d4np-core,d4np-json,d4np-jdbc`; **five
  modules are still empty** (`d4np-concurrent`, `d4np-security`, `d4np-spring-adapter`,
  `d4np-lock-redisson`, `d4np-test`), each a `module-info.java` and nothing else, and javadoc fails
  every one of them with *"No public or protected classes found to document"*. Item 8.7 inherits a
  list that is one shorter.
- **`@apiNote` and `@implNote` now work, because the parent POM declares them.** They are the JDK's
  own tags and the standard doclet rejects both with `error: unknown tag` unless configured —
  measured, two errors on both toolchains. RFC-0003 asked this item for an `@apiNote` by name, so
  `maven-javadoc-plugin` is pinned and given a three-tag block. It configures the tool and does not
  gate it; item 8.7 still owns the CI job.
- **Item 8.8 exists and carries its own evidence.** Do not merge it into 8.3 — different problem,
  different fix.
- **The two unreproducible CI commands are unchanged** from item 3.1: `japicmp:cmp` resolves no
  plugin (item 8.1), `-Pcoverage` matches no profile (item 8.2).
