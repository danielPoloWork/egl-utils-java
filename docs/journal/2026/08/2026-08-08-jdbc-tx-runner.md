# 2026-08-08 — `JdbcTxRunner`, and a specified surface that does not compile (ROADMAP item 4.4)

**Milestone 4, item 4.4.** RFC-0003 pinned FR-06 harder than any other contract in this milestone —
connection ownership, isolation restoration, the nesting refusal, a four-row failure-while-failing
table — which meant the interesting work was not deciding the design but finding the three places
the *specified* design does not survive contact with Java.

## What changed

`JdbcTxRunner`, `TxIsolation`, `TxCallback<T>` and `TxVoidCallback` land in `d4np-jdbc` (**25 new
tests**, 51 in the module, 333 in core unchanged), together with **the first jcstress harness this
project has owed outside core**. Three records:
[ADR-0030](../../../adr/0030-the-two-channels-out-of-a-transaction-body.md) (what propagates, and
why `null` is not a value),
[ADR-0031](../../../adr/0031-one-nesting-detector-for-the-whole-jvm.md) (one detector, JVM-wide) and
[ADR-0032](../../../adr/0032-name-the-void-transaction-form-differently.md) (the overload pair that
does not compile). C-01 reaches a log line for the first time; the patterns catalogue's Template
Method row gains its second, more instructive instance.

## The surface RFC-0003 specified does not compile

Its §FR-06 table gives both run methods one name:

```java
<T> T  inTransaction(TxCallback<T> body)
void   inTransaction(TxVoidCallback body)
```

Two overloads over two functional interfaces — one function type returning a value, one returning
nothing — make an implicit lambda whose body is a **statement expression with a value** applicable to
both, and the most-specific rules do not break the tie. Six call shapes, compiled against the pair
before anything was decided:

| Shape | Result |
|---|---|
| `c -> { doWork(c); }` | resolves to the void form |
| `String s = …inTransaction(c -> "value")` | resolves to the value form |
| **`…inTransaction(c -> executor.update(sql, id))`** | **`reference to inTransaction is ambiguous`** |
| `Probe::justRuns` / `Probe::returnsInt` | both resolve |
| `c -> { return f(c); }` | resolves |

One shape breaks, and it is the most idiomatic single-statement transaction there is. The compiler's
answer names neither the missing brace nor anything a reader can act on.

**Spring already solved this in the type FR-06 points Spring users at** — `TransactionTemplate` gained
`executeWithoutResult` beside `execute` in 5.2 for precisely this reason — so the void form is named
`inTransactionWithoutResult`. The six shapes stay in the tree as a compiled fixture rather than being
deleted, so a future overload cannot bring the ambiguity back quietly.

**The rename caught a second defect within the minute, and that argues for the tests rather than for
the rename.** The runtime message for a `null` return still read *"use inTransaction(TxVoidCallback)"*
— naming a method that no longer existed — and `rollsBackAndRefusesANullReturn` failed on the string.
A message pointing at a method nobody can call is the kind of rot no compiler reports.

## Two approved documents that cannot both be read literally

- **RFC-0003:** the runner *"rethrows the original unchanged"*. It says so while arguing about
  `Error`, which is where the sentence is unarguable.
- **RFC-0001:** no published method of this library declares a checked exception.
- **`TxCallback.run` declares `throws Exception`**, because JDBC's own methods do — RFC-0003 chose
  that deliberately over `throws Throwable`.

So a body may throw something `inTransaction` cannot declare. The literal answer is sneaky-throw, and
it was rejected on a measurement rather than on taste: with nothing declared,
`catch (SQLException e)` around the call is a **compile error**, so the exception a transaction body
is most likely to throw becomes the one the caller cannot name.

ADR-0030 translates in four cases and publishes the table on the method. The two "unchanged" rows are
asserted with `isSameAs` rather than by type, because a wrapper of the right class would pass a
weaker check. A `SQLException` becomes `JdbcAccessException` — not a special case bolted on, but the
translation every other operation in this module already makes, so a body doing raw JDBC fails in the
same shape as one going through the executor.

**`Unit` finds its first use in this library here, and not where ADR-0012 predicted.** That ADR named
this exact call site as a plausible first `Result<Unit>`; RFC-0003 recorded the guess as wrong,
because a transaction runner's failures are infrastructure faults. The type turns out to be right one
layer down — `inTransactionWithoutResult` runs the same machinery as the value form and needs
something non-`null` to hand it. Wrong about the signature, right about the need.

## The question the RFC's sentence does not answer

*"A nested `inTransaction` on the same thread is refused"* — per runner, or one for the JVM? They
differ in exactly one case, and it is not exotic:

```java
orders.inTransaction(c -> audit.inTransactionWithoutResult(a -> { … }));   // two databases
```

The deadlock argument that motivates the whole refusal does **not** apply here: the inner call
borrows from a different pool, so the outer transaction's locks are not in its way. It is refused
anyway, and ADR-0031 records why — two nested transactions with no coordination give **no atomicity**.
If the outer rolls back after the inner commits, the inner's work stands. The shape looks like one
atomic unit and is precisely not one; the sequential form makes that visible in the code, where the
nested form hides it. Refusing something *safer* is not the same as refusing something *correct*.

## The first harness owed outside core, and the proof it can fail

Items 3.1, 4.1 and 4.3 each recorded that a **stateless** type owes no jcstress harness — one over
`Validator`, `JsonMapper` or `SimpleJdbcExecutor` would be measuring somebody else's library. The
nesting detector is the first real per-thread state here, so spec §6's rule finally bites:
`JdbcTxNestingIsolationStress` runs two threads through one runner, and both must complete.

**Then it was made to fail once, on purpose.** Replacing the `ThreadLocal` with a plain
`static boolean[]` is a change **no sequential test in this repository detects** — the nesting tests
all still pass, because on one thread the shared and per-thread versions behave identically — and it
turns the harness red with exactly the outcomes it names:

```
ok, refused        1   25,00%   Forbidden
refused, ok        1   25,00%   Forbidden
```

The real implementation was then restored and the harness is green: **14/14 on JDK 21 and 28/28 on
JDK 17**, the same per-toolchain doubling core's harnesses have always shown. A harness that cannot
fail proves nothing, and the only way to know which kind you have is to break the thing it watches.

The detector's only failure direction is a **false refusal**, which is the reason it needed a harness
at all: an `IllegalStateException` reading *"this thread is already inside a transaction"* looks
exactly like a caller's bug, and an on-call engineer would spend the outage reading the wrong code.

## The failure-while-failing table, row by row

RFC-0003's four rows are implemented and tested individually, and one of them is where doing the
obvious thing would be wrong.

| Situation | Behaviour |
|---|---|
| callback threw, rollback succeeded | the callback's `Throwable` propagates |
| callback threw, **rollback also threw** | the original propagates with the rollback failure `addSuppressed` |
| commit threw | `JdbcAccessException`, **after an attempted rollback** so nothing is left open |
| restore or close threw **after a successful commit** | logged at `WARNING` and **swallowed** |

The last row is why the connection is deliberately **not** held in a try-with-resources: the language
would otherwise let a failing `close` replace the value the transaction just committed. The work is
committed, and reporting a failure invites a retry that applies it twice — strictly worse than a lost
log line.

None of that is observable after the fact, so `ScriptedConnection` is what makes it assertable: a
recording proxy over a **real** H2 connection that can be told to refuse one named call — including
only the *second* `setAutoCommit`, since the runner makes two and only the restore is the subject of
a row. Ordering is asserted as a subsequence, `setAutoCommit(false)` → `setTransactionIsolation` →
`commit` → `setTransactionIsolation(previous)` → `setAutoCommit(true)` → `close`.

## Smaller things worth carrying forward

- **C-01 reaches a log line.** The two lines FR-06 specifies carry the failure's **type name** and an
  SQLState, never its message — which is where a driver puts the failing statement. Asserted against
  a body whose exception message holds a credential, and the same assertion catches an unrendered
  `MessageFormat` placeholder (ADR-0014's trap, which item 3.2 measured). This is the weaker of
  C-01's two boundaries precisely because nobody reviews a log line for disclosure.
- **`TxIsolation.DEFAULT` means untouched**, and a test asserts `setTransactionIsolation` is never
  called at all — not that it is called with "read committed".
- **The retained-connection obligation is demonstrated, not asserted**, which is what RFC-0003 asked
  for: `aRetainedConnectionIsDead` captures the connection out of the body and shows it closed and
  unusable. Item 2.4's leaky-subclass treatment, applied to an obligation the library cannot enforce.

## Where the project stands

**Milestone 4 has one item left.** 4.0–4.4 are complete; **4.5 (`PageRequest` / `PageResponse<T>`)**
is the last, and it is the only one of the five whose contract touches no I/O at all.

## What the next session needs to know

- **Item 4.5 closes the other half of the injection story.** A column name cannot be a bind parameter
  in any database, so `SimpleJdbcExecutor` cannot defend an `ORDER BY`; FR-07's allowlist is the
  control for the threat model's second SQL row, which is still ▢ next to item 4.3's green one.
- **4.5 has three refinements already decided by RFC-0003** and worth re-reading rather than
  re-deriving: the whitelist is supplied at *validation* time rather than construction, a violation
  throws `ValidationException` (not `IllegalArgumentException`, which FR-19 would route to 500), and
  the comparison is exact and case-sensitive so the library does not pick a vendor's identifier
  folding.
- **Item 7.1's obligation list is unchanged at four**, but the cause-chain rule now has three
  independent demonstrations behind it: Jackson's `InvalidFormatException`, H2's `SQLException`, and
  — new today — the fact that this library's own *log* lines had to be written to avoid the same
  channel.
- **`d4np-jdbc` now runs jcstress**, so `-Pjcstress verify` costs more than it did. Item 4.5 adds no
  state and should not add a harness; saying so will be the fourth time that reasoning is recorded,
  which is itself worth noticing.
