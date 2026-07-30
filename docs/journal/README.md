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

#### July

- [2026-07-30 — the core error vocabulary (item 2.1)](2026/07/2026-07-30-core-error-vocabulary.md) —
  `d4np-core` gets its first public API; ADR-0011 and ADR-0012; a jcstress regression found and fixed.

> Milestone 1's twelve items (2026-07-26 → 2026-07-29) produced no checkpoints — the journal was
> scaffolded and left empty. Their record lives in the `ROADMAP.md` item notes, the ADRs and
> `docs/releases/v0.1.0.md`; it is **not** back-filled here, because a dated checkpoint written after
> the fact would claim a trail that did not exist.
