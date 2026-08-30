# Audit risk register — egl-utils-java

The **outcome** side of the security surface: scored findings of a concrete audit run.
`SECURITY.md` is the policy, [`threat-model.md`](threat-model.md) is the STRIDE analysis,
[`../compliance/README.md`](../compliance/README.md) is the standing control register — this file
records what a given audit actually found.

> **Location note.** `docs/security/README.md` places the register in "audit records" without naming
> a path, and no such directory is scaffolded. It is filed here, beside the threat model it derives
> from and inside the `security-auditor`'s own directory. Recorded as a choice, not a convention.

---

## Run 2026-07-27 — first audit

| | |
|---|---|
| **Phase** | `scaffold → audit` (`human_gate: false`; entry gates `consistency-lint`, `self-review` both green) |
| **Subject** | PR #9, the bootstrap scaffold — 46 files, 2942 lines changed |
| **Risk score** | **critical** — factors: `security-surface`, `large-change`, `wide-blast-radius` |
| **Security-auditor gate** | **REQUIRED** (`mandatory_gate_level: high`, `domain=software`) — so the STRIDE pass was mandatory |
| **Traceability-lint** | **FAIL** — 2 dangling edges (see R-04) |
| **Threat model** | [`threat-model.md`](threat-model.md), 6 boundaries × 6 STRIDE categories, every cell filled |
| **Amended** | 2026-07-27, before publication — see below |

> **Amendment, recorded rather than absorbed.** Between scoring this audit and opening its PR, three
> Dependabot PRs (#10 `action-gh-release 2.6.2→3.0.2`, #11 `setup-java 4→5`, #12 `checkout 6→7`) were
> merged into `main`. That changed two findings' facts, so the register was amended before publication
> rather than shipped describing a state that no longer held: **R-02**'s version numbers moved (the
> finding stands — `@v5` is as mutable as `@v4`), and a new **R-07** records the manifest-vs-rendered
> drift the merges created. Silently updating the numbers would have hidden that a register can go
> stale between analysis and publication; the earlier version of this run had already been written
> against pre-merge facts.

**Why "critical" is not alarming here, and why it still earned a real audit.** The score is
mechanical: any change touching `.github/**`, `tools/**` or `SECURITY.md` scores
`security_surface: 3`, and the bootstrap render touches all three plus 2942 lines across six
top-level areas. A first scaffold will therefore always score critical. That is the scorer working
as designed, not a judgement that the repo is dangerous — and the correct response is to do the deep
audit rather than to explain the number away. Doing it surfaced two live findings (**R-01**, **R-03**)
that have nothing to do with the render's size.

**Standing caveat on every finding below.** `src/` holds only `.gitkeep` — there is no product code.
So no finding is a code defect; every one is a **control that is absent, unenforced, or
unspecified**. The severities reflect realistic impact *once the specified code exists*, except
R-01/R-02/R-03, which are live today.

### Findings

| ID | Severity | Component | Finding | Realistic impact | Mitigation | Owner item |
|---|---|---|---|---|---|---|
| **R-01** | **high** | `SECURITY.md` · repo settings | `SECURITY.md` directs reporters to GitHub private vulnerability reporting, but the feature is **disabled** — verified: `GET /repos/…/private-vulnerability-reporting` → `{"enabled": false}` | The documented channel does not exist. A researcher following the policy finds no *Report a vulnerability* option; the realistic fallback is a **public issue** — precisely what the policy's first bold line forbids. For a library shipping JWT and AES primitives, that means a live vulnerability disclosed in the open before a fix exists | Enable private vulnerability reporting (Settings → Security → *Private vulnerability reporting*). One toggle, no code. Until then `SECURITY.md` overstates what the repo supports | **new** — needs a roadmap item or immediate owner action |
| **R-02** | **medium** | `.github/workflows/**` | **11 action references are tag-pinned, not SHA-pinned** — now `setup-java@v5` (×7) and `checkout@v7` (×4) after the amendment below; they were `@v4`/`@v6` when first scored. All 11 come from the *manifest-authored* `ci.setup_steps` / `ci.extra_jobs`; every template-provided action **is** SHA-pinned (`checkout@3d3c42e5… # v7.0.1`) | A mutable tag re-pointed at attacker code executes in CI with a repo-scoped `GITHUB_TOKEN`, and in `release.yml` alongside the GPG signing step. Impact is bounded today because the toolchain jobs **skip** until `pom.xml` exists, and no secrets beyond `GITHUB_TOKEN` are in play — but `release.yml` is the one that matters at 1.0.0. A version bump does **not** reduce this: `@v5` is as mutable as `@v4` | Pin all 11 to SHAs in `orchestrator/project.yaml` and re-render. The inconsistency is instructive: the factory pins what *it* controls, and manifest-authored fragments silently escape that discipline | **8.5** (NFR-12 gap) |
| **R-07** | **high** | `orchestrator/project.yaml` ↔ `.github/workflows/**` | **The rendered workflows have drifted from the manifest, and re-rendering silently reverts merged security updates.** Three Dependabot PRs (#10, #11, #12) were merged into the *generated* workflow files while the manifest still declares the old versions. Verified by rendering the current manifest to a scratch directory and diffing: **12 references** would change back — `setup-java@v5 → @v4` (7×), `checkout@v7 → @v6` (4×), and `softprops/action-gh-release@3d0d9888… # v3.0.2 → @3bb12739… # v2.6.2` | Any future `render.py` run **downgrades three merged dependency updates with no warning** — a re-render is routine (four were run today alone), and nothing in the render output flags a regression. The `action-gh-release` case is the sharpest: a correctly SHA-pinned action reverts to an older SHA, so the drift actively *undoes* supply-chain hardening rather than merely failing to add it. This is the structural conflict behind R-02: Dependabot's `github-actions` ecosystem edits rendered files whose source of truth is the manifest | Port the merged versions **into `orchestrator/project.yaml`** and re-render, ideally as SHA pins so R-02 closes in the same change. Longer term the fix is upstream: EADOS solves this for its own repo with `tools/sync_action_pins.py` (upstream #76), but that syncs *factory* CI to *factory* templates and has no consumer-repo equivalent. Do **not** simply re-render before porting — that is the failure mode | **8.5**, but the drift is live **now** and should not wait for M8 |
| **R-03** | **medium** | repo settings · `main` | **Branch protection is absent** — `GET /repos/…/branches/main/protection` → **404**, while the repo is **public**. `AGENTS.md` §6 names public/Pro as exactly the condition that unblocks the full ruleset | Nothing mechanically prevents a direct push to `main`, a force-push, or a merge that bypasses review. The contract's "one PR at a time, owner squash-merges" is policy honoured by habit, not enforcement. Note PR #9 merged as a **merge commit** although the contract states squash-only — evidence the setting is not in force | Enable the ruleset: require a PR, squash-only, no force-push, no deletion, linear history; `docs/workflow/github-setup.md` carries the one-time steps | **1.9-adjacent** — needs a roadmap item; `github-setup.md` documents it but nothing tracks it |
| **R-04** | **medium** | traceability graph | `traceability-lint` **FAILS**: `[pr-no-rfc] PR 2`, `[pr-no-rfc] PR 1`. Both predate RFC-0001 — #1 bootstrapped the governed project, #2 recorded the `init → design` transition | `traceability-lint` is `blocking: true` and an **entry gate of `audit → migrate`**, so this blocks that transition until resolved. It is not a data error: those PRs genuinely implement no requirement. Assigning them an RFC retroactively would be a lie recorded as evidence | Decide the policy: exempt governance/phase-record PRs from the RFC edge (the honest reading), or narrow `derive_links.py`, which currently emits any PR carrying a milestone and then hands the lint PRs it must fail. Do **not** backfill fake RFC ids | **new** — needs a decision before `audit → migrate` |
| **R-05** | **high** | `AuditLog` (FR-16) | **No redaction policy specified.** As written, `AuditLog` records before/after values of every state change | An audit store is typically retained longer and replicated wider than application logs. Faithfully recording secrets and PII into it converts a compliance feature into the project's widest data-leak surface — and under `posture: enterprise` this is exactly the control class the bar exists for | Field-level allowlisting, a `@Sensitive` opt-out, an explicit never-capture list — settled in **RFC-0002 before any code**, which is why item 3.0 blocks 3.3 | **3.0 → 3.3** |
| **R-06** | **medium → resolved** | `JwtTokenProvider` (FR-11) · JWKS | **JWKS trust posture unspecified.** ADR-003 pins caching and rate-limited refresh but says nothing about TLS verification, certificate/key pinning, or an allowlist of permitted JWKS origins | If a JWKS URL is caller-configurable, an attacker who can influence it (SSRF, config injection, DNS) serves their own key document and every subsequent token verifies. Relying on the JDK default trust store is a reasonable floor but an unstated one — and "unstated" is what this audit exists to catch | Specify in **RFC-0005**: JWKS origin allowlist, explicit TLS posture, and whether the URL may come from caller config at all  **Specified by RFC-0005 and implemented in item 6.1.** `JwksSource` fixes the URL at construction from an origin allowlist, never consults `jku`/`x5u`, refuses non-HTTPS rather than upgrading, refuses a 3xx rather than following it, and bounds the fetch in time and size. Certificate pinning is refused with a stated reason rather than silently omitted. **Reported here rather than struck through:** this register is `security-auditor`-owned and item **8.6** runs the next full pass, which is the role that closes a row | **6.0 → 6.1, done** |

### Remediation applied in this PR — R-02 closed, R-07 resolved with one residual

R-07 degrades with time (any re-render reverts merged updates), so it was fixed rather than filed. The
fix subsumes R-02, because porting the merged versions as **mutable tags** would have recreated the
same drift on the next Dependabot run — a fix guaranteed to expire. They were ported as **SHA pins**
instead.

| Action | Before | After | Sites |
|---|---|---|---|
| `actions/setup-java` | `@v5` (tag) | `@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95 # v5.6.0` | 7 |
| `actions/checkout` | `@v7` (tag) | `@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1` | 9 |
| `softprops/action-gh-release` | `@3bb12739… # v2.6.2` would have been re-rendered over the merged `v3.0.2` | `@3d0d9888cb7fd7b750713d6e236d1fcb99157228 # v3.0.2` | 1 |

SHAs were resolved from the GitHub refs API and cross-checked, not copied from a changelog: the
`setup-java` `v5` major tag and the `v5.6.0` release tag point at the **same** commit today, so the pin
is unambiguous. The `checkout` v7.0.1 SHA is deliberately **the same one the factory's own template
pins**, which keeps the manifest-authored pins in lockstep with the template-provided ones and avoids
introducing a second drift class between them.

**Verification — the state that actually matters:**

```
grep -rE "uses: [^@]+@(v[0-9]|main|master)" .github/workflows/   -> no matches
diff <on-disk workflows> <fresh render from the manifest>        -> IN LOCKSTEP, both files
pin inventory: 9 checkout · 7 setup-java · 1 setup-python · 1 action-gh-release  = 18, all SHA
ci.yml: 8 jobs, 6 gated on bootstrap (guard intact); release.yml: draft-release
```

**Residual — stated, not buried.** The `action-gh-release` pin is **hardcoded** in
`.eados-core/templates/.github/workflows/release.yml.tmpl:39` with no placeholder, so the manifest has
no hook for it. Fixing the cause therefore meant patching the **vendored template** — and
`.gitignore:65` ignores `/.eados-core/`, so **that patch is local-only and is not in this PR**. The
rendered `release.yml` committed here is correct, but anyone re-rendering from a clean bundle gets
`v2.6.2` back. The durable fix is upstream bumping the template pin; upstream v2.12.0 still ships
`v2.6.2`. This is the same root cause as [pgs-eados#350](https://github.com/danielPoloWork/pgs-eados/issues/350),
and the reason its recommendation matters beyond tidiness.

So: **R-02 → closed.** **R-07 → resolved for the 17 manifest-controlled references, residual on 1
template-controlled reference that a consumer repo cannot durably fix.**

### Positive controls — verified, not assumed

Recorded because an audit that lists only problems misrepresents the posture:

- **Secret scanning enabled** and **push protection enabled** (verified via the repo API). A targeted
  scan for literal `password=`/`token=`/`api_key=`/PEM material across the tree found **nothing**.
- **`consistency-lint` and `self-review` both green** on merged `main` — the `scaffold → audit` entry
  gates were satisfied by evidence, not asserted.
- **Injection defended by construction, not by discipline** — `SimpleJdbcExecutor` offers no
  string-concatenation overload, so `PreparedStatement` cannot be bypassed by a careless caller. This
  is the strongest control in the specification.
- **`OutputEncoder` correctly scoped** — v1 specified an input "sanitizer" against XSS and SQL
  injection; v2 rescoped it to context-aware *output* encoding. Removing a false-security control is
  a security improvement that a register would otherwise never credit.
- **Dependency hygiene is structural** — `maven-enforcer` makes the zero-dependency core a build
  property (NFR-08), so a PR leaking `com.fasterxml` into core fails the build rather than review.

### Not found

- **No code defect** — there is no product code, so no [bug-ledger](../bugs/README.md) record.
- **No vulnerability warranting coordinated disclosure** — hence no draft advisory. Worth noting that
  opening one today would be blocked by **R-01** itself.
- **`dependabot_security_updates: disabled`** was observed but is *not* filed as a finding:
  `.github/dependabot.yml` ships version updates for 2 ecosystems and NFR-11 puts OWASP
  Dependency-Check on every PR, so CVE coverage is specified by another control. Enabling it is
  cheap hardening, not a gap. Recorded here so the observation is not silently dropped.

### Disposition

Two findings are **live today and fixable with a settings toggle** — R-01 and R-03. Both are owner
actions; neither needs code, a PR, or a milestone. R-01 is the one to do first: it is `high`, it takes
one click, and until it is done the project's stated security policy is a promise the repository does
not keep.

R-02 has a scheduled owner (8.5). R-05 and R-06 are correctly sequenced behind their RFCs (3.0, 6.0) —
the roadmap already blocks the implementing items on them, which is the contract-first ordering
working as intended. **R-04 needs a decision**, not a fix, and it blocks `audit → migrate`.

Three findings — R-01, R-03, R-04 — have **no roadmap item** (R-07 was fixed in this PR rather than scheduled; R-02 closed with it). They are recorded here, but a register
entry is not a plan; they need items or owner action to become tracked work.
