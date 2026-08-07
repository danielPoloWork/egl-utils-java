# ADR-0026: Catch and rewrite Jackson's *unchecked* conversion failure, which the wrapping rule does not reach

- **Status:** Accepted
- **Date:** 2026-08-07
- **Deciders:** tech-lead (implementation of ROADMAP item 4.2), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-21 *Errors, and the payload that
  must not travel*; spec [§2 FR-21](../specs/01_spec_utils.md); compliance control **C-01**
  ([register](../compliance/README.md)); [ADR-0020](0020-render-violations-from-the-message-template.md)
  (the same rule for constraint violations);
  [ADR-0015](0015-strategy-registry-last-write-wins.md) (an exception that lands on the wrong status
  code misattributes the fault)

## Context

RFC-0003 states the wrapping rule for this module in one sentence: *"`JsonProcessingException` is
checked, so it is wrapped in `JsonConversionException extends RuntimeException`."* Item 4.1
implemented it for `readValue` and `writeValueAsString`, and the sentence is true for both — Jackson's
failure arrives as the checked exception, the compiler will not let it pass, and `JsonDiagnostics`
rebuilds the message from the target type and the structural path only.

**FR-21's conversion path does not raise that exception.** `ObjectMapper.convertValue` catches
Jackson's own `IOException` internally and rethrows it as an `IllegalArgumentException` carrying
`e.getMessage()` — Jackson's text, verbatim. That text quotes the value it rejected:

```text
java.lang.IllegalArgumentException: Cannot deserialize value of type `int` from String "hunter2":
  not a valid `int` value (through reference chain: …MistypedCredentials["password"])
```

Two things follow, and the second is the reason this is a decision rather than an implementation
detail.

1. **The rule as written does not cover it.** A rule phrased against *the checked exception* is a
   rule the compiler enforces; it says nothing about an unchecked one. Nothing in the language, the
   build, or any gate in this repository would have flagged a `convert` that let the
   `IllegalArgumentException` through. It would have compiled, passed every test that did not look
   for it, and shipped a **C-01 violation on the library's newest public method**.
2. **The status code would have been wrong too.** FR-19's table has no row for
   `IllegalArgumentException`, so it falls to the **500** fallback — reporting a client's
   ill-fitting payload as a server fault. That is the misattribution ADR-0015 recorded, arriving by
   a different route.

Catching `IllegalArgumentException` is not free advice, either. It is a broad type: a serializer a
host registered through `JsonMapper.withModules` may throw one of its own, and it would be caught
here too.

## Decision

**`ObjectMapperExtensions.convert` catches `IllegalArgumentException` from `convertValue` and
rethrows a `JsonConversionException` whose message this library built.** Jackson's message is not
read — not `getMessage()`, not `getOriginalMessage()` — exactly as `JsonDiagnostics` already refuses
to read it on the parse path. The structural path is recovered from the `cause`, which is where
`convertValue` puts the original `JsonMappingException`.

Jackson's exception survives as the `cause`, for the log and for the same reason item 4.1 kept it:
`ErrorDetail` draws the line at caller-facing message versus process-facing cause.

**The wrapping rule is restated in the form that actually holds**, and the restatement is the durable
half of this record: *no exception leaves this module carrying text this library did not write* —
whatever its checked-ness. `d4np-jdbc` (item 4.4) will meet the same shape, because a driver's
`SQLException` message carries the SQL it was given.

## Alternatives Considered

- **Let the `IllegalArgumentException` propagate.** The literal reading of RFC-0003, and rejected
  twice over: it leaks the payload (C-01) and lands on 500 (FR-19). It is also the outcome that
  happens *by default*, which is precisely why it needed a decision rather than a review.
- **Serialize to a string and read it back**, so the failure arrives as the checked
  `JsonProcessingException` the rule was written for. Rejected: it changes the operation to get the
  exception type — a full document is materialised for every conversion, and a value that round-trips
  differently through text than through Jackson's token buffer would convert differently too. Fixing
  a wrapping problem by changing what is wrapped is the wrong lever.
- **Catch the narrower shape by inspecting the cause** — rethrow when the cause is a
  `JsonProcessingException` and let other `IllegalArgumentException`s through. Rejected on what it
  optimises for: it preserves a host serializer's own exception type at the cost of a conditional
  whose false branch is *the leak*. A host serializer that throws `IllegalArgumentException` during a
  conversion has failed the conversion, and reporting that as `JsonConversionException` is accurate;
  its own exception is still the `cause`, so nothing is lost for the log.
- **Amend RFC-0003's sentence.** Rejected on the precedent item 2.5 set and item 4.1 followed: an
  approved RFC is not edited by the agent implementing it, because a document that changes to match
  the code it produced stops being a check on that code.

## Consequences

- **C-01 gains a third enforced call site** (after FR-14's `Validator` and FR-20's `JsonMapper`), and
  it is asserted the way the other two are — with a companion.
  `ObjectMapperExtensionsTest.noConversionMessageCarriesTheValue` proves our message is clean, and
  `theSameConversionLeaksThroughRawJackson` proves raw Jackson leaks `hunter2` on the same input, so
  the first assertion cannot go vacuous behind a conversion that stopped failing.
- **The leak channel is now documented rather than latent.** It is named in the class Javadoc, in the
  compliance register and in the threat model's information-disclosure row, so the next module that
  wraps a provider looks for the unchecked shape as well as the checked one.
- **A host serializer's `IllegalArgumentException` is reported as a conversion failure.** Stated
  because it is a real behavioural consequence, not hidden: the host's exception is the `cause`, and
  the log has everything.
- **Nothing here is enforceable by a gate.** No lint can tell a message that carries a payload from
  one that does not — the same limit the C-01 register already records. The tests are the
  enforcement, which is why they are paired.

## References

- [RFC-0003 §FR-21](../rfc/0003-jdbc-and-json-contracts.md) — the wrapping rule this narrows, and the
  message rule it applies.
- [`ObjectMapperExtensions.java`](../../d4np-json/src/main/java/it/d4np/utils/json/ObjectMapperExtensions.java)
  — the catch, and the comment that names this record at the line where it matters.
- [`JsonDiagnostics.java`](../../d4np-json/src/main/java/it/d4np/utils/json/JsonDiagnostics.java) —
  the single place a failure becomes text, extended here rather than duplicated.
- [ADR-0020](0020-render-violations-from-the-message-template.md) — the same decision for Bean
  Validation, where the leak channel was `getMessage()`'s `${validatedValue}` interpolation.
