# ADR-0037: A fencing token that restarts is worse than none, so empty is a required answer

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** tech-lead (implementation of ROADMAP item 5.3), owner, security-auditor (the tampering
  row this opens)
- **Related:** [RFC-0004](../rfc/0004-concurrency-contracts.md) §FR-10 (the contract this implements
  and extends in two places); spec [§2 FR-10, §3](../specs/01_spec_utils.md);
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (why the implementation is not here);
  [ADR-0028](0028-the-fr-05-operation-set-and-what-it-refuses.md) (**the precedent: a field means
  what the backend said, so do not fabricate one**);
  [ADR-0031](0031-one-nesting-detector-for-the-whole-jvm.md) (refuse rather than block — the
  reentrancy rule inherits it);
  [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) /
  [ADR-0034](0034-mint-a-validation-failure-from-outside-core.md) (bounding inside the type, because
  every thrower is in another module);
  [threat model](../security/threat-model.md) §2 *Tampering* (the row this adds) and *Denial of
  service* (the row this closes)

## Context

FR-10 makes lease time mandatory, which bounds the threat the threat model already records: a
crashed holder does not keep a lock forever, because the **backend** releases it rather than the
holder.

It says nothing about the converse, and the converse is the one that corrupts data. **A lease can
expire while the holder is still running** — a stop-the-world pause, a slow disk or a network
partition is enough. The backend grants the lock to someone else, and two processes each believe
they hold it. Both write.

No lease-based lock prevents this. It is a property of leases, not a defect in Redis or in any other
backend, and the only structural mitigation is a **fencing token**: a value that strictly increases
per key, which the holder passes to the resource it is protecting, and which that resource uses to
reject a write from a stale holder.

**The interface is the deliverable** (ADR-001 keeps implementations out of this module), so a token
absent from `LockHandle` today is a token no implementation can offer tomorrow without a MAJOR break.

## Decision

**`LockHandle.fencingToken()` returns `OptionalLong`**, and RFC-0004 settled that much. This record
pins the two clauses the RFC left as prose, because both are the kind an implementer would otherwise
get wrong in good faith.

### 1. A token that cannot survive the implementation's own restart must be empty

RFC-0004 says empty means *"this implementation cannot keep mutual exclusion across a lease expiry"*.
That is necessary and not sufficient: it does not say what an implementation should do when its
counter is monotonic **while it runs** and resets when it does not.

**It must return empty.** A restarted counter is *more* dangerous than an absent one:

| Token | What a resource does | Outcome |
|---|---|---|
| absent | rejects the design, or accepts the risk knowingly | the risk is visible |
| present, monotonic | rejects the stale writer | protected |
| **present, restarted** | **accepts the stale writer**, whose token happens to exceed the current one | **worse than no token, and silent** |

The third row is the failure mode this clause exists to forbid. A resource that has seen token 900,
faced with a restarted sequence now issuing 5, correctly rejects the *current* holder and keeps
accepting nothing — or, if the restart went the other way, accepts a writer it should have refused.
Either way the token *looked* like a guarantee.

This is [ADR-0028](0028-the-fr-05-operation-set-and-what-it-refuses.md)'s rule applied to a second
field: `JdbcAccessException` refuses to fabricate a SQLState no driver raised, because a field means
what the backend said and writing our own conclusion into it leaves a consumer unable to tell the two
apart. A best-effort token is exactly that fabrication.

### 2. The mitigation is the resource's, and the library says so rather than implying otherwise

A token protects nothing on its own. The resource being written to must record the highest token it
has accepted for a subject and refuse any write carrying a lower one. **This library cannot enforce
that**, and the Javadoc states it in the imperative rather than leaving a reader to infer that
holding a token is protective.

That is the same move item 4.4 made with `aRetainedConnectionIsDead` and item 4.5 with the `ORDER BY`
residual: where a library's guarantee ends at an obligation it cannot check, the obligation is
documented at the point a caller meets it.

### 3. `leaseExpiry()` is an estimate on the local clock, and `isHeld()` is not permission to write

Clock skew between the holder and the backend is precisely the condition that makes leases unsafe, so
`leaseExpiry()` is documented as what the acquiring process computed from *its own* clock, and
`isHeld()` as best-effort and local — it does not consult the backend. `true` means "not known to be
over", never "safe to write". A method that round-tripped would be a different and far more expensive
contract, and one that looked authoritative while racing the network would be worse than one that
admits what it is.

## Consequences

- **The threat model gains a *Tampering* row** — *a lease expiring mid-critical-section, so two
  holders write* — which had none, and the mitigation is the token plus the resource-side obligation,
  with the residual stated. The *Denial of service* row for FR-10 closes on the mandatory lease.
- **`d4np-lock-redisson` inherits a decision it may not like.** Redisson cannot supply a token that
  survives a failover, so it will return empty — which is the honest answer and is what this record
  exists to make unambiguous, so that the implementation milestone does not quietly invent a
  best-effort counter.
- **The contract is tested against a reference implementation in test scope**
  (`InMemoryDistributedLock`), which is how an interface-only item demonstrates its contract is
  satisfiable at all. It ships nothing: FR-10's *interface only* rule is about `src/main`, and item
  4.3 used a hand-written `DataSource` the same way.
- **`aStaleHolderDoesNotReleaseTheCurrentHolder` was shown to fail before it was trusted.** Replacing
  the owner-compared release with a plain `remove(key)` — the classic defect — turns that one test
  red and **no other**, which is item 4.4's rule applied to a contract test rather than a jcstress
  harness.
- **No jcstress harness, and the reason is new.** Items 3.1, 4.1, 4.3 and 4.5 each recorded that a
  *stateless* type owes none. This is the first item to owe none for a different reason: **the module
  ships no implementation**, so a harness here would measure a test fixture. The mutual-exclusion
  property is real and is asserted under contention by the contract test; proving it against a
  *backend* is an obligation the implementation milestone inherits.

## Alternatives

1. **Mandatory `long fencingToken()`.** Rejected in RFC-0004 and re-affirmed: the one planned
   implementation cannot honour it, so the interface would be unimplementable on day one. An
   interface nobody can implement is not a stronger guarantee.
2. **Omit the token entirely** and document the lease-expiry hazard in prose. Rejected: prose does not
   survive into an implementation, and adding the method later is MAJOR for every implementer.
3. **Allow a best-effort token, documented as such.** This is the tempting middle and it is the exact
   failure the decision above forbids — the danger is not that the token is imperfect, it is that a
   resource cannot tell an imperfect one from a sound one.
4. **A token type rather than `long`** (`Optional<FencingToken>`), so the ordering could carry its own
   comparison. Rejected on surface cost: `OptionalLong` allocates nothing and every resource that
   would consume it stores a number. A wrapper adds a published type for no property the `long` lacks.
