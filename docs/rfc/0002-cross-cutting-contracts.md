# RFC-0002: Cross-cutting core contracts — audit redaction, validation and metrics

- **Status:** Accepted (2026-08-02, owner authority — no peer-review round; see [Approval](#approval))
- **Author:** tech-lead · **Reviewers:** reviewer, security-auditor (FR-16 is a security control, not a
  feature), enterprise-architect · **Approver:** owner (@danielPoloWork)
- **Date:** 2026-08-01
- **Related:** spec [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) §2 FR-14, FR-15, FR-16,
  FR-17 · §5 · [RFC-0001](0001-core-contracts.md) (the contracts this one extends, and amends in two
  places) · [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) (error model) ·
  [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) (which routed the
  `Result<Void>` question here by name) · [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md)
  (the logging mechanism FR-15's fallback uses) ·
  [ADR-0018](../adr/0018-tokenizer-word-threshold-and-utf8-default.md) (which routed the FR-22
  amendment here) · [threat model](../security/threat-model.md) · Milestone 3 items 3.1–3.3

> Written before the code, as RFC-0001 was. `Validator`, `ExecutionTimeMetricAspect` and `AuditLog`
> do not exist yet — items 3.1, 3.2 and 3.3 — so every signature below is still free to change. Core
> stays at `0.x` through M2–M7 (RFC-0001 §Versioning), so nothing here is a binary-compatibility
> promise yet.

## Context

Milestone 3 holds three of the twenty-five specified items, and one of them is not a feature at all.

**FR-16 `AuditLog` is the reason this RFC exists before any M3 code.** The specification asks for
"annotation + service recording state-change audit trails (who / when / before-after values)" and the
manifest marks it `[GAP] NO REDACTION POLICY`, with the consequence spelled out: *as specified this
faithfully records secrets and PII into a store typically retained longer and replicated wider than
application logs.* That is worth restating plainly, because it inverts the usual risk calculus:

- an application log is noisy, short-retention and usually access-controlled as operational data;
- an **audit** store is deliberately long-retention, widely replicated, frequently exported for
  compliance review, and read by more people — which is the whole point of having one.

So a leak into the audit trail is *worse* than the same leak into a log, and it is the one place where
"we'll redact it later" does not work: the records are already written and already copied. **A
redaction policy is not a hardening pass on FR-16; it is the thing that decides whether FR-16 may be
built at all.**

Two further reasons this RFC is the right moment:

1. **ADR-0012 routed a question here by name.** `Ok` rejects `null` unconditionally and
   `java.lang.Void` is uninhabited, so a successful `Result<Void>` — which FR-17's own text
   recommends — **cannot be constructed**. ADR-0012 recorded the three costed options and deferred
   the choice to "the first RFC with real call sites", naming this one.
2. **ADR-0018 routed an amendment here.** RFC-0001 pins FR-22 as a table, a prose rule and
   pseudocode, and for `URLs` the prose and pseudocode contradict the table. Item 2.5 implemented the
   table and deliberately did not edit an approved RFC to match its own implementation; this RFC is
   where that amendment belongs.

FR-14 and FR-15 are smaller, but each has one decision that cannot be left to the implementation: what
a validation failure is allowed to *say*, and what an instrumentation failure is allowed to *do*.

## Decision

### FR-16 `AuditLog` — the redaction policy

#### The central rule

**Redaction happens at capture, not at write.** The `AuditEvent` that leaves the capture step is
already redacted, and there is no API on it that returns a raw value.

This is the load-bearing decision, and the alternative is the obvious one: hold the real values in the
event and let the sink redact. That fails for a specific reason — an `AuditEvent` is an ordinary
object. It gets passed to a host-supplied sink, and on the way it can be logged by an interceptor,
serialised by a queue, captured in a heap dump, or printed by a `toString()` in a debugger. Every one
of those is outside our control, and every one of them would see plaintext. **A sink cannot leak what
it never receives**, so the trust decision is made once, in one place, by us — not distributed across
every sink anyone ever writes.

#### What is captured — four layers, first match wins

The specification asks for field-level allowlisting, a `@Sensitive` opt-out and an explicit
never-capture list. Those are **three different defaults** — an allowlist denies by default, an opt-out
permits by default — so they are not alternatives to choose between. They are layers, and the contract
is the **precedence between them**:

| # | Layer | Effect | Overridable by |
|---|---|---|---|
| 1 | **Never-capture list** | value replaced with `[REDACTED]` | **nothing** |
| 2 | **`@Sensitive`** on the component | value replaced with `[REDACTED]` | nothing but layer 1's identical outcome |
| 3 | **`@Audited`** on the component | value captured | layers 1–2 |
| 4 | **`@Audited`** on the type | every component captured | layers 1–3 |
| 5 | *default* | **omitted entirely** — the name does not appear | — |

`@Sensitive` is not redundant against a deny-by-default allowlist, and the reason is layer 4: the
ergonomic way to audit a domain record is one type-level `@Audited`, and per-component `@Sensitive`
is what makes that safe. Layer 1 then covers the case both annotations miss — a component added to an
already-`@Audited` type months later by someone who never read this document.

**Redacted and omitted are different outcomes on purpose.** A component that is audited but blocked
appears as `name → [REDACTED]`, together with a boolean saying whether it *changed*; a component
nobody audited does not appear at all. "The password was changed at 14:02 by alice" is precisely the
record an audit trail exists to hold, and it carries no plaintext — one bit that a secret rotated is
not a secret.

#### The never-capture list, and how names are matched

Matching is on **whole tokens, not substrings**, and the field name is normalised with FR-22's
`StringCaseConverter` first. An entry matches when its token sequence appears as a **contiguous run**
in the component's token sequence.

That precision is not fussiness — substring matching is actively wrong here. `pin` is a substring of
`shipping`; `auth` is a substring of `author`. A substring list silently redacts the wrong fields and
teaches developers that the audit trail is unreliable, which is how a control gets disabled.
Conversely a bare token that is too generic is wrong the other way: `key` would swallow `primaryKey`
and `sortKey`, so the list carries the **pairs** `api_key`, `access_key`, `private_key`, `secret_key`,
`signing_key` rather than `key`.

The base list, as normalised token sequences:

```
password  passwd  pwd  secret  token  credential  credentials
api_key  access_key  private_key  secret_key  signing_key
authorization  cookie  session_id  otp
ssn  social_security_number  card_number  cvv  cvc  pin_code
```

**A host may add entries. A host may not remove one.** Additions are the case that must be easy —
every industry has its own identifiers — and removal is the case that must be impossible, because a
removal is invisible in review and permanent in the store.

**Over-redaction is the safe failure and is chosen deliberately.** `tokenCount` normalises to
`[token, count]` and is redacted. That is a real cost — a harmless counter loses its value in the
trail — and it is the right side to fail on. A field that must stay legible can be renamed.

**This makes FR-22's `Locale.ROOT` rule security-load-bearing, and that link should be read twice.**
Normalisation runs `StringCaseConverter`, so on a Turkish-locale JVM using the default-locale
overloads `API_KEY` would lower-case to `apı_key` with a dotless ı, **fail to match `api_key`, and the
key would be written to the audit store in clear**. Compliance control C-03 exists for exactly this,
and FR-16 is the first consumer that turns it from a correctness rule into a security one.

#### What a captured value may be

A captured value is rendered as text, and **only these types may be captured directly**: primitives
and their boxes, `CharSequence`, `Number`, `Boolean`, `Character`, `enum` constants, `java.time`
types, and `UUID`.

Anything else is refused unless its own type carries `@Audited`, in which case capture **recurses**
and applies all four layers again to the nested type.

The reason is a trap that the obvious design walks straight into. If a non-simple value were rendered
with `String.valueOf`, then for

```java
record Credentials(String user, @Sensitive String password) { }
```

a parent holding `@Audited Credentials credentials` would render the record's **generated
`toString()`, which includes every component — the password among them.** The `@Sensitive` marker
would be present, correct, and completely bypassed. So: `String.valueOf` is never applied to a
composite, and a component that is neither simple nor `@Audited` is a **misconfiguration that fails
loudly at first capture**, naming the offending type and component, rather than silently producing a
plausible-looking record.

Recursion is bounded at **depth 3** and cycles are detected by object identity. Exceeding either is an
error, not a truncation — a truncated audit record that looks complete is worse than a failed one.

Failing loudly is the right trade because the failure is a **developer error, surfaced at first use**:
it fires in the first test that exercises the annotated type, not in production.

#### Surface

| Operation | Signature | Behaviour |
|---|---|---|
| record | `void record(AuditEvent event)` | writes to the sink; throws `AuditWriteException` if it cannot |
| capture | `AuditEvent capture(String actor, String action, Object before, Object after)` | applies the policy and returns an event holding **no** raw value |
| policy | `AuditPolicy.withAdditionalNeverCapture(String... names)` | additive only; there is no removal method |

`record` **throws rather than returning a `Result`**, and this is the one place in the library where
that choice is made for loudness rather than composability: a returned `Result` that a caller ignores
is silent, and an audit trail that silently stops writing is a compliance hole that surfaces at the
next review, months later. A host that wants tolerance catches the exception and says so in its own
code.

`actor` is **supplied by the caller and must be non-blank**. Core does not read a security context —
that would be a Spring dependency in a zero-dependency module (ADR-001) — so "who" is the host's to
provide, and refusing a blank actor is what stops an unattributable record entering the store.

#### Explicit non-goals

- **No content inspection.** The library redacts by *declaration*, never by scanning values for things
  that look like card numbers or tokens. Content scanning has false positives that corrupt records and
  false negatives that create false confidence, and it turns every capture into a regex pass.
- **No deep reflection.** Values are read through **public accessors only** — record components and
  getters — so a consuming module needs no `opens` clause. Asking for deep reflective access over a
  consumer's package in order to build a redaction engine is the wrong privilege to request, and a
  type whose accessors are not public and exported is simply not auditable.

### FR-14 `Validator`

A programmatic wrapper over Jakarta Bean Validation 3.x, which spec §3 permits at **`provided`**
scope — so core gains a `requires static jakarta.validation` edge and no runtime dependency.

| Operation | Signature | Behaviour |
|---|---|---|
| validate | `Result<T> validate(T candidate, Class<?>... groups)` | `Ok(candidate)` when clean; `Err(ErrorDetail)` listing the violations |
| requireValid | `T requireValid(T candidate, Class<?>... groups)` | returns `candidate`, or throws `ValidationException` |
| violations | `List<String> violations(T candidate, Class<?>... groups)` | the rendered violations, empty when clean |

**A violation message must never contain the rejected value.** Jakarta's `ConstraintViolation` exposes
`getInvalidValue()`, and the natural message — *"'hunter2' is not a valid password"* — is a
credential-in-an-error-string, which under FR-19 becomes an RFC 7807 body and reaches the HTTP client.
So a rendered violation carries the **property path and the message template only**, and this is
registered against compliance control **C-01** rather than left to the implementer's judgement.

`ValidationException` extends `RuntimeException` directly — not `BusinessException`, whose FR-19
mapping is **422** — because FR-19 maps validation to **400**. It carries its violations as `String`,
for the reason ADR-0015 records: every `Throwable` is `Serializable`, and a field of the validated
type would make the exception serialisable only when the consumer's type happened to be.

**A missing provider fails at construction**, naming what is absent. `requires static` means the
compile-time edge exists and the runtime one may not, so the alternative is a `NoClassDefFoundError`
thrown from somewhere deep in the first validated call.

### FR-15 `ExecutionTimeMetricAspect`

Core owns the timing and the fallback; the AspectJ/Spring binding lives in `spring-adapter`, which
may depend on Micrometer at `provided` scope. Core may not (ADR-001), so core defines the sink as an
SPI and ships one implementation.

| Concern | Contract |
|---|---|
| Sink | `interface ExecutionTimeRecorder { void record(String name, Duration elapsed, boolean failed); }` |
| Default | logs through `System.Logger` (ADR-0014) — no dependency, routes to whatever the host already installed |
| Micrometer | a recorder in the adapter, wired when a `MeterRegistry` is present |
| Selection | resolved **once, at construction** — never per invocation |
| Failures | a recorder that throws **must not** propagate; the measured method's outcome is unchanged |
| Coverage | both normal and exceptional completions are recorded, distinguished by `failed` |

Three of those rows are decisions rather than description:

- **Resolution happens once.** A per-invocation lookup pays for itself on every call and, worse, lets
  the behaviour change mid-run when a registry appears or goes away, so two measurements of the same
  method are not comparable.
- **Instrumentation must never break the measured call.** A metrics backend that is down is an
  operational event; a business method that fails *because* the metrics backend is down is an outage
  the instrumentation caused. The recorder's exception is swallowed and logged **at most once per
  recorder** — an unbounded log on a failing sink is its own denial of service.
- **Failed invocations are timed too.** Recording only successes biases every latency number, usually
  in the direction that hides the problem, since failures are often the slow path.

### The error model — resolving `Result<Void>`

**Core mints `Unit`, and `Result.ok()` returns `Result<Unit>`.** ADR-0019 carries the reasoning and
the costed alternatives; the short version is that ADR-0012's rejection of a nullable `Ok` payload
still stands, and the remaining choice was between a `Unit` type and leaving the error model unable to
express "succeeded, nothing to return".

Leaving it is the worse option because it makes the model **asymmetric**: an expected failure is a
value only when the operation also has a payload, so every no-payload operation is pushed into the
exception channel — which is the outcome ADR-002 adopted `Result` to avoid.

Honesty about the call sites, because the roadmap asked this RFC to decide *with* them: **neither of
the two M3 types actually forces it.** `Validator.validate` returns `Result<T>` with the validated
object, and `AuditLog.record` throws by the decision above. The decision is made on the completeness
of the error model, not on a call site, and that is recorded rather than dressed up.

### Amendments to RFC-0001

RFC-0001 is Accepted, so it is amended here rather than rewritten, and a pointer is added to its
header so a reader who arrives at the original text is not misled.

1. **§FR-22, the tokenizer rule.** Its prose — *"an uppercase run of length ≥ 2 followed by a
   lowercase letter splits before the final uppercase"* — and its pseudocode both split `URLs` into
   `UR`+`Ls`, contradicting its own table, which pins `URLs` as one token. **The rule is amended to
   require at least two lowercase characters**, which is the unique reading that reproduces all eight
   table rows. ADR-0018 has the analysis and the measured consequences.
2. **§Error model, `Result<Void>`.** The sentence *"`Ok(null)` forbidden — use `Result<Void>`"* is
   amended to *"`Ok(null)` forbidden — use `Result<Unit>`"*. FR-17's text in the manifest is amended
   in the same change and the published spec re-rendered, per ADR-0010.

## Alternatives

1. **Redact at write, in the sink.** Rejected above: the event would carry plaintext through every
   interceptor, queue, heap dump and `toString()` between capture and sink, and every sink author
   would independently have to get redaction right.
2. **Denylist only — capture everything except `@Sensitive`.** The ergonomic option, and it produces
   complete records. Rejected on the failure mode: a component added later leaks **by default**, and
   the leak is silent, permanent and in the widest-replicated store the system has. Deny-by-default
   fails toward an incomplete record, which is recoverable; permit-by-default fails toward a published
   secret, which is not.
3. **Allowlist only — drop `@Sensitive` as redundant.** Superficially clean, since nothing is captured
   without `@Audited`. Rejected because it removes the safety of the type-level `@Audited` that makes
   the feature usable at all, and because it deletes the *declaration of intent* that makes a later
   `@Audited` on a secret visible in review.
4. **Content-based redaction** (scan values for card/token shapes). Rejected: false positives corrupt
   records, false negatives create confidence that is worse than none, and the cost is a regex pass on
   every captured value.
5. **Substring matching for the never-capture list.** Rejected by counterexample: `pin` ⊂ `shipping`,
   `auth` ⊂ `author`. Whole-token contiguous-run matching costs one call to a tokenizer this library
   already ships.
6. **Render composite values with `String.valueOf`.** Rejected by the record-`toString()` trap above —
   it silently bypasses `@Sensitive` on nested components, which is the exact failure the annotation
   exists to prevent.
7. **`AuditLog.record` returns `Result<Unit>`.** Consistent with the error model and rejected anyway:
   an ignored return value is silent, and silence is the failure mode an audit control cannot afford.
8. **Permit `null` in `Ok` and mark the payload `@Nullable`.** Not reopened — ADR-0012 rejected it on
   where the cost lands: every consumer of every `Result` inherits a null check on the happy path.
9. **Amend RFC-0001 in place rather than by this RFC.** Rejected on governance: item 2.5 declined to
   edit an approved RFC so it would match the implementation the same agent had just written, and that
   restraint is worth more than the tidiness of a single document.

## Consequences

- **FR-16's `[GAP]` closes**, and the manifest line gains a `[RESOLVED by RFC-0002]` pointer rather
  than a copy of this policy — the same treatment item 1.12 gave RFC-0001's five gaps.
- **The audit sink is an outbound data flow that the threat model does not have a boundary for.**
  B1–B6 cover consumer→library, library→host framework, library→external services, key material,
  supply chain and test scope; "library → host-supplied audit store" is none of them, and it is now
  the widest-retention sink in the system. **Filed for item 8.6**, which owns the next full STRIDE
  pass; the security-auditor owns that document and this RFC does not edit it.
- **Two compliance controls will be registered when the code lands**, not now, because the register's
  evidence column takes tests and there are none yet: the audit redaction policy (item 3.3) and the
  validation-message rule against C-01 (item 3.1).
- **C-03 stops being a lonely correctness rule.** FR-16 makes `Locale.ROOT` the difference between a
  matched and an unmatched never-capture entry, which is the strongest argument yet for closing C-03's
  stated gap with an ErrorProne pattern that forbids the default-locale overloads.
- **Over-redaction is now a documented, accepted cost.** `tokenCount` will be redacted. Anyone who
  reports that as a bug should be pointed here.
- **`Unit` is one new permanent public type.** Under RFC-0001 §Versioning adding a type is MINOR, and
  core is `0.x` until M8, so the cost of being wrong is a deletion rather than a major bump.
- **Item 3.3 inherits a fail-fast obligation** that is easy to under-build: a component that is neither
  simple nor `@Audited` must fail at first capture with the type and component named. Silently skipping
  it would look identical to a correctly-configured type in every test.
- **Nothing here is enforceable until items 3.1–3.3 land.** This RFC is a contract, not a control; the
  gates arrive with the code, and until then FR-16 remains a design on paper — which is the state the
  `[GAP]` marker was flagging in the first place.

## Approval

The approval encodes a **human decision** — no RFC self-approves (`AGENTS.md` §6). The record below was
**authorized by the owner (@danielPoloWork) in session on 2026-08-02** and transcribed by the agent.
The agent drafted this RFC and did not judge its soundness; the decision is the owner's.

```
approved-by: owner @danielPoloWork (2026-08-02)
```

**This document was drafted `Proposed` with an empty `approved-by:` and flipped only on the owner's
word**, in a separate change from the drafting. That sequence is the point: a status field an agent
set to `Accepted` in advance would be exactly the audit trail this section exists to prevent, so the
two acts are visible as two acts in the history.

**Review provenance — stated, not implied.** No independent `reviewer`, `security-auditor` or
`enterprise-architect` round ran, and **approval did not change that**. This acceptance rests on the
owner's direct authority (precedence layer 1, the terminal gate), not on a peer-review round. FR-16 is
a security control and the security-auditor role owns the threat model it touches, so the absence is
more material here than it was for RFC-0001 — recorded so a later reader knows which assurance this
RFC does *and does not* carry.

Reviewers (structured findings addressed): reviewer — **not run** ; security-auditor — **not run** ;
enterprise-architect — **not run**.

## References

- FR-14, FR-15, FR-16, FR-17 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md); FR-19 for
  the mapping table that separates 400 from 422.
- [RFC-0001](0001-core-contracts.md) §FR-22, §Error model, §Versioning.
- [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) — the three costed options
  for `Result<Void>`; [ADR-0019](../adr/0019-mint-unit-for-the-void-success.md) — the choice.
- [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) — the logging mechanism FR-15's default
  recorder uses; [ADR-0018](../adr/0018-tokenizer-word-threshold-and-utf8-default.md) — the FR-22
  amendment and the `Locale.ROOT` control.
- [threat model](../security/threat-model.md) §1 trust boundaries — and the missing one, filed above.
- OWASP *Logging Cheat Sheet* — the "do not log secrets" rule this policy makes mechanical rather than
  advisory.
