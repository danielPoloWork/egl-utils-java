# ADR-0038: Refuse the convenience form RFC-0004 sanctioned, because its return type cannot say what happened

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** tech-lead (implementation of ROADMAP item 5.3), owner
- **Related:** [RFC-0004](../rfc/0004-concurrency-contracts.md) §FR-10 *Surface* (which permits this
  method) and §Alternatives 6 (which sketches it);
  [ADR-0032](0032-name-the-void-transaction-form-differently.md) (item 4.4's ambiguity, the first of
  the three measurements below);
  [ADR-0036](0036-carry-context-through-an-spi-that-restores.md) (item 5.2's, the second);
  [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) and compliance control
  **C-02** (a failure never crosses a boundary as `null`);
  [ADR-0030](0030-the-two-channels-out-of-a-transaction-body.md) (one meaning per channel)

## Context

RFC-0004 §FR-10 pins `DistributedLock` at **one** method and adds:

> a convenience form is a default method item 5.3 **may** add without widening what an implementer
> owes.

Its §Alternatives 6 sketches it: a callback form, `runExclusively(key, lease, wait, body)`, which
would make the leaked-handle mistake unavailable in the common case exactly as item 4.4's
`inTransaction` did for transactions. The RFC rejected it as the *sole* form and left it available as
an addition.

Building it surfaced two problems, both measured, and neither visible when the RFC was written.

## Decision

**No convenience default method ships in item 5.3.** `DistributedLock` publishes exactly
`tryAcquire`, and the addition is filed as a roadmap item for the milestone that implements the
interface, when a real call site exists to shape it.

### 1. The natural return type cannot distinguish "not acquired" from "the body returned null"

The obvious signature is `<T> Optional<T> tryRunExclusively(…, Function<LockHandle, T> body)`, so
that a caller who did not get the lock and a caller whose body produced a value are told apart the
same way `tryAcquire` tells them apart. Measured:

```
Optional<T> return: 'lock not acquired' equals 'body returned null' ?  true
  notAcquired      = Optional.empty
  bodyReturnedNull = Optional.empty
```

They are the same value. A caller cannot tell *"someone else holds the lock"* from *"I held it, ran,
and my body returned null"* — two outcomes that demand opposite reactions. That is precisely the
collision **C-02** exists to prevent, and the collision `tryAcquire` does not have, because `empty`
there has exactly one meaning.

The available repairs each cost more than the convenience is worth: refusing a `null` body result
imposes a restriction `tryAcquire` does not, and a two-field result type publishes a class to
paper over a signature.

### 2. The value/void pair does not compile, and the reason completes a three-item pattern

A convenience form wants a void sibling, which means a `Function`/`Consumer` overload pair. Item 4.4
found the same shape ambiguous over `Connection` and renamed (ADR-0032); item 5.2 found the *nullary*
shape resolved but diverged silently on body syntax (ADR-0036). Measured here, at `--release 17`:

| Pair | Parameters | Result |
|---|---|---|
| `TxCallback<T>` / `TxVoidCallback` (item 4.4) | 1, a `Connection` | **ambiguous** — *reference to inTransaction is ambiguous* |
| `Supplier<T>` / `Runnable` (item 5.2) | 0 | **resolves**, and silently changes the returned type when a body gains braces |
| `Function<H,T>` / `Consumer<H>` (item 5.3) | 1, a `LockHandle` | **ambiguous** — *both method `<T>run(Function<Handle,T>)` and method `run(Consumer<Handle>)` match* |

**The three measurements agree, and the arity is what differs.** The unary pairs fail to compile; the
nullary one compiles and diverges. Neither outcome is acceptable in a published surface, and the
remedy — distinct names — is already established (`inTransactionWithoutResult`, `supply`/`run`). What
this record adds is that the pattern is now predictable rather than a surprise met three times: **a
value/void pair over two functional interfaces is never the right published shape in this library.**

### Why the deferral is cheap and the addition is not

Adding a method later is **MINOR**; removing one is **MAJOR** (RFC-0001 §Versioning). Item 4.5 took
the same reversible direction with `PageRequest.offset()` — added, because the caller's version had a
bug in it — and the inverse applies here: no caller exists yet, so nothing is being made harder.

The leaked-handle hazard the convenience form would remove is also **smaller here than it was for
transactions**, and that is worth stating because it is the argument that made the RFC's sketch
attractive. A leaked `Connection` is held until the pool starves. A leaked `LockHandle` is released
by the backend at the lease, **because FR-10 makes the lease mandatory** — so the hazard is bounded
by a guarantee this interface already enforces, and self-heals. That is a genuinely weaker case for
forcing a callback shape than item 4.4 had.

## Consequences

- **`DistributedLock` has exactly one abstract method**, asserted structurally by
  `publishesExactlyOneAbstractMethod`, so a convenience method added later must be a `default` —
  costing implementers nothing — rather than a new obligation on every implementation.
- **Filed as a roadmap item** rather than dropped, with the two measurements attached, so the
  implementation milestone re-decides it with a call site in hand instead of rediscovering the
  `Optional` collision.
- **RFC-0004 is not amended.** It permitted the method rather than requiring it, so declining is
  inside the contract; and item 2.5's precedent holds regardless — a document that changes to match
  the code it produced stops being a check on that code.
- **The catalogue gains nothing.** The callback shape is not a named pattern here, and recording a
  rejection of something that was never adopted would inflate the catalogue rather than inform it.

## Alternatives

1. **Ship it with `Optional<T>` anyway** and document the collision. Rejected: the documentation
   would have to say "empty means one of two opposite things", which is a defect with a footnote.
2. **Ship it returning a two-field result type** (`Acquired<T>` / `NotAcquired`). Rejected on cost —
   a published type, a japicmp baseline entry and a name to live with, so that a caller can avoid one
   `try`-with-resources.
3. **Ship only the void form** (`boolean tryRunExclusively(…, Consumer<LockHandle>)`, returning
   whether the lock was taken). This one actually works, and it is the shape the filed item should
   start from — it is deferred with the rest rather than shipped alone, because a surface that offers
   the void convenience and not the value one invites the question at every call site.
4. **Wait for the value/void pair to become unambiguous in a future Java release.** Not a plan.
