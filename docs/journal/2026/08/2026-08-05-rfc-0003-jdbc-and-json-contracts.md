# 2026-08-05 — RFC-0003, and two collisions the specification does not admit to (ROADMAP item 4.0)

**Milestone 4 opens with its design item, the third design-only item after RFC-0001 and RFC-0002.**
Second checkpoint today — item 3.3 closed Milestone 3 this morning. The deliverable is a contract, not
a feature: no production code changed and the test count is unmoved at 333. It is drafted **unapproved**
and blocks all five M4 implementation items until the owner says otherwise.

## What changed

[RFC-0003](../../../rfc/0003-jdbc-and-json-contracts.md) pins the contracts for **all five** Milestone
4 requirements, not the two the roadmap mandated — the owner widened the scope once the two
entanglements below were on the table. **[ADR-0023](../../../adr/0023-the-owner-approves-this-projects-rfcs.md)**
records that this project's RFC approver is the `owner`, closing a governance deviation that had drifted
in both directions unnoticed. Manifest FR-06 and FR-21 gain `[RESOLVED by RFC-0003]` pointers and the
spec is re-rendered; `delivery_state.refs` gains RFC-0002 (back-filled), RFC-0003 and PR #33, with
`manifest_rev` 4 → 5.

## The two collisions, because they are the reason this RFC is not two pages

**FR-05 and FR-06 contradict each other, and the contradiction is silent.** FR-05 promises
`SimpleJdbcExecutor` a *"try-with-resources lifecycle"*, so it takes its own `Connection` from the
`DataSource`. Inside an FR-06 transaction that is a **second** connection: the work commits outside the
transaction the caller opened, the rollback rolls back nothing that matters, nothing throws, nothing
logs — and **every single-statement test passes**, because with one statement there is no difference to
observe. The bug lives in the seam between two requirements rather than inside either, which is why no
amount of care on FR-06 alone would have found it.

**FR-20's hardening defeats FR-21's headline feature.** `FAIL_ON_UNKNOWN_PROPERTIES=false` is correct
for FR-20's job — tolerate a producer who added a field. Over that same mapper FR-21's *"partial
mapping"* cannot tell an omitted field from a **misspelled** one, so a PATCH client believes it changed
something it did not. One mapper, two jobs, opposite defaults.

## The decisions worth carrying forward

- **Explicit `Connection` transport, no ambient current connection.** The `ThreadLocal` is the
  ergonomic option and it fails in the direction that matters: a `DataSource`-backed executor would
  change its transactional semantics depending on the calling thread's state, and a hand-off to
  `CompletableFuture` or FR-09's own `AsyncExecutor` would silently revert to auto-commit.
- **Ambient state is then adopted in exactly one place, and the asymmetry is the transferable part.**
  Nesting is refused through a thread-scoped depth counter. As *transport* an ambient value fails
  **open into the wrong behaviour**; as a *detector* it fails **open into the documented behaviour**.
  A detector that fails open is safe; transport that fails open is the bug.
- **`Result.Err` from the callback commits.** *The exception channel demarcates the transaction; the
  value channel does not.* Interpreting `Err` as a rollback signal would give a core type a second
  meaning in one method and is ambiguous for a nested `Result`.
- **FR-21's null-vs-absent is answered beside the value, not inside it.** `PartialUpdate<T>` carries
  the set of property names the document actually contained, so nothing is widened: no `Optional`
  field (ADR-0011 calls it an anti-pattern), no `TYPE_USE` on `@Nullable`, no `null` in a `Result`
  (ADR-0012) — and it composes with records, which both rejected encodings could not.
- **The same object resolves the FR-20 collision**: `readPartial` rejects an unknown property while the
  mapper stays lenient. Leniency tolerates an unknown *addition*; strictness refuses an unknown
  *instruction*. A per-operation check, not a mapper-wide flag.

## What the next session needs to know

- **RFC-0003 is `Proposed`, and merging the PR is not acceptance.** The draft-then-flip sequence item
  3.0 established is repeated verbatim: `approved-by: (none — Proposed)` in the drafting commit, and a
  **separate** commit flips `Status` to `Accepted` on the owner's word, so the two acts stay visible as
  two acts. **Items 4.1–4.5 are blocked until approved, not merely until merged.**
- **`rfc_check.py` now FAILs by design, and ADR-0023 is why.** The gate requires
  `approved-by: tech-lead`; RFC-0001 wrote that and passes, RFC-0002 wrote `owner @danielPoloWork` and
  fails. The gate's expectation also contradicts `AGENTS.md` §6, because `tech-lead` is the *authoring*
  role — satisfying it literally is self-approval. **Do not "fix" the red verdict by writing
  `tech-lead`**; quote the output and point at the ADR. Wiring this tool into CI as-is would make the
  pipeline red for a reason the pipeline cannot explain.
- **`authority_check.py` should be run per role, not once.** Item 4.0 legitimately spans two: `tech-lead`
  owns `docs/rfc/**` and is DENIED the other six paths, while **`enterprise-architect` covers all eight**
  (`ROADMAP.md`, `CHANGELOG.md`, `docs/adr/**`, `docs/journal/**`, `orchestrator/project.yaml`,
  `docs/specs/**`). One human holds both, so the answer is *which role covers which path*, not an
  override of the role table. Worth remembering for items 5.0, 6.0 and 7.0, which have the same shape.
- **Three name collisions were caught before the code, by one test derived from ADR-001:** rename where
  a wrong choice **compiles and diverges**, keep where it cannot compile. `DataAccessException` →
  **`JdbcAccessException`** (Spring ships one for the same job, so a host's `catch` silently fails to
  match and the exception escapes to the 500 fallback), `Sort` → `PageSort`, and `RowMapper` **keeps its
  name** (a wrong import cannot compile, and the two are behaviourally identical). Milestones 5–7 add
  `CustomThreadPoolFactory`, `AsyncExecutor` and `DistributedLock` — apply the same test there.
- **FR-19's mapping table is short two rows, and one of them is a live misattribution.**
  `JdbcAccessException` → 500 + alert and `JsonConversionException` → 400 are filed on **item 7.1**,
  together with the rule that the fallback handler must not render a cause chain's `getMessage()` —
  which is where the driver's SQL and Jackson's source snippet live. Separately, FR-07's whitelist
  violation throws `ValidationException` rather than `IllegalArgumentException` precisely because the
  table has no row for the latter, so it would report **client-supplied input as a 500**.
- **`INCLUDE_SOURCE_IN_LOCATION` is a second FR-20 gap the requirement never named.** Jackson embeds a
  snippet of the source document in a parse error; FR-19 turns that into an RFC 7807 body. Disabled
  explicitly rather than trusted as a default, for the reason RFC-0001 wrote UTF-8 out instead of
  calling `Charset.defaultCharset()`. **Two C-01 call sites are owed but not registered** — the
  register's evidence column takes tests, and there are none until 4.1–4.4 land.
- **`C-03` gains no consumer, and that is a decision.** FR-07's whitelist compares exactly and
  case-sensitively, because SQL identifier folding is vendor-specific — PostgreSQL lower, Oracle upper,
  MySQL filesystem-dependent — so normalising would pick a vendor. RFC-0002 made C-03
  security-load-bearing for FR-16; RFC-0003 deliberately keeps it out of FR-07.
- **No new trust boundary, unlike RFC-0002.** B3 already covers `jdbc → DataSource`, and both the
  handed-over `Connection` and the untrusted JSON document sit inside B1. Stated explicitly in the RFC
  because a reader of RFC-0002 — which routed a missing boundary to item 8.6 — will look for the same
  here and should find the reasoning rather than silence.
- **The M4 public surface is 15 types, stated now rather than discovered at item 8.1.** The two
  descriptors answer one question in opposite directions: `d4np-jdbc` takes **`requires transitive
  java.sql`**, because `Connection` and `ResultSet` appear in interfaces the *consumer implements*;
  `d4np-json` keeps Jackson **non-transitive**, which is only consistent because FR-21 mints its own
  `JsonTypeToken` rather than exposing `TypeReference`. The mint is what makes the second answer honest
  — and it stops a Jackson major version that moved that type from becoming **our** MAJOR bump.
- **NFR-03 is the one performance gate in this project that can be a real CI gate today**, and item 4.3
  should say so when it lands. NFR-01's 2 ns/op and NFR-06's 400 MB/s are *absolute* numbers against a
  named machine, which is why item 8.3 exists. NFR-03 is a **relative** comparison between two
  harnesses in the same JMH invocation, so a slow runner slows both arms and the machine cancels.
- **`RowMapper` rather than reflective mapping is what makes NFR-03 reachable at all.** A lambda over
  the same `ResultSet` *is* the hand-written loop plus one virtual call, so the ≤10% budget becomes a
  statement about the framing — statement preparation, parameter binding, result iteration. Per-row
  reflection would spend the budget before the framing was measured.
- **Three back-fills, each a one-token fix in a line this item was already editing.** `refs.rfcs` gains
  RFC-0002 (design step 6 was skipped for item 3.0, although the manifest's own comment promised the
  list would grow); `manifest_rev` moves 4 → 5, because `render.py` calls it *"the
  optimistic-concurrency counter"* for the **file**, so a content write moves it even with no
  transition; and `docs/adr/README.md`'s count read **24** when it should have read 26 — ADR-0021 and
  ADR-0022 added their rows without incrementing it, because `adr-index` asserts the bijection and
  every status but **not** that number. Now 27. **A number no gate checks is a number that drifts.**
- **ADR-0012's prediction did not hold, and that is the useful direction to record.** It named item 4.4
  as a plausible first `Result<Unit>` consumer and routed the signature question here. A transaction
  runner's failures are infrastructure faults, which RFC-0001's own table assigns to the unchecked
  shape, so `inTransaction` returns `T` and throws. The cost of the wrong guess is **zero**, which is
  exactly the property ADR-0019 bought when it refused to wait for this call site — the second time in
  this project that declining to defer paid off.
- **Item 4.4 owes two things that are easy to under-build:** a named jcstress harness for the
  thread-scoped depth counter (spec §6 — a thread-safety claim without one is not a claim), and a
  *demonstration* that a leaked `Connection` breaks, in the shape items 2.4 and 3.3 used rather than a
  Javadoc sentence.
- **The connection-ownership rule is a contract, not a control.** Nothing stops a captured
  `DataSource`-backed executor from being used inside a transaction block. What the decision buys is
  that the mistake is visible in the lambda's capture list instead of hidden in ambient state. Anyone
  reporting that as a gap should be pointed at RFC-0003 §Alternatives 1.

## Verification

Temurin **17.0.20+8** and **21.0.12+8**, Maven 3.9.9, Windows 10 Pro 19045: `clean verify`
(**`Tests run: 333`**, unchanged — this item ships no production code) on both; `-Pjmh,jcstress verify`
on both, zero failed and zero interesting; CI's real quality goals `spotless:check -pl '!d4np-bom'`,
`checkstyle:check` and `validate`; `python tools/consistency_lint.py`; and
`traceability.py ROADMAP.md RFC-0001 RFC-0002 RFC-0003` → `roadmap-covers-rfcs: OK`, so back-filling
the two ids was checked rather than assumed.

**The spec re-render was verified as a diff, not as a claim.** A fresh
`render.py orchestrator/project.yaml --out <tmp>` after the manifest edit produces a
`docs/specs/01_spec_utils.md` **byte-identical** to the committed file — the ADR-0010 property item 1.5
learned the hard way, and stronger evidence than item 3.0's "checked idempotent beforehand". The render
was still never applied in place: it writes 44 templates and would overwrite 16 hand-maintained files
(risk **R-07**).

**Item 2.1's `@Contended` workaround was confirmed live rather than assumed.** jcstress still prints
`[N/A]` for its own probe — that is the documented, unfixable half, since the probe derives class names
from a directory classpath entry — but `-XX:-RestrictContended` appears in **both** configuration flag
lists this run, which is the visible half `-jvmArgsPrepend` was chosen to give. The padding is in
effect, so the harnesses retain the sensitivity they exist for.

Two CI commands still cannot be reproduced locally and are unchanged since item 3.1: `japicmp:cmp`
resolves no plugin (item 8.1) and `-Pcoverage` matches no profile, so Maven warns and runs a plain
`verify` (item 8.2). `rfc_check.py` reports FAIL, **by design** — see ADR-0023.
