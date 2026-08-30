# 2026-08-30 — RFC-0004, and a requirement that names a type the module may not import (ROADMAP item 5.0)

**Milestone 5 opens.** [RFC-0004](../../../rfc/0004-concurrency-contracts.md) pins FR-08, FR-09 and
FR-10 for `d4np-concurrent` and is drafted `Proposed` with an empty `approved-by:`, awaiting the
owner. What is worth carrying forward is less the contracts than the **method**: item 4.4 paid for
RFC-0003 pinning an FR-06 surface that did not compile, so this time every surface claim was compiled
before it was written down. Three probes ran first. Two of them changed the answer, and one of those
changed it in a direction I did not predict.

## What changed

`docs/rfc/0004-concurrency-contracts.md`; ROADMAP item 5.0 flipped with its entry, the checkpoint
pointer, and the Spec Coverage Map's §5 row; `refs.rfcs` gains **RFC-0004** and `manifest_rev` moves
7 → 8; the threat model gains a row. No production code, no ADR, no `CHANGELOG.md` entry — item 3.0
added one only because it shipped `Unit`.

## The requirement names a type this module may not import

FR-09 asks for *"MDC context propagation"*. `MDC` is SLF4J's. It is unreachable here, and not as a
matter of taste:

- `d4np-concurrent`'s `enforce-adr-001` allowlist is default-deny with exactly **two** entries —
  `it.d4np:*` and test scope. `d4np-core`'s has a **third**, for `jakarta.validation-api` at
  `provided`. There is no provided-scope escape in this module, so SLF4J fails `mvn validate`.
- This library does not log through SLF4J at all. It logs through `System.Logger`, which has **no
  MDC**.

The part that makes this a revisit rather than a discovery is that **ADR-0014 named the trigger when
it made the decision**: *"a module that later needs structured context will find `System.Logger`
thin, and that is the moment to revisit, not now."* FR-09 is that module. Finding a prediction like
that already written down is the strongest argument I have seen in this repo for the habit of
recording consequences you do not yet have to act on.

The answer is FR-15's shape one milestone later — the module owns a context SPI and ships nothing
that reads a logging framework — and the contract is pinned under **ADR-0010 rung 1**, *an RFC
outranks the spec for every section it pins*, so FR-09's spec sentence is superseded rather than
edited. Two tempting alternatives were refused on their failure modes rather than on principle: a
reflective `org.slf4j.MDC` lookup works when SLF4J happens to be present and **silently propagates
nothing** otherwise, and shipping the binding in `d4np-spring-adapter` is *legal* — that allowlist
bans only Redisson — but MDC is SLF4J and not Spring, so a Jakarta EE host would take a Spring
dependency to get context propagation, in a project whose whole thesis is framework independence.

## A lifecycle method that is replaced at run time, and nothing in the source says so

`ExecutorService` became `AutoCloseable` in Java 19, with a default `close()` that waits
`awaitTermination(1, DAYS)` in a loop. We compile at `--release 17`, where the method does not exist,
and ship to consumers on 17 **and** 21. Five results, from a wrapper compiled at `--release 17` by
JDK 21's javac and driven from a consumer compiled on 21:

| Probe | Result |
|---|---|
| No `close()` declared; consumer try-with-resources; 3 s task | interface default runs — `awaitTermination(1, DAYS)`, **3017 ms**, configured budget ignored |
| `close()` declared, 500 ms budget | ours runs — **510 ms** |
| Same object via the `ExecutorService` static type | ours still runs — **513 ms**, a genuine override |
| `@Override` on it at `--release 17` | **compile error** — *"does not override or implement a method from a supertype"* |
| JDK 17 consumer, try-with-resources | **compile error** — *"cannot be converted to AutoCloseable"* |

So `ManagedThreadPool` declares `AutoCloseable` **and** `close()` explicitly, and the annotation that
would tell a reader why cannot be written. The dangerous property is that the failure is a
*deletion*: someone tidying an un-annotated method that looks like convenience restores row 1, and
only on JDK 21 consumers. Item 5.1 owes a test that drives `close()` **through the `ExecutorService`
static type** — a test through the concrete type keeps passing after exactly that regression.

## The probe that found something worse than the ambiguity it was looking for

Item 4.4 hit `reference to inTransaction is ambiguous` on an overload pair over two functional
interfaces, so I compiled `submit(Supplier<T>)` / `submit(Runnable)` expecting the same. **It is not
ambiguous.** All four shapes resolve — and which one they resolve to depends on the *syntax* of the
body:

| Call | Binds to | Future |
|---|---|---|
| `submit(() -> returnsInt())` | `Supplier` | `CompletableFuture<Integer>` |
| **`submit(() -> { returnsInt(); })`** | **`Runnable`** | **`CompletableFuture<Void>`** |

Rows one and two are the same call with braces added — to insert a log line, say — and the result is
silently discarded. That is *worse* than the ambiguity: an ambiguity is a compile error the author
must resolve, this compiles and diverges. ADR-001's naming-consequence rule therefore applies more
strongly here than it did in item 4.4, and the methods are **`supply` and `run`** — which is what the
JDK already does in the very class FR-09 wraps.

I had written "the pair is ambiguous, rename it" as the expected finding before running the probe.
The probe's actual answer supports the same decision through a different mechanism, which is the case
where it would have been easiest to ship the wrong reasoning behind a right conclusion.

## Two contracts that exist because the requirement bounds the wrong side

- **FR-08's rejection handler cannot fire in the shape a reader will picture.**
  `Executors.newFixedThreadPool` uses an unbounded queue, so the *explicit*
  `RejectedExecutionHandler` the requirement mandates is decoration and the threat model's
  *rejection storm* row is mitigated by nothing. The queue capacity is therefore mandatory with no
  defaulting overload — FR-05's structural move applied again.
- **FR-10 bounds starvation and not corruption.** A mandatory lease stops a lock being held forever;
  it does nothing about the lease **expiring while the holder is still running**, which gives two
  writers. No lease-based lock can fix that, and the only structural mitigation — a fencing token —
  has to exist in the *interface* or no implementation can add it without a MAJOR break.
  `OptionalLong` is the honest shape: mandatory makes the interface unimplementable by
  `d4np-lock-redisson` on day one, absent forecloses it forever.

## Smaller things worth carrying forward

- **The threat model gains a row rather than only moving one**, which no RFC here has done before —
  RFC-0003 explicitly added none. Information disclosure had eight rows and none for a pooled worker
  thread carrying one task's context into the next, which is FR-09's real defect and the reason
  `install()` restores rather than clears.
- **NFR-02 goes to item 8.3, not 8.8**, and the distinction is RFC-0003's: NFR-03 could be a real
  gate because it is a *relative* ratio in one JMH invocation; NFR-02 is bounded **absolutely** at
  5 µs. It is also loose by roughly two orders of magnitude against `supplyAsync`'s ~100 ns, so a
  4 µs regression would pass — item 5.2 reports the number, not a verdict.
- **The public-type count is stated twice on purpose** — nine top-level and two nested, eleven total
  — because RFC-0003 stated one number and was wrong by exactly the nested types it named in prose
  and did not count.
- **A rendered file has drifted from its template, and it was found by a check run for another
  reason.** Editing `orchestrator/project.yaml` meant re-rendering to prove ADR-0010's byte-identity
  property for the spec — which it holds — and the same run showed `.github/workflows/ci.yml`
  carrying **five comment lines the render does not produce**. It predates this milestone and is on
  `origin/main`. `consistency_lint.py` does not compare the two, so a hand-edit there leaves no trace
  and the next render reverts it silently. **Filed as item 8.9** rather than repaired here; the gate
  is worth more than the reconciliation, because the drift is benign today (comments) and that is
  precisely why it survived.

## Where the project stands

**Milestone 4's item 4.5 is still unmerged**, on `feat/page-request-response` at `9df7559`, with no
PR opened. This branch was taken from `main` without it, which has two visible consequences: the
ROADMAP still shows `- [ ] 4.5`, and RFC-0004 cites **no ADR above 0032**, because ADR-0033 and
ADR-0034 arrive with 4.5 and linking them would ship two 404s. Whichever of the two PRs merges second
needs a rebase — both edit the *Latest checkpoint* pointer.

## What the next session needs to know

- **Item 5.1 is unblocked once RFC-0004 is accepted**, and it inherits two obligations that are easy
  to lose: the `close()` test must go through the **`ExecutorService`** static type, and NFR-05's
  jcstress harness must be **shown to fail** before it is trusted — sabotage the state it watches,
  confirm it goes red, restore. Item 4.4 established that rule and it is the only reason a green
  harness means anything.
- **Items 5.1, 5.2 and 5.3 are each expected to carry one ADR** — the `close()`/`@Override` skew, the
  context SPI (security-relevant, so mandatory under the enterprise posture), and the fencing-token
  contract. RFC-0004 pins the contracts and deliberately does not pre-write those records, which is
  how 4.1–4.5 were sequenced.
- **`rfc_check.py` FAILs by design** (ADR-0023) and the failure is expected output. Do not write
  `approved-by: tech-lead` to make it green — `tech-lead` is the authoring role, so that is
  self-approval.
