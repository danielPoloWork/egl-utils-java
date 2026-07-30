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
 * <p><strong>Still no {@code requires} beyond the mandated {@code java.base}</strong>, and that is
 * a statement of fact rather than an omission: the error vocabulary is built on {@code java.lang},
 * {@code java.io} and {@code java.util} alone. ADR-001 forbids third-party dependencies here; spec
 * §3 permits {@code jakarta.validation-api} as {@code provided}, which will appear as a {@code
 * requires static} edge when FR-14 needs it.
 */
module it.d4np.utils {
  exports it.d4np.utils;
}
