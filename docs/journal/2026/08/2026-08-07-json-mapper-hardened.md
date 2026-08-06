# 2026-08-07 — `JsonMapper`, and a claim the RFC could not keep (ROADMAP item 4.1)

**Milestone 4, item 4.1 — the first production code this project has placed outside `d4np-core`, and
the first compile-scope third-party dependency in the repository.** Core's single edge is `provided`
behind `requires static`, so nothing before today shipped a dependency a consumer actually resolves.
ADR-001 puts Jackson in this module precisely so that everyone else keeps not resolving it.

## What changed

`JsonMapper`, `JsonConversionException` and the package-private `JsonDiagnostics` land in
`d4np-json` (**21 new tests**, 333 in core unchanged), with `exports it.d4np.utils.json` and three
non-transitive Jackson `requires` on the descriptor. Two records:
[ADR-0024](../../../adr/0024-take-a-jackson-type-in-one-signature.md) (the customisation seam and the
module edge) and [ADR-0025](../../../adr/0025-render-java-time-as-iso-8601.md) (the one setting
beyond FR-20's list). C-01 gains its second enforced call site; the threat model's
polymorphic-deserialization row moves ▢ → ✅; the patterns catalogue gains **Facade** as row 9.

## The claim RFC-0003 could not keep

Two sentences, both approved, that cannot both be literally true:

- §FR-20 — the absent getter is paid for by letting construction **take a list of Jackson `Module`s**.
- §Consequences — the descriptor's non-transitive `requires com.fasterxml.jackson.databind` is *"only
  consistent because no Jackson type appears in a signature."*

`com.fasterxml.jackson.databind.Module` is a Jackson type, and it is in a published signature. The
RFC's own defence — *"the type token is what keeps that descriptor honest"* — answers the FR-21
question it was written for (`TypeReference`) and does not reach this one.

**The fix was not a better argument, it was a measurement.** A consumer module, compiled *and run*
against the built artifacts:

| Consumer source | Jackson `requires` declared? | Result |
|---|---|---|
| `create()`, `readValue`, `writeValueAsString` | no | **compiles and runs** |
| `withModules(List.of())` | no | **compiles** |
| `withModules(List.of(new SimpleModule()))` | no | **fails** — *package … is not visible* |
| the same | yes | compiles |

Row two is the one that corrects an intuition: **javac wants the read edge where the consumer's own
source *names* the type, not where an invoked signature mentions one.** Confirmed again with a
synthetic two-module probe over an overloaded pair. So the separate `withModules` name is an API
choice that buys nothing structurally, and the consumer that constructs a `Module` was always going
to name Jackson anyway. `requires transitive` would have saved that consumer one line — and made a
Jackson major version our MAJOR bump, the cost that already killed exposing `TypeReference`.

The alternative that *would* have kept the sentence literally true is `findAndRegisterModules()`, and
it is recorded rather than dismissed: it loses because service loading registers whatever is on the
classpath, which is implicit configuration inside the one class whose value is that its configuration
is explicit.

## The default that moved

RFC-0003 asks for `INCLUDE_SOURCE_IN_LOCATION` to be *set explicitly rather than relied upon as a
default*, "in either direction". That reads like caution until you look:

```
jackson-core 2.15.3   INCLUDE_SOURCE_IN_LOCATION(true)
jackson-core 2.22.1   INCLUDE_SOURCE_IN_LOCATION(false)
```

Both versions are inside the supported matrix, because a Spring Boot 3.2 host's own dependency
management downgrades this library to its managed Jackson. **The explicit `disable` is the only
reason those two hosts behave the same way.** RFC-0001's UTF-8 argument now has a counter-example
attached to it.

## The version pin runs the other way from item 3.1's, on purpose

Bean Validation is `provided`: the host's copy wins at run time, so ours had to be the matrix
**floor**. Jackson is `compile`: ours is what a plain Jakarta EE host resolves, so an old pin ships
an unpatched Jackson to exactly the hosts with no dependency management. Pinned at the **latest
stable, 2.22.1** — and the floor is paid by the code instead, which is a claim, so it is run:

```bash
mvn -B -pl d4np-json -am -Djackson.version=2.15.3 clean verify   # green, 21/21
```

The command lives in the module POM so item 4.2 inherits the obligation rather than the folklore. It
earned its keep on the first run, catching a version-coupled assertion: 2.22 renders the suppressed
location as `REDACTED (…INCLUDE_SOURCE_IN_LOCATION disabled)` and 2.15.3 as `UNKNOWN`, so the test
now asserts the *absence of the snippet* rather than the presence of a marker.

**The BOM import was verified too, and it is not a style preference:** `jackson-annotations` is at
**2.22** while `databind` is at **2.22.1** — Jackson dropped the patch digit from annotations in 2.20,
as its own BOM comment says — so hand-pinning `${jackson.version}` across the three artifacts does not
merely risk `dependencyConvergence`, it fails to resolve.

## C-01, narrowed by running it

RFC-0003 names two defences: our message rule, and the disabled source location protecting the cause.
The first holds absolutely. The second is narrower than it reads:

```
com.fasterxml.jackson.databind.exc.InvalidFormatException:
  Cannot deserialize value of type `int` from String "hunter2": not a valid `int` value
  at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 25]
```

The setting did exactly what it promises — the snippet is gone — and the **rejected value is still
there**, quoted in the body of Jackson's own message, which no Jackson setting governs. So the
exception this library throws is clean and **its cause is not**.

That turns RFC-0003's advice to item 7.1 into a requirement: the FR-19 fallback handler must not
render a cause chain's `getMessage()`. `jacksonsOwnMessageStillQuotesTheValue` is the test that says
why, and the compliance register's C-01 row now carries the finding rather than the assumption.

## Every dangerous setting has a companion that proves the payload is live

The habit item 3.3 established, applied three times, because "default typing is off" is a property of
what happens to a document, not of a configuration call:

- the gadget-shaped document is read as data here, and **does** instantiate the class it names under a
  mapper with default typing activated;
- the truncated credential document leaks `hunter2` through a mapper with `INCLUDE_SOURCE_IN_LOCATION`
  enabled;
- `java.time` fails outright without the module.

If a companion ever stops failing, the payload has gone inert and the main assertion has quietly
become vacuous — the class of defect this repository has now recorded five times.

The structural half of FR-20 is tested structurally: `publishesNoHandleToTheConfiguredMapper` walks
the reflected surface and fails on any public constructor or any method mentioning an `ObjectMapper`,
because a getter added later would compile and pass every other test in the file.

## Three smaller things worth carrying forward

- **The literal `null` document is refused.** It is valid JSON and Jackson answers it with a `null`
  reference; C-02 says this library never hands one back, so the caller gets a named refusal rather
  than a `NullPointerException` two frames later.
- **A document-supplied name is bounded in content, not only in length.** RFC-0003's 64-character
  bound does nothing about a `Map` key containing a newline, which is a log-injection primitive rather
  than a formatting problem, so ISO control characters are stripped as well.
- **The name collides with Jackson's own `com.fasterxml.jackson.databind.json.JsonMapper`, and is
  kept.** Under item 4.0's naming test the wrong import cannot diverge silently — that type has no
  `create()`, so the mistake fails to compile. The cost is paid inside our file, which names Jackson's
  builder by its fully qualified name exactly as `Validator` names `jakarta.validation.Validator`.

## The gate `clean verify` does not run — clean this time

`-Xdoclint:all -Xwerror` passes on **both** toolchains, which is a first: item 2.1 and item 3.3 each
found JDK-17 "no comment" noise on the components of a `Serializable` **record**, and nothing
published here is one. It is a data point for item 8.4 rather than a contradiction of the earlier two.

## Where the project stands

**Milestone 4 is under way** — 4.0 (RFC-0003) and 4.1 done, 4.2–4.5 open, and the README milestone
table now reads 🚧 for M4. `d4np-jdbc` still has no code; the three remaining JSON/JDBC items all
start from a pinned contract.

## What the next session needs to know

- **Item 4.2 inherits three things**, not one: the `delegate()` seam (package-private, which is what
  keeps "the mapper is unreachable" true while `ObjectMapperExtensions` still works), `JsonDiagnostics`
  for its messages, and the floor-build command in the POM.
- **`JsonTypeToken` and `PartialUpdate` are 4.2's**, deliberately not started here. `readValue` takes a
  `Class<T>` only, so a generic target is unreadable until 4.2 lands — stated in the Javadoc rather
  than discovered.
- **Item 7.1's list is now four obligations**, not three: two FR-19 rows (`JsonConversionException` →
  400, `JdbcAccessException` → 500 + alert), the no-cause-chain-rendering rule — and that rule is now
  *required*, with a test naming the reason, rather than prudent.
- **`AuditComponents` was not reused.** Item 3.3 suggested FR-20's shape rules should extend core's
  reflection seam; they did not need to, because Jackson owns the reflection here and this module
  never walks a host's type itself. Recorded so the suggestion is not left looking unaddressed.
- **The two unreproducible CI commands are unchanged** from item 3.1: `japicmp:cmp` resolves no plugin
  (item 8.1) and `-Pcoverage` matches no profile (item 8.2), so a green local `-Pcoverage` is still a
  vacuous green — and it now covers a second module.
