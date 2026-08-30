# ADR-0034: Mint a validation failure from outside core, and bound what it is allowed to say

- **Status:** Accepted
- **Date:** 2026-08-29
- **Deciders:** tech-lead (implementation of ROADMAP item 4.5), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-07 (which specifies the throw
  this made possible); [RFC-0002](../rfc/0002-cross-cutting-contracts.md) §FR-14 (the type's original
  contract); spec [§2 FR-07, FR-19](../specs/01_spec_utils.md);
  [ADR-0015](0015-strategy-registry-last-write-wins.md) (the status-code misattribution this avoids,
  and the known-keys coupling it inverts);
  [ADR-0020](0020-render-violations-from-the-message-template.md) (control C-01 on the provider
  path); [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) (bounding at the type rather than
  at each caller); [ADR-0027](0027-a-partial-update-renders-names-not-values.md) (the same bounding,
  one milestone earlier, applied to names); [ADR-0033](0033-publish-no-accessor-for-the-unvalidated-sort.md)

## Context

RFC-0003 §FR-07 is explicit that a sort-whitelist violation throws **`ValidationException`, not
`IllegalArgumentException`**, and the argument is a mapping-table argument rather than a taste one:
FR-19 maps validation to **400** and has no row for `IllegalArgumentException`, so the latter falls
to the **500** fallback and reports client-supplied input as a server fault. The RFC closes the point
with "`d4np-jdbc → d4np-core` makes the type available".

It is not available. `ValidationException`'s constructor is **package-private**, deliberately, and
the class documented why: *"`Validator` is the only thing that may decide an object is invalid."*
That sentence was true while a Bean Validation provider was the only way to reach the verdict. FR-07
is the first place in this project that reaches it without one — the allowed set is the repository's
knowledge, supplied per call, and is not expressible as an annotation on the type being validated, so
no provider is in the picture at all. The specified throw did not compile.

The second half of the problem arrives with the door. A violation about client input necessarily
quotes some of that input — FR-07's names the rejected sort property, which is the only thing that
makes the 400 actionable — and this exception's message reaches an FR-19 **response body** and a log
line. A property name holding `\r\n` folds one log line into two. `Validator`'s own path never had to
think about this, because it renders `path: message-template` from the host's own annotations.

## Decision

**`ValidationException` gains a public static factory, `of(String validated, List<String>
violations)`, and both doors funnel through one private constructor that bounds every string it is
given.** The provider path keeps a separate, package-private door — `fromProvider(...)` — so that
"a provider evaluated the annotations on an object and it failed them" remains a sentence only
`Validator` can say, and the class documentation stops claiming a monopoly it no longer has.

Three things are bounded inside the exception rather than at each thrower, on ADR-0022's rule that a
guarantee a caller can forget is advisory:

| Bound | Value | Why |
|---|---|---|
| ISO control characters | stripped from every string | a name holding `\r\n` forges a log line; stripping beats escaping because this text reaches a log *and* an HTTP body, which escape differently (control C-04's precedent for refusing over normalising) |
| length of one violation, and of the validated name | 200 characters, then `...` | the mint accepts caller-built text, and a client-supplied property has no natural length |
| violations listed in the message | 20, then "and N more" | `KeyDiagnostics`' own idiom; `violations()` still returns all of them |

Truncation never cuts a surrogate pair in half, because half of one is not a character and is not
valid UTF-8 in the response body either.

**Two obligations are documented on `of` and are not mechanically enforceable**, so they are stated
as the contract they are: what it reports must be a rejection of *input against a rule* rather than a
failure of this library (otherwise a 400 misattributes a defect, the mirror of the misattribution
ADR-0015 recorded), and it must **name what was rejected and not the rule that rejected it**.

That second obligation is the one worth reading twice, because it is the exact **inverse** of
`StrategyNotFoundException`, which lists every key it *does* hold. Both are right. That exception's
known-keys list is safe only because FR-19 maps it to a **500 with no body**, a coupling ADR-0015
recorded as standing; this exception's message travels inside a **400 that has one**, and FR-07's
allowlist is a repository's column vocabulary — internal schema. `PageRequestTest`'s
`namesWhatWasRejectedAndNeverWhatWasAllowed` asserts every allowlist entry is absent from the message.

## Alternatives Considered

- **Make the existing constructor public** — one word, no new member. Rejected because it publishes
  no obligation with the capability: the constructor carries none of the "input against a rule, and
  do not name the rule" contract, and a public constructor on an exception is the member a reader is
  least likely to consult documentation for. The factory is also where the emptiness check lives —
  an exception asserting that nothing was wrong is not one this type can carry.

- **Throw `IllegalArgumentException` from `d4np-jdbc` and leave core untouched** — the smallest
  possible diff, and it is what the specification's own "at construction" phrasing suggests.
  Rejected because RFC-0003 §FR-07 rejected it first with the FR-19 table as evidence, and nothing
  about needing to edit core makes that argument weaker. A 500 for `?sort=nope` is the single most
  common request a paginated endpoint gets wrong.

- **Mint a `JdbcValidationException` in `d4np-jdbc`** — no core change at all. Rejected because
  FR-19's mapping table is keyed on types: a second validation exception means a second row, in every
  adapter, forever, and the first host to miss it gets a 500 for the same input. One vocabulary for
  "the client sent something the rules refuse" is the point of having the type in core.

- **Bound the strings at each caller instead of inside the exception** — `d4np-jdbc` already has
  `PageDiagnostics`. Rejected as the *only* defence, for ADR-0022's reason: the next module to mint
  one of these would have to remember. It is kept as the inner of two bounds, because the outer one
  truncates the whole violation and would otherwise cut off the sentence explaining what was wrong.

## Consequences

- **A public API addition to `d4np-core`, MINOR under SemVer**, and the first one this milestone. It
  widens what a consumer may do — anyone can now mint a `ValidationException` — and the honest
  statement of the risk is that a host could use it to report something that is not a client mistake.
  What bounds the damage is that the mapping it feeds is FR-19's and the misuse produces a 400 rather
  than a leak.

- **The bounding applies to `Validator`'s path too, and that is asserted rather than assumed.**
  `producesTheSameExceptionTheProviderDoorDoes` compares both doors on the same input, and core's
  suite is unchanged at every other assertion — the bounding is a no-op on `path: template` text, and
  the test is what says so after the next person changes the cap.

- **The class documentation's "only `Validator`" claim is corrected in place** rather than left to be
  discovered as false by the next module. This is the second time a claim of that shape has not
  survived a second module (ADR-0024 corrected `d4np-json`'s "no Jackson type in any signature"),
  which is worth noticing as a pattern: a monopoly claim written by the first implementation is a
  prediction, not a contract.

- **C-01 gains its sixth enforced call site and its first one that deliberately quotes client
  input.** Every previous site kept the rejected value out; this one puts it back in, bounded, and
  the register records why the two rules are consistent — a value the client did not send is a
  disclosure, and a value it did send is an acknowledgement.

## References

- [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-07 — the specified throw, and the FR-19
  argument for it.
- `ValidationExceptionTest.TheMint` — the bounding, the surrogate case, the empty-violations refusal,
  and the two-doors equivalence.
- `PageRequestTest.ValidatedAgainst` — the first caller, including the allowlist-never-named
  assertion.
- [compliance register](../compliance/README.md) control C-01 and control C-06.
