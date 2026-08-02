# RFC-0001: Core module contracts and error model

- **Status:** Accepted (2026-07-27, owner authority — no peer-review round; see [Approval](#approval))
- **Author:** tech-lead · **Reviewers:** reviewer, enterprise-architect (cross-cutting — this RFC sets
  the contract convention every later module inherits) · **Approver:** tech-lead
- **Date:** 2026-07-27
- **Related:** spec [`.spec/d4np-java.md`](../../.spec/d4np-java.md) §2 items 1–4, 17–18, 22–24 · §5 ·
  §6 (NFR-01, NFR-04) · [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (module split) ·
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) (error model) ·
  [ADR-004](../../.spec/adr/d4np_java_adr_004_generated_layout.md) (generated layout) ·
  Milestone 2 "core foundations", items 2.1–2.5

> Written before the code. `d4np-core` does not exist yet — ADR-004 places the Maven reactor at
> Milestone 1 item 1.1 — so every signature below is still free to change. After 1.0 it is not:
> japicmp gates binary compatibility per module (NFR-09).

> **Amended in two places by [RFC-0002](0002-cross-cutting-contracts.md) §Amendments to RFC-0001.**
> The body below is left as approved rather than rewritten, so this pointer is how a reader avoids
> being misled by it. (1) **§FR-22's tokenizer rule** and its pseudocode split `URLs` into `UR`+`Ls`,
> contradicting the table in the same section, which pins `URLs` as one token; the rule now requires
> **at least two** lowercase characters after an uppercase run
> ([ADR-0018](../adr/0018-tokenizer-word-threshold-and-utf8-default.md)). (2) **§Error model's
> "`Ok(null)` forbidden — use `Result<Void>`"** named a construction that cannot exist, because `Void`
> is uninhabited; it is **`Result<Unit>`**
> ([ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) recorded the proof,
> [ADR-0019](../adr/0019-mint-unit-for-the-void-success.md) the fix).

## Context

Nine of the twenty-five specified items belong to `d4np-core` and are scheduled into Milestone 2,
but the imported specification (v2.0, *Reviewed draft*) leaves most of them without a behavioural
contract. Its §5 *Method-Level Contract Summary* is normative for **items 4, 7 and 17 only** — five
table rows covering three core types out of nine. The manifest records the shortfall as explicit
`[GAP]` markers on FR-01, FR-02, FR-22, FR-23, FR-24 and on the §5 table itself, and states they
must close before the spec is frozen.

Three constraints make this the moment to decide, not later:

1. **The roadmap asks for it by name.** Milestone 2 item 2.4 reads "GenericFactory and FluentBuilder
   — *contracts defined first*". Items 2.1–2.5 cannot be implemented against an undefined contract
   without the implementation silently becoming the specification.
2. **The surface is permanent once published.** `capabilities.public_api: true`; NFR-09 puts japicmp
   on every module against the previous release. Tightening a contract after 1.0 — adding a thrown
   exception, forbidding a previously-accepted argument — is a MAJOR bump. Contract-first is the
   only cheap ordering.
3. **Core cannot borrow its way out.** ADR-001 fixes `d4np-core` at **zero third-party
   dependencies**. There is no Guava, no commons-lang3, and no annotation processor available to
   supply a missing behaviour later; whatever these types promise, core implements on the JDK alone.

Further constraints that bind the decisions below: the JDK **17** baseline (`--release 17`) makes
sealed types and records available but forbids anything 21-only; every module ships `module-info`
(§1.1), which changes how classpath resources resolve; and `d4np-core` owns no persistent state.

## Decision

Pin the contract for all nine core types on three axes — **nullability, error semantics, thread
safety** — extending the §5 table from five rows to full coverage of core. Where the specification
already settled a contract (items 3, 4, 17, 18) this RFC restates it and fills only what was left
open; where it was silent (items 1, 2, 22, 23, 24) this RFC decides.

### API contract (`api` / `systemdesign`)

#### Functions and payloads

**FR-01 `GenericFactory<T, K>`** — keyed construction without exposing concrete types.

| Operation | Signature | Behaviour |
|---|---|---|
| register | `void register(K key, Supplier<? extends T> supplier)` | **Rejects a duplicate key** with `IllegalStateException`; both arguments non-null |
| replace | `void replace(K key, Supplier<? extends T> supplier)` | The explicit override — succeeds whether or not the key is bound |
| create | `T create(K key)` | Invokes the supplier; throws `FactoryKeyNotFoundException` (message lists the known keys) when unbound |
| tryCreate | `Optional<T> tryCreate(K key)` | `Optional.empty()` when unbound |
| keys | `Set<K> keys()` | Unmodifiable snapshot |

A supplier returning `null` is a programming error: `create` throws `IllegalStateException` naming
the key rather than propagating the `null`.

**FR-02 `FluentBuilder<T>`** — abstract base for fluent domain-object builders. Template method:

```
final T build()                     // validate(), then construct(); NOT overridable
protected abstract T construct()    // subclass builds the instance
protected abstract void validate()  // subclass asserts its invariants
protected final void require(Object value, String field)   // accumulates a missing field
```

`build()` collects **every** violation and throws one `BuilderValidationException` listing all of
them — never fail-on-first, which turns filling a ten-field builder into ten round trips.
`build()` is **repeatable and returns a distinct instance per call**; the builder is not reset and
stays mutable, so a partially-configured builder is a legitimate prototype. Two rules make that
safe: `construct()` must **defensively copy** every collection or array it takes from the builder,
and the builder itself is **not thread-safe** — documented, not synchronized.

**FR-03 `Lazy<T>`** — the specification pinned safe publication and "at most once", and left the
initializer-exception policy as "memoized-or-retried per option". Pinning the default and the
recursion case:

| Aspect | Contract |
|---|---|
| Default failure policy | **Retry** — a thrown initializer exception propagates and is *not* remembered; the next `get()` attempts initialization again |
| Opt-in | `Lazy.memoizingFailures(supplier)` remembers the first failure and rethrows it on every later `get()` |
| Re-entrancy | An initializer that calls `get()` on the same `Lazy` throws `IllegalStateException` — a recursive initializer is a defect, not a supported mode |
| Null | Initializer returning `null` → `IllegalStateException` (spec §5) |
| Publication | Double-checked `volatile`; jcstress-verified (NFR-01) |

**FR-04 `StrategyRegistry<K, S>`** — already normative in §5; carried over unchanged:
`Optional<S> find(K)`, `S getOrThrow(K)` throwing `StrategyNotFoundException` with the known-keys
list, `ConcurrentHashMap`-backed lock-free reads, `register` last-write-wins with a warning log.

**FR-17 `Result<T>` / FR-18 `BusinessException`** — settled by ADR-002; restated for completeness.
`Result<T>` is `sealed` with exactly `Ok<T>` and `Err<T>`, `map` / `flatMap` / `recover` /
`orElseThrow(Function<ErrorDetail, ? extends X>)`; `Ok(null)` forbidden — use `Result<Void>`.
`BusinessException extends RuntimeException`. One detail ADR-002 left implicit is pinned here:
`ErrorDetail` is a `record ErrorDetail(String code, String message, Throwable cause)` — `code` is a
**`String`, not an enum**, because a shared library cannot enumerate its consumers' business codes;
`cause` is nullable, `code` and `message` are not.

**FR-22 `StringCaseConverter`** — one tokenizer, then a renderer per target case. The pinned rules:

| Input | Tokens | camelCase | snake_case | kebab-case |
|---|---|---|---|---|
| `HTTPServer` | `HTTP`,`Server` | `httpServer` | `http_server` | `http-server` |
| `parseHTTPRequest` | `parse`,`HTTP`,`Request` | `parseHttpRequest` | `parse_http_request` | `parse-http-request` |
| `URLs` | `URLs` | `urls` | `urls` | `urls` |
| `s3Client` | `s3`,`Client` | `s3Client` | `s3_client` | `s3-client` |
| `user2Name` | `user2`,`Name` | `user2Name` | `user2_name` | `user2-name` |
| `already_snake` | `already`,`snake` | `alreadySnake` | `already_snake` | `already-snake` |
| `__leading__` | `leading` | `leading` | `leading` | `leading` |
| `""` | — | `""` | `""` | `""` |

Rules behind the table: an uppercase run of length ≥ 2 followed by a lowercase letter splits
**before the final uppercase** (`HTTP|Server`); a digit run **joins the preceding token** and never
starts one; separator runs (`_`, `-`, space) collapse and are trimmed. All case mapping uses
**`Locale.ROOT`** — never `String.toLowerCase()`'s default-locale overload, which on a Turkish-locale
JVM maps `I` to dotless `ı` and silently corrupts every identifier the converter touches.

Two properties are guaranteed and one is explicitly **not**: each conversion is **idempotent**
(`toSnake(toSnake(x)) == toSnake(x)`) and total (never throws on any `String`, `null` → `null` is
*not* offered — `null` throws `NullPointerException`). Round-tripping is **not** guaranteed across
acronyms: `HTTPServer → http_server → httpServer`. Stating that as a non-guarantee is deliberate;
the alternative is an acronym dictionary in a zero-dependency module.

**FR-23 `ObjectUtils`** — the specification did not enumerate the helper set, so this RFC fixes it
by a rule rather than a wishlist: **`ObjectUtils` contains only what `java.util.Objects` does not.**
Anything the JDK already provides — `equals`, `deepEquals`, `hashCode`, `toString`,
`requireNonNull`, `requireNonNullElse`, `requireNonNullElseGet`, `isNull`, `nonNull`, `compare` —
is deliberately **not** re-exported; the Javadoc points at `java.util.Objects` instead. The set:

```
boolean anyNull(Object... values)          boolean allNonNull(Object... values)
String  requireNonBlank(String v, String name)
<T extends Comparable<T>> int compareNullsFirst(T a, T b)
<T extends Comparable<T>> int compareNullsLast(T a, T b)
boolean isEmpty(CharSequence) / (Collection<?>) / (Map<?,?>) / (Object[])
boolean isNotEmpty(CharSequence) / (Collection<?>) / (Map<?,?>) / (Object[])
```

`isEmpty` is offered as **typed overloads, never `isEmpty(Object)`** — an `Object` parameter defers
to runtime what the compiler can settle, and silently answers `false` for a type nobody considered.

**FR-24 `ResourceLoaderUtils`** — the JPMS-correct resolution rule is the load-bearing decision:

| Operation | Signature | Behaviour |
|---|---|---|
| find | `Optional<URL> find(Class<?> anchor, String name)` | `Optional.empty()` when absent |
| open | `InputStream open(Class<?> anchor, String name)` | `ResourceNotFoundException` naming the resource, the anchor and its module; **caller closes** |
| readString | `String readString(Class<?> anchor, String name)` | UTF-8; `ResourceNotFoundException` when absent |
| readString | `String readString(Class<?> anchor, String name, Charset cs)` | explicit charset |

Resolution goes through the **caller-supplied `Class<?>` anchor** (`anchor.getResourceAsStream`) —
never `ClassLoader.getSystemResourceAsStream`, never the thread context class loader. On JPMS a
resource inside a named module is encapsulated unless its package is `open`, so a system or TCCL
lookup returns `null` for exactly the deployment shape §1.1 mandates ("all modules ship
`module-info`"). Anchoring on a class in the owning module is the only rule that holds across all
three shapes: exploded directory, JAR on the classpath, and named module.

Names are always **absolute**; a leading `/` is optional and normalized away, because
`Class.getResourceAsStream` otherwise treats a bare name as *package-relative* — the single most
common surprise in this API. A name containing `..` is rejected with `IllegalArgumentException`.
The explicit charset default is **UTF-8, written out** rather than `Charset.defaultCharset()`,
which at the JDK 17 baseline is still platform-dependent (JEP 400 lands in 18).

**Non-goal, stated so it is not requested later:** no directory or wildcard listing. Enumerating
resources cannot be made to behave uniformly across JAR, exploded and modular layouts, and an API
that works in tests and returns empty in production is worse than no API.

#### Error model

Consumers handle exactly three failure shapes from core, and the choice between them is mechanical:

| Shape | When | Types |
|---|---|---|
| `Result.Err` with `ErrorDetail` | an **expected** outcome the caller branches on | ADR-002 |
| Unchecked domain exception | a **defect or absent binding** the caller cannot sensibly branch on | `FactoryKeyNotFoundException`, `StrategyNotFoundException`, `BuilderValidationException`, `ResourceNotFoundException`, `IllegalStateException` |
| `BusinessException` | a **rule violation aborting the use case**, handled at a boundary | ADR-002; mapped to RFC 7807 422 by FR-19 |

No core method returns `null` to signal absence, and no core method throws a checked exception —
`Optional` for absence, unchecked for defects, `Result` for expected failure. All four new
exception types extend `RuntimeException`. `BuilderValidationException` and
`FactoryKeyNotFoundException` are **new to the specification** and are introduced by this RFC.

#### Versioning

The BOM is the artifact consumers pin (ADR-001). For `d4np-core`, a **MAJOR** bump is: any
binary-incompatible change japicmp flags; any change to the package root; any raise of the JDK
baseline; **adding a thrown unchecked exception to an existing method's documented contract**; or
tightening an accepted-input range. Adding an overload, a new type, or a new `ObjectUtils` helper is
MINOR. The tables above are the contract japicmp and the Javadoc are both held to; core stays at
`0.x` through M2–M7, so binary compatibility is not yet promised and 1.0.0 is the M8 deliverable.

### Data & schema

Not applicable — `d4np-core` owns no persistent state. `d4np-jdbc` executes the caller's SQL and
owns no schema of its own (manifest `secondary_lang: ""`), so nothing in Milestone 2 falls inside
ADR-0004's secondary-data frame.

### Scalability budgets (`scalability`)

The two numeric budgets that bind Milestone 2:

| Axis | Metric | Target | Tool | Milestone item |
|---|---|---|---|---|
| performance | `Lazy.get()` steady state, volatile-read path | **≤ 2 ns/op** | JMH | 2.2 |
| correctness | `Lazy` initialization race | **0 anomalous states** | jcstress | 2.2 |
| performance | `StrategyRegistry.find` at 1k strategies, 8-thread read load | **≤ 50 ns/op** | JMH | 2.3 |

Both performance numbers are recorded in the manifest's `spec.nfr_budgets`. `domains/software.yaml`
declares every NFR axis `hard_budget: false`, so the `nfr-budgets` gate does not require them; they
are stated here so the audit phase checks a number rather than an adjective.

**Known risk, carried not hidden:** NFR-01's 2 ns/op sits close to JMH's measurement noise floor,
and GitHub-hosted runners vary by more than 10% in CPU model and steal time — so as an *absolute*
CI gate it will be flaky. The manifest schedules the fix at Milestone 8 item 8.3 (pin the perf gate
to a stable runner, or compare against a stored per-runner baseline). Until 8.3 lands, treat the
JMH numbers for items 2.2 and 2.3 as **tracked on the reference machine and advisory in CI**. An
RFC that declared them blocking today would be stating a gate the pipeline cannot hold.

### Algorithm sketch (`pseudocode`)

Two places where the control flow is not obvious. Language-free:

```
Lazy.get():
    v ← value            # single volatile read — the steady-state path, no lock
    if v ≠ EMPTY: return v
    lock:
        if value ≠ EMPTY: return value
        if initializing_by_current_thread: raise IllegalState("re-entrant")
        mark initializing_by_current_thread
        try:      r ← supplier()
                  if r = null: raise IllegalState("initializer returned null")
                  value ← r            # volatile write publishes it
        catch e:  if memoizing: failure ← e
                  raise e
        finally:  clear initializing_by_current_thread
        return value

tokenize(s):                       # the one source of truth for all three case renderings
    tokens ← []; buf ← []
    for each code point c in s:
        if c is separator:                 flush(buf → tokens)
        else if c is digit:                buf.append(c)          # digits never split
        else if c is upper:
            if buf ends with lower or digit:            flush(buf); buf.append(c)
            else if buf is upper-run and next c is lower: flush(buf); buf.append(c)
            else                                        buf.append(c)
        else:                              buf.append(c)
    flush(buf → tokens); return tokens
```

### Cross-cutting

**Security.** Three of these contracts are security-load-bearing, and each is a decision rather than
an observation: `Locale.ROOT` in `StringCaseConverter` stops locale-dependent identifier corruption
from becoming an authorization bug when a converted name is used as a key; `ResourceLoaderUtils`
rejects `..` so a caller-supplied resource name cannot escape its anchor; and `ErrorDetail.message`
travels to the client through FR-19's problem+json, so the Javadoc states it is **caller-facing text
and must not carry secrets, credentials or PII** — the redaction question for FR-16 `AuditLog` is
Milestone 3 item 3.3 and out of scope here.

**Performance.** `Lazy.get()` and `StrategyRegistry.find` are the two hot paths: a single volatile
read and a `ConcurrentHashMap` lookup respectively, both **allocation-free on the success path**.
`tryCreate`/`find` returning `Optional` allocates, which is why the hot-path budget is stated
against `find` at 1k entries rather than against `Optional` construction.

## Alternatives

1. **`GenericFactory` uses last-write-wins, matching `StrategyRegistry`.** Rejected: the two have
   different lifecycles. A strategy registry is designed for runtime reconfiguration, where
   last-write-wins is the feature; a factory is wired once at startup, where a duplicate key is
   almost always two modules claiming the same discriminator — and silent overwrite makes the winner
   depend on registration order, i.e. on classpath order. `replace()` keeps the override available
   with the intent visible at the call site. The asymmetry is deliberate and documented on both types.
2. **`Lazy` memoizes initializer failures by default.** Rejected: it permanently poisons a singleton
   after one transient fault (absent config at startup, a network blip), and the rethrown exception
   carries a stack trace from a foreign, earlier call site — a debugging trap. Retry is the safer
   default; `memoizingFailures()` covers the case where the failure is expensive and deterministic.
3. **Compile-time required-field enforcement for `FluentBuilder`** (staged / step-builder, or a
   generated builder). Rejected on a hard constraint, not taste: a generic base class cannot express
   "field X must be set" in the type system, and generating stages needs an annotation processor —
   which ADR-001 forbids `d4np-core` from carrying. Accumulated runtime validation is the strongest
   contract available at zero dependencies.
4. **`ObjectUtils` as a commons-lang3-style general helper collection.** Rejected: japicmp (NFR-09)
   makes every helper permanent public surface, and most candidates duplicate `java.util.Objects`,
   so the library would carry a maintenance obligation for methods the JDK already ships. The
   "only what `Objects` lacks" rule gives reviewers a mechanical test for the next proposed helper.
5. **Resource resolution via the thread context class loader or the system class loader.** Rejected
   on a mechanism: for a resource inside a named module whose package is not `open`, both return
   `null`, and §1.1 requires every module to ship `module-info`. This alternative works on the
   classpath and fails in the deployment the compatibility matrix commits to.
6. **`isEmpty(Object)` as a single reflective/instanceof-dispatch helper.** Rejected: it converts a
   compile-time type error into a silent `false` for any type not enumerated in the dispatch chain.
7. **Checked exceptions, or `Result` everywhere with no exceptions.** Already decided in ADR-002
   (options B and C, rejected there); referenced rather than re-litigated.

## Consequences

**Easier.** Milestone 2 items 2.1–2.5 become implementable against a written contract, and each
contract row maps to a test: the FR-22 table is a table-driven test as written, the `Lazy` rows are
the jcstress harness's assertions, and the exception taxonomy is the negative-test list. Six of the
manifest's thirteen `[GAP]` markers close (FR-01, FR-02, FR-22, FR-23, FR-24, and the §5
contract-table shortfall for core). Reviewers gain two mechanical rules — "does `java.util.Objects`
already do this?" and "does this method return `null`?" — that need no judgement call.

**Harder.** Four new exception types (`FactoryKeyNotFoundException`, `BuilderValidationException`,
`ResourceNotFoundException`, plus the already-specified `StrategyNotFoundException`) are permanent
public surface from the first release. `GenericFactory` deliberately diverges from
`StrategyRegistry` on duplicate keys, which is a documentation burden on both types — a reviewer
who reads only one will guess wrong. And the `ObjectUtils` rule will reject helpers that feel
useful; that is the intended cost.

**Migration.** None — no code exists. This RFC constrains code that has not been written, which is
the whole point of authoring it before Milestone 1 item 1.1 establishes the reactor.

**Follow-ups.**

- **Amend the imported specification.** §2 items 1, 2, 22, 23, 24 and the §5 table are superseded by
  the tables above. `.spec/d4np-java.md` is an imported v2.0 draft; back-porting these contracts into
  it (or marking it superseded by this RFC) is a separate change and needs the owner's call on which
  document is authoritative going forward.
  **RESOLVED 2026-07-29 by ROADMAP item 1.12 / [ADR-0010](../adr/0010-single-specification-authority.md):**
  the imported draft is superseded provenance and carries a banner saying so; the manifest's `spec.*`
  block is the source of record, published as [`../specs/01_spec_utils.md`](../specs/01_spec_utils.md);
  and an RFC outranks the specification wherever it pins a contract — so the five requirement lines
  this RFC pins now point here rather than restating a contract they no longer define.
- Milestone 8 item 8.3 owns the NFR-01/NFR-06 perf-gate reproducibility problem noted above.
- Milestone 3 item 3.3 owns FR-16 `AuditLog` redaction; Milestone 4 owns FR-21
  `ObjectMapperExtensions` (`d4np-json`, out of core).
- The remaining `[GAP]` markers — FR-06 transaction semantics, FR-12 AAD and key-message cap,
  NFR-11 CVSS threshold, NFR-12 provenance — belong to later RFCs in their own milestones.

## Approval

The approval encodes a **human decision** — no RFC self-approves (`AGENTS.md` §6,
`review-protocol.md`). The record below was **authorized by the owner (@danielPoloWork) in session on
2026-07-27** and transcribed by the agent. The agent drafted this RFC and did not judge its
soundness; the decision is the owner's.

```
approved-by: tech-lead (2026-07-27)
```

**Review provenance — stated, not implied.** Procedure step 3 — independent `reviewer` and
`enterprise-architect` findings — **did not run**. No structured findings were raised, so none were
resolved. This approval therefore rests on the owner's direct authority (precedence layer 1, the
terminal gate) rather than on a peer-review round. It is recorded here so the audit trail cannot be
read as reviewed when it was not, and so a later reader knows which assurance this RFC does *and does
not* carry.

Reviewers (structured findings addressed): reviewer — **not run** ; enterprise-architect — **not run**.

## References

- Specification: [`.spec/d4np-java.md`](../../.spec/d4np-java.md) v2.0, 2026-07-14 — §1.1, §2, §4, §5, §6
- [ADR-001 — Multi-module split & dependency policy](../../.spec/adr/d4np_java_adr_001_module_split.md) (Accepted)
- [ADR-002 — Error model](../../.spec/adr/d4np_java_adr_002_error_model.md) (Accepted)
- [ADR-004 — Generated repository layout](../../.spec/adr/d4np_java_adr_004_generated_layout.md) (Accepted 2026-07-26)
- Manifest: `orchestrator/project.yaml` — `spec.functional_reqs`, `spec.nfr_budgets`, `spec.milestones` (Milestone 2)
- Review protocol: `.eados-core/orchestrator/os/rfc/review-protocol.md`
- JEP 400 (UTF-8 by default, JDK 18) — the reason UTF-8 is written out rather than defaulted
