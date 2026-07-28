/**
 * The Spring Boot adapter — the only module allowed to see Spring (ADR-001, spec §3).
 *
 * <p>The two {@code requires} edges below mirror this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces. This is the only module with more than one internal edge, which is
 * why the lint compares sets rather than checking for a single expected name.
 *
 * <p>No {@code exports} yet: no production types exist, and exporting an empty package does not
 * compile. Milestone 7 adds {@code exports it.d4np.utils.spring;} with the first types.
 *
 * <p>Spring and AspectJ are {@code provided} by ADR-001 so the host pins its own versions. In
 * module terms that is {@code requires static}, not {@code requires} — a detail worth getting right
 * when item 7.1 lands, because a plain {@code requires} would make this module unresolvable for any
 * host that does not put Spring on the module path.
 */
module it.d4np.utils.spring {
  requires it.d4np.utils;
  requires it.d4np.utils.json;
}
