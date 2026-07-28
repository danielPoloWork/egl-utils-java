/**
 * Distributed locking backed by Redisson (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces. Note it points at {@code it.d4np.utils.concurrent}, not at the
 * core: this is the one module in the family that does <em>not</em> depend on core directly, and
 * spec §3 draws it that way on purpose, so the lock abstraction it implements stays the concurrent
 * module's contract rather than becoming a second one.
 *
 * <p>No {@code exports} yet: no production types exist, and exporting an empty package does not
 * compile. Milestone 5 adds {@code exports it.d4np.utils.lock.redisson;} with the first types.
 *
 * <p>Redisson is a {@code compile} dependency here — the only place in the family where it is
 * legal. Its {@code requires} edge arrives with the code.
 */
module it.d4np.utils.lock.redisson {
  requires it.d4np.utils.concurrent;
}
