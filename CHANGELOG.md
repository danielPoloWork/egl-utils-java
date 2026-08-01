# Changelog

All notable changes to `egl-utils-java` are documented here, following
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning 2.0.0](https://semver.org/).

Every PR that introduces a user-visible change adds a line to `[Unreleased]` in the same
PR. A release PR moves the `[Unreleased]` entries into a new per-version file under
`docs/changelog/v<MAJOR>/v<X.Y.Z>.md` and adds an index row below.

## [Unreleased]

### Added

- **The core error vocabulary — the first public API of `d4np-core`** (ROADMAP item 2.1, FR-17, FR-18,
  ADR-002, RFC-0001): `Result<T>` sealed over `Ok<T>` and `Err<T>` with `map`, `flatMap`, `recover`
  and `orElseThrow`; `ErrorDetail(String code, String message, Throwable cause)`; and the unchecked
  `BusinessException` that carries the same detail. `Ok` rejects a `null` payload, and no operation
  accepts or returns `null`.
- `it.d4np.utils.Nullable` — the library's own nullability marker, so `d4np-core` keeps zero
  third-party dependencies while NullAway still checks it ([ADR-0011](docs/adr/0011-declare-the-nullability-annotation-in-core.md)).
- `d4np-core` now **exports** `it.d4np.utils` from its module descriptor; consumers can
  `import it.d4np.utils.*;` for the first time.
- **`Lazy<T>`** — thread-safe, compute-once initialization (ROADMAP item 2.2, FR-03, NFR-01,
  RFC-0001, [ADR-0013](docs/adr/0013-lazy-initialization-by-double-checked-volatile.md)).
  `Lazy.of(supplier)` **retries** after a failed initialization; `Lazy.memoizingFailures(supplier)`
  remembers the first failure and rethrows it unchanged. An initializer that returns `null` or calls
  `get()` on the `Lazy` it is initializing raises `IllegalStateException`. **The first NFR in this
  project backed by a measurement rather than an assertion:** NFR-01 budgets `get()` at ≤ 2 ns/op in
  steady state, and `LazyGetBenchmark` measures **0.827 ns/op on JDK 17 and 0.945 ns/op on JDK 21** —
  0.2–0.4 ns/op above the raw volatile read the budget is made of, which is the delta that says the
  call inlines ([report](docs/benchmarks/2026-08-01-lazy-get.md)). Safe publication and at-most-once
  initialization are proven by two named jcstress harnesses, not claimed.

### Changed

- The `jcstress` profile passes `-jvmArgsPrepend "-XX:+UnlockDiagnosticVMOptions
  -XX:-RestrictContended"`, restoring `@Contended` padding in the forked test VMs. jcstress's own
  capability probe cannot survive a classpath directory containing a class named `*Result*`, and
  without this the concurrency harness silently loses sensitivity (see item 2.1).

### Deprecated

### Removed

### Fixed

### Security

---

## Released versions

| Version | Date | Milestone | Entries |
|---------|------|-----------|---------|
| v0.1.0 | 2026-07-29 | M1 — Project bootstrap & CI | [docs/changelog/v0/v0.1.0.md](docs/changelog/v0/v0.1.0.md) |

