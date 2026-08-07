# ADR-0027: `PartialUpdate` renders its property names and never its value — which is why it is a class and not a record

- **Status:** Accepted
- **Date:** 2026-08-07
- **Deciders:** tech-lead (implementation of ROADMAP item 4.2), owner
- **Related:** [RFC-0003](../rfc/0003-jdbc-and-json-contracts.md) §FR-21 *null-versus-absent*; spec
  [§2 FR-21](../specs/01_spec_utils.md); compliance control **C-01**
  ([register](../compliance/README.md));
  [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) (redaction belongs where the data is
  captured, not where it is rendered);
  [ADR-0026](0026-rewrite-jacksons-unchecked-conversion-failure.md) (the other payload channel item
  4.2 found)

## Context

RFC-0003 sketched `PartialUpdate<T>` as `public final class PartialUpdate<T>` with three members. A
Java record is the obvious implementation of exactly that shape, and this project reaches for records
freely — every fixture, `AuditEvent`, `ErrorDetail`.

A record would have generated `toString()`, and the generated one **prints the components**:

```text
PartialUpdate[value=Credentials[user=ada, password=hunter2], presentProperties=[password, user]]
```

`value` was built from a document this library treats as untrusted; that is the premise of the whole
module. Control C-01 forbids that text in an exception message crossing to a client, and the
reasoning does not stop at the exception: **a `toString()` reaches a log far more casually than an
exception does** — one `log.debug("patch {}", update)` and a password is in the log file, written by
a line whose author was not thinking about disclosure at all. The exception path at least announces
that something went wrong.

There is a second, less obvious half. The present-name set looks like the target type's own
vocabulary — `readPartial` refuses any name the target does not declare — but for a `Map` target
**every key is a known property by construction**, so the set is client input. A key holding `\r\n`
folds one log line into two, which is a log-injection primitive rather than a formatting problem. The
same bound `JsonMapper` already applies to a `Map` key reaching an exception message applies here for
the same reason.

## Decision

**`PartialUpdate` is a final class with a hand-written `toString()` that names the value's *type* and
lists the present property names, bounded.** The names go through `JsonDiagnostics` — truncated at 64
characters, stripped of ISO control characters, capped in count — which is the same code path every
message in this module uses.

```text
PartialUpdate[it.example.Credentials present=['password', 'user']]
```

**`equals` and `hashCode` do read the value**, and the asymmetry is deliberate rather than an
oversight: comparing is not disclosing. Two readings are equal when both the value and the
present-name set are equal, because two documents that produce an equal instance are still different
updates — which is the entire premise of the type.

Construction stays **package-private**: only this module's own `readPartial` knows which names a
document carried, and a `PartialUpdate` a caller could assemble by hand would be a claim about a
document rather than a reading of one. A record's canonical constructor is as public as the record,
which is a second reason the shape does not fit.

## Alternatives Considered

- **Make it a record and accept the generated `toString()`.** Rejected on the disclosure above. The
  saving is roughly twenty lines of boilerplate against a payload in a log file, written by whoever
  logs the object next.
- **Make it a record and override `toString()`.** Legal, and rejected as the worse half of both
  options: it still publishes a canonical constructor, and it leaves a reader to notice that the one
  generated member that mattered was overridden. A class states the intent where the type is
  declared.
- **Render the value but redact it.** Rejected because this type cannot know what is sensitive in a
  caller's own domain object — the knowledge ADR-0022 put in `AuditPolicy`, supplied by the host.
  Inventing a policy here would be guessing, and a guess that renders is a guess that leaks.
- **Bound the names in `presentProperties()` too, not only in `toString()`.** Rejected because those
  names have a job: they must match what a caller passes to `isPresent(String)`. A truncated name in
  the accessor would silently answer `false` for a property the document did contain, trading a log
  concern for a correctness bug.
- **Say nothing and document "do not log this".** Rejected as the class of guarantee ADR-0022 already
  ruled advisory: a property a consumer has to remember is not a property of the type.

## Consequences

- **The published `toString()` format is part of the contract from the first release**, and is
  asserted exactly — `rendersTheTypeAndTheNamesAndNeverTheValue` reads a credential document and
  checks the rendering names the type, lists both properties and does **not** contain the password;
  `boundsTheNamesItRenders` puts a CR/LF key through a `Map` target and checks the rendering carries
  neither character.
- **`PartialUpdate` is not `Serializable`**, for the reason ADR-0015 recorded for `PageResponse`: it
  would be serialisable only when `T` happened to be, which is a promise the type cannot keep.
- **The value is still reachable** — `value()` returns it, and a caller that logs *that* is logging
  its own object, which is its own decision. The line this draws is that **this library's own
  rendering** never does it for them.
- **One more place depends on `JsonDiagnostics`**, which was documented as an implementation detail
  of exception messages and is now the module's single bounding routine. Its documentation says so
  rather than leaving the second caller to look accidental.

## References

- [RFC-0003 §FR-21](../rfc/0003-jdbc-and-json-contracts.md) — the shape this implements, and the
  null-versus-absent argument behind it.
- [`PartialUpdate.java`](../../d4np-json/src/main/java/it/d4np/utils/json/PartialUpdate.java) — the
  type, and the class documentation that states the rendering rule.
- [`JsonDiagnostics.java`](../../d4np-json/src/main/java/it/d4np/utils/json/JsonDiagnostics.java) —
  the bounding, shared with every message in the module.
- [ADR-0022](0022-redact-at-capture-behind-a-typed-event.md) — where the knowledge of what is
  sensitive lives in this project, and why it is not here.
