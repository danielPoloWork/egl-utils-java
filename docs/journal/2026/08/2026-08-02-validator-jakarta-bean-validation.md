# 2026-08-02 — `Validator`, and the message that would have carried the password (ROADMAP item 3.1)

**Milestone 3, item 3.1 — the first code written against RFC-0002.** The type is small; the decision
inside it is not, and it is the one thing the RFC left to the implementation.

## What changed

`Validator` and `ValidationException` land in `d4np-core` (196 → **216** tests), wrapping Jakarta
Bean Validation 3.x behind `Result`/`ErrorDetail`. `core` gains its **first third-party dependency
edge ever** — `jakarta.validation-api` at `provided` scope, `requires static jakarta.validation` in
the module descriptor — which is the one edge spec §3 permits and NFR-08's allowlist has been holding
open since item 1.7. [ADR-0020](../../../adr/0020-render-violations-from-the-message-template.md)
records the rendering decision; the patterns catalogue gains an **Adapter** row; compliance control
**C-01** gains its first mechanically-enforced call site.

## The decision that mattered

RFC-0002 says a rendered violation carries "the property path and the message template only". It does
not say which Jakarta accessor produces it, and the readable one is the leaking one:
`ConstraintViolation.getMessage()` is interpolated, interpolation resolves `${validatedValue}`, and
quoting the rejected value is the most common reason anyone writes a custom constraint message at
all. So `@Pattern(message = "${validatedValue} is not a valid password")` renders `hunter2 is not a
valid password`, which FR-19 turns into an RFC 7807 body and hands to the HTTP client.

`getMessageTemplate()` is source text and cannot contain a runtime value. The cost is real and is
recorded rather than glossed: a caller sees `name:
{jakarta.validation.constraints.NotBlank.message}`, not prose. Resolving that template against a
`ValidationMessages` bundle is the consumer's localisation step, and FR-19's handler (item 7.1) is
where it belongs.

**The test proves it rather than asserting it.** `ValidatorTest.neverCarriesTheRejectedValue`
validates an object whose constraint message asks for the value by name, and asserts `hunter2`
appears in none of the three exits — `validate`'s `ErrorDetail`, `violations`, and the
`ValidationException` from `requireValid`.

## Two smaller things worth carrying forward

- **A provider-configured interpolator would not have been enough.** Hibernate Validator's
  `ParameterMessageInterpolator` fixes the factory *we* build; `Validator.using(..)` accepts a
  host-configured validator whose interpolator is the host's business. A guarantee that holds for one
  of two constructors is not a guarantee — so the rule lives in the rendering, not in the
  configuration.
- **`requires static` means a missing provider is a runtime condition**, so `create()` catches
  `RuntimeException` *and* `LinkageError` and refuses at construction with a message naming both
  artifacts. Both shapes are tested through the package-private `fromProvider` seam, because the rest
  of the suite needs a provider on the classpath and the failure cannot be reproduced by removing it.

## Where the project stands

Milestone 3 has **2 of 4 items closed**. 3.2 (`ExecutionTimeMetricAspect`) and 3.3 (`AuditLog`) are
next; both are pinned by RFC-0002 and neither depends on this item.

## What the next session needs to know

- **`Validator` is the precedent for every later `provided`-scope dependency.** The shape is:
  version pinned in the *module's* POM (not the parent), the artifact named in that module's enforcer
  allowlist, `requires static` in the descriptor, and a construction-time refusal that names what is
  missing. `d4np-json` (Jackson) and `d4np-spring-adapter` (Micrometer, per RFC-0002 §FR-15) will each
  want it.
- **3.0's `Unit` still has no call site**, exactly as RFC-0002 predicted: `validate` returns
  `Result<T>` with a payload. `AuditLog.record` throws, so item 3.3 will not produce one either.
- **Two CI jobs cannot be reproduced locally yet**, and neither is new to this item: `japicmp:cmp`
  resolves no plugin (item 8.1 owns wiring it) and `-Pcoverage` matches no profile, so Maven warns and
  runs an ordinary `verify` (item 8.2 owns JaCoCo/PIT). Anyone reading a green local `-Pcoverage` as
  a coverage gate is reading a vacuous green — the same class of defect items 1.2, 1.7 and 1.8 each
  found once.
