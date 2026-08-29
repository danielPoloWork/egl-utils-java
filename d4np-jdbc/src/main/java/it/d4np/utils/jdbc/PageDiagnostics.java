package it.d4np.utils.jdbc;

/**
 * Bounds a client-supplied sort property before it reaches a log line or an exception message
 * (control C-01).
 *
 * <p>Package-private and non-instantiable: an implementation detail of {@link PageSort} and {@link
 * PageRequest}, not a utility offered to consumers.
 *
 * <p><strong>This is the third copy of one idea in this repository, and it is re-decided rather
 * than drifted into.</strong> {@code KeyDiagnostics} bounds a rendered key list in {@code
 * d4np-core} and {@code JsonDiagnostics} bounds a document-supplied property name in {@code
 * d4np-json}; item 4.1 duplicated rather than exported, because exporting a package-private detail
 * across a module boundary to save fifteen lines is the worse trade. The trade is unchanged at
 * three, and the reason is that the three are not one thing said three times: the caps differ (20
 * rendered keys, 64 characters of a JSON property name, 64 of a sort property), and so do the
 * destinations — {@code KeyDiagnostics} bounds host-configured keys behind an exception FR-19 maps
 * to a <strong>500 with no body</strong>, where these names arrive in a query string and leave in a
 * <strong>400 that has one</strong>. A shared helper would have to be parameterised on all of that
 * and exported from core forever, which is a larger permanent surface than the duplication it
 * removes.
 *
 * <p>Recorded here rather than filed, on ADR-0029's disposal of the same shape: a <em>fourth</em>
 * call site should reopen the extraction on its own terms, not have it settled in passing by
 * whichever item happens to need it.
 */
final class PageDiagnostics {

  /** How much of one property name survives into a message. */
  static final int MAX_PROPERTY_LENGTH = 64;

  /** What a truncated name ends with, so a reader can tell it was cut. */
  private static final String TRUNCATED = "...";

  private PageDiagnostics() {}

  /**
   * Strips every ISO control character and truncates at {@link #MAX_PROPERTY_LENGTH}.
   *
   * <p>The length bound alone would not be enough: a property name holding {@code \r\n} folds one
   * log line into two, which is a forgery primitive rather than a formatting problem — the finding
   * item 4.1 recorded when RFC-0003's own bound turned out to say nothing about control characters.
   *
   * @param property the caller's property name
   * @return the bounded form, possibly ending in {@code ...}; never {@code null}
   */
  static String bounded(String property) {
    StringBuilder stripped = new StringBuilder(property.length());
    property
        .codePoints()
        .filter(codePoint -> !Character.isISOControl(codePoint))
        .forEach(stripped::appendCodePoint);
    if (stripped.length() <= MAX_PROPERTY_LENGTH) {
      return stripped.toString();
    }
    int cut =
        Character.isHighSurrogate(stripped.charAt(MAX_PROPERTY_LENGTH - 1))
            ? MAX_PROPERTY_LENGTH - 1
            : MAX_PROPERTY_LENGTH;
    return stripped.substring(0, cut) + TRUNCATED;
  }
}
