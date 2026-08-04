package it.d4np.utils;

import java.util.List;

/**
 * Constraint violations reported as a defect — thrown by {@link Validator#requireValid}.
 *
 * <p><strong>Not a {@link BusinessException}, and the reason is the status code.</strong> FR-19
 * maps {@code BusinessException} to <strong>422</strong> and validation to <strong>400</strong>, so
 * inheriting from it would silently give every validation failure the wrong response. It extends
 * {@link RuntimeException} directly and FR-19 maps it on its own row.
 *
 * <p><strong>Not {@code jakarta.validation.ValidationException} either</strong>, despite the shared
 * simple name. The provider's exception is a provider-failure signal — "validation could not be
 * performed" — and it arrives with the API this module only {@code requires static}. This one says
 * the opposite: validation was performed and the object failed it. A call site that catches both
 * should import them by qualified name and mean it.
 *
 * <p><strong>The violations are {@code String}, not the validated type or Jakarta's {@code
 * ConstraintViolation}.</strong> Every {@link Throwable} is {@link java.io.Serializable}, so a
 * payload typed on the consumer's object would make this exception serialisable only when that
 * object happened to be — the failure mode ADR-0015 records, silent and only in the hosts that
 * serialise exceptions. Rendering to {@code String} at the throw site also keeps the C-01 guarantee
 * with the exception: {@link Validator} renders {@code path: message-template}, so the rejected
 * value cannot travel inside this exception either.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries; the violation list is copied at construction.
 *
 * @see Validator#requireValid(Object, Class[])
 */
public final class ValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The rendered violations, in the deterministic order {@link Validator} produced them.
   *
   * <p>{@link List#copyOf(java.util.Collection)} is immutable and serialisable, and every element
   * is a {@code String} — so the list survives the serialisation {@link Throwable} promises,
   * whatever type was being validated.
   */
  private final List<String> violations;

  /**
   * Package-private: {@link Validator} is the only thing that may decide an object is invalid.
   *
   * @param validated the simple name of the validated type, for the message
   * @param violations what was wrong, rendered as {@code path: template}; copied, not retained
   */
  ValidationException(String validated, List<String> violations) {
    super(describe(validated, violations));
    this.violations = List.copyOf(violations);
  }

  /**
   * Every violation, in the order {@link Validator} reported them.
   *
   * @return an unmodifiable list with at least one element; never {@code null}
   */
  public List<String> violations() {
    return violations;
  }

  private static String describe(String validated, List<String> violations) {
    return violations.size() == 1
        ? validated + ": " + violations.get(0)
        : validated + ": " + violations.size() + " violations: " + violations;
  }
}
