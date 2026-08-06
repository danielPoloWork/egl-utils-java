package it.d4np.utils.json;

/**
 * A JSON document could not be read, or an object could not be written (FR-20, FR-21, RFC-0003).
 *
 * <p><strong>Not a {@code BusinessException}, and the reason is the status code.</strong> FR-19
 * maps {@code BusinessException} to <strong>422</strong> and a malformed payload to
 * <strong>400</strong>, so inheriting from it would report a client's broken document as a rule
 * violation. It extends {@link RuntimeException} directly and FR-19 maps it on its own row, which
 * RFC-0003 filed as an obligation on item 7.1.
 *
 * <p><strong>Jackson's exception is checked; this one is not.</strong> RFC-0001's rule — no
 * published method of this library throws a checked exception — holds for every module, so {@code
 * JsonProcessingException} is wrapped here at every boundary rather than declared.
 *
 * <p><strong>The message carries the property path and the target type, and nothing else</strong>
 * (compliance control <strong>C-01</strong>). It is built by this library from the type being
 * converted and Jackson's structural path; no part of it comes from Jackson's own message, because
 * that is where a rejected token, a source snippet and — under a parse error on a credential
 * document — the credential itself would live. ADR-0020 established the rule for constraint
 * violations; this is the same rule applied to serialization.
 *
 * <p><strong>Two defences, and which one is load-bearing matters.</strong> The message rule above
 * is ours and always holds. Disabling {@code INCLUDE_SOURCE_IN_LOCATION} (see {@link JsonMapper})
 * additionally keeps the source snippet out of the {@link #getCause() cause}, which protects a
 * boundary handler careless enough to render a cause chain — the failure mode RFC-0003 filed
 * against item 7.1.
 *
 * <p><strong>The cause is kept.</strong> Jackson's exception is the diagnosis, and it belongs in
 * the log: {@code ErrorDetail} already draws that line — caller-facing message, process-facing
 * cause.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries. It declares no fields, so the serialisability {@code Throwable} promises holds whatever
 * was being converted — the failure mode ADR-0015 recorded for a typed payload.
 *
 * @see JsonMapper
 */
public final class JsonConversionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Package-private: only this module's own conversions may decide a document is unreadable.
   *
   * @param message built by {@link JsonDiagnostics}, so the C-01 rule is applied in one place
   * @param cause Jackson's own exception, for the log
   */
  JsonConversionException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * The cause-less form, for a refusal this library makes rather than one Jackson reports.
   *
   * @param message built by {@link JsonDiagnostics}
   */
  JsonConversionException(String message) {
    super(message);
  }
}
