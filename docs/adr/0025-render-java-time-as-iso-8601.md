# ADR-0025: Render `java.time` as ISO-8601, a fifth setting FR-20 does not name

- **Status:** Accepted
- **Date:** 2026-08-07
- **Deciders:** tech-lead (implementation of ROADMAP item 4.1), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-20; spec
  [§2 FR-20](../specs/01_spec_utils.md), [§1 compatibility matrix](../specs/01_spec_utils.md);
  [ADR-0010](0010-single-specification-authority.md) (the spec is the frozen contract, and a
  divergence is recorded rather than absorbed);
  [ADR-0012](0012-the-null-boundary-of-the-core-error-vocabulary.md) (the precedent for recording a
  gap between what a document says and what the code does)

## Context

FR-20 names three settings and RFC-0003 adds a fourth, saying so explicitly: *"This RFC adds two
things the requirement does not state."* An implementation that quietly adds a fifth is the
implementation outrunning its design — the gap ADR-0012 exists to record — so the fifth is decided
here or not at all.

The setting is `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`, and it matters because of what
FR-20's own third clause does *not* do. Registering the `JavaTimeModule` makes `java.time` types
serializable at all; it does not choose their representation. With the feature at Jackson's default,
`Instant.parse("2026-08-07T10:15:30Z")` is written as `1786184130.000000000` — an epoch number.

That default is out of step with every host in the compatibility matrix this library targets. Spring
Boot 3.2+ disables it in its own auto-configured mapper, so a service that uses the framework's
mapper on one path and `JsonMapper` on another would emit **two wire formats for the same field**,
with no error anywhere and a consumer's parser breaking depending on which path produced the
document. An epoch number also discards the offset and zone information that `OffsetDateTime` and
`ZonedDateTime` exist to carry.

The general rule this sits under was already established and needs no decision: **every setting is
written out, including the ones that merely restate today's default.** `deactivateDefaultTyping()`
and `INCLUDE_SOURCE_IN_LOCATION` are both stated explicitly for the reason RFC-0001 wrote UTF-8 out
instead of calling `Charset.defaultCharset()` — an explicit value survives a default that moves, in
either direction. That is not hypothetical here: `INCLUDE_SOURCE_IN_LOCATION` defaults to **enabled**
in Jackson 2.15.3 and to **disabled** in 2.22.1, and both versions are inside the supported matrix,
because a Spring Boot 3.2 host's dependency management downgrades this library to its own managed
Jackson. Measured from both releases' sources, not assumed.

## Decision

**`JsonMapper` disables `WRITE_DATES_AS_TIMESTAMPS`, so `java.time` values are rendered as ISO-8601
text.** It is the only configuration decision in item 4.1 that neither FR-20 nor RFC-0003 states, and
it is recorded here rather than folded into the code as an obvious extra.

The published Javadoc names it as such — *"the one setting beyond the four the requirement and the
RFC name"* — so a reader comparing the type against the requirement finds the difference explained
rather than has to notice it.

## Alternatives Considered

- **Leave it at Jackson's default and ship epoch numbers.** The literal reading of FR-20, and
  rejected: the requirement asks for a *pre-configured* mapper, and handing a consumer the one
  representation their framework does not use makes the configuration a liability rather than a
  service. The failure it produces is the quiet kind — two formats in one service, discovered by a
  downstream parser.
- **File it as a new ROADMAP item and ship 4.1 literal.** The AGENTS.md §10 escape hatch for
  genuinely out-of-scope work, and rejected because it is not out of scope: it is one line inside the
  method whose entire job is this configuration, and deferring it means every consumer between the
  two items either accepts the wrong format or stops using `JsonMapper`. Deferral would also make the
  change a **behavioural break** when it lands, where today it is a first release.
- **Amend RFC-0003 to add the setting.** Rejected on the precedent item 2.5 set and RFC-0002
  followed: an approved RFC is not edited by the agent that implements it, because a document that
  changes to match the code it produced stops being a check on that code. An ADR that records the
  divergence and points at it is the mechanism AGENTS.md §7 provides.
- **Make the date format a construction option.** Rejected as surface bought for a preference: it
  doubles the factory surface for a choice with one defensible default, and a host that genuinely
  needs epoch numbers can register a module that says so — which is exactly what the additive
  customisation seam is for (ADR-0024).

## Consequences

- **The wire format is part of the published contract from the first release.** `Instant` renders as
  `"2026-08-07T10:15:30Z"` and `LocalDate` as `"2026-08-07"`, asserted as an exact document by
  `JsonMapperTest.readsAndWritesJavaTimeAsIso8601`, with a companion test showing what the default
  would have produced instead. Changing it later would be a MAJOR-grade behavioural break under
  RFC-0001 §Versioning, which is the right weight for a wire format.
- **The specification is unchanged and FR-20 keeps its text.** Spec §2 FR-20 enumerates three
  settings; this record is the fourth-and-fifth's home, alongside RFC-0003 §FR-20 for
  `INCLUDE_SOURCE_IN_LOCATION`. ADR-0010's precedence ladder makes that the correct place — the
  manifest's `spec` block is the source of record, and a divergence is documented against it rather
  than edited into it.
- **The explicit-restatement rule now has a measured justification**, not just an inherited one: the
  `INCLUDE_SOURCE_IN_LOCATION` default flipped between two Jackson versions this library must work
  under, so the explicit `disable` is what makes a 2.15.3 host and a 2.22.1 host behave the same way.
  A reader who thinks stating a default is noise should read that line as the counter-example.
- **Nothing here is enforceable by a gate**, and no gate is claimed. The tests are the enforcement,
  and they are exact-string assertions precisely because a looser one would survive a change of
  format.

## References

- [RFC-0003 §FR-20](../rfc/0003-jdbc-and-json-contracts.md) — the four settings this adds the fifth
  to.
- [`JsonMapper.java`](../../d4np-json/src/main/java/it/d4np/utils/json/JsonMapper.java) — the
  configuration and the table that documents it.
- Jackson `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` — `true` in the 2.15.3 sources, `false` in
  the 2.22.1 sources; the version pin and the floor-build command are in
  [`d4np-json/pom.xml`](../../d4np-json/pom.xml).
- [ADR-0024](0024-take-a-jackson-type-in-one-signature.md) — the customisation seam a host with a
  different need uses.
