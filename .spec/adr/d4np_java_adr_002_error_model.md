# ADR-002: Error model — `Result<T>` for expected outcomes, unchecked `BusinessException`

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-14 |
| **Related spec** | [d4np-java.md](../d4np-java.md) (§2 items 17–19, §5) |

## Context
v1 defined both `Result<T>` (item 17) and a **checked** `BusinessException` (item 18) with no rule for when either applies — two competing error channels whose interaction was unspecified. This is the decision consuming teams fight about most; a shared library must settle it, not export the ambiguity.

## Options considered

**A. `Result<T>` for expected business outcomes; `BusinessException` unchecked, for use-case-aborting violations** *(chosen)*
- ✅ Clear rule of thumb teams can apply mechanically: *if the caller is expected to branch on it* (insufficient funds, duplicate email), it is a `Result.Err` with a typed `ErrorDetail`; *if it aborts the use case and only a boundary handler cares* (invariant broken, unauthorized), it is a thrown `BusinessException`.
- ✅ Unchecked keeps signatures clean through streams/`CompletableFuture` chains, where checked exceptions are famously unusable (`AsyncExecutor`, item 9, would otherwise force wrapper boilerplate).
- ✅ The two channels interoperate by design: `result.orElseThrow(detail -> new BusinessException(detail))` at the boundary; `GlobalExceptionHandler` (item 19) maps `BusinessException` → RFC 7807 422.
- ❌ Two mechanisms still exist — mitigated by the mechanical rule above, documented with examples in the spec's §5 contract table.

**B. Checked `BusinessException` everywhere (v1's implicit lean)**
- ✅ Compiler-forced handling.
- ❌ Checked exceptions compose with nothing modern: lambdas, streams, and async pipelines all require sneaky-throw or wrapper noise; twenty years of ecosystem experience (Spring, JPA, modern JDKs) has moved to unchecked for exactly this reason.

**C. `Result<T>` everywhere, no exceptions**
- ✅ Single channel, maximally explicit.
- ❌ Java is not Rust: without `?`-style propagation, deep `flatMap` chains bury business logic; framework boundaries (Spring MVC, Bean Validation) speak exceptions anyway, so the "no exceptions" promise dies at the adapter and the codebase ends up with both channels *without* a rule.

## Decision
**Option A.** `Result<T>` is a sealed type (`Ok`/`Err`) with `map/flatMap/recover/orElseThrow`; `Ok(null)` is forbidden (`Result<Void>` for effects). `BusinessException` extends `RuntimeException`, carries the same `ErrorDetail` type, and is the only exception `GlobalExceptionHandler` maps to 422 — everything else follows the spec's mapping table.

## Consequences
- Service APIs in consuming codebases get a lintable convention: public use-case methods return `Result<T>`; repositories/domain internals may throw.
- `ErrorDetail` becomes the shared vocabulary across both channels and the problem+json wire format — one error taxonomy end to end.
- Reversing this (e.g., a team mandating checked exceptions) means superseding this ADR; the library will not add checked variants.
