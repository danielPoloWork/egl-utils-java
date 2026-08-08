package it.d4np.utils.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.List;
import java.util.Set;

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
 * <p>Package-private and non-instantiable: this is an implementation detail of the text this module
 * produces — every {@link JsonConversionException} message, and {@link PartialUpdate#toString()},
 * which bounds its names here for the same reason and not as a courtesy. It duplicates the
 * truncate-and-cap shape of core's {@code KeyDiagnostics} rather than sharing it, because that type
 * is package-private in {@code it.d4np.utils} and exporting an implementation detail across a
 * module boundary to save fifteen lines would be the worse trade.
 */
final class JsonDiagnostics {

  /** How long a single property name may be before the message truncates it. */
  static final int MAX_NAME_LENGTH = 64;

  /** How many path segments a message renders before it stops. */
  static final int MAX_PATH_SEGMENTS = 10;

  /**
   * How many property names a message lists before it stops and reports only the count.
   *
   * <p>The path cap above stops one deep document from turning a failure into a paragraph; this one
   * stops one <em>wide</em> document from doing it. A partial update naming two hundred unknown
   * properties is a client that has misunderstood the endpoint, and printing all two hundred helps
   * nobody while filling a log.
   */
  static final int MAX_NAMES = 5;

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
   * The message for a conversion between two object shapes that Jackson refused (FR-21).
   *
   * <p>The target is passed as text rather than as a {@code Class}, because the generic overload's
   * target is a {@link JsonTypeToken} whose rendering is a type name this library built.
   *
   * @param source the type the caller handed over
   * @param target the target's type name, which is the host's own source in both overloads
   * @param failed Jackson's failure — <strong>an unchecked {@code IllegalArgumentException} on this
   *     path, not a {@code JsonProcessingException}</strong>, so the mapping exception carrying the
   *     path is looked for in its cause (ADR-0026)
   * @return caller-facing text carrying no part of either object
   */
  static String describeConvert(Class<?> source, String target, Throwable failed) {
    String message = "cannot convert " + source.getTypeName() + " to " + target;
    return failed.getCause() instanceof JsonProcessingException mapping
        ? message + at(mapping)
        : message;
  }

  /**
   * The message for a conversion that would have answered with {@code null}.
   *
   * <p>A source object whose serializer writes {@code null} — a host module is entitled to register
   * one — converts to nothing at all. Control C-02 says this library never hands that back, on the
   * same reasoning as the literal {@code null} document.
   *
   * @param source the type the caller handed over
   * @param target the target's type name
   * @return caller-facing text naming the refusal rather than the absence
   */
  static String describeNullConversion(Class<?> source, String target) {
    return "cannot convert "
        + source.getTypeName()
        + " to "
        + target
        + ": the conversion produced null, and this library never answers with null";
  }

  /**
   * The message for a document that is not a JSON object, which a partial update has to be.
   *
   * @param target the type the caller asked for
   * @return caller-facing text naming the shape it wanted, never the shape it got
   */
  static String describeNonObjectDocument(Class<?> target) {
    return "cannot apply a partial update to "
        + target.getTypeName()
        + ": the document is not a JSON object";
  }

  /**
   * The message for a partial update naming properties the target does not declare (FR-21).
   *
   * <p>This is the one refusal in this module that exists <em>because</em> {@code
   * FAIL_ON_UNKNOWN_PROPERTIES} is disabled everywhere else: leniency is right for reading a
   * document you do not own and wrong for applying an instruction, so the strictness is per
   * operation rather than per mapper (RFC-0003).
   *
   * @param target the type the caller asked for
   * @param unknown the offending names, already sorted; each one is document input and is bounded
   *     here
   * @return caller-facing text naming what was rejected, never any value the document carried
   */
  static String describeUnknownProperties(Class<?> target, Set<String> unknown) {
    String preamble = "cannot apply a partial update to " + target.getTypeName() + ": ";
    return unknown.size() == 1
        ? preamble + "unknown property " + renderNames(unknown)
        : preamble + unknown.size() + " unknown properties " + renderNames(unknown);
  }

  /**
   * Renders a set of property names, bounded in count and — one by one — in length and content.
   *
   * <p>A name is not a value, which is why it may be echoed at all (RFC-0003). It is still client
   * input whenever the target is a {@code Map}, so the bound is the same one a path segment gets.
   *
   * @param names the names to render, in the order they should appear
   * @return {@code ['a', 'b']}, with a marker and no more than {@value #MAX_NAMES} entries
   */
  static String renderNames(Set<String> names) {
    StringBuilder rendered = new StringBuilder("[");
    int shown = 0;
    for (String name : names) {
      if (shown == MAX_NAMES) {
        rendered.append(", ").append(TRUNCATED);
        break;
      }
      rendered.append(shown == 0 ? "" : ", ").append('\'').append(bound(name)).append('\'');
      shown++;
    }
    return rendered.append(']').toString();
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
