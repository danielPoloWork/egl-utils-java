# Compliance docs — egl-utils-java

The **control register** for `egl-utils-java`, present because this project runs under the
**enterprise governance posture** (`governance.posture: enterprise`, ADR-0015; see
[`AGENTS.md`](../../AGENTS.md) §3/§7/§10). It records the controls the project commits to and the
**evidence** each one maps to — so a reviewer can trace a claim ("access is authenticated",
"secrets never land in logs") to the artifact that substantiates it, not to a memory.

This is the **enterprise counterpart** to the always-present security surface: `SECURITY.md` is
the policy, [`../security/threat-model.md`](../security/threat-model.md) is the STRIDE analysis,
the audit risk register is the outcome — and this register is the standing map of *controls →
evidence* the raised bar expects to exist between audits.

## How to use it

- **One row per control.** A control is a commitment the project is held to — an authn/authz
  rule, a crypto choice, a data-handling constraint, a dependency-hygiene gate, a trust-boundary
  assumption.
- **Every control names its evidence.** The ADR that decided it (a security-relevant decision
  **requires** an ADR under this posture — `AGENTS.md` §7), plus where it is enforced or verified
  (a test, a CI gate, the threat model, a code path).
- **Same-PR upkeep.** A change that touches a registered control updates its row in the same PR —
  the `consistency_lint.py` posture check keeps this register and the `AGENTS.md` posture
  declaration in lockstep (neither may exist without the other).

## Control register

The register opens with the controls the shipped code is actually held to. A control appears here only
once something in the repository is bound by it — a register of aspirations is the thing this posture
exists to replace.

| # | Control | Decided in (ADR) | Evidence (test / gate / doc) | Status |
|---|---------|------------------|------------------------------|--------|
| C-01 | **An error message crossing to a client carries no secrets, credentials or PII.** `ErrorDetail.message` is caller-facing text: it becomes the RFC 7807 `problem+json` body at trust boundary B2 and reaches the HTTP client. Diagnostics that must stay in the process go in `ErrorDetail.cause`, which the adapter logs and never serialises | [RFC-0001](../rfc/0001-core-contracts.md) §Cross-cutting (an RFC outranks the spec where it pins a contract, [ADR-0010](../adr/0010-single-specification-authority.md)); error model [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) | Stated normatively in the `ErrorDetail` and `BusinessException` Javadoc (the published contract); [threat model](../security/threat-model.md) §2 information-disclosure row for B2; `ErrorDetailTest` asserts `toString()` does not unroll the cause's stack trace into a log line. **Not mechanically enforceable** — no gate can tell a safe message from an unsafe one; the enforcing control is FR-19's mapping table, item 7.1 | 🚧 partial — contract published, boundary handler not built |
| C-02 | **A failure never crosses a boundary as `null`.** No core method returns `null` or accepts it; `Result.Ok` rejects a `null` payload so an absent value cannot masquerade as a successful outcome, `Lazy.get()` rejects an initializer that produces one, and the two keyed lookups answer a missing key with an empty `Optional` or an exception naming the keys they do hold — `StrategyRegistry`/`StrategyNotFoundException` and `GenericFactory`/`FactoryKeyNotFoundException`. A supplier or builder that produces `null` is rejected at the boundary rather than passed on | [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md); [ADR-0011](../adr/0011-declare-the-nullability-annotation-in-core.md) for how the exceptions are declared; [ADR-0015](../adr/0015-strategy-registry-last-write-wins.md) for the registry's two missing-key shapes | NullAway at `ERROR` over `it.d4np` on the JDK 21+ cells ([ADR-0009](../adr/0009-errorprone-nullaway-on-jdk-21-cells.md)) — a build gate, not a review promise; `ResultTest` asserts the rejection on both arms and for every operation; `LazyTest` asserts it for the initializer result, including that the rejection is retried rather than wedging the instance; `StrategyRegistryTest` asserts every entry point rejects `null` and that a rejected registration leaves the registry untouched; `GenericFactoryTest` asserts both `create` and `tryCreate` reject a supplier that returns `null`, so a defect stays distinguishable from an absent key; `FluentBuilderTest` asserts `build()` rejects a `null` from `construct()`; `NullableTest` asserts the marker reaches the sites NullAway reads | ✅ enforced |
| C-03 | **A converted identifier means the same thing on every host.** `StringCaseConverter` maps case with `Locale.ROOT` and never the default-locale `toLowerCase()` / `toUpperCase()` overloads. On a Turkish-locale JVM the default overload maps `I` to dotless `ı`, so an identifier derived here would stop matching the key it came from — silently, and only on some hosts. Where a converted name is used as a lookup key or a permission string, that is an authorization defect, not a formatting one | [RFC-0001](../rfc/0001-core-contracts.md) §FR-22 and §Cross-cutting; [ADR-0018](../adr/0018-tokenizer-word-threshold-and-utf8-default.md) | `StringCaseConverterTest.mapsCaseWithLocaleRoot` **sets the default locale to `tr-TR`** and asserts `IDToken` still converts to `id_token` — the failure is reproduced rather than reasoned about. **Not mechanically enforced**: no gate forbids the default-locale overload, so a future call site could reintroduce it; an ErrorProne pattern would be the way to close that | 🚧 partial — enforced by test at the one call site that exists |
| C-04 | **A caller-supplied resource name cannot escape its anchor.** `ResourceLoaderUtils` rejects any name containing `..` with `IllegalArgumentException` on every entry point, and resolves through the caller-supplied `Class<?>` anchor rather than the system or thread-context class loader. Rejecting beats normalizing where the name may come from request input: a refusal is trivially correct, a normalizer has to be proven airtight | [RFC-0001](../rfc/0001-core-contracts.md) §FR-24 and §Cross-cutting; [ADR-0018](../adr/0018-tokenizer-word-threshold-and-utf8-default.md) | `ResourceLoaderUtilsTest.rejectsTraversal` asserts four traversal shapes against `find`, `open` **and** `readString`, so a new entry point that skipped normalization would fail; the anchor rule is additionally what keeps the lookup working under JPMS encapsulation | ✅ enforced |
