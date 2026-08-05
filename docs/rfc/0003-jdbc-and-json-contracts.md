# RFC-0003: Persistence and serialization contracts — transactions, pagination and JSON hardening

- **Status:** **Proposed** — no approval recorded; see [Approval](#approval)
- **Author:** tech-lead · **Reviewers:** reviewer, enterprise-architect (two module surfaces and the
  first `java.sql` edge), security-auditor (FR-20's CVE class and the C-01 message rules) ·
  **Approver:** owner (@danielPoloWork)
- **Date:** 2026-08-05
- **Related:** spec [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md) §2 FR-05, FR-06, FR-07,
  FR-20, FR-21 · §3 · §5 · [RFC-0001](0001-core-contracts.md) (the error model, nullability and
  versioning rules this one inherits) · [RFC-0002](0002-cross-cutting-contracts.md) (the
  outside-`BusinessException` precedent, and `Unit`) ·
  [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) (the dependency policy, and the
  naming-consequence rule applied three times below) ·
  [ADR-002](../../.spec/adr/d4np_java_adr_002_error_model.md) (error model) ·
  [ADR-0005](../adr/0005-jpms-module-names-and-export-less-descriptors.md) (the descriptors these
  contracts complete) · [ADR-0011](../adr/0011-declare-the-nullability-annotation-in-core.md) (why a
  published signature is the dependency you cannot keep to yourself) ·
  [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) (whose prediction about
  item 4.4 this RFC contradicts) · [ADR-0014](../adr/0014-log-through-the-jdk-system-logger.md) (the
  logging mechanism FR-06 uses) · [ADR-0015](../adr/0015-strategy-registry-last-write-wins.md) (keep a
  diagnostic exception outside `BusinessException`; carry its payload as text) ·
  [ADR-0020](../adr/0020-render-violations-from-the-message-template.md) (never render the value) ·
  [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) (a guarantee a consumer can switch
  off is advisory) · [threat model](../security/threat-model.md) · Milestone 4 items 4.1–4.5

> Written before the code, as RFC-0001 and RFC-0002 were. Nothing in `d4np-jdbc` or `d4np-json` exists
> yet — both modules hold a single `module-info.java` and no package directory — so every signature
> below is still free to change. Both modules stay at `0.x` through M2–M7 (RFC-0001 §Versioning), so
> nothing here is a binary-compatibility promise yet.

## Context

Milestone 4 holds five of the twenty-five specified items, split across the first two capability
modules. The roadmap asks this RFC to settle two of them — FR-06 and FR-21, the two carrying a live
`[GAP]` marker. **Reading all five together showed that neither gap can be closed honestly on its
own**, so the owner widened the scope to cover all five. The two entanglements are the reason.

**FR-05 and FR-06 contradict each other on connection ownership, and the contradiction is silent.**
FR-05 promises `SimpleJdbcExecutor` a *"try-with-resources lifecycle"* — the executor takes a
`Connection` from the `DataSource`, uses it, closes it. Inside an FR-06 transaction that is a
**second** connection, so the executor's work runs in its own auto-committed transaction, **outside**
the one the caller opened. The rollback rolls back nothing that matters. Nothing throws, nothing logs,
and every single-statement test passes — because with one statement there is no difference to observe.
This is the highest-risk under-specification in Milestone 4, and it lives in the seam between two
requirements rather than inside either.

**FR-20's hardening defeats FR-21's headline feature.** FR-20 pins `FAIL_ON_UNKNOWN_PROPERTIES=false`,
which is correct for its job — an inbound document from a producer who added a field must not fail.
FR-21's job is *"partial-mapping helpers"*, and over that same mapper a partial update cannot tell an
intentionally-omitted field from a **misspelled** one: both are silently dropped, so a PATCH client
believes it changed something it did not. One mapper, two jobs, opposite defaults.

FR-07 joins for a smaller reason: its §5 row states no component list for `PageResponse`, no
defensive-copy rule, no shape for the whitelist it requires, and never mentions multi-field or
descending order although `sort` is one of its three parameters. It is the last of the six §5 rows
the specification's own `[GAP]` line counts, and leaving it for item 4.5 to invent would repeat what
this RFC exists to stop.

One more reason this is the right moment. **ADR-0012 named item 4.4 by name** as one of three plausible
first consumers of `Result<Unit>` and routed the signature question to "RFC-0002/RFC-0003". RFC-0002
settled `Unit` on the completeness of the error model and explicitly declined to wait for this call
site. §Error model below records what happened when the call site finally arrived: **the prediction
did not hold**, and that costs nothing precisely because ADR-0019 refused to depend on it.

## Decision

### The shared error and naming rules

Two rules apply to both modules, and stating them once removes five arguments later.

**Every exception these modules throw is unchecked and none extends `BusinessException`.** RFC-0001's
mechanical table already assigns the shapes; the trap is that FR-19 maps `BusinessException` to
**422**, a client-fixable rule violation. A database that is down, or a payload that will not parse,
is not that. `JdbcAccessException` and `JsonConversionException` therefore extend `RuntimeException`
directly, exactly as RFC-0002 placed `ValidationException` and ADR-0015 placed
`StrategyNotFoundException` — and for the same reason, which is that the mistake is invisible in
review and only shows up as a wrong HTTP status in production.

**A colliding type name is renamed when a wrong choice compiles and diverges, and kept when it cannot
compile.** ADR-001 already made this call once, renaming FR-06's type to `JdbcTxRunner` because
*"shipping a same-named competitor would guarantee import confusion in exactly the codebases most
likely to adopt this library."* Applied as a test rather than a feeling, it gives three answers:

| Candidate name | Collides with | Wrong choice… | Decision |
|---|---|---|---|
| `DataAccessException` | `org.springframework.dao.DataAccessException` | **compiles** — a host's `catch` silently fails to match, and the exception escapes to the 500 fallback | renamed **`JdbcAccessException`** |
| `Sort` | `org.springframework.data.domain.Sort` | does not compile where we take it as a parameter, but two `Sort`s in one file cost a reader | renamed **`PageSort`** — the weak case, taken because a new type has no users to break |
| `RowMapper` | `org.springframework.jdbc.core.RowMapper` | does not compile, and the two are behaviourally identical one-method interfaces | **kept** |

The same test is why FR-21's exception is `JsonConversionException` and not `JsonMappingException`,
which is Jackson's own.

### FR-06 `JdbcTxRunner` — the transaction contract

#### Connection ownership — the load-bearing decision

**The transactional `Connection` is passed explicitly to the callback, and it is the only thing the
callback receives. There is no ambient, thread-scoped current connection.**

The alternative is the ergonomic one: bind the connection to a `ThreadLocal` and let
`SimpleJdbcExecutor` pick it up. It is rejected for a specific reason rather than on taste. With
ambient transport, an executor built from a `DataSource` at startup — the normal shape — **changes its
transactional semantics depending on whether an enclosing transaction happens to exist on the current
thread.** Two identical call sites, two different behaviours, and no local signal which one you got.
It also breaks the moment work crosses a thread: `CompletableFuture` and FR-09's own `AsyncExecutor`
are in this library, and a hand-off silently reverts to auto-commit. The failure mode of ambient
transport is precisely the failure mode this decision exists to remove.

So `SimpleJdbcExecutor` gains a second construction path and the pair is the contract:

| Factory | Connection lifecycle |
|---|---|
| `SimpleJdbcExecutor.on(DataSource ds)` | **owns** it — acquires and closes one per operation (FR-05's try-with-resources promise) |
| `SimpleJdbcExecutor.on(Connection conn)` | **borrows** it — never closes it; this is the form used inside a transaction |

**The residual risk is stated rather than claimed away.** A `DataSource`-backed executor captured from
outside the lambda and used inside the block is legal Java that does the wrong thing. What makes this
acceptable is that the mistake is **visible in the lambda's capture list** — a reviewer reading the
block sees the executor come from somewhere else — whereas ambient state hides the same mistake inside
a passing test. Item 4.3 owes an `@apiNote` on `on(DataSource)` naming it.

Handing over the raw `Connection` rather than a narrowed facade pays for itself twice: it is also what
lets a host reach for a savepoint, a driver-specific hint or a `readOnly` flag this API deliberately
does not wrap.

#### Isolation

`TxIsolation` is an enum — `DEFAULT`, `READ_UNCOMMITTED`, `READ_COMMITTED`, `REPEATABLE_READ`,
`SERIALIZABLE` — and not JDBC's `int` constants, which accept any `int` at compile time and include
`TRANSACTION_NONE`, a value that is meaningless for a transaction runner.

**`DEFAULT` means `setTransactionIsolation` is never called.** It does not mean "read committed". A
`DataSource` in a real host is a pool whose isolation level is the host's configured decision, and a
library that silently overrode it would be changing the meaning of SQL it did not write.

**A non-`DEFAULT` level is restored before the connection is returned.** `close()` on a pooled
connection returns it for reuse, so a level left changed leaks into whatever borrows it next — a
cross-request correctness bug with no symptom at the site that caused it. The same rule and the same
reason apply to `autoCommit`: the runner records `getAutoCommit()` on entry, sets `false`, and restores
the recorded value in the `finally` before closing. A connection left at `autoCommit=false` makes the
*next* borrower's statements sit uncommitted until something else commits or the connection is reaped,
which is data loss attributable to nobody.

**Known limitation, carried not hidden:** if restoration itself fails, JDBC gives a library no way to
invalidate a pooled connection — `close()` returns it regardless. The failure is logged and the host's
pool validation is the only remaining defence. Recorded so it is a known gap rather than a surprise.

#### Nesting, and the one place ambient state is correct

**Nesting and suspension are not supported. A nested `inTransaction` on the same thread is refused with
`IllegalStateException`.**

Suspension — Spring's `REQUIRES_NEW` — needs a second connection and a manager that owns both. That is
a transaction *manager*, and FR-06's own text scopes this type to non-Spring hosts and points Spring
users at `TransactionTemplate`. Building half a transaction manager whose behaviour diverges from
Spring's in one case is worse than building none.

Refusing it requires *detecting* it, and with explicit transport there is nothing to detect from: a
nested call would simply take a second connection from the pool and open a genuinely independent
transaction. That is not merely undocumented, it is dangerous — the outer transaction holds locks the
inner one needs, on one thread, so the pool cannot resolve the wait and a pool of modest size hangs.

So a **thread-scoped depth counter** is used, and the asymmetry with the transport decision above is
principled rather than convenient: as *transport*, an ambient value silently changes semantics and
fails **open** into the wrong behaviour; as a *detector*, it can only produce a loud refusal, and when
it is wrong — work moved to another thread — it fails **open into exactly the documented
`REQUIRES_NEW` behaviour**, which is the same outcome as having no detector. A detector that fails open
is safe; transport that fails open is the bug.

`IllegalStateException` rather than a new exception type: RFC-0001's table already assigns "a defect
the caller cannot sensibly branch on" to that shape and names `IllegalStateException` in it, FR-19
maps both it and any new type to the same 500 fallback, and a dedicated type would be a public name for
a case nobody should catch.

**Savepoints are not offered.** They are only useful with the nesting just refused, and
`Connection.setSavepoint` is optional in JDBC — an API that works on some drivers and throws
`SQLFeatureNotSupportedException` on others is worse than no API. The caller holds the `Connection`.

#### What triggers a rollback

**Any `Throwable` out of the callback rolls back. A returned value never does.**

Including `Error`: leaving a transaction open on an `OutOfMemoryError` holds database locks until the
connection is reaped. The runner rolls back and then **rethrows the original unchanged** — which only
looks like a contradiction of ADR-0021's bounded `RuntimeException | LinkageError` catch. That rule is
about *swallowing*; this is cleanup followed by propagation, and the two are opposites.

**A callback returning `Result.Err` commits.** This is a decision, not an oversight, and it is the one
most likely to be questioned. Interpreting `Err` as a rollback signal would give a core type a second
meaning in exactly one method — it means "a value the caller branches on" everywhere else — and it is
ambiguous the moment a body returns `Result<Result<T>>`. The rule that replaces it is teachable in one
line: **the exception channel demarcates the transaction; the value channel does not.** A body that
must roll back on a business rule throws, and FR-18 already names the type for that —
`BusinessException`, *"a rule violation that aborts the use case"*. Item 4.4 owes this sentence in the
`inTransaction` Javadoc, because a caller who guesses wrong here commits work they believe was undone.

#### Failure while failing

| Situation | Rule | Why |
|---|---|---|
| callback threw, rollback succeeded | the callback's `Throwable` propagates | it is the diagnosis |
| callback threw, **rollback also threw** | the callback's `Throwable` propagates with the rollback failure `addSuppressed` | the original is the cause, the rollback failure the consequence; replacing it points the on-call engineer at the wrong system. This is what try-with-resources does, so Java programmers already read it correctly |
| commit threw | `JdbcAccessException` wrapping it; nothing was applied | |
| **restore or `close` threw after a successful commit** | logged and suppressed — **never** propagated | the work is committed. Reporting a failure invites a retry that applies it twice, which is strictly worse than a lost log line |

#### Error translation, and the SQL that must not travel

`SQLException` is checked and RFC-0001 forbids core from throwing checked exceptions; that rule extends
to every published module method here. So `SQLException` is wrapped in **`JdbcAccessException extends
RuntimeException`**, carrying the `SQLState` as a `String`, the vendor code as an `int`, and the
original as `getCause()`. Both payload fields are primitives-or-`String` for ADR-0015's reason: every
`Throwable` is `Serializable`, and a field of a consumer's type would make the exception serialisable
only when that type happened to be — silently, and only in the hosts that serialise.

**`JdbcAccessException.getMessage()` contains no SQL and no parameter value.** It is a fixed,
code-derived sentence plus the `SQLState` and vendor code. Driver messages routinely embed the failing
statement, and a bound parameter can appear in a vendor message — so a message built by concatenating
the driver's is a credential-or-PII channel that FR-19 turns into an RFC 7807 body. This is the same
shape as ADR-0020's rule for constraint violations: **the diagnostic is rendered from what we know, not
from what the layer below said.** The driver's text survives in the `cause`, which is where a host's
own logging can reach it and where a boundary handler must not.

That last clause is an obligation on someone else, so it is filed rather than assumed: **item 7.1's
handler must not render a cause chain's `getMessage()` into the 7807 body**, and FR-19's mapping table
needs a `JdbcAccessException → 500 + alert` row.

#### Surface

| Operation | Signature | Behaviour |
|---|---|---|
| construct | `static JdbcTxRunner on(DataSource dataSource)` | isolation `DEFAULT` |
| construct | `static JdbcTxRunner on(DataSource dataSource, TxIsolation isolation)` | level applied on entry, restored on exit |
| run | `<T> T inTransaction(TxCallback<T> body)` | commits on normal return and returns the body's value; rolls back on any `Throwable` |
| run | `void inTransaction(TxVoidCallback body)` | as above, no value |

```java
@FunctionalInterface public interface TxCallback<T>  { T    run(Connection connection) throws Exception; }
@FunctionalInterface public interface TxVoidCallback { void run(Connection connection) throws Exception; }
```

`throws Exception`, not `throws Throwable`: JDBC's own methods throw checked `SQLException`, so the
body must be allowed to, and `Exception` covers it. ADR-0021 chose `Throwable` for `Invocation.proceed`
only because `ProceedingJoinPoint.proceed()` forced it; no such constraint exists here, and the wider
declaration would push every caller into `catch (Throwable)`. An `Error` from the body still rolls
back — it is simply not in the declared `throws`.

**`inTransaction` returns `T` and throws; it does not return `Result`.** See §Error model.

**Read-only is deliberately absent.** `Connection.setReadOnly` is a hint in the JDBC specification and
drivers differ on honouring it, no requirement asks for it, and adding an overload later is MINOR under
RFC-0001 §Versioning while removing one is MAJOR. Omitted in the reversible direction.

#### Thread safety and logging

`JdbcTxRunner` is **immutable and thread-safe**: it holds the `DataSource` and an isolation resolved
once at construction, following ADR-0021's resolve-once rule. The depth counter is thread-scoped, which
is per-thread state reached through one instance — the exact shape ADR-0022's
`AuditCaptureIsolationStress` was written for — so under spec §6 (*a thread-safety claim without a
named jcstress test is not a claim*) **item 4.4 owes a named harness** proving two threads' depth
counters never observe each other.

A `Connection` is not thread-safe, and **the callback must not retain or share the one it is given.**
The library cannot enforce that, so it is a stated caller obligation in the same class as RFC-0001's
defensive-copy rule for `FluentBuilder` — and item 4.4 should *demonstrate* the failure the way item
2.4 demonstrated the leaky subclass, rather than assert the rule.

FR-06 makes exactly **one** log claim, through `System.Logger` per ADR-0014: **a rollback is logged at
`DEBUG`, and a rollback that itself failed at `WARNING`.** A rollback is the normal outcome of a
business rule, so `WARNING` would train operators to ignore the level; a *failed* rollback means the
connection's state is unknown, which is genuinely exceptional. Commits are not logged at all — one line
per transaction is how instrumentation becomes the dominant cost of the thing it observes (ADR-0021's
precedent). Every parameter is pre-rendered before it reaches the logger, because `System.Logger`
substitutes through `MessageFormat`, which renders a `Long` through the *default* locale — item 3.2
measured that trap. No line carries SQL or a parameter value.

### FR-05 `SimpleJdbcExecutor`

FR-05 carries no `[GAP]`, so this RFC extends it rather than resolving it: the connection-ownership
pair above, the mapping shape below, and the three contract rows §5 never gave it.

**Parameterized statements only, and the guarantee is structural.** Every operation takes
`(String sql, Object... params)`, binds through `PreparedStatement`, and **no `Statement` is created
anywhere in the module.** There is no overload without a params slot and none accepting pre-interpolated
SQL; a zero-parameter call is still a `PreparedStatement`. The threat model's *"SQL altered via string
concatenation"* row moves from specified to defended-by-construction when item 4.3 lands, not before.

**Row mapping is a caller-supplied `RowMapper<T>`, not reflection.**

```java
@FunctionalInterface public interface RowMapper<T> { T map(ResultSet row) throws SQLException; }
```

FR-05 asks for *"POJO row mapping"*, and a `RowMapper` is POJO row mapping with the mapping written by
the person who owns the POJO. Reflective mapping loses on three counts, any one of which is sufficient:
it needs `opens` or public setters — the same privilege RFC-0002 refused for FR-16 — it cannot be
checked at compile time, and **it cannot meet NFR-03.** That last point is the one worth keeping:
NFR-03 budgets ≤ 10% overhead against a hand-written `ResultSet` loop, and a `RowMapper` lambda over
the same `ResultSet` *is* that loop plus one virtual call, so the budget becomes a statement about the
framing — statement preparation, parameter binding, result iteration — rather than about the mapping.
Per-row reflection would spend the whole budget before the framing was measured. Precedent for keeping
a specified name while changing its shape is ADR-0021, which did it for FR-15.

| Aspect | Contract |
|---|---|
| Nullability | no operation returns `null`; a single-row query that matches nothing returns `Optional<T>` |
| Errors | `SQLException` → `JdbcAccessException`; arguments are non-null and `sql` non-blank, rejected with `IllegalArgumentException` |
| Thread safety | `on(DataSource)` is thread-safe — stateless over a `DataSource` the host guarantees. `on(Connection)` is **as thread-safe as that connection, which is to say not**: documented, not synchronized, as RFC-0001 documented `FluentBuilder` |

**The operation list stays item 4.3's call**, deliberately. Three shapes are needed at minimum — a
list query, a single-row query returning `Optional`, and an update returning the affected count — and
naming more here would be inventing surface the specification does not ask for. Batch operations are
**not** in the first cut; adding them is MINOR.

### FR-07 `PageRequest` / `PageResponse<T>`

**The specification's "at construction" applies to the bounds, and it cannot apply to the whitelist.**
Read closely, FR-07 attaches `IllegalArgumentException` *at construction* to the page/size bounds and
then names no exception for the sort clause. That is not sloppiness, it is forced: `PageRequest` is
built at the HTTP edge from query parameters, and the set of sortable columns is the **repository's**
knowledge, not the controller's. So the whitelist is supplied at validation time:

| Operation | Signature | Behaviour |
|---|---|---|
| construct | `static PageRequest of(int page, int size, PageSort sort)` | `page ≥ 0`, `1 ≤ size ≤ 200`; violations → `IllegalArgumentException` |
| construct | `static PageRequest of(int page, int size, PageSort sort, int maxSize)` | as above with the caller's ceiling — this is what "configurable" means |
| validate | `PageSort validatedAgainst(Set<String> allowedProperties)` | returns the sort, or throws `ValidationException` naming the rejected property |

**`maxSize` is a parameter, never a system property or a static field.** A host-global default is
untestable in parallel, and it makes one call return different results in two modules of one
application.

**A whitelist violation throws `ValidationException`, not `IllegalArgumentException`, and that is a
deliberate refinement with the mapping table as its evidence.** FR-19 maps validation to **400** and
has no row for `IllegalArgumentException`, so it would fall to the **500** fallback — reporting
client-supplied input as a server fault, which is the exact misattribution ADR-0015 recorded. The
whitelist check is by definition a check of client data against an allowlist, which is what
`ValidationException` means; `d4np-jdbc → d4np-core` makes the type available.

**Whitelist comparison is exact and case-sensitive — no normalisation.** SQL identifier case folding is
vendor-specific: PostgreSQL folds unquoted names to lower case, Oracle to upper, MySQL depends on the
filesystem. A library that normalises picks a vendor. Exact matching means the host lists identifiers
exactly as their schema spells them, which is both stricter and vendor-neutral — and it is why FR-07
does **not** become a third consumer of control C-03's `Locale.ROOT` rule.

`PageSort` is an ordered list of `PageSort.Order(String property, PageSort.Direction direction)` with
`Direction { ASC, DESC }` and a `PageSort.unsorted()`. Multi-field and descending order are both
supported and neither is mentioned in FR-07; leaving them out would have made `sort` a single column,
which no paginated list survives contact with.

`PageResponse<T>` carries `content`, `page`, `size` and `totalElements`. Two rules:

- **`totalPages()` and `hasNext()` are derived, never stored.** A stored derived value can disagree
  with its inputs after any refactor; deriving makes disagreement impossible.
- **`totalElements` is a `long`.** An `int` overflows at 2.1 billion, and a row count is exactly the
  quantity that grows.
- **`content` is defensively copied with `List.copyOf` and exposed as an unmodifiable view**, which
  also **rejects `null` elements** — a null row in a page means nothing, and the rejection comes free.

**Neither type is `Serializable`.** A `PageResponse<T>` would be serialisable only when `T` happened
to be — ADR-0015's failure mode, and it fails silently in exactly the hosts that serialise. The wire
format this library actually targets is JSON, which FR-20 owns.

Both types live in `d4np-jdbc` because spec §3 assigns FR-05..07 there. The cost is stated rather than
hidden: **a consumer who wants only pagination takes the JDBC module** — which has zero third-party
dependencies, so the cost is one JAR, not a dependency tree.

### FR-20 `JsonMapper`

The three settings FR-20 names are normative and are carried over unchanged: `JavaTimeModule`
registered, `FAIL_ON_UNKNOWN_PROPERTIES=false`, and **no default typing** — the polymorphic
deserialization CVE class configured away. This RFC adds two things the requirement does not state.

**`INCLUDE_SOURCE_IN_LOCATION` is disabled explicitly.** Jackson embeds a snippet of the source
document in the location of a parse error, so a malformed body — which may hold a password, a token or
a card number — travels inside an exception message toward FR-19's 7807 body. It is set explicitly
rather than relied upon as a default, for the reason RFC-0001 wrote UTF-8 out instead of calling
`Charset.defaultCharset()`: an explicit value survives a version bump that changes a default, in either
direction.

**`JsonMapper` does not expose its configured `ObjectMapper`.** No getter, and no `ObjectMapper` in any
signature. ADR-0022's rule applies directly: a guarantee a consumer can switch off is advisory, and one
call to `activateDefaultTyping` on an exposed mapper re-opens the exact CVE class this requirement
exists to close. The guarantee has to be a property of the **type**, not of our call path.

The cost is that a host with a legitimate need — a custom serializer, a mix-in — has no handle. So
construction accepts an optional list of Jackson `Module`s: **additive customisation only**, the same
shape RFC-0002 gave `AuditPolicy.withAdditionalNeverCapture`, where entries can be added and never
removed. The residual is stated: a `Module` can register a deserializer that does something dangerous,
but that is the host's own code, deliberately written and registered — not a configuration flag flipped
by accident, which is what default typing is.

**Thread safety, and why the two decisions hold each other up.** `JsonMapper` is thread-safe because a
Jackson `ObjectMapper` is thread-safe *once configured and never reconfigured* — and "never
reconfigured" is exactly what not exposing it guarantees. Neither half stands alone, in the same way
ADR-0013's `null`-as-marker stood only because a `null` result was rejected. **No jcstress harness is
owed**: the claim reduces to Jackson's own guarantee over an object we never mutate, and a harness would
be measuring Jackson — item 3.1's reasoning for `Validator` over a Bean Validation provider, verbatim.

### FR-21 `ObjectMapperExtensions`

**The type keeps its name and is a final class of static methods whose first parameter is a
`JsonMapper`.** Java has no extension methods, so the name cannot mean what it says; subclassing
`ObjectMapper` would expose the mapper §FR-20 just decided not to expose and would tie us to Jackson's
internals; and instance methods on `JsonMapper` would blur the two requirements. Static helpers over a
supplied collaborator is the shape `ObjectUtils` and `ResourceLoaderUtils` already use in this library.
Renaming was rejected on traceability, as ADR-0021 rejected renaming FR-15: the spec is the frozen
contract, so a rename costs a spec change to gain a word, and every future reader tracing FR-21 by name
lands on nothing.

#### The operation set

| Operation | Signature | Behaviour |
|---|---|---|
| convert | `<T> T convert(JsonMapper m, Object source, Class<T> target)` | deep conversion between POJO shapes |
| convert | `<T> T convert(JsonMapper m, Object source, JsonTypeToken<T> target)` | the same, for a generic target |
| read partial | `<T> PartialUpdate<T> readPartial(JsonMapper m, String json, Class<T> target)` | the instance **plus** which properties the document actually contained |

#### null-versus-absent, answered without a tri-state field

`{"a": null}` and `{}` are different documents that produce the same POJO. Every obvious encoding of
the difference collides with a rule this project already holds: ADR-0011 calls an `Optional` field an
anti-pattern and deliberately left `@Nullable` a declaration annotation, so `Map<String, @Nullable
Object>` is inexpressible without widening `@Target` to `TYPE_USE`; and `Result` cannot carry `null` at
all (ADR-0012).

**So the distinction is carried beside the value, not inside it.**

```java
public final class PartialUpdate<T> {
  T value();                          // never null
  Set<String> presentProperties();    // top-level names the document contained, sorted, unmodifiable
  boolean isPresent(String property);
}
```

`isPresent("a")` with a `null` value is an explicit null; `!isPresent("a")` is an absence. Nothing is
widened, no field type changes, and it composes with records — which is what the two rejected
encodings could not do. The set is **sorted** for the reason item 3.1 sorted its violations: an
unordered report makes a message assertion flaky and a log line undiffable.

**Scope bound, stated rather than discovered:** the present-property set covers **top-level names
only.** Nested partial semantics would need a path language, and inventing one in an RFC is how a
library acquires a query syntax nobody asked for. Adding it later is MINOR.

#### How FR-20's leniency and FR-21's strictness coexist

**`readPartial` rejects an unknown property name; `FAIL_ON_UNKNOWN_PROPERTIES` stays `false`.**

This is the resolution of the second collision, and it works because the two requirements have
different jobs. Leniency is for reading a document **you do not own** — a producer added a field and
your consumer must not break. Strictness is for **applying a partial update** — a client sent
`emailAddres` and believes they changed something. One is tolerance of an unknown *addition*; the
other is refusal of an unknown *instruction*. A per-operation check gives strictness exactly where it
belongs without changing what every other read in the application does, which is what flipping the
mapper-wide flag would have done.

The rejection names the offending property, **truncated at 64 characters and with a count when there
are several** — the shape item 2.3's `KeyDiagnostics` established. A property *name* is client input,
so echoing it unbounded into a message or a log is its own problem; echoing a bounded name is not the
thing control C-01 forbids, which is echoing a *value*.

#### Generics without a Jackson type in the signature

`readValue(s, List<Foo>.class)` cannot be written, so a generic target needs a type token. The obvious
move is Jackson's `TypeReference`, and it is rejected: it would put a Jackson type in a **published
signature**, forcing `requires transitive com.fasterxml.jackson.core` and contradicting
`d4np-json`'s own descriptor, which says re-exporting Jackson is the wrong default *because FR-20
disables default typing precisely so Jackson's behaviour is not part of this module's contract*. Worse,
japicmp would then guard a Jackson type on our surface, so **a Jackson major version that moved or
renamed it would become our MAJOR bump.**

ADR-0011 already recorded the general form of this argument for annotations — a type appearing in
published signatures is the one dependency you cannot keep to yourself — and it generalises exactly. So
`d4np-json` mints **`JsonTypeToken<T>`**, an abstract class subclassed anonymously at the call site.

The counter-argument is real and is recorded: `d4np-json` exists *in order to* depend on Jackson, and
every consumer of it has Jackson on the classpath, so hiding the type can be read as ceremony. It loses
on the japicmp consequence, which is a compatibility cost the module cannot pay back. A
`TypeReference` overload can be added later and would be MINOR.

#### Errors, and the payload that must not travel

`JsonProcessingException` is checked, so it is wrapped in **`JsonConversionException extends
RuntimeException`** — outside `BusinessException`, because a malformed payload is 400 and
`BusinessException` is 422.

**No message this module produces contains the payload or any fragment of it.** A
`JsonConversionException` message carries the **property path and the target type only** — ADR-0020's
rule for constraint violations, applied to serialization. Jackson's own exception survives as the
`cause`, and disabling `INCLUDE_SOURCE_IN_LOCATION` is what keeps a snippet out of *that* too. Two
defences, and which one is load-bearing matters: **the message rule is ours and always holds**; the
Jackson setting protects the `cause` from a boundary handler careless enough to render it.

FR-19's table needs a `JsonConversionException → 400` row, filed for item 7.1.

**No `Result`-returning form is offered.** RFC-0002 gave `Validator` a dual shape because a caller
genuinely branches on validity mid-flow; here the branch is "reject the request", which is what FR-19's
boundary handler already does with an exception. Offering both would double the surface for one
behaviour. Adding a `Result` overload later is MINOR.

### Error model — what happened to the `Result<Unit>` prediction

**`JdbcTxRunner` returns `T` and `void`, and throws. It is not the first `Result<Unit>` consumer, and
ADR-0012 predicted that it would be.**

The prediction is worth recording rather than quietly falsifying. ADR-0012 named item 4.4 as one of
three plausible first call sites and routed the signature question here; RFC-0002 then minted `Unit` on
the completeness of the error model and **explicitly rejected waiting for this call site**. With the
call site now in front of us, the rejection turns out to have been right: the failures a transaction
runner produces — no connection available, commit refused, a deadlock the database detected — are
infrastructure faults, and RFC-0001's own mechanical table assigns those to the unchecked-exception
shape, not to `Result.Err`. Returning `Result` would invite `if (result.isErr())` branching on
conditions where every branch does the same thing.

So `Unit` remains correct and remains unused by this milestone, and the cost of the prediction being
wrong is zero — which is the property ADR-0019 was buying when it declined to wait.

### Data & schema

`d4np-jdbc` owns **no schema**: it executes the caller's SQL, defines no tables, ships no migration and
assumes no dialect (manifest `secondary_lang: ""`, and RFC-0001 recorded the same for Milestone 2).
What is new is that it owns **transaction semantics over the caller's schema**, which is the first thing
in this project to fall inside ADR-0004's secondary-data frame at all. Concretely, the library issues
exactly three statements of its own — `setAutoCommit`, `commit`/`rollback`, and `setTransactionIsolation`
when a non-`DEFAULT` level is configured. Every other statement is the caller's.

Isolation is the single place where a library decision changes the *meaning* of SQL it did not write,
which is the whole reason `DEFAULT` means untouched.

H2 is a **test-scope** dependency for item 4.3's suite and NFR-03's harness, never a runtime one; the
module's enforcer allowlist already permits `*:*:*:*:test` and bans a compile-scope driver.

### Scalability budgets

One numeric budget binds Milestone 4:

| Axis | Metric | Target | Tool | Milestone item |
|---|---|---|---|---|
| performance | `SimpleJdbcExecutor` row mapping vs a hand-written `ResultSet` loop (H2 in-memory, 10k rows) | **≤ 10% overhead** | JMH | 4.3 |

**No budget is invented for FR-06, FR-07, FR-20 or FR-21.** No NFR names them, and item 2.4 already
recorded why inventing one is worse than leaving a thing unmeasured.

**NFR-03 is the one performance gate in this project that can be a real CI gate today**, and the reason
is non-obvious. NFR-01's 2 ns/op and NFR-06's 400 MB/s are *absolute* numbers against a named reference
machine, which is why item 8.3 exists and why they are advisory in CI. NFR-03 is a **relative**
comparison between two harnesses measured in the same JMH invocation, so the machine cancels: a slow
runner slows both arms. Item 4.3 should say so when it lands, because a reader who has met 8.3 will
assume this budget shares the problem.

### Versioning

RFC-0001 §Versioning is **cited, not restated** — the BOM is what consumers pin, and its list of MAJOR
triggers holds. Two additions specific to these modules:

1. Its rules were written for `d4np-core`; they apply **verbatim** to `d4np-jdbc` and `d4np-json`.
2. For `d4np-json` there is one extra trigger: a **Jackson major version that changes a type appearing
   in our published signatures** would be a MAJOR bump for us. That is precisely the trigger FR-21's
   own type token exists to avoid ever arming.

Both modules are `0.x` until M8, so nothing above is a compatibility promise yet, and 1.0.0 remains the
M8 deliverable.

### No amendments to RFC-0001 or RFC-0002

A reader who has seen RFC-0002 will look for an amendments section, so its absence is stated. Nothing
here contradicts either document. RFC-0001's *"no core method throws a checked exception"* is
**extended** rather than amended — no published method of any module throws one — and a callback the
*caller* implements declaring `throws Exception` is the opposite direction: this library is accepting a
checked exception, not raising one. RFC-0001's §Data & schema non-applicability was scoped to Milestone
2 and stays true of it. The one prediction this RFC contradicts lives in **ADR-0012**, which is an ADR
and is handled in §Error model above.

## Alternatives

1. **An ambient, thread-scoped current connection as the transport.** Rejected on the failure mode: an
   executor built from a `DataSource` would change its transactional semantics depending on the calling
   thread's state, and a hand-off to `CompletableFuture` or FR-09's `AsyncExecutor` would silently
   revert to auto-commit. The bug it produces is the invisible one this decision exists to remove.
2. **Nesting via suspension (`REQUIRES_NEW`), or no detector and documented `REQUIRES_NEW` semantics.**
   Suspension rejected: it needs a manager owning two connections, and FR-06 explicitly points Spring
   users at `TransactionTemplate` — half a transaction manager that diverges in one case is worse than
   none. Documenting the accidental version rejected too: the outer transaction holds locks the inner
   one waits for, on one thread, so a modest pool hangs with no error to read.
3. **Savepoints.** Rejected: only useful with the nesting refused above, and `setSavepoint` is optional
   in JDBC, so the API would work on some drivers and throw on others. The caller holds the
   `Connection` and can do it directly — which is an argument for the raw handover, not against it.
4. **A `Result.Err` returned by the callback triggers rollback.** Rejected: it gives a core type a
   second meaning in exactly one method, is ambiguous for a nested `Result`, and the rule it displaces
   — *the exception channel demarcates, the value channel does not* — is teachable in one line.
5. **`inTransaction` returns `Result<T>` / `Result<Unit>`.** Rejected: its failures are infrastructure
   faults the caller cannot usefully branch on, which RFC-0001's table assigns to the unchecked shape.
   This is where ADR-0012's prediction about item 4.4 does not hold, recorded in §Error model.
6. **`JdbcAccessException extends BusinessException`.** Rejected: FR-19 maps `BusinessException` to
   **422**, so a dead database would reach the client as a client error. ADR-0015 recorded this exact
   failure for `StrategyNotFoundException`, and it is likelier here because "data access" reads as
   business-adjacent.
7. **Keep the name `DataAccessException`.** Rejected under the naming test above: Spring ships one for
   the same job, so a host's `catch` clause silently fails to match and the exception escapes to the 500
   fallback — a divergence that compiles.
8. **Reflective POJO row mapping.** Rejected on three independent counts: it needs `opens` or public
   setters (the privilege RFC-0002 refused for FR-16), it is not compile-checkable, and per-row
   reflection cannot fit inside NFR-03's 10%.
9. **Flip `FAIL_ON_UNKNOWN_PROPERTIES` to `true` so partial mapping is strict.** Rejected: it is a
   mapper-wide setting and FR-20's leniency exists for a real reason — tolerating a producer who added
   a field. A per-operation check buys strictness exactly where an unknown name is an *instruction*
   rather than an *addition*.
10. **Expose Jackson's `TypeReference` in FR-21's signatures.** Rejected on the compatibility
    consequence: it forces `requires transitive`, contradicts `d4np-json`'s own descriptor, and makes a
    Jackson major version that moved the type into our MAJOR bump. The counter-argument — that this
    module exists to depend on Jackson — is acknowledged and loses on that cost alone.
11. **Expose the configured `ObjectMapper`.** Rejected by ADR-0022's rule: a guarantee a consumer can
    switch off is advisory, and one `activateDefaultTyping` call re-opens the CVE class FR-20 exists to
    close.
12. **`Optional` fields, or a `TYPE_USE`-widened `@Nullable`, for null-versus-absent.** Rejected:
    ADR-0011 calls the first an anti-pattern and deliberately left the marker declaration-only. A
    present-property set answers the question without touching either, and without changing a
    published annotation's target set as a side effect of a JSON helper.
13. **Put `PageRequest`/`PageResponse` in `d4np-core`.** Rejected: spec §3 assigns FR-05..07 to
    `d4np-jdbc`, so moving one is a spec change bought to save a dependency edge on a module that has
    zero third-party dependencies. The cost of leaving it is one JAR, not a tree.
14. **Normalise sort identifiers through `StringCaseConverter`.** Rejected: SQL identifier case folding
    is vendor-specific — PostgreSQL lower, Oracle upper, MySQL filesystem-dependent — so normalising
    picks a vendor. Exact matching is stricter and vendor-neutral.
15. **Make `PageResponse<T>` `Serializable`.** Rejected for ADR-0015's reason: it would be serialisable
    only when `T` happened to be, failing silently and only in the hosts that serialise.
16. **Rename `ObjectMapperExtensions`** to something Java can actually express. Rejected on
    traceability, as ADR-0021 rejected renaming FR-15: the spec is the frozen contract, so the rename
    costs a spec change and every future reader tracing FR-21 by name lands on nothing.
17. **Pin FR-05's full operation list here.** Rejected as over-reach: FR-05 carries no `[GAP]`, three
    shapes are enough to state the contract rows, and naming more would be inventing surface the
    specification does not ask for.

## Consequences

- **FR-06's and FR-21's `[GAP]` markers close**, and each manifest line gains a
  `[RESOLVED by RFC-0003]` pointer rather than a copy of the contract — the treatment ADR-0010
  established and RFC-0002 reused.
- **FR-05, FR-07 and FR-20 gain contract rows they never had.** With RFC-0001's nine types and
  RFC-0002's three, the six here take the specification's §5 `[GAP]` — *"only 5 of ~25 public types
  carry a nullability/error/thread-safety contract row"* — down to the types Milestones 5–7 own.
- **All five M4 items are unblocked, not the two the roadmap named.** Item 4.0's text blocks 4.2 and
  4.4; covering FR-05, FR-07 and FR-20 means 4.1, 4.3 and 4.5 also start from a pinned contract. That
  is the scope widening the owner approved, and it is why this RFC is longer than its brief.
- **Fifteen new public types across two modules**, all additive and all MINOR under RFC-0001
  §Versioning while both modules are `0.x`: in `d4np-jdbc` — `SimpleJdbcExecutor`, `RowMapper`,
  `JdbcTxRunner`, `TxCallback`, `TxVoidCallback`, `TxIsolation`, `JdbcAccessException`, `PageRequest`,
  `PageResponse`, `PageSort`; in `d4np-json` — `JsonMapper`, `ObjectMapperExtensions`, `JsonTypeToken`,
  `PartialUpdate`, `JsonConversionException`. This is the surface japicmp starts guarding at 1.0.0, so
  it is stated as a number now rather than discovered at item 8.1.
- **The two module descriptors gain their first non-core edges, and the same question gets opposite
  answers.** `d4np-jdbc` gains `exports it.d4np.utils.jdbc` and **`requires transitive java.sql`**,
  because `Connection` and `ResultSet` appear in interfaces the *consumer implements* — non-transitive
  would make our own API unusable without every consumer adding the edge themselves. `d4np-json` gains
  `exports it.d4np.utils.json` and a **non-transitive** `requires com.fasterxml.jackson.databind`, which
  is only consistent because no Jackson type appears in a signature. The type token is what keeps that
  descriptor honest rather than aspirational. Both additions are invisible to the `jpms-congruence`
  lint, which compares only the family-root edges against the internal `<dependency>` set.
- **Three threat-model rows become implementable and none is added.** *SQL altered via string
  concatenation* (FR-05), *injection via `ORDER BY`* (FR-07) and *polymorphic-deserialization gadget
  chain* (FR-20) each move from ▢ to enforceable when their item lands. Unlike RFC-0002, **no new trust
  boundary is needed**: `d4np-jdbc → DataSource` is already **B3**, and both the `Connection` handed to
  caller code and the untrusted document `readPartial` reads sit inside **B1**. Said explicitly because
  a reader of RFC-0002 will expect a routed gap here.
- **Two compliance call sites will be registered when the code lands**, not now, because the register's
  evidence column takes tests: `JdbcAccessException`'s SQL-free message against **C-01** (items 4.3 and
  4.4), and `JsonConversionException`'s path-only message plus the disabled source location, also
  against C-01 (items 4.1 and 4.2). **C-03 gains no consumer** — the exact-match whitelist decision is
  what keeps the locale rule out of FR-07.
- **Three obligations are filed on item 7.1, which owns FR-19.** Two new rows —
  `JdbcAccessException → 500 + alert` and `JsonConversionException → 400` — and one rule: the fallback
  handler must not render a cause chain's `getMessage()` into the 7807 body, because that is where the
  driver's SQL and Jackson's source snippet live.
- **Item 4.4 owes a named jcstress harness** for the thread-scoped depth counter, and a demonstration
  that a leaked `Connection` breaks — the shape items 2.4 and 3.3 used, rather than a Javadoc sentence.
- **The connection-ownership rule is a contract, not a control.** Nothing stops a captured
  `DataSource`-backed executor from being used inside a transaction block; what this decision buys is
  that the mistake is visible in the lambda's capture list instead of hidden in ambient state. Anyone
  who reports that as a gap should be pointed here, and at alternative 1.
- **Nothing here is enforceable until items 4.1–4.5 land.** This RFC is a contract; the gates arrive
  with the code, and until then FR-06 and FR-21 are designs on paper — which is the state their `[GAP]`
  markers were flagging.

## Approval

The approval encodes a **human decision** — no RFC self-approves (`AGENTS.md` §6). This document is
drafted `Proposed` with an empty record, and the record below is filled only on the owner's word, in a
change separate from the drafting:

```
approved-by: (none — Proposed)
```

**Review provenance — stated, not implied.** No independent `reviewer`, `enterprise-architect` or
`security-auditor` round has run. FR-20's default-typing stance and the two C-01 message rules are
security decisions whose reviewer role owns the threat model they touch, so the absence is recorded
here rather than left to be inferred — a later reader needs to know which assurance this RFC does *and
does not* carry.

Merging the pull request that introduces this file is not itself acceptance. The `Status` line above
moves to `Accepted` with a date, and `approved-by:` gains a record, only when the owner says so — it is
left `Proposed` rather than pre-filled, because a status field an agent set to `Accepted` in advance is
exactly the audit trail this section exists to prevent.

**On the approver role:** this project's RFCs are approved by the **owner**, not by the `tech-lead` that
`.eados-core`'s RFC protocol names — which is why the mechanical `rfc_check.py` gate reports a failure
against RFC-0001's form. [ADR-0023](../adr/0023-the-owner-approves-this-projects-rfcs.md) records that
deviation and why the honest attribution wins over the green gate.

Reviewers (structured findings addressed): reviewer — **not run** ; enterprise-architect — **not run** ;
security-auditor — **not run**.

## References

- FR-05, FR-06, FR-07, FR-20, FR-21 in [`docs/specs/01_spec_utils.md`](../specs/01_spec_utils.md);
  §3 for the two modules' dependency rows; §5 for the contract-row gap; FR-19 for the mapping table
  that separates 400, 422 and 500; NFR-03 for the only budget that binds this milestone.
- [RFC-0001](0001-core-contracts.md) §Error model, §Versioning, §Data & schema.
- [RFC-0002](0002-cross-cutting-contracts.md) — `ValidationException` outside `BusinessException`, the
  additive-only policy shape, and the `Unit` decision this RFC declines to consume.
- [ADR-001](../../.spec/adr/d4np_java_adr_001_module_split.md) — the dependency policy and the
  naming-consequence rule; [ADR-0006](../adr/0006-enforce-the-dependency-policy-per-module.md) — the
  default-deny allowlists that make H2 legal at test scope and a driver illegal at compile scope.
- [ADR-0005](../adr/0005-jpms-module-names-and-export-less-descriptors.md) — the descriptors whose
  `exports` and third-party edges arrive with these types.
- [ADR-0011](../adr/0011-declare-the-nullability-annotation-in-core.md) — a type in a published
  signature is the dependency you cannot keep to yourself; and the deliberate absence of `TYPE_USE`.
- [ADR-0012](../adr/0012-the-null-boundary-of-the-core-error-vocabulary.md) §the three plausible call
  sites — the prediction §Error model corrects;
  [ADR-0019](../adr/0019-mint-unit-for-the-void-success.md) — why that costs nothing.
- [ADR-0015](../adr/0015-strategy-registry-last-write-wins.md),
  [ADR-0020](../adr/0020-render-violations-from-the-message-template.md),
  [ADR-0021](../adr/0021-time-through-an-advice-body-core-can-own.md),
  [ADR-0022](../adr/0022-redact-at-capture-behind-a-typed-event.md) — the four precedents this RFC
  applies rather than re-argues.
- [ADR-0023](../adr/0023-the-owner-approves-this-projects-rfcs.md) — the approver role, and the
  mechanical gate it deviates from.
- [threat model](../security/threat-model.md) §1 boundary **B3** and §2's three ▢ rows for FR-05,
  FR-07 and FR-20.
- OWASP *SQL Injection Prevention Cheat Sheet* — parameterized statements as the primary defence, which
  FR-05 makes structural rather than advisory. CWE-502 *Deserialization of Untrusted Data* — the class
  FR-20's default-typing stance closes.
