# Session Journal

Dated end-of-session checkpoints — what got done, where the project stands, and how the
next session resumes. One file per session that changed the project's state, at
`docs/journal/<YYYY>/<MM>/<YYYY-MM-DD>-<short-slug>.md`. The journal is the dated trail;
`ROADMAP.md` is the forward plan — checkpoints never live inline in the roadmap.

At the close of a state-changing session, the agent:

1. Creates the dated file under `docs/journal/<YYYY>/<MM>/`.
2. Adds a link row to this index (newest first, grouped by year/month).
3. Updates the *Latest checkpoint* pointer in `ROADMAP.md`.

## Index

### 2026

_(newest first)_

#### August

- [2026-08-01 — `StrategyRegistry` and the library's first logging (item 2.3)](2026/08/2026-08-01-strategy-registry.md) —
  FR-04 closed and NFR-04 measured at 12.8 ns/op (JDK 21) / 17.8 (JDK 17); ADR-0014 and ADR-0015; a
  documented performance claim refuted by its own benchmark, on one toolchain only.
- [2026-08-01 — `Lazy<T>` and safe publication (item 2.2)](2026/08/2026-08-01-lazy-safe-publication.md) —
  FR-03 closed and NFR-01 measured at 0.83–0.95 ns/op; ADR-0013; one `volatile` found to be guarded by
  review rather than by any gate.

#### July

- [2026-07-30 — the core error vocabulary (item 2.1)](2026/07/2026-07-30-core-error-vocabulary.md) —
  `d4np-core` gets its first public API; ADR-0011 and ADR-0012; a jcstress regression found and fixed.

> Milestone 1's twelve items (2026-07-26 → 2026-07-29) produced no checkpoints — the journal was
> scaffolded and left empty. Their record lives in the `ROADMAP.md` item notes, the ADRs and
> `docs/releases/v0.1.0.md`; it is **not** back-filled here, because a dated checkpoint written after
> the fact would claim a trail that did not exist.
