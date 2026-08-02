# ADR-0020: Render constraint violations from the message template, never the interpolated message

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** tech-lead (implementation of ROADMAP item 3.1), owner
- **Related:** [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §FR-14; spec
  [§2 FR-14](../specs/01_spec_utils.md), §2 FR-19, [NFR-08](../specs/01_spec_utils.md);
  compliance control **C-01** ([register](../compliance/README.md));
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (dependency policy);
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) (error model);
  [ADR-0015](0015-strategy-registry-last-write-wins.md) (why an exception payload is `String`)

## Context

RFC-0002 §FR-14 pins the rule — *"a rendered violation carries the property path and the message
template only"* — and registers it against compliance control C-01. It does not say **which Jakarta
accessor** produces that string, and the two candidates differ in exactly the way the control cares
about.

`ConstraintViolation` offers three readings of the same violation:

| Accessor | Returns | Can it contain the rejected value? |
|---|---|---|
| `getInvalidValue()` | the rejected value itself | **yes, by definition** |
| `getMessage()` | the message **after** interpolation | **yes** — interpolation resolves `${validatedValue}` |
| `getMessageTemplate()` | the message **as the developer wrote it** | no — it is source text, fixed at compile time |

`getMessage()` is the obvious choice and the one every tutorial uses, because it is the readable one.
It is also the leak. A constraint written as

```java
@Pattern(regexp = "\\d{8,}", message = "${validatedValue} is not a valid password")
String password
```

produces, from `getMessage()`, the string `hunter2 is not a valid password`. Under FR-19 that string
becomes the `detail` of an RFC 7807 `application/problem+json` body and is returned to the HTTP
client — a credential in an error response, logged by every proxy on the way. Nothing about the leak
is exotic: `${validatedValue}` is a documented, supported Jakarta EL expression, and quoting the
rejected value is the single most common reason a team writes a custom constraint message at all.

The decision has to be taken here rather than left to the FR-19 handler, because by the time the
`ErrorDetail` reaches the Spring adapter the value is already inside a `String` and no downstream
filter can tell `hunter2` from a legitimate token of the message.

## Decision

**`Validator` renders every violation as `<property path>: <message template>` and reads neither
`getMessage()` nor `getInvalidValue()`.** A bean-level constraint, whose property path is empty,
renders as `<bean>`. The same rendering is the only thing that reaches all three exits of the type —
the `ErrorDetail` of a failed `validate`, the list from `violations`, and the payload of the
`ValidationException` thrown by `requireValid` — so there is one rendering to audit, not three.

The rendered list is **sorted lexicographically** before it is returned: the provider hands back an
unordered `Set`, and an unordered report makes a message assertion flaky and a log line
undiffable.

## Alternatives Considered

- **Use `getMessage()`, and document that constraint messages must not quote the value.** Rejected
  because it is a review promise, not a control: the failing case is a constraint written months
  later in a consumer's own codebase, which no gate of ours ever sees. C-01 is registered as *"an
  error message crossing to a client carries no secrets"* — a rule the library can keep on its own is
  worth more than a rule every consumer has to remember.
- **Use `getMessage()` and strip the invalid value from it afterwards.** Reject-by-scanning: take the
  interpolated message and remove any occurrence of `String.valueOf(getInvalidValue())`. Rejected on
  the same grounds RFC-0002 rejected content-based redaction for FR-16 — a value that appears
  trimmed, truncated, case-folded or partially escaped survives the scan, and the result *looks*
  sanitised, which is worse than a template nobody mistakes for prose.
- **Configure the provider with a non-interpolating message interpolator** (Hibernate Validator's
  `ParameterMessageInterpolator`, or a custom one). Rejected because it only works for the factory
  *we* build: `Validator.using(..)` accepts a host-configured validator — a Spring
  `LocalValidatorFactoryBean` — whose interpolator is the host's business. A guarantee that holds for
  one of two constructors is not a guarantee.
- **Return the `ConstraintViolation` objects and let the caller render.** Rejected twice over: it
  puts a `jakarta.validation` type in the public signature of a module that only `requires static`
  it, and it hands every caller the same loaded gun this decision exists to unload.

## Consequences

- **An uninterpolated template is less readable, and that is the price.** `name:
  {jakarta.validation.constraints.NotBlank.message}` is what a caller sees, not *"name must not be
  blank"*, and a custom message keeps its literal `{max}` placeholders. This is a deliberate trade:
  the string is a machine-readable violation key, and a consumer that wants prose for a human already
  owns the localisation step — it resolves the template against its own `ValidationMessages` bundle,
  which is exactly what the template is for. FR-19's handler is where that resolution belongs, and
  item 7.1 owns it.
- **C-01 gains its first mechanically-enforced call site.** `ValidatorTest.neverCarriesTheRejectedValue`
  validates an object whose constraint message asks for the value by name and asserts the value
  appears in none of the three exits. The control's register row moves from "contract published" to
  a test that fails if the rendering ever changes.
- **The `<bean>` token is now part of the published contract**, because a caller may match on the
  prefix. It is asserted by a stub delegate rather than a custom class-level constraint: the provider
  instantiates a `ConstraintValidator` reflectively, and doing that from a test fixture would mean
  opening `it.d4np.utils` to an automatic module for the sake of one token.
- **`Validator` is catalogued as an Adapter** (structural): Bean Validation's vocabulary —
  `ConstraintViolation`, `ValidatorFactory`, an unordered `Set` — is translated into this library's
  own (`Result`, `ErrorDetail`, an ordered `List<String>`), and the translation is the layer where
  the C-01 rule is applied once for every caller.
- **The provider stays optional.** Core declares `jakarta.validation-api` at `provided` scope and
  `requires static jakarta.validation` (NFR-08, ADR-001), so a consumer that never validates carries
  nothing; the cost is that a missing provider is a runtime condition, which `Validator.create()`
  turns into an `IllegalStateException` at construction naming both artifacts rather than a
  `NoClassDefFoundError` from the first validated call.

## References

- [RFC-0002 §FR-14](../rfc/0002-cross-cutting-contracts.md) — the contract this record implements.
- Jakarta Bean Validation 3.0, §5.1 `ConstraintViolation` and §6.3.1 message interpolation —
  `${validatedValue}` is a standard interpolation variable, not a provider extension.
- [`Validator.java`](../../d4np-core/src/main/java/it/d4np/utils/Validator.java),
  [`ValidationException.java`](../../d4np-core/src/main/java/it/d4np/utils/ValidationException.java).
- Compliance register, control [C-01](../compliance/README.md).
