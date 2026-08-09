# ADR-0031: One nesting detector for the whole JVM, so two pools cannot be nested either

- **Status:** Accepted
- **Date:** 2026-08-08
- **Deciders:** tech-lead (implementation of ROADMAP item 4.4), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-06 (*Nesting, and the one place
  ambient state is correct*); spec [§2 FR-06](../specs/01_spec_utils.md) and
  [§6](../specs/01_spec_utils.md) (a thread-safety claim without a named jcstress test is not a
  claim); [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) (a guarantee a consumer can
  switch off is advisory)

## Context

RFC-0003 refuses nesting and says how: *"a nested `inTransaction` on the same thread is refused with
`IllegalStateException`"*, detected by *"a thread-scoped depth counter"*. The reasoning is worked out
in full — suspension needs a manager owning two connections, and a nested call would otherwise take a
second connection and wait on locks the outer transaction holds, on one thread, so a modest pool
hangs with nothing to read.

Implementing it surfaces a question the sentence does not answer: **is the detector per runner, or
one for the JVM?**

They differ in exactly one case, and it is not exotic — an application with two databases:

```java
JdbcTxRunner orders = JdbcTxRunner.on(ordersPool);
JdbcTxRunner audit  = JdbcTxRunner.on(auditPool);

orders.inTransaction(c -> audit.inTransactionWithoutResult(a -> { … }));   // legal? 
```

A per-runner detector allows it. The deadlock argument does not apply — the inner call borrows from a
*different* pool, so the outer transaction's locks are not in its way.

## Decision

**One detector, `static`, shared by every `JdbcTxRunner` in the JVM.** The two-pool nesting above is
refused with the same `IllegalStateException` as the single-pool case.

**It is a flag, not a counter**, and that is a deliberate narrowing of RFC-0003's wording rather than
a misreading. Nesting is refused, so the depth can only ever be 0 or 1: a counter would be a counter
of one. The flag buys something a counter does not — `ThreadLocal.remove()` clears the entry
outright, where a decrement leaves a thread permanently marked if an increment ever goes unbalanced,
and these threads come from pools that outlive the transaction by hours.

## Alternatives Considered

- **A per-runner detector, allowing two pools to nest.** The permissive reading, and rejected on what
  it would actually be offering. Two nested transactions with no coordination give **no atomicity**:
  if the outer rolls back after the inner commits, the inner's work stands. What the shape *looks*
  like — one atomic unit — is precisely what it is not, and a library that accepts the syntax
  endorses the reading. RFC-0003 declined to build a transaction manager; this is the smallest place
  that decision has to be honoured, because the alternative is a false comfort rather than a missing
  feature. The deadlock argument being inapplicable makes the two-pool case *safer*, not *correct*.
- **Refuse across pools, but allow it behind an opt-in flag.** Rejected on ADR-0022's rule: a
  guarantee a consumer can switch off is advisory. It would also mean shipping the semantics of a
  distributed transaction under a boolean.
- **Detect the same *pool* rather than any transaction**, so one-pool nesting is refused and two-pool
  nesting allowed. Rejected as the worst of the three: it makes whether a call is legal depend on
  whether two `DataSource` references happen to be the same object, which is a wiring detail a
  caller reading the block cannot see.
- **A counter, as RFC-0003 worded it.** Rejected on the unbalanced-increment failure above. The
  observable behaviour is identical, so this is an implementation narrowing rather than a contract
  change — recorded here so a reader comparing the code against the RFC finds the difference
  explained rather than has to notice it.

## Consequences

- **An application with two databases must sequence its transactions rather than nest them**, and the
  refusal says so at the first run rather than at the first partial failure. That is a real
  restriction and it is the point: the sequential form makes the absence of atomicity visible in the
  code, where the nested form hid it.
- **The detector is real per-thread state, which is what makes item 4.4 the first here to owe a
  jcstress harness.** Items 3.1, 4.1 and 4.3 each recorded that a *stateless* type owes none —
  a harness over `Validator`, `JsonMapper` or `SimpleJdbcExecutor` would be measuring somebody else's
  library. `JdbcTxNestingIsolationStress` proves two threads transacting through one runner never
  observe each other's flag.
- **The harness was shown to be falsifiable rather than assumed to be.** Replacing the `ThreadLocal`
  with a plain `static boolean[]` — a change no sequential test in this repository detects, including
  the nesting tests, which still pass — turns the harness red with exactly the outcomes it names
  (`refused, ok` and `ok, refused`, at 25% each). A harness that cannot fail is a harness that proves
  nothing, so it was made to fail once on purpose.
- **A false refusal is the detector's only failure direction**, which is why it needs the harness at
  all: an `IllegalStateException` reading *"this thread is already inside a transaction"* looks
  exactly like a caller's bug, and an on-call engineer would spend the outage looking at the wrong
  code.

## References

- [RFC-0003 §FR-06](../rfc/0003-jdbc-and-json-contracts.md) — the refusal, and why ambient state is
  correct for a detector and wrong for transport.
- [`JdbcTxRunner.java`](../../d4np-jdbc/src/main/java/it/d4np/utils/jdbc/JdbcTxRunner.java) — the
  flag, and the comment on it that names this record.
- [`JdbcTxNestingIsolationStress.java`](../../d4np-jdbc/src/jcstress/java/it/d4np/utils/jdbc/JdbcTxNestingIsolationStress.java)
  — the harness spec §6 asks for.
