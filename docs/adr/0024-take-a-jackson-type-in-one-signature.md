# ADR-0024: Take a Jackson type in one signature, and keep the module edge non-transitive

- **Status:** Accepted
- **Date:** 2026-08-07
- **Deciders:** tech-lead (implementation of ROADMAP item 4.1), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-20 and §Consequences; spec
  [§2 FR-20](../specs/01_spec_utils.md), §3, [NFR-07 / NFR-08](../specs/01_spec_utils.md);
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (dependency policy);
  [ADR-0005](0005-jpms-module-names-and-export-less-descriptors.md) (the descriptors);
  [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) (a type in a published signature is
  the dependency you cannot keep to yourself);
  [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) (a guarantee a consumer can switch off
  is advisory)

## Context

RFC-0003 makes two statements about `d4np-json` that cannot both be literally true, and item 4.1 is
where the code has to pick one.

§FR-20 says the configured `ObjectMapper` is never exposed, and that the cost — a host with a
legitimate need for a custom serializer or a mix-in has no handle — is paid by letting **construction
accept an optional list of Jackson `Module`s**, additive only.

§Consequences says `d4np-json` gains `exports it.d4np.utils.json` and a **non-transitive** `requires
com.fasterxml.jackson.databind`, *"which is only consistent because no Jackson type appears in a
signature"*.

`com.fasterxml.jackson.databind.Module` **is** a Jackson type, and the customisation seam puts it in
a published signature. The RFC's own justification for the descriptor — *"the type token is what
keeps that descriptor honest"* — answers the FR-21 question it was written for (`TypeReference` in
`convert`/`readPartial`) and does not reach this one.

Two things therefore need settling: whether the seam forces `requires transitive`, and what the
consumer-visible cost actually is. Neither is answerable by reading the module system's rules
loosely, because the rule that matters — when a consumer must declare a read edge — is narrower than
"when a type appears in a signature you call".

## Decision

**The seam keeps the Jackson `Module` in its signature, and the descriptor keeps every Jackson edge
non-transitive.** The RFC's claim is restated in the form that is true:

> No Jackson type appears in the signature of any operation a consumer needs in order to **use** this
> module. The one that does — `JsonMapper.withModules(List<Module>)` — is reached only by a consumer
> that is already naming Jackson types in its own source, because a `Module` has to be constructed
> before it can be passed.

**The rule was measured rather than reasoned about**, with a consumer module compiled and run against
the built artifacts:

| Consumer source | `requires com.fasterxml.jackson.databind` in the consumer? | Result |
|---|---|---|
| `JsonMapper.create()`, `readValue`, `writeValueAsString` | absent | **compiles, and runs** |
| `JsonMapper.withModules(List.of())` | absent | **compiles** |
| `JsonMapper.withModules(List.of(new SimpleModule()))` | absent | **fails**: *package `com.fasterxml.jackson.databind.module` is not visible* |
| the same, with the edge declared | present | compiles |

The third row is the whole cost, and the second row is why it is not larger: **javac requires the
read edge where the consumer's own source names the type, not where an invoked signature mentions
it.** A separate two-module probe confirmed the general form — a consumer calling the no-argument arm
of an overloaded pair compiles without reading the module named in the other arm's parameter type.

Two consequences of that measurement, recorded because the intuition runs the other way:

- **The distinct method name is an API choice, not a module-system necessity.** `withModules` reads
  better than a second `create`, and that is the whole reason; an overload would have cost a consumer
  nothing.
- **`requires transitive` would buy exactly one thing** — letting a customising consumer skip a
  one-line declaration it needs anyway to write `new SimpleModule()`.

## Alternatives Considered

- **`requires transitive com.fasterxml.jackson.databind`.** Rejected on the reason FR-20 exists:
  default typing is disabled precisely so that Jackson's behaviour is **not** part of this module's
  contract, and re-exporting Jackson makes it part of every consumer's. It also arms the trigger
  RFC-0003 §Versioning names — a Jackson major version that moves a type on our re-exported surface
  becomes our MAJOR bump — which is the cost that already killed exposing `TypeReference`. The
  benefit it buys is one `requires` line in the one consumer that customises.
- **Register modules through `ObjectMapper.findAndRegisterModules()` (the `ServiceLoader` path), and
  take no `Module` at all.** This is the only alternative that keeps the RFC's sentence literally
  true, which is why it is recorded rather than dismissed. Rejected because it inverts the property
  this type exists for: `JsonMapper`'s value is that its configuration is *explicit and stated*, and
  service loading registers whatever happens to be on the classpath — including a module arriving
  transitively that the host never chose, changing serialization behaviour on a dependency bump with
  nothing in the code to read. The same argument RFC-0003 used for writing
  `INCLUDE_SOURCE_IN_LOCATION` out instead of trusting a default, applied to modules.
- **Mint a `JsonModule` wrapper so no Jackson type is named.** Rejected as ceremony that changes
  nothing: to build one, a consumer still has to construct a Jackson `Module` and therefore still has
  to read the Jackson module. It would move the declaration without removing it, and add a type to
  the surface japicmp guards from 1.0.0.
- **Offer no customisation at all.** Rejected: the RFC weighed this and chose the seam, and a host
  with a custom serializer whose only option is to abandon `JsonMapper` and build a raw
  `ObjectMapper` ends up with *no* hardening — the guarantee is worth less if it is all-or-nothing.

## Consequences

- **RFC-0003's §Consequences sentence is narrowed rather than contradicted**, and this record is
  where a reader arriving at that sentence should land. The descriptor is unchanged; only the reason
  given for it is corrected, and it now rests on a measurement instead of on a claim that was too
  broad by one method.
- **`d4np-json`'s descriptor names `com.fasterxml.jackson.core` and
  `com.fasterxml.jackson.datatype.jsr310` explicitly**, although `databind` requires the first
  transitively: this module reads `StreamReadFeature` and `JavaTimeModule` directly, and a descriptor
  that relies on another module's transitivity breaks silently the day that module narrows it.
- **The `jpms-congruence` lint does not see any of this**, as RFC-0003 predicted — it compares the
  family-root edges against the internal `<dependency>` set, so the Jackson edges are outside its
  scope. The proof is the consumer probe, which is a one-off and is recorded here rather than
  automated: a permanent gate would need a tenth reactor module, which ADR-001's nine-module split
  does not admit.
- **A consumer's own types remain the consumer's obligation.** Jackson reflects over them, so a
  modular consumer must `exports` (or `opens`) the package holding them; the probe reproduces the
  failure and the message names exactly what to add. This library does not and should not open
  anything on a consumer's behalf — the line RFC-0002 drew when it refused deep reflection for FR-16.

## References

- [RFC-0003 §FR-20](../rfc/0003-jdbc-and-json-contracts.md) and §Consequences — the two statements
  this record reconciles.
- [`module-info.java`](../../d4np-json/src/main/java/module-info.java),
  [`JsonMapper.java`](../../d4np-json/src/main/java/it/d4np/utils/json/JsonMapper.java).
- JLS 21 §7.7.1 (`requires`) and §6.6.1 — accessibility is required where a type is *named*, which is
  the asymmetry the measurement above exercises.
- [ADR-0011](0011-declare-the-nullability-annotation-in-core.md) — the general form of the argument,
  recorded first for annotations.
