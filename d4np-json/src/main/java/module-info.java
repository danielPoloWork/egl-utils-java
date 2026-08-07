/**
 * JSON mapping built on Jackson (ADR-001, spec §3).
 *
 * <p>The {@code requires} edge below mirrors this module's POM exactly — the invariant {@code
 * consistency_lint.py} enforces.
 *
 * <p><strong>The {@code exports} clause arrived with the first types, in ROADMAP item 4.1</strong>
 * ({@code JsonMapper} and {@code JsonConversionException}, FR-20). Until then the descriptor
 * deliberately exported nothing, because {@code exports} of a package that holds no class is a
 * compile error rather than a forward declaration. Item 4.2 adds FR-21's types to this same package
 * and needs no further clause.
 *
 * <p><strong>Every Jackson edge is NON-transitive, and item 4.1 proved that the default path holds
 * to it</strong> (ADR-0024). Re-exporting Jackson to every consumer would contradict FR-20, which
 * disables polymorphic default typing precisely so that Jackson's behaviour is not part of this
 * module's contract. The honest form of the claim is narrower than "no Jackson type appears in a
 * signature": {@code JsonMapper.withModules} takes a Jackson {@code Module}, because additive
 * customisation has nothing else to take. What holds — measured, by compiling and running a
 * consumer module that never names Jackson — is that a read edge is required where a consumer's
 * <em>own source</em> names a Jackson type, not where an invoked signature mentions one. So the
 * default path costs a consumer no declaration, and the consumer that builds a {@code Module} was
 * always going to name one.
 *
 * <p>{@code com.fasterxml.jackson.core} and {@code com.fasterxml.jackson.datatype.jsr310} are named
 * here rather than left to arrive through {@code databind}'s own {@code requires transitive}: this
 * module reads {@code StreamReadFeature} and {@code JavaTimeModule} directly, and a descriptor that
 * relies on someone else's transitivity breaks silently when they narrow it.
 */
module it.d4np.utils.json {
  requires it.d4np.utils;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.datatype.jsr310;

  exports it.d4np.utils.json;
}
