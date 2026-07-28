/**
 * JSON mapping built on Jackson (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces.
 *
 * <p>No {@code exports} yet: no production types exist, and exporting an empty package does not
 * compile. Milestone 4 adds {@code exports it.d4np.utils.json;} with the first types.
 *
 * <p>Jackson's own {@code requires} edges arrive with the code. Whoever adds them should expect
 * {@code com.fasterxml.jackson.databind} to be a real named module and {@code requires transitive}
 * to be the wrong default: FR-20 disables polymorphic default typing precisely so Jackson's
 * behaviour is not part of this module's contract, and re-exporting Jackson to every consumer would
 * contradict that.
 */
module it.d4np.utils.json {
  requires it.d4np.utils;
}
