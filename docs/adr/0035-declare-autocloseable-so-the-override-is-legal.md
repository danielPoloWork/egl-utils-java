# ADR-0035: Declare `AutoCloseable` explicitly, because it is the guard rather than the method

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** tech-lead (implementation of ROADMAP item 5.1), owner
- **Related:** [RFC-0004](../rfc/0004-concurrency-contracts.md) §FR-08 *close(), and the JDK 19
  default that silently replaces it* (the three bullets this narrows);
  spec [§2 FR-08, NFR-05](../specs/01_spec_utils.md);
  [ADR-0024](0024-take-a-jackson-type-in-one-signature.md) (the precedent: an approved RFC's claim
  replaced by a measurement rather than by a better argument);
  [ADR-0025](0025-render-java-time-as-iso-8601.md) (recording a divergence from an approved RFC
  rather than amending it);
  [ADR-0009](0009-errorprone-nullaway-on-jdk-21-cells.md) (the ErrorProne configuration that makes
  `@Override` mandatory here)

## Context

RFC-0004 §FR-08 pins `ManagedThreadPool`'s lifecycle against a real JDK skew: `ExecutorService`
became `AutoCloseable` in **Java 19**, with a default `close()` that calls `shutdown()` and then
`awaitTermination(1, DAYS)` in a loop. This project compiles at `--release 17`, where the method does
not exist, and ships to consumers running 17 **and** 21. The RFC drew three conclusions from a probe:

1. `close()` must be declared, or a JDK 21 consumer's try-with-resources runs the interface default
   and the configured drain budget is never consulted — measured at 3017 ms against a 3 s task with a
   500 ms budget.
2. `AutoCloseable` must be declared too, or a JDK **17** consumer cannot write try-with-resources at
   all.
3. **`@Override` on that `close()` is a compile error at `--release 17`**, so the one marker that
   would tell a reader the method is an override cannot be written — and the method therefore reads
   as removable convenience, whose deletion silently restores the one-day drain.

**Conclusion 3 is true of a shape the RFC does not specify.** The RFC's probe measured a class
implementing `ExecutorService` *alone*. The type the RFC actually requires implements
`ExecutorService` **and** `AutoCloseable` — conclusion 2 — and once the second interface is declared,
the supertype method exists at every release level.

This was not found by re-reading the RFC. It was found by **ErrorProne failing the build**:

```
[MissingOverride] close implements method in AutoCloseable; expected @Override
```

## Decision

**`ManagedThreadPool` declares `implements ExecutorService, AutoCloseable` and its `close()` carries
`@Override`.** The interface declaration — not the method — is what holds the guarantee up.

Measured on Temurin 21.0.12.1+1, compiling at `--release 17`:

| Shape | `@Override` on `close()` | Deleting `close()` |
|---|---|---|
| `implements ExecutorService` only | **compile error** — *"method does not override or implement a method from a supertype"* | compiles; inherits the one-day default |
| `implements ExecutorService, AutoCloseable` | **compiles clean**, and ErrorProne's `MissingOverride` at `failOnWarning` makes it **mandatory** | **compile error** — *"is not abstract and does not override abstract method close() in AutoCloseable"* |

So declaring `AutoCloseable` does **three** jobs where the RFC credited it with one:

1. It lets a JDK 17 consumer write try-with-resources (the RFC's conclusion 2).
2. It makes `@Override` legal, which reverses the RFC's conclusion 3.
3. **It makes `close()` an abstract method this class must implement**, so deleting the method fails
   the build rather than silently reverting to the JDK default — which removes the hazard conclusion
   3 was warning about.

## The guard has an expiry date, and that is the part worth carrying

The protection above is a property of the **`--release 17` baseline**, not of the source. Measured
both ways:

| Compiled at | `close()` deleted, `AutoCloseable` still declared |
|---|---|
| `--release 17` (today) | **compile error** — the deletion is caught |
| JDK 21 natively (if the baseline moves) | **compiles**, and inherits the one-day default |

On a 19+ baseline `ExecutorService` supplies a default `close()`, so `AutoCloseable`'s abstract
method is already satisfied and the class no longer has to declare anything. At that point both
guards evaporate together: `@Override` stays legal (so removing the interface no longer breaks the
build) and the method becomes deletable (so removing it no longer breaks the build either).

Raising the JDK baseline is already a **MAJOR** bump under RFC-0001 §Versioning. This is one more
thing that release has to re-check, and it is recorded here rather than left to be rediscovered,
because the failure mode is silent: a pool that drains for a day instead of its configured budget
looks like a hang, not like a regression.

## Consequences

- **RFC-0004 §FR-08's third bullet is superseded by this record**, in the direction that makes the
  code better than the document predicted: the method carries a marker, and its deletion is caught.
  **The RFC is not amended.** Item 2.5 set that precedent and item 4.1 restated it — a document that
  changes to match the code it produced stops being a check on that code — so the RFC keeps its
  measurement and this ADR narrows it.
- **`ManagedThreadPoolTest.declaresAutoCloseableItself` asserts the interface structurally**, so a
  future "redundant on 21" cleanup fails a test as well as the compiler. That matters precisely
  because the compiler's half of the guard is the one that expires.
- **The test RFC-0004 asked for by name could not be written in the form it described**, and this is
  the second place the same skew bites. The RFC asks for try-with-resources over an
  `ExecutorService` variable; **this project's test sources also compile at `--release 17`**, where
  that is *"incompatible types: try-with-resources not applicable to variable type
  (ExecutorService cannot be converted to AutoCloseable)"*. The test therefore dispatches through
  `ExecutorService.class.getMethod("close").invoke(pool)`, which is not a workaround but the faithful
  version: what regresses is `invokeinterface` on `ExecutorService.close()`, and that is exactly the
  dispatch `Method.invoke` performs. On a 17 runtime the lookup throws `NoSuchMethodException`, and
  the test asserts the runtime is below 19 rather than skipping silently.
- **A second try-with-resources test runs over `AutoCloseable`**, which does compile at the baseline,
  covering the interface-dispatch path a consumer on either JDK actually writes.
- No public surface changes: `AutoCloseable` was already required by the RFC, and `@Override` is an
  annotation.

## Alternatives

1. **Keep the RFC's shape — omit `@Override` and suppress `MissingOverride`.** Rejected on cost and
   on honesty: it needs a `@SuppressWarnings` whose justification would have to be *"the annotation
   is illegal"*, which is false in the shape we ship. A suppression that documents a
   non-existent constraint is worse than no comment.
2. **Drop `implements AutoCloseable` and rely on `ExecutorService` supplying it on 19+.** Rejected —
   it is the one declaration doing real work here, and dropping it breaks JDK 17 consumers,
   invalidates the `@Override`, and makes the method deletable. This is the exact edit the structural
   test exists to catch.
3. **Amend RFC-0004.** Rejected on item 2.5's precedent, as above.
4. **Raise the baseline to 21 now, so the skew disappears.** Rejected: it is a MAJOR bump for a
   pre-1.0 library whose stated support matrix is 17 **and** 21, and it would trade a recorded,
   tested hazard for a compatibility break.
