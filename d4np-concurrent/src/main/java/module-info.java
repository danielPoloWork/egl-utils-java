/**
 * Concurrency utilities with no third-party dependencies (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces.
 *
 * <p><strong>The {@code exports} arrived with the first types, in ROADMAP item 5.1</strong> — it
 * could not be written earlier, because {@code exports} of a package that holds no class is a
 * compile error. FR-08's three types open the package; FR-09 and FR-10 add to it.
 *
 * <p>This module is the one {@code d4np-lock-redisson} builds on, so its exported surface is what
 * decides how much of Redisson a consumer can avoid. jcstress (item 1.8) is the gate that holds
 * that surface honest under contention, and NFR-05 is the first requirement in this project to
 * mandate a named harness rather than leave one to judgement.
 *
 * <p><strong>No {@code requires} beyond core, and none is coming.</strong> Everything FR-08 needs
 * is in {@code java.base} — {@code java.util.concurrent}, {@code java.time} and the platform logger
 * — so this module adds no read edge at all. That is the contrast with {@code d4np-jdbc}, which
 * needs {@code requires transitive java.sql} because a consumer implementing {@code RowMapper}
 * names {@code ResultSet} in its own source.
 */
module it.d4np.utils.concurrent {
  requires it.d4np.utils;

  exports it.d4np.utils.concurrent;
}
