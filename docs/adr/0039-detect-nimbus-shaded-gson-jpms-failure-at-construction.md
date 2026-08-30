# ADR-0039: Keep Nimbus at the latest, and turn its JPMS failure into a startup error

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** tech-lead (implementation of ROADMAP item 6.1), owner, security-auditor
- **Related:** [ADR-003](../../.spec/adr/d4np_java_adr_003_jwt_library.md) (**whose "minimal
  transitive footprint" claim this narrows**); [RFC-0005](../rfc/0005-security-contracts.md);
  spec [§2 FR-11, §3](../specs/01_spec_utils.md);
  [ADR-0005](0005-jpms-module-names-and-export-less-descriptors.md) (the descriptors this
  interacts with);
  [ADR-0024](0024-take-a-jackson-type-in-one-signature.md) (**the precedent: an approved document's
  dependency claim replaced by a measurement**);
  [ADR-0035](0035-declare-autocloseable-so-the-override-is-legal.md) (the other place a JPMS-era
  behaviour differed from what a document assumed)

## Context

ADR-003 chose Nimbus JOSE+JWT, and one of its three stated reasons was:

> ✅ Minimal transitive footprint (**no Jackson requirement — ships its own minimal JSON handling**),
> aligning with the ADR-001 dependency budget for `d4np-security`.

**Measured at 10.3, that is true and incomplete.** `mvn dependency:tree` shows exactly one entry and
no transitives, so the Maven half holds. The module half does not: Nimbus's "own minimal JSON
handling" is a **shaded copy of Gson**, and Gson's `SqlTypesSupport` decides whether to register
`java.sql.Date` adapters like this:

```java
try { Class.forName("java.sql.Date"); … }   // present
catch (ClassNotFoundException e) { … }      // absent
```

That guard is exhaustive on the **class path**. On the **module path** there is a third state it does
not consider: the class is *present but unreadable*. When `java.sql` is resolved into the graph and
`com.nimbusds.jose.jwt` does not read it, the JVM raises `IllegalAccessError` — an `Error`, not the
`ClassNotFoundException` being caught. The static initialiser dies, and **every** subsequent JSON
operation fails with:

```
NoClassDefFoundError: Could not initialize class com.nimbusds.jose.util.JSONObjectUtils
```

Nimbus 10.3's descriptor requires neither `java.sql` nor `static java.sql`:

```
requires jcip.annotations static, jdk.crypto.cryptoki, com.google.gson static,
         org.bouncycastle.provider static, java.base mandated,
         org.bouncycastle.pkix static, com.google.crypto.tink static
```

so **nothing a consuming module declares can grant the edge** — readability is per-module and only
`--add-reads` or the module's own descriptor can supply it.

**Measured across versions, without the flag:**

| Nimbus | Modular test suite |
|---|---|
| 9.37.3 | **fails** |
| 10.0 | **fails** |
| **10.0.2** | **passes** |
| 10.1 | **fails** |
| 10.2 | **fails** |
| 10.3 | **fails** |

## Decision

**Pin the latest (10.3), and detect the condition at provider construction with a message naming the
exact flag.**

Three parts:

1. **The version stays latest.** Item 4.1 established that a `compile`-scope dependency is what a host
   with no dependency management resolves, so an old pin ships a stale library to exactly the hosts
   least equipped to notice — and that argument is *stronger* for a JOSE implementation than it was
   for Jackson.
2. **`JwsEngine` probes the JSON machinery in its constructor** and, on a `LinkageError`, throws an
   `IllegalStateException` explaining the cause and quoting
   `--add-reads com.nimbusds.jose.jwt=java.sql`. Cheap: the JVM initialises the class once.
3. **The module's own build carries the flag**, and modular consumers are told they must too — in the
   package Javadoc, the README and this record.

### Why not pin 10.0.2, which demonstrably works

Because **it is an island, not a trend.** 10.0 fails, 10.0.2 passes, 10.1 through 10.3 fail. Nothing
about that pattern suggests the maintainers fixed it and then regressed deliberately; it looks like a
shaded-Gson version that happened not to reach the code path. **Pinning a security library to a lucky
patch release is depending on an accident**, and it would freeze `d4np-security` three minor versions
behind on the one dependency where being behind is most expensive.

### Why the construction-time probe is the part that matters

Without it, the failure surfaces **at the first token verified** — in a request, under load, as a
`NoClassDefFoundError` naming a shaded class the reader has never heard of, with no hint that the fix
is a JVM flag. With it, the failure surfaces when the provider is built, and says what to add.

That is the same reasoning ADR-003 already applies to a weak HS256 secret — *"rejected at provider
construction — misconfiguration fails fast rather than weakening tokens silently"* — extended from a
misconfiguration the library can see to one the module system imposes.

**Measured both ways.** Before the probe, the suite failed with `NoClassDefFoundError` on
`JSONObjectUtils`. After it, the same run without the flag fails with:

> the JOSE provider's JSON support could not initialise on this module path. Nimbus's shaded Gson
> reads java.sql.Date, its module does not declare that edge, and its ClassNotFoundException guard
> does not catch the IllegalAccessError JPMS raises instead. Add
> `--add-reads com.nimbusds.jose.jwt=java.sql` to the JVM, or run this library on the class path.

## Consequences

- **ADR-003's "minimal transitive footprint" claim is narrowed rather than falsified**, and this is
  the second time an approved document's dependency claim has needed a measurement rather than a
  better argument — ADR-0024 did the same to RFC-0003's *"no Jackson type appears in a signature"*.
  A claim of that shape is about Maven; JPMS is a second axis and has to be checked separately.
- **Modular consumers inherit a JVM flag**, which is a real cost and is stated as one rather than
  buried. Class-path consumers are unaffected. `d4np-security` is the only module in this repository
  with such an obligation.
- **The obligation is unenforceable by us**, so it is made *discoverable* instead: the failure is at
  startup and names the flag.
- **Report upstream.** The Gson guard is wrong for JPMS and the Nimbus descriptor is missing an
  optional edge; either fix removes this entirely. Filed as a roadmap follow-up so a future Nimbus
  bump re-checks it rather than carrying the flag forever.
- **Item 6.2's `AesEncryptor` is unaffected** — it uses `javax.crypto` from `java.base` and touches no
  JOSE type — so this cost belongs to FR-11 alone.

## Alternatives

1. **Pin 10.0.2.** Rejected above: an island, and three minor versions behind on a JOSE library.
2. **Pin 9.37.3, matching a Spring Boot 3.2-era managed version** (item 4.1's floor-build instinct).
   Rejected: measured to fail *as well*, so it buys nothing and costs more.
3. **Ship without the probe and document the flag only.** Rejected: the documentation is read by
   whoever configures the build, and the failure is met by whoever is on call.
4. **Call `Module.addReads` reflectively at startup.** Rejected: `addReads` is caller-sensitive and a
   no-op unless the caller is the module itself, so this does not work — and if a variant did, a
   library silently rewriting another module's readability is worse than a documented flag.
5. **Move off Nimbus.** That is ADR-003's supersession and far beyond item 6.1. Recorded so a future
   reader knows it was considered and why it was not taken here: the defect is in a shaded
   transitive, it is fixable upstream, and the alternatives ADR-003 rejected have not improved.
