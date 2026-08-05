package it.d4np.utils;

/**
 * A state change that cannot be audited as declared — thrown by {@link AuditLog#capture}.
 *
 * <p><strong>This is almost always a misconfiguration, and it fires in the first test that
 * exercises the annotated type rather than in production.</strong> RFC-0002 chose loud failure over
 * a plausible partial record for one reason: a truncated audit record that looks complete is worse
 * than a refused one, because nobody re-checks a record that arrived. The five causes are all
 * developer errors:
 *
 * <ul>
 *   <li>a component that is neither a simple value nor an {@link Audited} composite — the case that
 *       would otherwise render a record's generated {@code toString()} and publish every component
 *       it has, {@link Sensitive} ones included;
 *   <li>nesting deeper than three levels, or a cycle in the object graph;
 *   <li>a type with no {@code @Audited} type or component, which would produce an empty record;
 *   <li>a type or accessor this library cannot read without deep reflection, which FR-16 forbids
 *       asking for;
 *   <li>a before/after pair that is not two states of one type, or two accessors mapping to one
 *       component name.
 * </ul>
 *
 * <p><strong>Not a {@link BusinessException}</strong> — FR-19 maps that to <strong>422</strong>, a
 * rule the caller's user violated, and this says the program's own annotations are wrong. It is the
 * same distinction {@link BuilderValidationException} draws: a defect in this application, not in
 * the data it was handed.
 *
 * <p><strong>The message names the path and the type and never a value.</strong> Capture may
 * already have read raw values by the time it refuses, so an exception that quoted "the offending
 * value" would be a leak inside the mechanism that exists to prevent one — the message carries the
 * component's path and its class, which is what a developer needs and all they need.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the state every {@link Throwable}
 * carries.
 *
 * @see AuditLog#capture(String, String, Object, Object)
 * @see Audited
 */
public final class AuditCaptureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Package-private: only the capture engine can find these.
   *
   * @param message what is wrong, naming the path and the type
   */
  AuditCaptureException(String message) {
    super(message);
  }

  /**
   * Package-private, for the reflective failures that carry an underlying cause.
   *
   * @param message what is wrong, naming the path and the type
   * @param cause what the JVM or the accessor threw
   */
  AuditCaptureException(String message, Throwable cause) {
    super(message, cause);
  }
}
