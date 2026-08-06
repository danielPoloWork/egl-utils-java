package it.d4np.utils.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.List;

/**
 * The one place a failed conversion is turned into text, so control <strong>C-01</strong> is
 * applied once rather than at every call site.
 *
 * <p><strong>Nothing here reads Jackson's message.</strong> Not {@code getMessage()}, not {@code
 * getOriginalMessage()}: those carry the rejected token, the parse location and — with {@code
 * INCLUDE_SOURCE_IN_LOCATION} enabled, which it is by default on the older Jackson a Spring Boot
 * 3.2 host downgrades this library to — a snippet of the document itself. A message assembled from
 * the target type and Jackson's <em>structural</em> path cannot carry a value, whatever the
 * document held. The rule is ADR-0020's, applied to serialization instead of validation.
 *
 * <p><strong>A property name is not a value, and is bounded rather than banned.</strong> RFC-0003
 * settled this for FR-21: echoing a value is what C-01 forbids, echoing a bounded name is not.
 * Bounded means both length and content here — a name reaching a path is the target type's own
 * property in the ordinary case, but for a {@code Map} target it comes straight from the document,
 * so it is truncated at {@value #MAX_NAME_LENGTH} characters and stripped of ISO control
 * characters. The second half is what stops a crafted name from folding a log line in two.
 *
 * <p>Package-private and non-instantiable: this is an implementation detail of two exceptions'
 * messages, not a utility offered to consumers. It duplicates the truncate-and-cap shape of core's
 * {@code KeyDiagnostics} rather than sharing it, because that type is package-private in {@code
 * it.d4np.utils} and exporting an implementation detail across a module boundary to save fifteen
 * lines would be the worse trade.
 */
final class JsonDiagnostics {

  /** How long a single property name may be before the message truncates it. */
  static final int MAX_NAME_LENGTH = 64;

  /** How many path segments a message renders before it stops. */
  static final int MAX_PATH_SEGMENTS = 10;

  /** Marks both kinds of truncation, so a reader knows the text is not the whole story. */
  private static final String TRUNCATED = "...";

  private JsonDiagnostics() {}

  /**
   * The message for a document that could not be read.
   *
   * @param target the type the caller asked for
   * @param failed Jackson's exception, read for its path only
   * @return caller-facing text carrying no part of the document
   */
  static String describeRead(Class<?> target, JsonProcessingException failed) {
    return "cannot read JSON as " + target.getTypeName() + at(failed);
  }

  /**
   * The message for an object that could not be written.
   *
   * @param source the type the caller handed over
   * @param failed Jackson's exception, read for its path only
   * @return caller-facing text carrying no part of the object
   */
  static String describeWrite(Class<?> source, JsonProcessingException failed) {
    return "cannot write " + source.getTypeName() + " as JSON" + at(failed);
  }

  /**
   * The message for the literal {@code null} document, which Jackson reads without complaint.
   *
   * @param target the type the caller asked for
   * @return caller-facing text naming the refusal rather than the absence
   */
  static String describeNullDocument(Class<?> target) {
    return "cannot read JSON as "
        + target.getTypeName()
        + ": the document is the literal null, and this library never answers with null";
  }

  /**
   * Renders the structural path of a mapping failure, or nothing at all.
   *
   * <p>A {@code JsonParseException} — a document that is not JSON — has no path, because the parser
   * never reached a property. That case gets the bare type name rather than an invented location.
   *
   * @param failed Jackson's exception
   * @return {@code ": at <path>"}, or an empty string when there is no path to render
   */
  private static String at(JsonProcessingException failed) {
    String path = path(failed);
    return path.isEmpty() ? "" : ": at " + path;
  }

  /**
   * The path as {@code order.lines[2].sku}.
   *
   * @param failed Jackson's exception
   * @return the rendered path, empty when the failure carries none
   */
  static String path(JsonProcessingException failed) {
    if (!(failed instanceof JsonMappingException mapping)) {
      return "";
    }
    List<JsonMappingException.Reference> references = mapping.getPath();
    StringBuilder rendered = new StringBuilder();
    for (JsonMappingException.Reference reference :
        references.subList(0, Math.min(references.size(), MAX_PATH_SEGMENTS))) {
      String name = reference.getFieldName();
      if (name != null) {
        rendered.append(rendered.length() == 0 ? "" : ".").append(bound(name));
      } else if (reference.getIndex() >= 0) {
        rendered.append('[').append(reference.getIndex()).append(']');
      }
    }
    return references.size() > MAX_PATH_SEGMENTS
        ? rendered.append(TRUNCATED).toString()
        : rendered.toString();
  }

  /**
   * Bounds one property name in both length and content.
   *
   * @param name the name as Jackson reported it, which for a {@code Map} target is document input
   * @return a name safe to put in a message and in a log line
   */
  private static String bound(String name) {
    StringBuilder safe = new StringBuilder(Math.min(name.length(), MAX_NAME_LENGTH));
    name.codePoints()
        .limit(MAX_NAME_LENGTH)
        .filter(codePoint -> !Character.isISOControl(codePoint))
        .forEach(safe::appendCodePoint);
    return name.codePointCount(0, name.length()) > MAX_NAME_LENGTH
        ? safe.append(TRUNCATED).toString()
        : safe.toString();
  }
}
