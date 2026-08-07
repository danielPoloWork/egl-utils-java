# Threat model — egl-utils-java

> **Owner:** the **security-auditor** role (it drafts here; findings feed the audit risk
> register). Produced and kept current by the **audit threat-modeling sub-mode**
> (`/eados security` → `/eados audit`). Method: **STRIDE**. Scaffolded empty on purpose —
> an explicit `n/a` with a reason is honest; an unexamined boundary is not.

**This pass:** 2026-07-27, first audit. Risk score **critical**
(`security-surface`, `large-change`, `wide-blast-radius`) → the security-auditor gate was
**REQUIRED**, so this pass is mandatory, not optional. Findings feed
[`risk-register.md`](risk-register.md).

**Read this caveat first.** At the time of this pass `src/` contained only `.gitkeep` — **no product
code existed**. So almost every threat below is **design-stage**: the "mitigation" is a control the
specification commits to, and `Status` says whether that control is *implemented*, *specified* (most),
or *unspecified* (a real gap). Calling a specified-but-unwritten control "mitigated" would be the
single easiest lie in this document, so it is not made. The one boundary that is live today is the
build and supply chain — and that is where every confirmed finding sits.

> **Updated 2026-07-30 (ROADMAP item 2.1), and deliberately only where it had become false.** The
> first product code has landed — `Result`, `ErrorDetail` and `BusinessException` in `d4np-core` — so
> the "no product code exists" framing above is no longer true as written and is now past-tense. The
> **analysis is unchanged**: no trust boundary moved, no new untrusted input or external service was
> introduced, and no `Status` glyph was upgraded, because the B2 information-disclosure control is
> enforced by FR-19's handler (item 7.1), not by the type that carries the message. This file is
> **security-auditor-owned** and item 8.6 runs the next full STRIDE pass; the edit here is a factual
> correction, not a re-analysis.

A library's boundaries are not a service's: `egl-utils-java` has no network listener, no tenancy, no
session. Its attack surface is **what a consuming application hands it**, plus **what it hands
back**, plus **how it is built and shipped**.

## 1. Scope & trust boundaries

| # | Boundary | Untrusted inputs crossing it | Assumptions |
|---|---|---|---|
| **B1** | **Consumer → library API** (the primary boundary; every module) | JWT strings, ciphertext envelopes, SQL bind parameters, `PageRequest` page/size/sort fields, resource names, strings to encode, factory/strategy keys | The caller may pass wholly attacker-controlled values. The library must not assume validation happened upstream — it is a library, so it is the last line, not the first |
| **B2** | **Library → host framework** (`spring-adapter`, FR-19) | none inbound; **outbound** `ErrorDetail.code`/`.message` become RFC 7807 `problem+json` reaching the HTTP client | The host terminates TLS and routes; the adapter controls what leaves in the response body |
| **B3** | **Library → external services** (`lock-redisson` → Redis; `jdbc` → `DataSource`; `security` → JWKS endpoint) | Redis responses, JDBC driver responses, **JWKS documents fetched over the network** | The host owns connection config and credentials; the JWKS endpoint is reachable and its TLS is verified by the JDK default trust store |
| **B4** | **Key material → crypto** (`KeyProvider` SPI: env var / JCEKS / KMS → `AesEncryptor`, `JwtTokenProvider`) | key ids parsed out of the ciphertext envelope `v1:{keyId}:{iv}:{ct+tag}` | Keys are supplied by the host and never hard-coded; the SPI implementation is trusted, the *key id in the envelope is not* |
| **B5** | **Build & supply chain** (GitHub Actions, third-party deps, Maven Central publication) | third-party artifacts (Nimbus, Jackson, Redisson, Micrometer), GitHub Action code resolved at run time, PR content from forks | CI runs with a repo-scoped `GITHUB_TOKEN`; release signing uses a GPG key held by the owner |
| **B6** | **Test scope → production** (`d4np-test`, FR-25) | none; the risk is *reachability* — test-only reflection helpers requiring `--add-opens` becoming importable from production code | The CI dependency check fails production code importing the test module |

Deliberately **out of scope**, with reasons: multi-tenancy (a library holds no tenant state), session
management (no sessions), network edge / TLS termination (the host's job — B2's assumption), and
authorization policy (the library supplies JWT *verification* primitives, never an authorization
model).

## 2. STRIDE pass

Every cell carries a threat, a mitigation, or an explicit `n/a (reason)`.
Status: ▢ specified-not-implemented · ⚠ **gap** · ✅ implemented & verified · ➖ n/a.

### Spoofing — is the caller who it claims?

| Threat considered | Boundary / component | Mitigation / control | Status |
|---|---|---|---|
| Forged JWT accepted — `alg=none`, or an HS256 token verified against an RS256 public key (algorithm confusion) | B1/B3 · `JwtTokenProvider` (FR-11) | ADR-003 hardened profile: **per-key algorithm allowlist**, never HS256 and RS256 on one verifier, `alg=none` structurally impossible; negative tests for alg-confusion and alg=none are named deliverables | ▢ |
| Expired or wrong-audience token accepted | B1 · `JwtTokenProvider` | mandatory `exp` with configurable skew (default 60 s); `typ`/`aud`/`iss` checks **on by default** | ▢ |
| Poisoned JWKS — attacker-controlled key document served to the verifier | B3 · JWKS fetch | JWKS caching with rate-limited refresh (ADR-003). **Not specified: TLS/pinning posture or an allowlist of JWKS origins** — relies on JDK default trust | ⚠ |
| Weak HS256 secret enabling offline forgery | B4 · `JwtTokenProvider` | HS256 secrets under 256 bits **rejected at construction** | ▢ |
| Malicious commit attributed to the owner | B5 · git history | ➖ n/a — commit signing is not a stated control; the repo's protection is review + squash-merge by the owner. Recorded as a deliberate omission, not an oversight | ➖ |

### Tampering — can data or code be altered in flight or at rest?

| Threat considered | Boundary / component | Mitigation / control | Status |
|---|---|---|---|
| Ciphertext or associated context altered undetected | B1 · `AesEncryptor` (FR-12) | AES-**256-GCM** only with a 128-bit auth tag — AEAD makes tampering detectable; ECB prohibited | ▢ |
| Envelope `keyId` swapped to force decryption under an attacker-chosen key | B4 · envelope `v1:{keyId}:{iv}:{ct+tag}` | The key id is **untrusted input** (B4). GCM authentication fails if the key is wrong, so a swap yields a `CryptoException`, not plaintext. **No AAD binding the envelope header to the ciphertext is specified** — a known FR-12 gap already scheduled to item 6.2 | ⚠ |
| SQL altered via string concatenation | B1 · `SimpleJdbcExecutor` (FR-05) | Defended **by construction**: no string-concatenation overload exists, so `PreparedStatement` is enforced rather than recommended | ▢ |
| Injection via `ORDER BY` — a column name cannot be a bind parameter | B1 · `PageRequest` (FR-07) | Sort fields validated against a **caller-supplied whitelist**; violation throws at construction | ▢ |
| A mutable Action tag re-pointed to attacker code, executing in CI with `GITHUB_TOKEN` | B5 · `.github/workflows/**` | Template-provided actions are SHA-pinned. **11 references authored in the manifest's CI fragments are tag-pinned** (`setup-java@v5`, `checkout@v7` since the Dependabot merges) — register **R-02**; the merges also created live manifest-vs-rendered drift that a re-render would revert — register **R-07** | ⚠ |
| Dependency substitution / typosquat pulling a hostile artifact | B5 · Maven resolution | `maven-enforcer` banned-dependency + convergence rules (NFR-08); OWASP Dependency-Check per PR (NFR-11). Neither is active until the reactor exists | ▢ |
| Published artifact modified between build and consumer | B5 · Maven Central | GPG-signed artifacts + reproducible-build plugin (NFR-12). **No build provenance/attestation** — item 8.5 | ▢ |

### Repudiation — can an action be denied for lack of a trail?

| Threat considered | Boundary / component | Mitigation / control | Status |
|---|---|---|---|
| A state change cannot be attributed after the fact | B1 · `AuditLog` (FR-16) | who / when / before-after values recorded. **Shipped in item 3.3:** `capture(actor, action, before, after)` refuses a blank actor or action, so an unattributable record cannot enter a store; a blocked component still records **that** it changed, which keeps attribution intact for the values that are never shown | ✅ |
| The audit trail itself becomes the leak — secrets and PII faithfully recorded into a store retained longer and replicated wider than app logs | B1 · `AuditLog` | **Closed in item 3.3 under the policy RFC-0002 settled first** ([ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md), control **C-05**): redaction happens at capture, so the event that leaves the library holds no raw value and no API returns one — a sink cannot leak what it never receives. Four layers with a fixed precedence (never-capture list > `@Sensitive` > `@Audited` component > `@Audited` type > omitted), whole-token matching, and a composite refused rather than rendered, because `String.valueOf` on a record prints every component including the `@Sensitive` ones. Proven by removing the guard and watching the password appear in the event. **The ✅ is about the record's content, not about the flow:** §1 still has no boundary for "library → host-supplied audit store", now the widest-retention sink in the system, and RFC-0002 routed that pass to **item 8.6** because the security-auditor owns this document | ✅ |
| Release cannot be traced to its source | B5 · release flow | SemVer + release notes generated from conventional commits; `links.yaml` records pr → rfc → milestone → commit. **Two PRs currently have no requirement lineage** — register **R-04** | ⚠ |

### Information disclosure — can data leak across a boundary?

| Threat considered | Boundary / component | Mitigation / control | Status |
|---|---|---|---|
| Crypto internals or partial plaintext leaked through an exception | B1 · `AesEncryptor` | `CryptoException` **never** leaks `javax.crypto` internals or partial plaintext (spec §5, normative) | ▢ |
| Server-side detail reaching the HTTP client via the error body | B2 · `GlobalExceptionHandler` (FR-19) | Normative mapping table; `ErrorDetail.message` is documented **caller-facing text that must not carry secrets, credentials or PII** (RFC-0001) — that statement is now in the shipped `ErrorDetail`/`BusinessException` Javadoc rather than only in the RFC (item 2.1), and is registered as control **C-01**. Still ▢, because the control that *enforces* it is the FR-19 handler, which is item 7.1. `StrategyNotFoundException` → 500 + alert rather than echoing internals. **Item 4.1 hardened one input to this row and sharpened one obligation on it.** `JsonMapper` disables `INCLUDE_SOURCE_IN_LOCATION` explicitly — the default **flipped** between two Jackson versions inside the supported matrix, enabled in 2.15.3 and disabled in 2.22.1 — and every `JsonConversionException` message is built from the target type and the structural path only, never from Jackson's text. But running it narrowed what that buys: the setting governs the source *snippet*, while `InvalidFormatException` quotes the rejected value in the body of its own message, which no Jackson setting controls. So the exception this library throws is clean and **its cause is not**, which makes RFC-0003's advice to item 7.1 — the fallback handler must not render a cause chain's `getMessage()` — a requirement rather than a caution. No row is added for it, per RFC-0003 §Consequences; it belongs to this one. **Item 4.2 found a second channel into this row and closed it** ([ADR-0026](../adr/0026-rewrite-jacksons-unchecked-conversion-failure.md)): FR-21's `convert` fails through `ObjectMapper.convertValue`, which rethrows Jackson's mapping failure as an **unchecked** `IllegalArgumentException` carrying Jackson's own message — so the wrapping rule, written against the *checked* exception, did not reach it, and no compiler or gate would have said so. It is caught and rewritten. Item 4.2 also widened the row past exceptions: `PartialUpdate.toString()` renders the value's type and its property names rather than the value ([ADR-0027](../adr/0027-a-partial-update-renders-names-not-values.md)), because a `toString()` reaches a log more casually than an exception reaches a client. Still ▢, for the unchanged reason — the control that enforces this row is the FR-19 handler | ▢ |
| Known-keys list in `StrategyNotFoundException` disclosing internal configuration to an end user | B1→B2 · `StrategyRegistry` (FR-04) | The keys list is **specified as part of the exception message** for debuggability. Safe only because FR-19 maps it to a **500 with no body detail**; if any adapter ever surfaces that message, it becomes disclosure. Recorded as a **standing coupling**, not a defect | ▢ |
| IV reuse under a fixed key destroying GCM confidentiality | B1 · `AesEncryptor` | Unique random 96-bit IV per operation from `SecureRandom`; IV-uniqueness property test over 10⁷ operations (NFR-06) | ▢ |
| Polymorphic-deserialization gadget chain (the Jackson CVE class, CWE-502) | B1 · `JsonMapper` (FR-20) | **Closed in item 4.1** ([ADR-0024](../adr/0024-take-a-jackson-type-in-one-signature.md), [ADR-0025](../adr/0025-render-java-time-as-iso-8601.md)): default typing is **explicitly deactivated**, and the configured `ObjectMapper` is unreachable — no getter, no `ObjectMapper` in any signature — so there is nothing for a consumer to call `activateDefaultTyping` on, which is what makes this a property of the type rather than of our call path (ADR-0022's rule). `JsonMapperTest.neverLetsTheDocumentChooseTheClass` reads a gadget-shaped document and asserts the class it names is treated as data, with a companion test showing the **same document does instantiate that class** under a mapper with default typing on, so the payload cannot go inert unnoticed. `FAIL_ON_UNKNOWN_PROPERTIES=false` limits shape coupling, not gadget exposure, and is not counted here. **Residual, stated:** a `@JsonTypeInfo` annotation on a *host's own* base type is the host's reviewed decision and is deliberately not overridden — pinned by `annotationDrivenPolymorphismStaysTheHostsDecision` | ✅ |
| XSS via unescaped output in a consuming view | B2 · `OutputEncoder` (FR-13) | Context-aware **output** encoding (HTML body / attribute / JS / URL). Deliberately **not** an input sanitizer — input filtering for XSS manufactures false security | ▢ |
| Credential or key committed to the repository | B5 · repo | GitHub **secret scanning enabled** and **push protection enabled** (verified via API). A targeted scan for literal `key=`/`token=`/PEM material found nothing | ✅ |
| Resource path traversal reading files outside the intended root | B1 · `ResourceLoaderUtils` (FR-24) | Names are absolute; a name containing `..` is **rejected**; resolution is anchored to a caller-supplied `Class<?>` (RFC-0001) | ▢ |

### Denial of service — can the surface be exhausted?

| Threat considered | Boundary / component | Mitigation / control | Status |
|---|---|---|---|
| Unbounded page size exhausting memory on a large table | B1 · `PageRequest` (FR-07) | `1 ≤ size ≤ maxSize`, default 200, configurable; violation throws at construction | ▢ |
| Thread-pool exhaustion or a rejection storm | B1 · `CustomThreadPoolFactory` (FR-08) | Explicit `RejectedExecutionHandler`; 0 jcstress anomalies for rejection/shutdown races; graceful shutdown drains within timeout (NFR-05) | ▢ |
| A failing `Lazy` initializer retried on every call (thundering herd) | B1 · `Lazy` (FR-03) | Default policy is **retry**, chosen deliberately over memoize; RFC-0001 documents this exact trade-off and directs expensive-failure initializers to `memoizingFailures()` | ▢ |
| Distributed lock held forever, starving every other holder | B1/B3 · `DistributedLock` (FR-10) | **Lease time mandatory** — the interface cannot be implemented without one | ▢ |
| JWKS refresh storm against the identity provider | B3 · JWKS fetch | Rate-limited refresh (ADR-003) | ▢ |
| Algorithmic blow-up on hostile input to the case converter | B1 · `StringCaseConverter` (FR-22) | Single-pass tokenizer, linear in code points; total function (never throws on any `String`) | ▢ |
| CI exhausted by fork-PR runs | B5 · Actions | ➖ n/a — the repo has one collaborator and no fork traffic; revisit when external contribution opens | ➖ |

### Elevation of privilege — can a caller gain authority it was not granted?

| Threat considered | Boundary / component | Mitigation / control | Status |
|---|---|---|---|
| Claims trusted before signature verification | B1 · `JwtTokenProvider` | Signature verified **before any claim access** (spec §4) | ▢ |
| Test-only reflection reachable from production, defeating encapsulation | B6 · `ReflectionUtils` (FR-25) | Test scope only; CI check **fails production code importing the test module**; `--add-opens` requirement documented and helpers fail with an actionable message when absent | ▢ |
| A third-party type leaking into a core public API, widening what a consumer must trust | B1 · core/jdbc/concurrent | Spring/Jackson/Redisson types never appear in those public APIs — CI-enforced by `maven-enforcer` (NFR-08), so a violation fails the build, not review | ▢ |
| Direct push to `main` bypassing review | B5 · repo | **Branch protection is absent** (`/branches/main/protection` → 404) while the repo is public — register **R-03** | ⚠ |
| A vulnerability disclosed publicly because the documented private channel does not exist | B5 · `SECURITY.md` | **Private vulnerability reporting is disabled** (`{"enabled": false}`) although `SECURITY.md` instructs reporters to use it — register **R-01** | ⚠ |
| Elevation inside the library via a privileged operation | B1 | ➖ n/a — the library defines no privilege model and performs no authorization decision; it supplies verification primitives only | ➖ |

## 3. Findings → the risk register

Seven threats survived analysis as actionable items and are recorded, scored, in
[`risk-register.md`](risk-register.md): **R-01** disclosure channel absent, **R-02** unpinned CI
actions, **R-03** `main` unprotected, **R-04** traceability dangling edges, **R-05** `AuditLog`
redaction unspecified, **R-06** JWKS trust posture unspecified, **R-07** rendered workflows drifted
from the manifest.

No confirmed, reproducible **defect** was found (there is no code to defect), so no
[bug-ledger](../bugs/README.md) record is opened. No vulnerability warranting coordinated disclosure
was found, so no draft advisory is opened — and note that opening one would currently be blocked by
**R-01** itself.
