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

