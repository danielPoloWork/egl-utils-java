# 2026-08-30 — FR-11's provider, and a dependency claim that is true in Maven only (ROADMAP item 6.1)

`JwtTokenProvider`, `JwtVerifier`, `JwtProfile`, `JwtClaims`, `JwksSource` and
`JwtVerificationException` open `d4np-security` — the module's first public API, **60 tests**, and
[ADR-0039](../../../adr/0039-detect-nimbus-shaded-gson-jpms-failure-at-construction.md).

## The headline: a "minimal footprint" claim is about Maven, and JPMS is a second axis

ADR-003 chose Nimbus partly for a *"minimal transitive footprint (no Jackson requirement — ships its
own minimal JSON handling)"*. `mvn dependency:tree` confirms it: one entry, no transitives.

The "own minimal JSON handling" is a **shaded copy of Gson**. Gson decides whether to register
`java.sql.Date` adapters with `Class.forName` guarded by `catch (ClassNotFoundException)` — exhaustive
on the class path, and blind to a third state on the **module** path: *present but unreadable*. With
`java.sql` resolved and `com.nimbusds.jose.jwt` not reading it, the JVM raises `IllegalAccessError`,
which is an `Error` and is not caught. The static initialiser dies and every JSON operation fails.

Nimbus 10.3's descriptor requires neither `java.sql` nor `static java.sql`, so **nothing a consuming
module declares can grant the edge.**

Measured across six versions without the flag:

| Nimbus | Result |
|---|---|
| 9.37.3, 10.0 | fail |
| **10.0.2** | **passes** |
| 10.1, 10.2, 10.3 | fail |

**The shape of that result is what decided the response.** 10.0.2 is an island, not a trend — pinning
to it would freeze a JOSE implementation three minor versions back on the strength of an accident,
and item 4.1's rule (a compile-scope dependency is what a host with no dependency management
resolves) argues harder for JOSE than it did for Jackson.

So the version stays latest and **the failure moves from the first token to the first provider**:
`JwsEngine` probes the JSON machinery in its constructor and, on a `LinkageError`, throws naming
`--add-reads com.nimbusds.jose.jwt=java.sql`. Measured both ways — before, `NoClassDefFoundError` on
a shaded class nobody has heard of, in a request; after, a startup failure that says what to add.
That is ADR-003's own treatment of a weak HS256 secret, extended from a misconfiguration the library
can see to one the module system imposes.

**Second time an approved document's dependency claim needed a measurement rather than a better
argument** — ADR-0024 did it to RFC-0003's *"no Jackson type appears in a signature"*.

## Two measurements that corrected my own reasoning

**The JDK's default redirect policy is `NEVER`, not `NORMAL`.** I wrote the opposite into
`JwksSource`'s Javadoc and asserted it in a test, and the test failed. The correction improves the
design rather than just the prose: since the default is already right, `followRedirects(NEVER)`
documents intent and changes nothing — and says nothing about a **caller-supplied** client. So
RFC-0005's clause 3 is enforced by an explicit **3xx refusal on arrival**, which holds for any
client. A default that happens to be right is not a control.

**The RFC 7515 vector is expired, and that turns out to be useful.** Appendix A.1's token is from
2011, so a hardened verifier cannot accept it. But the signature is checked *before* any claim — so
the **reason code is the proof**: `EXPIRED` means parse, algorithm, `typ` and HMAC all passed, and a
one-bit change to the signature flips it to `SIGNATURE_INVALID`.

## Where this module deliberately differs from FR-12

RFC-0005 gives `CryptoException` a **uniform** message across every failure, because distinguishing
them is a decryption oracle. `JwtVerificationException` carries a `Reason`, and the asymmetry is a
decision rather than an inconsistency: an attacker holding a token already knows whether it is
expired and which audience they put in it, while the *application* needs to tell a re-authenticate
from a misrouted request from a security event worth an alert. What is never distinguished is
anything the attacker does not already control.

## Smaller things worth carrying forward

- **The capability split is structural.** `JwtVerifier` verifies; `JwtTokenProvider` extends it and
  signs. A service consuming an IdP's tokens gets a type with **no `sign` method to reach** —
  ADR-0022 applied to a capability rather than a setting, and the verify-only implementation is not
  castable to the signing one.
- **The patterns catalogue gains the rejection this item earns.** Strategy is the obvious pattern for
  "select an algorithm", and here **it is the vulnerability**: a verifier that reads `alg` from the
  token and looks up a matching verifier *is* the algorithm-confusion attack. The negative suite
  forges it — an HS256 token signed with the issuer's RSA **public** key — and pairs it with a naive
  verifier that accepts it, so the rejection is evidence rather than an absence.
- **`JwtClaims` cannot be minted outside a verification** (package-private constructor), and its
  `toString()` renders claim *names* and never values.
- **`iss` and `aud` are constructor arguments, not defaults.** ADR-003 says these checks are "on by
  default"; a default can be turned off, and neither has a sensible value to default *to*.

## One caveat on the verification

**The Adoptium images this host fetched are pruned.** The JDK 17 run failed with
`SecurityException: Can't read cryptographic policy directory: unlimited` until `conf/security/policy`
was copied from the 21 image — so **the JDK-17 results for this item come from a locally repaired
JDK**, which is stated rather than glossed. The same images carry no `javadoc.exe`, as item 5.1
recorded. Neither is a property of the branch.

## Where the project stands

Items **6.2** (`AesEncryptor`), **6.3** (`OutputEncoder`) and **6.4** (the dependency gate) remain,
all three unblocked by RFC-0005.

## What the next session needs to know

- **Item 6.2 is unaffected by ADR-0039.** `AesEncryptor` uses `javax.crypto` from `java.base` and
  touches no JOSE type, so the `--add-reads` obligation belongs to FR-11 alone.
- **Item 6.3 must verify OWASP Java Encoder's zero transitive dependencies with
  `mvn dependency:tree` before adding the allowlist entry** — and after this item, "verify" should be
  read to include the *module* axis, not just the Maven one. That is exactly the check ADR-003's
  claim passed and then failed.
- **Item 6.4 carries the follow-up** to re-measure whether Nimbus still needs the flag; the
  construction-time probe means a Nimbus bump that fixes it will simply stop firing.
