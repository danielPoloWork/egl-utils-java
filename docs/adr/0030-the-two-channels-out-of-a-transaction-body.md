# ADR-0030: The two channels out of a transaction body — what propagates, and why `null` is not a value

- **Status:** Accepted
- **Date:** 2026-08-08
- **Deciders:** tech-lead (implementation of ROADMAP item 4.4), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-06 (*What triggers a rollback*,
  *Failure while failing*); [RFC-0001](../rfc/0001-core-contracts.md) §Cross-cutting (no published
  method of this library throws a checked exception); spec [§2 FR-06](../specs/01_spec_utils.md);
  [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) (`Result` cannot carry `null`);
  [ADR-0019](0019-mint-unit-for-the-void-success.md) (the `Unit` prediction this falsifies a second
  time); [ADR-0026](0026-rewrite-jacksons-unchecked-conversion-failure.md) (no exception leaves a
  module carrying text the library did not write); compliance control **C-02**

## Context

RFC-0003 settles the transaction's demarcation rule in one sentence — **the exception channel
demarcates the transaction, the value channel does not** — and spells out three consequences: any
`Throwable` out of the body rolls back, a returned value never does, and a returned `Result.Err`
therefore **commits**. Those are decided and this record does not reopen them.

What it does not settle is what the caller actually catches, and the reason is that two approved
documents cannot both be read literally here.

- **RFC-0003** says the runner *"rethrows the original unchanged"*. It says it while arguing about
  `Error`, which is where the sentence is unarguable.
- **RFC-0001** says no published method of this library declares a checked exception.
- **`TxCallback.run` declares `throws Exception`**, because JDBC's own methods do and a body has to
  be able to call them. RFC-0003 chose that deliberately over `throws Throwable`.

A body may therefore throw a checked exception that `inTransaction` cannot declare. Java offers
exactly two ways out, and **the first was measured rather than reasoned about**:

1. **Sneaky throw** — rethrow the checked exception through a generic cast, so it propagates
   literally unchanged. It costs the caller the ability to catch it: with nothing declared, `try {
   runner.inTransaction(..) } catch (SQLException e)` is a **compile error** — *"exception
   SQLException is never thrown in the body of the corresponding try statement"*. The one exception
   a transaction body is most likely to throw becomes the one the caller cannot name.
2. **Translate** — turn it into something unchecked, keeping the original as the cause.

There is a second, smaller question in the same family. `inTransaction` returns `T` in a
NullAway-annotated package, so the return is implicitly `@NonNull`; a body that returns `null` would
be handing back a value the analyser has already told every consumer cannot happen.

## Decision

**Translation, in four cases, and the table is published on the method rather than buried here:**

| The body threw | The caller catches |
|---|---|
| a `RuntimeException` | that exception, **unchanged** — the same instance |
| an `Error` | that error, **unchanged** — the same instance |
| a `SQLException` | `JdbcAccessException` wrapping it |
| any other checked exception | `java.lang.reflect.UndeclaredThrowableException` wrapping it |

Rows one and two are RFC-0003's sentence in full, and they cover everything the language allows to
travel untouched. Row three is not a special case bolted on: **a `SQLException` is what every other
operation in this module already translates**, so a body doing raw JDBC produces the same failure
shape as one going through `SimpleJdbcExecutor` — with `sqlState()` and `vendorCode()` intact.
Row four uses the JDK's own name for exactly this situation and adds **no public surface**.

**`inTransaction` refuses a `null` return, and rolls the transaction back before it does.** A body
with nothing to return uses `inTransactionWithoutResult`, which exists so that this refusal is
available. Rolling back first matters as much as refusing: committing work whose result is then
rejected would be the worst of both.

## Alternatives Considered

- **Sneaky throw, for literal fidelity to "unchanged".** Rejected on the measured cost above. A
  contract whose most common failure cannot be caught by name is not a better contract for being
  more literal, and callers would be pushed into `catch (Exception e)` plus an `instanceof` chain —
  which is worse than a documented wrapper in every way including fidelity, since it hides the type
  the caller wanted.
- **Declare `throws Exception` on `inTransaction`.** Honest and rejected: it breaks RFC-0001's rule
  for every module, and it forces every call site into `catch (Exception)` even when the body throws
  nothing checked at all.
- **Wrap *everything* checked in `UndeclaredThrowableException`, with no `SQLException` case.**
  Simpler by one branch, and rejected because it makes the module inconsistent with itself: the same
  driver failure would surface as `JdbcAccessException` through the executor and as
  `UndeclaredThrowableException` through the runner, and the SQLState would be two unwraps away.
- **Mint a `TransactionCallbackException`.** Rejected as public surface bought for a rare case.
  `UndeclaredThrowableException` already means precisely *"a checked exception escaped where it was
  not declared"*, and Spring reaches for it in the same place.
- **Allow a `null` return and annotate it `@Nullable`.** Rejected: it forces a null check on every
  caller of the *common* path to accommodate the rare one, and the rare one already has a method.
- **Refuse `null` but commit anyway**, on the grounds that the body succeeded. Rejected: the value
  is the transaction's result, and rejecting the result while keeping its effects is the outcome
  with no defensible reading.

## Consequences

- **Every row of the table is a named test**, and the two "unchanged" rows assert **identity**
  (`isSameAs`) rather than type, because a wrapper that happened to be of the right class would pass
  a weaker assertion.
- **`Unit` finds its first use in this library, and not where ADR-0019 predicted.** ADR-0012 named
  this call site as a plausible first `Result<Unit>`; RFC-0003 recorded that the prediction failed,
  because a transaction runner's failures are infrastructure faults belonging to the unchecked
  shape. The type turns out to be right one layer down: `inTransactionWithoutResult` runs the same
  machinery as the value form and needs something non-`null` to hand it, and `Unit.INSTANCE` is that
  something. The prediction was wrong about the *signature* and right about the *need*.
- **C-02 gains a call site with an unusual shape** — a refusal that must undo work first. Every
  earlier instance (`Ok`, `Lazy`, `readValue`, `RowMapper`) could simply refuse.
- **A caller catching `SQLException` around `inTransaction` still will not compile**, and that is now
  correct rather than surprising: the runner does not throw one. They catch `JdbcAccessException`,
  which the Javadoc table tells them.

## References

- [RFC-0003 §FR-06](../rfc/0003-jdbc-and-json-contracts.md) — the demarcation rule and the
  failure-while-failing table this implements.
- [`JdbcTxRunner.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/JdbcTxRunner.java) — the
  published table, and `asUnchecked` where it is applied.
- [`TxCallback.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/TxCallback.java) — why the
  body may declare `throws Exception` at all.
- [ADR-0026](0026-rewrite-jacksons-unchecked-conversion-failure.md) — the same module-wide rule met
  from the Jackson side one milestone item earlier.
