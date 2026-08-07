# 2026-08-07 — `ObjectMapperExtensions`, and a leak the wrapping rule did not reach (ROADMAP item 4.2)

**Milestone 4, item 4.2 — FR-21 over the mapper item 4.1 built this morning.** Four new public types,
no new module edge, no new dependency: the descriptor's `exports it.d4np.utils.json` from 4.1 already
covered them, which was written down at the time rather than hoped for.

## What changed

`ObjectMapperExtensions`, `PartialUpdate<T>` and `JsonTypeToken<T>` land in `d4np-json` (**31 new
tests**, 52 in the module, 333 in core unchanged), with `JsonDiagnostics` extended rather than
duplicated. Two records:
[ADR-0026](../../../adr/0026-rewrite-jacksons-unchecked-conversion-failure.md) (the unchecked leak
channel) and [ADR-0027](../../../adr/0027-a-partial-update-renders-names-not-values.md) (why
`PartialUpdate` is a class and not a record). C-01 gains its **third** enforced call site and stops
being only about exceptions; the patterns catalogue gains a **rejection**, not an adoption.

## The leak the rule did not reach

RFC-0003 states the wrapping rule in one sentence: *"`JsonProcessingException` is checked, so it is
wrapped in `JsonConversionException`."* True, and item 4.1 implemented exactly that for the parse and
write paths.

**FR-21's conversion path never raises it.** `ObjectMapper.convertValue` catches Jackson's own
`IOException` internally and rethrows:

```text
java.lang.IllegalArgumentException: Cannot deserialize value of type `int` from String "hunter2":
  not a valid `int` value (through reference chain: …MistypedCredentials["password"])
```

Unchecked, carrying Jackson's message verbatim, quoting the value. And that is the part worth
carrying forward:

> A rule phrased against the *checked* exception is a rule **the compiler enforces**. It says nothing
> about the unchecked one, and nothing in the language, the build, or any gate in this repository
> would have flagged a `convert` that let it through.

It would have compiled, passed every test that was not looking for it, and shipped a C-01 violation on
the library's newest public method — landing on FR-19's **500** fallback rather than 400 into the
bargain, which is ADR-0015's misattribution arriving by a different route.

ADR-0026 catches and rewrites it, and restates the rule in the form that actually holds: **no
exception leaves this module carrying text this library did not write, whatever its checked-ness.**
Item 4.4 inherits it directly — a driver's `SQLException` message carries the SQL it was given.

The narrower alternative — rethrow only when the cause is a `JsonProcessingException`, so a host
serializer's own `IllegalArgumentException` keeps its type — was rejected on what it optimises for:
a conditional whose false branch *is* the leak.

## The decision a record would have made for us

RFC-0003 sketched `PartialUpdate<T>` as `public final class` with three members. A record implements
that shape exactly, and this project reaches for records freely.

A record's generated `toString()` prints its components:

```text
PartialUpdate[value=Credentials[user=ada, password=hunter2], presentProperties=[password, user]]
```

One `log.debug("patch {}", update)` away, written by an author not thinking about disclosure at all —
and a `toString()` reaches a log far more casually than an exception reaches a client. ADR-0027 makes
it a class with a hand-written rendering that names the value's **type** and lists its property names.
Construction stays package-private, which a record's canonical constructor could not have allowed.

`equals` still reads the value, and the asymmetry is stated rather than left to be noticed: comparing
is not disclosing.

**The second half is the one that is easy to miss.** The present-name set looks like the target's own
vocabulary — `readPartial` refuses any name the target does not declare — except that with a `Map`
target **every key is a known property by construction**. So the names are bounded through the same
routine every message in this module uses, and deliberately **not** in `presentProperties()`, where a
truncated name would answer `isPresent` with a wrong `false`: a log concern traded for a correctness
bug.

## A confident wrong answer, caught by a test

`readPartial` reports *every* unknown property rather than the first Jackson trips on, by subtracting
the exception's `getKnownPropertyIds()` from the names read out of the document. Those ids belong to
**the type Jackson was populating** — which for a nested offender is the nested type. So:

```json
{"reference":"B-9","lines":[{"sku":"A-1","nope":1}]}
```

reported `['lines', 'reference']` as unknown properties of `Basket`: a fluent, complete, entirely
wrong answer, of the kind a reviewer reads straight past. `refusesAnUnknownPropertyAtEveryDepth`
failed on the first run and the subtraction now applies only when the failure's path is at the top
level; below it, Jackson has already named the single offender.

## Two refinements of the RFC, recorded without their own ADR

They narrow RFC-0003 rather than diverge from it, so the Javadoc and this entry are the record:

- **The refusal covers every depth; the report covers the top level only.** The RFC bounds the *set*
  — a nested report would need a path language — and it does not bound the *check*. A safety property
  with a shallow end is not one.
- **`JsonTypeToken` refuses a token over a type variable at construction.** `new JsonTypeToken<T>() {}`
  inside a generic method captures what erasure already discarded; Jackson resolves it to the
  variable's bound and hands back a `LinkedHashMap` that fails at a cast somewhere else entirely.
  Jackson's own `TypeReference` tolerates it. Refusing is the same reasoning that refuses the literal
  `null` document: a wrong answer that fails later is worse than a refusal that fails now.

## Companions, again, and what each one is for

The discipline item 4.1 established, applied to the two claims here that could go quietly wrong:

| Claim | The companion that keeps it honest |
|---|---|
| The type token is what makes a generic target work | `theRawTargetLosesTheElementTypeAndSaysNothing` — the untyped call **succeeds**, handing back `LinkedHashMap`s |
| No conversion message carries the payload | `theSameConversionLeaksThroughRawJackson` — raw Jackson leaks `hunter2` on that exact input |
| Strictness is per operation, not per mapper | `leavesEveryOtherReadLenient` — refuses the update, then reads the *same* document through `readValue` |

## Where the project stands

**Milestone 4 is half done** — 4.0, 4.1 and 4.2 complete; **4.3, 4.4 and 4.5 are all `d4np-jdbc`**,
which still has no code. FR-20 and FR-21 are both closed, so `d4np-json` is feature-complete against
the specification as it stands.

## What the next session needs to know

- **Item 4.3 starts an empty module**, and `javadoc:javadoc` currently fails on it — *"No public or
  protected classes found to document"* — so the doclint gate has to be scoped to
  `-pl d4np-core,d4np-json` until the first JDBC type lands. Not a defect; a consequence of a module
  existing before its code.
- **Item 4.3 owns the one numeric budget in this milestone**, NFR-03, and RFC-0003 asks it to say
  *why* that budget can be a real CI gate when NFR-01 and NFR-06 cannot: it is a **relative**
  comparison inside one JMH invocation, so the machine cancels.
- **Item 4.4 inherits ADR-0026's restated rule**, and the shape is already known: a `SQLException`
  message carries the SQL it was given, so `JdbcAccessException` must be built from what the library
  knows, never from the driver's text.
- **Item 7.1's obligation list is unchanged at four**, but one of them now has a second reason: the
  fallback handler must not render a cause chain's `getMessage()`, and the cause of a *conversion*
  failure is an `IllegalArgumentException` whose message quotes the value just as
  `InvalidFormatException`'s does.
- **The floor build is now load-bearing for three Jackson APIs** — `getPath()`,
  `getKnownPropertyIds()` and `readerFor(..).with(..)` — and `-Djackson.version=2.15.3 clean verify` is
  green on all 52 tests. Any FR-21 change should re-run it rather than assume it.
- **The two unreproducible CI commands are unchanged** from item 3.1: `japicmp:cmp` resolves no plugin
  (item 8.1), `-Pcoverage` matches no profile (item 8.2).
