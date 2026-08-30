# 2026-08-30 — RFC-0005, and a mandate the roadmap did not list (ROADMAP item 6.0)

**Milestone 6 opens.** [RFC-0005](../../../rfc/0005-security-contracts.md) is drafted `Proposed` with
an empty `approved-by:`, awaiting the owner. It is the security RFC, so the `security-auditor` role's
absence from the review is the material fact about it rather than boilerplate — recorded as such in
§Approval.

## What changed

`docs/rfc/0005-security-contracts.md`; ROADMAP item 6.0 flipped with its entry, the checkpoint
pointer, and **item 8.9's provenance corrected**; `refs.rfcs` gains RFC-0005 and `manifest_rev` moves
8 → 9. No production code, no ADR, no `CHANGELOG.md` entry — item 3.0 added one only because it
shipped `Unit`.

## The first finding was in the roadmap line above the work

Item 6.0 lists three mandates and says it *"Blocks 6.2–6.4"*. The risk register says otherwise:
**R-06** — *JWKS trust posture unspecified*, medium — carries the remediation *"Specify in RFC-0005:
JWKS origin allowlist, explicit TLS posture, and whether the URL may come from caller config at all"*,
owned **6.0 → 6.1**.

So there are **four** mandates, and **item 6.1 is blocked on this RFC too** — not for its algorithm
profile, which ADR-003 owns, but for where its key document may come from. An item starting on FR-11
before this landed would have built the one part of it with no contract behind it.

Worth noting how it was found: by reading the risk register before starting, not by reading the
roadmap. The register routes work at a granularity the roadmap does not carry.

## The two decisions I expect to be questioned

**The per-key message cap is an asymmetry, not a number.** NIST gives the number — 2³² invocations
under one key with random 96-bit IVs — and it is operational rather than academic: at 10 000
encryptions/second a key exhausts it in under five days. But `AesEncryptor` is stateless and
concurrent and the same key runs on every replica, so a process-local counter sees one pod in fifty.

The asymmetry is what makes a decision possible: **a local count reaching 2³² proves the global limit
is breached; a local count below it proves nothing.** So refuse at the local cap — sound, never a
false refusal — expose the count for host aggregation, route the rotation trigger to the
`KeyProvider` because the cap is a property of the *key*, and say plainly that a local count below
the cap is not evidence the key is safe. ADR-0037's shape on a second subject.

**A caller-supplied AAD is offered rather than forced, and that needed a rule.** FR-05's SQL and
FR-08's queue bound were both *forced* — no overload omits the safe parameter. The rule separating
them, stated for the first time because this is the first time the answer is not force-it: **force
the safe parameter when omitting it makes a mandated control unreachable; offer it when omitting it
merely declines an optional one.** An unbounded queue makes FR-08's rejection handler unfirable. No
caller AAD disables nothing the spec mandates — and forcing `new byte[0]` at every call site teaches
people to defeat the parameter.

## Things the specification did not say that turned out to matter

- **The envelope header is not authenticated unless it is passed as AAD.** The threat model reasons
  that a `keyId` swap fails authentication — true, and only *accidentally*, because the attacker
  picked a key that does not work. It does not cover `v1` at all: the first `v2` format creates a
  downgrade. The header becomes mandatory AAD.
- **GCM's per-invocation plaintext limit is currently enforced by accident.** 2³⁹−256 bits is ~64 GiB
  and a Java `byte[]` caps near 2 GiB, so **the JVM's array limit is doing the work, not this
  library** — which stops being true the moment a stream overload is added.
- **The envelope's encoding was unspecified, and with `:` as delimiter that is a parsing question.**
  `keyId` is constrained to `[A-Za-z0-9_-]{1,64}`; IV and ciphertext are base64url without padding.
- **`CryptoException` needs one message for every failure**, and that is C-01's next call site with a
  *new* justification: every previous one was about disclosure, and this denies a **decryption
  oracle**.
- **Redirects.** Three of R-06's four parts are what anyone would write. The third is the one an
  implementer who satisfied the first two would omit: a permitted origin answering 302 to an attacker
  origin defeats the allowlist entirely.

## Supersede a contract, amend a fact

`OutputEncoder` takes the OWASP dependency — reimplementing escaping is not a place to be original —
and item 6.0's framing of that as *"legal under ADR-001 but a decision"* understates it. **Two gates
say no today:** the enforcer allowlist permits only `it.d4np:*`, test scope and `com.nimbusds:*`, and
spec §3's row reads `security -> core [compile: nimbus-jose-jwt]`.

RFC-0004 *superseded* FR-09's spec sentence under ADR-0010 rung 1. This one is **amended** instead,
and the rule is new: **supersede a contract, amend a fact.** FR-09's sentence was prose about
behaviour, which an RFC outranks by construction. Spec §3's row is a *fact about what the built
artifact depends on*, mirrored by a build gate — and a superseded-but-wrong fact tells a reader
something false about the artifact in their hands.

The amendment lands with **item 6.3**, not here, because amending §3 now would describe a dependency
the build does not yet have — the same falsehood in the other direction.

## Two register rows, and a correction to something this project said two items ago

Verifying NFR-11's posture meant re-rendering and diffing the workflows. That answered two open rows:
**R-07**'s twelve reverting references and its SHA-pinned `action-gh-release` regression are **no
longer reproducible** — the full recursive diff is now exactly one hunk, five comment lines — and
**R-02**'s tag-pinned actions are all full SHAs. Both *reported, not closed*; the register is
`security-auditor`-owned and item 8.6 runs the next pass.

**And it corrects item 8.9.** I filed that item during item 5.0 claiming 5.0 found the `ci.yml` drift.
The audit found it first, as R-07. Item 5.0 found the residue left after R-07's main body was
remediated. The finding stands and the gate is still missing; only the credit moves — and the lesson
is that a "new" finding in a repo with a risk register is worth checking against the register first.

## Where the project stands

Items **6.1–6.4** are unblocked once RFC-0005 is accepted — **6.1 included**, which is the correction
above. Each carries at least one ADR: the JWKS posture, the cap's soundness asymmetry, the OWASP
dependency, and the gate threshold are all security-relevant decisions under the enterprise posture.

## What the next session needs to know

- **Do not start 6.1 before RFC-0005 is Accepted.** The algorithm profile is ADR-003's and needs
  nothing from this RFC; the JWKS trust posture is entirely this RFC's.
- **Item 6.3 must verify three things rather than assume them:** OWASP Java Encoder's zero transitive
  dependencies (`mvn dependency:tree`), no OWASP type in a published signature, and a non-transitive
  `requires`. If the first has changed, the decision reopens.
- **Item 6.4 must introduce the dependency-check plugin before tightening it**, and should expect to
  need an NVD API key — stated in the RFC as a question to answer by running it, not as a fact.
- **No public type count is stated in this RFC, deliberately.** RFC-0003 stated one and was wrong;
  the surface here depends on the `KeyProvider` SPI shape items 6.1–6.3 still own. Item 8.1 gets it
  from japicmp.
