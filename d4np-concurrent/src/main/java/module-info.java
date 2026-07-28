/**
 * Concurrency utilities with no third-party dependencies (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces.
 *
 * <p>No {@code exports} yet: no production types exist, and exporting an empty package does not
 * compile. Milestone 5 adds {@code exports it.d4np.utils.concurrent;} with the first types.
 *
 * <p>This module is the one {@code d4np-lock-redisson} builds on, so its exported surface is what
 * decides how much of Redisson a consumer can avoid. jcstress (item 1.8) is the gate that will hold
 * that surface honest under contention.
 */
module it.d4np.utils.concurrent {
  requires it.d4np.utils;
}
