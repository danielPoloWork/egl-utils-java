/**
 * The zero-dependency core of the d4np family — the shared error vocabulary and the types every
 * other module builds on (ADR-001, spec §3).
 *
 * <p><strong>This module owns the family root package {@code it.d4np.utils}</strong>, so no other
 * module may place a type there: two modules sharing one package is a split package, which the
 * module system rejects outright rather than warning about. Capability modules therefore live in
 * {@code it.d4np.utils.<capability>}. See ADR-0005.
 *
 * <p><strong>The single {@code exports} clause arrived with the first types, in ROADMAP item
 * 2.1</strong> — {@code Result}, {@code ErrorDetail}, {@code BusinessException} and the {@code
 * Nullable} marker (RFC-0001, ADR-002). Until then the descriptor deliberately exported nothing,
 * because {@code exports} of a package that holds no class is a compile error ("package is empty or
 * does not exist") rather than a forward declaration. Items 2.2–2.5 add types to this same package
 * and need no further clause; a capability module's own package is exported by that module's
 * descriptor.
 *
 * <p><strong>The one {@code requires} is {@code static}</strong>, and that is what keeps ADR-001's
 * zero-dependency claim true while FR-14 wraps Bean Validation: spec §3 permits {@code
 * jakarta.validation-api} at {@code provided} scope only, so the edge must exist at compile time
 * and must not be resolved at run time. A plain {@code requires} would make the module system
 * refuse to start any consumer that does not ship a Bean Validation API — including every consumer
 * that never touches {@code Validator} — turning an opt-in capability into a mandatory dependency.
 * The price is that a missing provider becomes a runtime condition, which is why {@code
 * Validator.create()} refuses at construction with a message naming both artifacts rather than
 * letting a {@code NoClassDefFoundError} escape from the first validated call (RFC-0002 §FR-14).
 */
module it.d4np.utils {
  requires static jakarta.validation;

  exports it.d4np.utils;
}
