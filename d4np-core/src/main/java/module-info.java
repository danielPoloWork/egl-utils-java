/**
 * The zero-dependency core of the d4np family — the shared error vocabulary and the types every
 * other module builds on (ADR-001, spec §3).
 *
 * <p><strong>This module owns the family root package {@code it.d4np.utils}</strong>, so no other
 * module may place a type there: two modules sharing one package is a split package, which the
 * module system rejects outright rather than warning about. Capability modules therefore live in
 * {@code it.d4np.utils.<capability>}. See ADR-0005.
 *
 * <p><strong>No {@code exports} yet, and no {@code requires} beyond the mandated {@code
 * java.base}.</strong> That is a statement of fact, not an oversight: this module has no production
 * types, and {@code exports} of a package that holds no class is a compile error ("package is empty
 * or does not exist"), not a forward declaration. Milestone 2 (RFC-0001) adds {@code exports
 * it.d4np.utils;} in the same change as the first types.
 *
 * <p>ADR-001 forbids third-party dependencies here; spec §3 permits {@code jakarta.validation-api}
 * as {@code provided}, which will appear as a {@code requires static} edge when FR-14 needs it.
 */
module it.d4np.utils {}
