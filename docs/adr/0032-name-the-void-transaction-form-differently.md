# ADR-0032: Name the void transaction form differently, because the overload pair does not compile

- **Status:** Accepted
- **Date:** 2026-08-08
- **Deciders:** tech-lead (implementation of ROADMAP item 4.4), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-06 *Surface* (the table this
  diverges from); spec [§2 FR-06](../specs/01_spec_utils.md);
  [ADR-0021](0021-time-through-an-advice-body-core-can-own.md) (a specified name kept while its shape
  changed); [ADR-0025](0025-render-java-time-as-iso-8601.md) (the precedent for recording a
  divergence from an approved RFC rather than amending it);
  [ADR-0030](0030-the-two-channels-out-of-a-transaction-body.md) (why a void form has to exist at
  all)

## Context

RFC-0003's §FR-06 surface table gives both run methods the same name:

| run | `<T> T inTransaction(TxCallback<T> body)` |
| run | `void inTransaction(TxVoidCallback body)` |

That is the natural design and it does not compile for a caller. Two overloads over two functional
interfaces — one whose function type returns a value, one whose returns `void` — make an **implicit
lambda whose body is a statement expression with a value** applicable to both, and Java's
most-specific rules do not break the tie.

Measured, before anything was decided, with six call shapes compiled against the pair:

| Call shape | Result |
|---|---|
| `runner.inTransaction(c -> { doWork(c); });` — block, no value | resolves to the void form |
| `String s = runner.inTransaction(c -> "value");` | resolves to the value form |
| `runner.inTransaction(c -> executor.update(sql, id));` | **`reference to inTransaction is ambiguous`** |
| `runner.inTransaction(Probe::justRuns);` — void method reference | resolves |
| `runner.inTransaction(Probe::returnsInt);` — value method reference | resolves |
| `Integer n = runner.inTransaction(c -> { return f(c); });` | resolves |

**One shape breaks, and it is the most idiomatic single-statement transaction there is.** A caller
who writes it is told *"reference to inTransaction is ambiguous"* — a message that names neither the
missing brace nor the two candidates in any useful way, for a line that looks obviously correct.

This is not a novel problem. `ExecutorService.submit(Runnable)` versus `submit(Callable<T>)` is the
JDK's own instance of it and a long-standing papercut. More to the point, **Spring hit it in exactly
the type FR-06 points Spring users at**: `TransactionTemplate.execute(TransactionCallback<T>)` gained
a differently named sibling, `executeWithoutResult(Consumer<TransactionStatus>)`, in Spring 5.2 for
this reason.

## Decision

**The value form keeps `inTransaction`. The void form is named
`inTransactionWithoutResult`.**

The name is Spring's `executeWithoutResult` translated into this API's vocabulary, so a reader
arriving from `TransactionTemplate` — which is the population FR-06 explicitly addresses — recognises
the shape rather than learning a local invention.

No overload is kept for compatibility: keeping one would reintroduce the ambiguity it exists to
remove. There is nothing to be compatible with, since neither method has ever been released.

## Alternatives Considered

- **Keep both overloads and document the brace.** The literal reading of the RFC, and rejected on
  who pays. The workaround is two characters and invisible until you know it; the error message does
  not teach it; and every consumer meets the shape eventually. A compile error is the *safe* kind of
  failure, which is why this is a usability decision rather than a correctness one — but "it fails
  safely and confusingly" is not the bar this project sets.
- **Drop the void form and let callers return something.** Rejected: with `null` refused
  (ADR-0030), every void body would end `return Unit.INSTANCE;`, which leaks an internal placeholder
  into every call site to save a method name.
- **Name it `inTransactionVoid` or `runInTransaction`.** Rejected on recognisability rather than
  taste. `Void` names a Java type that is not involved; `runInTransaction` reads as a *different*
  operation rather than a variant of the same one, and puts the two methods far apart in an
  alphabetical member list.
- **Amend RFC-0003's surface table.** Rejected on the precedent item 2.5 set and items 4.1 and 4.3
  followed: an approved RFC is not edited by the agent implementing it, because a document that
  changes to match the code it produced stops being a check on that code. An ADR that records the
  divergence and points at it is the mechanism AGENTS.md §7 provides.

## Consequences

- **The published surface differs from an approved RFC's table by one method name**, which is the
  cost, and it is stated in the Javadoc at the point of use rather than only here — the method's own
  documentation shows the call that would not have compiled.
- **Every shape a caller can write now resolves**, and the six-shape probe that established the
  problem is kept as a compiled fixture rather than deleted, so a future overload cannot
  reintroduce the ambiguity unnoticed.
- **The rename caught a second defect the moment it landed**, which is worth recording because it
  argues for the tests rather than for the rename: the runtime message for a `null` return still
  read *"use inTransaction(TxVoidCallback)"* — naming a method that no longer existed — and
  `rollsBackAndRefusesANullReturn` failed on the string. A message pointing at a method nobody can
  call is the kind of rot no compiler reports.
- **`TxVoidCallback` keeps its name**, since nothing about the interface was ambiguous; only the
  method that accepts it was.

## References

- [RFC-0003 §FR-06 *Surface*](../rfc/0003-jdbc-and-json-contracts.md) — the table this diverges from.
- [`JdbcTxRunner.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/JdbcTxRunner.java) — the
  method, and the call it documents as the reason for its name.
- [`TxCallShapes.java`](../../d4np-jdbc/src/test/java/it/d4np/utils/jdbc/TxCallShapes.java) — the six
  shapes, kept compiled.
- Spring Framework `TransactionTemplate.executeWithoutResult` — the same problem solved the same way
  in the type FR-06 defers to.
