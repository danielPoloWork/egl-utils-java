# 2026-08-30 — FR-10's interface, and a token that is safer absent (ROADMAP item 5.3)

**Milestone 5 closes.** `DistributedLock`, `LockHandle` and `DistributedLockException` land in
`d4np-concurrent`, with [ADR-0037](../../../adr/0037-a-fencing-token-that-restarts-is-worse-than-none.md)
and [ADR-0038](../../../adr/0038-refuse-the-convenience-form-the-rfc-sanctioned.md).

## What an interface-only item can actually deliver

Nothing here ships an implementation — ADR-001 keeps it out so no consumer of the concurrency
utilities drags a backend client. So the deliverable beside the three types is a **contract test run
against a reference `InMemoryDistributedLock` in test scope**, which is the only way to show an
interface-only contract is satisfiable at all. Item 4.3 used a hand-written `DataSource` the same
way; FR-10's *interface only* rule is about `src/main`.

The clock is injected, so a lease expires without sleeping. That is what makes the load-bearing test
deterministic rather than timing-dependent — and that test is the one worth naming:

**`aStaleHolderDoesNotReleaseTheCurrentHolder`.** A holder's lease expires, a second process acquires
the same key, and the first then finishes and calls `close()`. A `DEL key` implementation deletes the
*second* holder's lock. Replacing the owner-compared release with a plain `remove(key)` turns **that
one test red and no other** — item 4.4's falsifiability rule applied to a contract test instead of a
jcstress harness.

## A fencing token that restarts is worse than one that is absent

RFC-0004 said empty means *"this implementation cannot keep mutual exclusion across a lease expiry"*.
That is necessary and not sufficient: it does not say what to do when the counter is monotonic **while
the implementation runs** and resets when it does not.

| Token | What the protected resource does | Outcome |
|---|---|---|
| absent | rejects the design, or accepts the risk knowingly | the risk is visible |
| present, monotonic | rejects the stale writer | protected |
| **present, restarted** | **accepts the stale writer** | worse than none, and silent |

The third row is the point. A restarted counter *looks* like a guarantee. So ADR-0037 requires empty
— ADR-0028's rule on a second field: `JdbcAccessException` refuses to fabricate a SQLState no driver
raised, because a field means what the backend said.

The other half is that the token protects nothing on its own: the **resource** must record the
highest it has accepted and refuse anything lower. This library cannot enforce that, so the Javadoc
says it in the imperative — item 4.4's treatment of an obligation the library cannot check.

## Declining a method the RFC explicitly permitted

RFC-0004 §Alternatives 6 sketches a callback convenience form and permits item 5.3 to add it. Two
measurements say not yet:

- **`Optional.ofNullable(null)` is `Optional.empty`.** So a `<T> Optional<T> tryRunExclusively(…)`
  cannot tell *"someone else holds the lock"* from *"I held it, ran, and my body returned null"* —
  opposite reactions, one value. Exactly the collision C-02 exists to prevent, and one `tryAcquire`
  does not have.
- **The value/void pair completes a three-item pattern, and arity is what decides it:**

| Pair | Parameters | Result |
|---|---|---|
| `TxCallback<T>`/`TxVoidCallback` (4.4) | 1 | **ambiguous** |
| `Supplier<T>`/`Runnable` (5.2) | 0 | resolves, diverges silently on body syntax |
| `Function<H,T>`/`Consumer<H>` (5.3) | 1 | **ambiguous** |

Met three times now, so it is a rule rather than a surprise: *a value/void pair over two functional
interfaces is never the right published shape here.*

Filed on item 7.3 with the shape to start from — the **void-only** form has neither problem. Adding
later is MINOR; the deferral costs nothing.

**And the hazard the convenience form would remove is smaller here than item 4.4's**, which is worth
recording because it is what made the RFC's sketch attractive: a leaked `Connection` is held until the
pool starves, whereas a leaked `LockHandle` is released by the backend at the lease — *because FR-10
makes the lease mandatory* — so it self-heals.

## Smaller things worth carrying forward

- **The key is carried beside the message, not inside it.** The RFC says *never the key in full*, but
  truncating `order:tenant-42:user-7` protects a log line's shape and not the tenant. So
  `getMessage()` names only the operation — a closed set, never caller text — and the failure's type,
  while `key()` carries the bounded value. RFC-0001's `message`-versus-`cause` split: correlating a
  log line becomes a decision rather than an accident.
- **The bounding is inside the type because every thrower is in another module**, so a rule each of
  them must remember is advisory by construction (ADR-0022, ADR-0034's shape).
- **No jcstress harness, and for the first time not because the type is stateless.** Items 3.1, 4.1,
  4.3 and 4.5 each recorded that; here the module **ships no implementation**, so a harness would
  measure a fixture. Mutual exclusion is asserted under contention — sixteen racers, one winner — and
  proving it against a backend is item 7.3's.
- **The threat model gains a row and closes one, for the first time in a single item.** The mandatory
  lease closes *"lock held forever"*; it *creates* the converse, *"a lease expiring mid-critical-
  section"*, which is 🚧 rather than ✅ because two residuals are real.

## Where the project stands

**Milestone 5 is complete.** M6 (`security`) and M7 (`adapters and test support`) remain, and both
begin with an RFC item — 6.0 and 7.0. Item 8.3 is still pullable forward at any time.

## What the next session needs to know

- **Item 4.5's deferred extraction has reached its fourth call site and is filed as item 8.10.** Item
  5.1 correctly declined to count itself (a pool name is a developer constant, not client input); a
  lock key is client-derived, so this one counts. The original objection is weaker than it was —
  only the *cap* differs, and a cap is an argument — but the cost is unchanged: the helper must be
  exported from core and is MAJOR-locked at 1.0. Decide before the freeze.
- **M6 opens with FR-11/FR-12, and ADR-0034's shape is the precedent to copy or argue with** — item
  4.5's journal flagged `CryptoException` and the JWT failures as the same question, and item 5.3 has
  now applied it a second time in `DistributedLockException`.
- **`d4np-concurrent` is feature-complete** at 11 public types across FR-08, FR-09 and FR-10, with
  zero third-party dependencies at any scope.
