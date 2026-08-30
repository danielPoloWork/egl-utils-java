# RFC-0005: Security module contracts — envelope binding, key lifetime, JWKS trust and the dependency gate

- **Status:** Proposed
- **Author:** tech-lead · **Reviewers:** security-auditor (every section; this is the RFC its role
  exists for), reviewer, enterprise-architect (a new third-party surface and a change to spec §3's
  dependency graph) · **Approver:** owner (@danielPoloWork)
- **Date:** 2026-08-30
- **Related:** spec [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) §2 FR-11, FR-12, FR-13
  · §3 (the dependency row FR-13 changes) · §5 · NFR-06, NFR-10, NFR-11 ·
  [ADR-003](../../.spec/adr/d4np_java_adr_003_jwt_library.md) (**the JWT profile this RFC adds a
  trust posture to, without contradicting it**) ·
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (the dependency budget FR-13 spends) ·
  [ADR-0010](../adr/0010-single-specification-authority.md) (the precedence ladder — and the reason
  FR-13's change is an *amendment* where RFC-0004's was a *supersession*) ·
  [RFC-0001](0001-core-contracts.md) (error model, nullability, versioning) ·
  [RFC-0003](0003-jdbc-and-json-contracts.md) (control **C-01**: no exception or log line carries text
  this library did not write) ·
  [RFC-0004](0004-concurrency-contracts.md) (the SPI-not-a-dependency shape, and the
  supersede-versus-amend precedent this RFC departs from) ·
  [ADR-0028](../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md) /
  [ADR-0037](../adr/0037-a-fencing-token-that-restarts-is-worse-than-none.md) (**a field means what
  the backend said; a guarantee you cannot keep must be reported as absent**) ·
  [ADR-0024](../adr/0024-take-a-jackson-type-in-one-signature.md) (a third-party type in a published
  signature is the dependency you cannot keep to yourself) ·
  [risk register](../security/risk-register.md) **R-06** (routed to this RFC by name), **R-02** and
  **R-07** (reported on below) ·
  [threat model](../security/threat-model.md) §1 boundaries **B1**, **B3**, **B4**, **B5**

---

## Context

Milestone 6 is the security module: JWT (FR-11), symmetric encryption (FR-12) and output encoding
(FR-13). ADR-003 already chose Nimbus and pinned the hardened JWT profile, so — unlike M4 and M5 —
this RFC is not writing a module's contracts from nothing. It exists to close **stated gaps**, and
the gaps are the parts a specification is least able to leave open.

**There are four mandates, and item 6.0's own description lists three.** The fourth is in the risk
register rather than the roadmap:

| # | Mandate | Where it is stated |
|---|---|---|
| 1 | FR-12: **no AAD support** | spec §2 FR-12 `[GAP]` |
| 2 | FR-12: **no per-key message cap driving the rotation trigger** | spec §2 FR-12 `[GAP]` |
| 3 | NFR-11: **no CVSS failing threshold, no suppression policy** — plus whether `OutputEncoder` may take a compile dependency on OWASP Java Encoder | spec §2 NFR-11 `[GAP]`; ROADMAP item 6.0 |
| 4 | **R-06 — the JWKS trust posture ADR-003 left unspecified** | [risk register](../security/risk-register.md), whose remediation column reads *"Specify in **RFC-0005**: JWKS origin allowlist, explicit TLS posture, and whether the URL may come from caller config at all"*, owner **6.0 → 6.1** |

**Mandate 4 also corrects the roadmap's dependency line.** Item 6.0 says it *"Blocks 6.2–6.4"*. R-06
is an FR-11 finding whose remediation is owned `6.0 → 6.1`, so **item 6.1 is blocked on this RFC
too** — not for its algorithm profile, which ADR-003 owns, but for where its JWKS URL may come from.
An item that starts before this lands would build the one part of FR-11 that has no contract.

**This is the RFC the `security-auditor` role exists for**, and no such review has run. That is
recorded in §Approval rather than implied, because for this document the absence is more material
than it was for any of the four before it.

---

## Decision

### The rules this RFC inherits rather than re-argues

| Rule | Source | Applied here |
|---|---|---|
| Expected outcome → value; defect or infrastructure fault → unchecked | RFC-0001 §Error model | A wrong key is an expected outcome; a missing provider is a defect |
| No published method declares a checked exception | RFC-0001 | `CryptoException` and the JWT failures are unchecked |
| No exception or log line carries text this library did not write | RFC-0003, control **C-01** | `CryptoException` and every JWT failure below |
| A guarantee a consumer can switch off is advisory | ADR-0022 | Why the encryptor publishes no `Cipher` and no key material |
| A guarantee you cannot keep must be reported as absent, not approximated | ADR-0028, ADR-0037 | **The per-key message cap, which is the hardest decision in this document** |
| A third-party type in a published signature is a dependency you cannot keep to yourself | ADR-0024 | Why `OutputEncoder` takes and returns `String` |
| Rename where a wrong choice compiles and diverges | ADR-001's naming-consequence rule | `CryptoException` versus the JDK's `GeneralSecurityException` |

---

## FR-12 `AesEncryptor` — the envelope, and the two gaps

### The envelope header is authenticated, not merely present

FR-12 pins the envelope as `v1:{keyId}:{iv}:{ct+tag}` and says nothing about whether the header is
covered by GCM's authentication. **It is not, unless it is passed as AAD**, and the consequence is
not the one the threat model currently records.

The threat model's *Tampering* row reasons that swapping `keyId` yields a `CryptoException` because
GCM authentication fails under the wrong key. True, and it is the weaker of the two protections: it
holds because the attacker picked a key that does not work, not because the field is protected. The
field that has no such accidental defence is **`v1`**. A future `v2` — a different mode, a different
IV length, a different tag size — creates a **downgrade**: strip `v2:` and write `v1:`, and a
decryptor that trusts the version applies the older interpretation to newer ciphertext.

**Decision: the envelope header — the literal bytes `v1:{keyId}:` — is passed as GCM AAD on every
operation.** Version and key id become authenticated rather than advisory, `keyId` substitution fails
*by construction* rather than incidentally, and a `v2` cannot be forged into a `v1`. This costs
nothing: AAD is authenticated, not encrypted, and adds no ciphertext.

### A caller-supplied AAD is offered, and deliberately not mandated

Binding application context — a record id, a tenant, a column name — prevents **ciphertext
relocation**: an attacker who can write to storage moves a valid ciphertext from row A to row B, and
without bound context it decrypts happily in the wrong place.

**Decision: `encrypt(byte[] plaintext, byte[] context)` and `encrypt(byte[] plaintext)` both exist**,
and the caller's context is appended to the mandatory header AAD.

**Why this is offered rather than forced, when FR-05's SQL and FR-08's queue bound were both forced.**
The structural move — *no overload that omits the safe parameter* — is right when omitting it makes a
mandated control **unreachable**: an unbounded queue means FR-08's rejection handler can never fire,
and a concatenating overload means FR-05's parameterisation can be bypassed. Omitting a caller AAD
disables nothing this specification mandates; it declines a defence against an attack that does not
apply to every caller. Forcing `encrypt(plaintext, new byte[0])` at every call site trains people to
pass empty arrays, which is worse than a documented choice. **The rule that distinguishes the two
cases is stated here because this is the first time the answer has been "offer, not force":** force it
when the omission breaks a stated guarantee, offer it when the omission merely declines an optional
one.

`decrypt` requires the same context, and a mismatch is an authentication failure — loud, which is
correct.

### The per-key message cap, and who can actually count

NIST SP 800-38D §8.3 is explicit: with **random 96-bit IVs**, the number of invocations under one key
must not exceed **2³² (4 294 967 296)** to keep the IV-collision probability at or below 2⁻³². Beyond
it, GCM's confidentiality degrades — this is the reason FR-12's `[GAP]` exists.

**The cap is operationally reachable, not academic**, and the arithmetic belongs in the document
rather than in a reader's head: at 10 000 encryptions per second one key exhausts 2³² in **under five
days**; at 1 000 per second, in about **fifty**. A long-lived key on a busy service crosses it.

**The hard part is not the number. It is that this library cannot count.** `AesEncryptor` is stateless
and concurrent (spec §5) and the same key is used by every replica; a per-process counter observes one
pod out of fifty. Four options, and the asymmetry between them is the decision:

| Option | Why it fails or holds |
|---|---|
| Count per process, refuse past 2³² | **Sound but insufficient** — see below |
| Count per process, refuse past 2³²/N for an assumed fleet size N | Invents a number the library cannot know, and is wrong the moment the fleet scales |
| Count per process, warn only | A warning nobody reads is the outcome ADR-0022 calls advisory |
| Do not count; expose usage and let the host aggregate | Necessary, and on its own leaves the local case unguarded |

**Decision: the last two, combined, and the asymmetry is why.** A local counter reaching 2³² is
**proof** the global limit is breached — one process alone has spent the whole budget — so refusing
there produces **no false refusals**. A local counter *below* 2³² proves nothing, because fifty
processes each at 2³¹ have collectively doubled it. So:

- `AesEncryptor` counts its own invocations per key id and **refuses** past 2³², throwing
  `CryptoException`. Sound, never spurious.
- It exposes `long invocations(String keyId)` so a host can aggregate across replicas.
- **The rotation trigger belongs to the `KeyProvider` SPI, not to the encryptor**, because the cap is a
  property of the *key* and only the thing that issues keys can retire one.
- The Javadoc states the insufficiency in the imperative: *a process-local count below the cap is not
  evidence the key is safe.*

This is ADR-0037's shape on a second subject. There, an implementation that cannot keep a fencing
token monotonic must report **empty** rather than a best effort, because a best-effort guarantee is
worse than an absent one. Here, a library that cannot count globally must not imply that it has —
so it enforces exactly the half it can prove and says so about the other half.

### Two bounds FR-12 never mentions

**GCM has a per-invocation plaintext limit of 2³⁹ − 256 bits (≈ 64 GiB)**, beyond which security is
lost. FR-12 says nothing about it. **Today it is unreachable and not because of anything this library
does:** a Java `byte[]` cannot exceed about 2 GiB, so the JVM's array limit is what enforces GCM's
bound. That is an *accident*, and it is worth writing down because it stops being one the moment a
stream or channel overload is added — which is exactly the kind of convenience a later item would add
without re-deriving this. **A stream overload must bound the plaintext explicitly**, and this
sentence is the reason.

**The envelope's encoding is unspecified, and the delimiter makes that a correctness question.** With
`:` as the separator, a `keyId` containing `:` makes the envelope ambiguous to parse. **Decision:
`keyId` is constrained at construction to `[A-Za-z0-9_-]{1,64}`, and the IV and ciphertext are
encoded as base64url without padding.** The whole envelope is then URL-safe and unambiguous by
construction, rather than by a parser being careful.

### Errors

| Condition | Shape |
|---|---|
| Wrong key, tampered ciphertext, AAD mismatch | `CryptoException` — and **the same message for all three**, because distinguishing them is an oracle |
| Malformed envelope, unknown version, unparseable key id | `CryptoException`, same message |
| Per-key cap exhausted | `CryptoException`, distinct message — it is an operational fault, not an attacker signal |
| No `KeyProvider`, key id not resolvable at construction | `IllegalStateException` / `IllegalArgumentException` — a defect in the wiring |

**`CryptoException` carries no `javax.crypto` internals, no partial plaintext, no key id and no
cause** (spec §5 states the first two; the last two are this RFC's). The key id is caller-controlled
metadata that identifies which key is in use, and the cause is where `AEADBadTagException` and its
message live. **The uniform message is the load-bearing decision:** an exception that says *"bad tag"*
for a tampered ciphertext and *"unknown key"* for a substituted id hands an attacker a decryption
oracle one bit at a time. C-01's fourth enforced call site, and the first where the *reason* is
oracle-avoidance rather than disclosure.

---

## FR-11 — the JWKS trust posture ADR-003 left open (R-06)

ADR-003 pins caching and rate-limited refresh and says nothing about **where the key document comes
from or whether the transport is trustworthy**. R-06 scores that medium and routes it here. The
attack it names is concrete: if the JWKS URL can be influenced — SSRF, config injection, DNS — an
attacker serves their own key document and *every subsequent token verifies*. The hardened algorithm
profile is irrelevant against a verifier holding the attacker's key.

**Decision, in four parts:**

1. **The JWKS URL is fixed at construction and comes from an allowlist of origins.** A per-request or
   per-token URL — including the `jku`/`x5u` header claims, which are attacker-controlled by
   definition — is **never** consulted. This is the whole of the SSRF answer: the set of reachable
   origins is decided by the host at wiring time.
2. **HTTPS only, structurally.** The construction path accepts no `http://` URL — refused with
   `IllegalArgumentException`, not silently upgraded. The JDK default trust store is the floor; a
   host may supply its own `SSLContext`.
3. **Redirects are not followed.** A permitted origin that answers `302` to an attacker origin defeats
   the allowlist entirely, and this is the clause most likely to be omitted by an implementer who has
   satisfied (1) and (2) and believes the job is done.
4. **The fetch is bounded** — connect timeout, read timeout, and a maximum response size — because a
   permitted origin that is slow or enormous is a denial of service against the verifier. The
   rate-limited refresh ADR-003 already pins bounds the *frequency*; this bounds the *response*.

**What is deliberately not pinned, and why the refusal is recorded rather than silent:** certificate
or public-key **pinning** is not mandated. It is the strongest available control and it is
operationally brittle — a pin outlives the certificate rotation of an identity provider the host does
not operate, and a library that mandates one converts an IdP's routine rotation into an outage in
someone else's system. The allowlist plus HTTPS plus no-redirects is the floor this RFC requires; a
host that wants pinning supplies its own `SSLContext`, which (2) already permits. **R-06 closes on
this section, and item 6.1 implements it.**

---

## FR-13 `OutputEncoder` — the dependency decision

### Reimplementing is the option that loses, and it is not close

FR-13 asks for context-aware output encoding across HTML-body, HTML-attribute, JavaScript and URL
contexts, with *"OWASP Java Encoder semantics"* named in the requirement itself. Three options:

| Option | Verdict |
|---|---|
| Reimplement the escaping | **Rejected.** Context-aware escaping is a domain where correctness is measured in CVEs and where every subtle case — attribute values without quotes, `</script>` inside a JS string literal, `javascript:` URLs, HTML5 named entities — has already been found the expensive way by someone else. A hand-rolled encoder in a library whose selling point is enterprise security posture is the single worst do-it-yourself decision available here |
| Do not ship FR-13 | Rejected: it is a stated requirement, and the XSS row in the threat model has no other owner |
| **Take the dependency** | **Adopted** |

### What taking it costs, stated exactly rather than waved through

Item 6.0's own text calls this *"legal under ADR-001 but a decision, not a default"*. It is more than
that: **two gates say no today, and both must be changed deliberately.**

1. **`d4np-security`'s `maven-enforcer` allowlist permits exactly three patterns** — `it.d4np:*`,
   `*:*:*:*:test` and `com.nimbusds:*`. `org.owasp.encoder:*` is absent, so the dependency fails
   `mvn validate` rather than review. Item 6.3 adds the fourth entry, and it is a **build-gate
   change**, recorded as one.
2. **Spec §3's dependency row reads `security -> core [compile: nimbus-jose-jwt]`** — the graph the
   specification states and the enforcer mirrors.

Three conditions make it acceptable, and item 6.3 must **verify** rather than assume them:

- **Zero transitive dependencies.** OWASP Java Encoder is understood to have none, which is what keeps
  it inside ADR-001's budget for this module — but that is a claim, and item 6.3 proves it with
  `mvn dependency:tree` before the allowlist entry lands. If it has acquired any, this decision
  reopens.
- **No OWASP type in a published signature.** `OutputEncoder` takes `String` and returns `String`, so
  a future swap is an implementation change. ADR-0024 measured the cost of the alternative in
  `d4np-json`; the lesson transfers without needing to be relearned.
- **Non-transitive `requires`**, like Jackson in `d4np-json`, so an OWASP major version is not our
  MAJOR bump.

### Supersede a contract, amend a fact

RFC-0004 superseded FR-09's spec sentence under ADR-0010 rung 1 rather than editing the manifest, and
that was right **because FR-09's sentence is a contract** — prose about behaviour, which an RFC
outranks by construction.

**Spec §3's dependency row is not a contract. It is a fact about what the built artifact depends on**,
mirrored by a build gate, and a superseded-but-wrong fact is worse than a superseded-but-wrong
sentence: a reader checking what `d4np-security` pulls in would be told something false about the
artifact in their hands. **So this one is amended at rung 2 and the spec re-rendered**, which is what
RFC-0002 did for FR-17.

**The amendment lands with item 6.3, not with this RFC**, and the sequencing is deliberate: amending
§3 now would make the specification describe a dependency the build does not yet have — the same
falsehood in the other direction. The decision is made here; the fact changes when the fact changes.

---

## NFR-11 — turning the scan into a gate

NFR-11's `[GAP]` is that *"no CVSS failing threshold or suppression-file policy is stated, so the scan
is a report rather than a gate."* Item 6.4 additionally records that **the plugin is not declared at
all** — the `security / owasp dependency-check` CI job fails with `No plugin found for prefix
'dependency-check'` — so 6.4 introduces it and only then tightens it.

### The threshold, and why it is scope-dependent

**Decision: fail the build at CVSS ≥ 7.0 (High) for dependencies a consumer resolves — `compile` and
`runtime` scope — and report without failing for `test` scope.**

- **7.0 rather than 9.0.** Critical-only is defensible for an application that can compensate; it is
  not for a library whose two production dependencies are a JOSE implementation and an encoder. A
  High in Nimbus is a direct problem for every consumer.
- **7.0 rather than 4.0.** A Medium threshold on a transitive graph produces a steady stream of
  findings that cannot be acted on, and the observable result is a growing suppression file. **A gate
  that trains its owner to suppress is worse than no gate**, which is the same failure item 4.3
  avoided when it refused to ship a flaky NFR-03 gate.
- **Scope-dependent, because reachability differs.** A vulnerability in a test-scope artifact reaches
  no consumer — item 4.3 made the same distinction when it put H2 at test scope so *"spec §3's JDBC
  API only"* stayed checkable.

### The suppression policy, which is the half that decides whether the gate survives

Every suppression entry must carry, and CI must enforce the presence of:

1. the **CVE id**, so the entry is specific rather than a wildcard;
2. a **stated reason** — why the code path is unreachable, or which compensating control applies;
3. an **expiry date**, after which the suppression stops working and the build fails again; and
4. a **review at every release**, listed in the release checklist.

**The expiry is the load-bearing one.** A suppression without one is a permanent exception created
under time pressure, and it is how every dependency gate in the industry dies. An expired suppression
failing the build is the *desired* behaviour, not a nuisance.

### What item 6.4 must confirm before it can gate

- **The plugin must be introduced first.** Tightening a plugin that is not declared produces a
  different error, not a stricter gate.
- **An NVD API key is very likely required.** Recent OWASP Dependency-Check versions throttle or
  refuse bulk NVD access without one, which turns a per-PR scan into something that times out. This is
  stated as a **question item 6.4 must answer by running it**, not as a fact this RFC establishes —
  and if a key is needed, the credential's storage is itself a B5 concern.
- **The first run will produce findings.** The policy above is what makes that survivable; adopting the
  threshold and the suppression discipline in the same change is the point.

---

## Two register entries this RFC can report on

Verifying NFR-11's posture required re-rendering the manifest and diffing the workflows, and that
answers two open register rows. **Reported, not closed** — the register is `security-auditor`-owned
and item 8.6 runs the next full pass:

- **R-07 (*high*, rendered workflows drifted)** described **12 references** reverting on a re-render,
  including a SHA-pinned `action-gh-release` regressing to an older SHA. **That is no longer
  reproducible.** A full recursive diff of `.github/workflows` against a fresh render now differs in
  exactly **one hunk: five comment lines** in `ci.yml` about the dependency-check step. The version
  drift is gone.
- **R-02 (*medium*, action references tag-pinned rather than SHA-pinned)** is likewise no longer
  reproducible: every action reference in both workflows is a full SHA.

**This corrects a provenance claim item 5.0 made.** ROADMAP item **8.9** says the `ci.yml` drift was
*"filed by item 5.0, which found it rather than fixed it"*. The audit found it first, as R-07; item
5.0 found the **residue** left after R-07's main body was remediated. The item's text is corrected in
the same change as this RFC, and the finding stands — a rendered file that does not round-trip is
still a hole, and `consistency_lint.py` still does not check it.

---

## Error model, data, budgets, versioning

**Error model — no amendment.** FR-12's failures are infrastructure or attacker-supplied input, both
of which RFC-0001's table already assigns to the unchecked shape. No operation here returns `Result`,
and the `Unit` question does not arise.

**Data & schema.** `d4np-security` owns no persistent state. The ciphertext envelope is a *format*
rather than a schema, and its versioning is the `v1` prefix — now authenticated (above), which is
what makes the version a control rather than a label.

**Scalability budgets.**

| Axis | Metric | Target | Tool | Item |
|---|---|---|---|---|
| performance | AES-256-GCM throughput on the reference machine (AES-NI) | **≥ 400 MB/s** | JMH | 6.2 (NFR-06) |
| security | IV uniqueness over 10⁷ operations | **zero collisions** | property test | 6.2 (NFR-06) |

NFR-06's 400 MB/s is an **absolute** number against a named machine, so it belongs with NFR-01 and
NFR-02 in **item 8.3**'s stable-runner problem and explicitly not with item 8.8's fork-count problem —
the distinction RFC-0003 drew and RFC-0004 applied. Tracked on the reference machine, advisory in CI,
until 8.3 lands.

**A note on the IV-uniqueness test, because 10⁷ is not 2³².** Ten million operations exercise
`SecureRandom` and the plumbing; they do not approach the birthday bound that motivates the cap above.
The property test proves *the IV is not being reused by a bug*; the cap protects against reuse by
*probability*. They are different controls and item 6.2 should not let one stand in for the other.

**Versioning.** `d4np-security` publishes its first API in M6, so nothing is a compatibility change
yet. Adding an envelope version is MINOR; changing what `v1` means is MAJOR and, given the AAD
binding above, is also a decryption break for existing data — which is the property the binding buys.

---

## Alternatives

1. **Leave the envelope header unauthenticated and rely on the wrong-key failure.** Rejected: it
   protects `keyId` by accident and `v1` not at all, so the first `v2` introduces a downgrade.
2. **Mandate a caller-supplied AAD at every call site.** Rejected above — it forces `new byte[0]` on
   callers with nothing to bind, and an empty-array ritual teaches people to defeat the parameter.
3. **Refuse past a fleet-adjusted fraction of 2³².** Rejected: the library cannot know the fleet size,
   and a wrong divisor produces false refusals — the one failure mode the chosen design does not have.
4. **Do not count at all; make the cap purely the host's problem.** Rejected as the sole answer: the
   single-process case is real, is exactly the case a library *can* prove, and refusing there costs
   nothing.
5. **Distinguish `CryptoException` messages by failure cause** for debuggability. Rejected: it is a
   decryption oracle. The cause chain carries the detail for whoever is entitled to it.
6. **Reimplement output encoding to keep `d4np-security` at one dependency.** Rejected on the only
   ground that matters: escaping correctness is not a place to be original.
7. **Move `OutputEncoder` to its own module** so `d4np-security` keeps a single third-party surface.
   Rejected on cost — a tenth artifact, a BOM entry, a japicmp baseline and a release note — for a
   dependency that is already zero-transitive. Revisit if a second encoder dependency ever appears.
8. **Mandate certificate pinning for JWKS.** Rejected above: it converts an IdP's routine rotation into
   an outage in a system the host does not operate. Available to a host via its own `SSLContext`.
9. **Fail the dependency gate at CVSS ≥ 4.0.** Rejected: it manufactures a suppression file, and a
   gate whose owner has learned to suppress is worse than one that does not exist.

## Consequences

- **`d4np-security` gains its second and last planned third-party dependency**, and the module's
  enforcer allowlist grows from three patterns to four. Item 6.3 makes that change and proves the
  zero-transitive claim by running `mvn dependency:tree`.
- **Spec §3's dependency row is amended at rung 2 and the spec re-rendered — by item 6.3, not here.**
  This is the first time this project amends rather than supersedes, and the rule that decides it is
  new: **supersede a contract, amend a fact.**
- **R-06 closes on the JWKS section**, and **item 6.1 is blocked on this RFC** — a dependency the
  roadmap's own line omits.
- **The threat model gains two rows and sharpens one.** New: *a `v2 → v1` envelope downgrade* (B1,
  closed by header AAD) and *a per-key invocation count exceeding the GCM birthday bound* (B4,
  mitigated locally and residual globally). Sharpened: the *Tampering* row for `keyId` substitution,
  which currently reasons from the wrong-key failure and becomes structural. **No new trust boundary**
  — B1, B3 and B4 already cover the API, the JWKS fetch and the key material — stated because RFC-0002
  routed a missing boundary to item 8.6 and a reader will look for the same.
- **C-01 gains its next call site with a new justification.** Every previous one was about
  *disclosure*; `CryptoException`'s uniform message is about denying an **oracle**, which is a
  different reason for the same rule and is worth the register recording as such.
- **Items 6.1–6.4 each carry at least one ADR.** The JWKS posture, the cap's soundness asymmetry, the
  OWASP dependency, and the gate threshold are all security-relevant decisions, and the enterprise
  posture requires a record for each (AGENTS.md §7). This RFC pins the contracts and does not
  pre-write those records, which is how items 4.1–4.5 and 5.1–5.3 were sequenced.
- **No public type count is stated.** RFC-0003 stated one and was wrong by exactly the nested types it
  named in prose; RFC-0004 stated two numbers to avoid that. Here the surface depends on decisions
  items 6.1–6.3 still own — the `KeyProvider` SPI's shape above all — so a number now would be a guess
  dressed as a commitment. **Item 8.1 gets the count from japicmp, which is where it should come
  from.**

## Approval

The approval encodes a **human decision** — no RFC self-approves (`AGENTS.md` §6). This document is
drafted `Proposed` with an **empty `approved-by:`**, to be flipped only on the owner's word, in a
change separate from the drafting, so the two acts are visible as two acts.

```
approved-by:
```

**On the approver role:** this project's RFCs are approved by the **owner**, not the `tech-lead` that
`.eados-core`'s protocol names — satisfying that gate literally would be self-approval, since
`tech-lead` is the authoring role.
[ADR-0023](../adr/0023-the-owner-approves-this-projects-rfcs.md) records the deviation, and
`rfc_check.py` reporting a failure is **expected output**.

**Review provenance — and here the absence is the finding.** No independent `reviewer`,
`enterprise-architect` or `security-auditor` round has run. For RFC-0001 through RFC-0004 that was
worth recording; **for this one it is the material fact about the document's assurance.** Every
section is a security decision: an AEAD binding, a key-lifetime bound taken from NIST, a trust posture
whose failure mode is universal token forgery, and a dependency gate's threshold. The
`security-auditor` role owns the threat model and the risk register this RFC reads from and writes
back to, and it has not reviewed the answers. **A later reader should treat this document as
owner-approved and un-reviewed, and item 8.6's audit pass as the first independent look at it.**

Reviewers (structured findings addressed): reviewer — **not run** ; enterprise-architect — **not run**
; security-auditor — **not run**.

## References

- FR-11, FR-12, FR-13 and NFR-06, NFR-10, NFR-11 in
  [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) §2; §3 for the dependency row FR-13
  amends; §5 for the contract-row gap.
- [ADR-003](../../.spec/adr/d4np_java_adr_003_jwt_library.md) — the Nimbus choice and the hardened
  profile this RFC adds a trust posture to. The three-digit series is closed, so the addition lands
  here rather than as an edit to that record.
- [ADR-0010](../adr/0010-single-specification-authority.md) — the precedence ladder, and the rung the
  supersede-versus-amend distinction turns on.
- [ADR-0028](../adr/0028-the-fr-05-operation-set-and-what-it-refuses.md),
  [ADR-0037](../adr/0037-a-fencing-token-that-restarts-is-worse-than-none.md) — a guarantee that
  cannot be kept is reported as absent rather than approximated, which is the per-key cap's shape.
- [ADR-0024](../adr/0024-take-a-jackson-type-in-one-signature.md) — the measured cost of a third-party
  type in a published signature.
- [risk register](../security/risk-register.md) **R-06** (closed by the JWKS section), **R-02** and
  **R-07** (reported above); [threat model](../security/threat-model.md) §1 B1/B3/B4 and §2's rows for
  FR-11, FR-12 and FR-13.
- **NIST SP 800-38D** §8.3 — the 2³² invocation limit for random 96-bit IVs, and §5.2.1.1 for the
  2³⁹ − 256 bit plaintext bound per invocation.
- **OWASP** *Cross Site Scripting Prevention Cheat Sheet* — context-aware output encoding, and why
  input filtering for XSS is the anti-pattern FR-13 already refuses.
- **RFC 7515 / 7519** — the JWS and JWT vectors item 6.1's suite must carry; **RFC 8725** (JWT Best
  Current Practices) for the `jku`/`x5u` guidance this RFC's JWKS section applies.
