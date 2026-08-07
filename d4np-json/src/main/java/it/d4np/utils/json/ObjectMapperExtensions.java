package it.d4np.utils.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deep conversion and partial mapping over a {@link JsonMapper} (FR-21, RFC-0003).
 *
 * <pre>{@code
 * JsonMapper json = JsonMapper.create();
 *
 * OrderView view = ObjectMapperExtensions.convert(json, order, OrderView.class);
 * List<OrderView> views =
 *     ObjectMapperExtensions.convert(json, orders, new JsonTypeToken<List<OrderView>>() {});
 *
 * PartialUpdate<Order> patch = ObjectMapperExtensions.readPartial(json, body, Order.class);
 * }</pre>
 *
 * <h2>Why static methods over a supplied mapper, under a name that promises otherwise</h2>
 *
 * <p>Java has no extension methods, so the name cannot mean what it says, and RFC-0003 kept it
 * anyway: the specification is the frozen contract, so renaming costs a spec change to gain a word
 * and leaves every future reader tracing FR-21 by name at a dead end — the reasoning ADR-0021 used
 * when it declined to rename FR-15.
 *
 * <p>The two shapes that would have justified the name were both rejected before the code.
 * Subclassing {@code ObjectMapper} would publish the mapper {@link JsonMapper} exists not to
 * publish and would tie this library to Jackson's own inheritance surface. Instance methods on
 * {@code JsonMapper} would fold FR-21's operations into the type whose entire value is how little
 * it exposes. Static helpers over a supplied collaborator is the shape {@code ObjectUtils} and
 * {@code ResourceLoaderUtils} already use here.
 *
 * <h2>The mapper is still not reachable</h2>
 *
 * <p>These helpers use the configured {@code ObjectMapper} through a package-private accessor.
 * {@code it.d4np.utils.json} is exported but <strong>not open</strong>, so a package-private member
 * of it is unreachable from any other module — that is what lets FR-21 work over the mapper while
 * FR-20's guarantee stays structural rather than procedural. No signature here mentions an {@code
 * ObjectMapper}, and {@code JsonMapperTest.publishesNoHandleToTheConfiguredMapper} asserts the same
 * property over this class's reflected surface.
 *
 * <h2>Every failure is a {@link JsonConversionException}, including the unchecked one</h2>
 *
 * <p>RFC-0003 wrote the wrapping rule against {@code JsonProcessingException}, which is checked.
 * The conversion path does not raise one: {@code ObjectMapper.convertValue} catches Jackson's own
 * mapping failure and rethrows it as an <strong>unchecked</strong> {@code IllegalArgumentException}
 * carrying Jackson's message — which quotes the rejected value. Nothing forces that exception to be
 * caught, so a wrapping rule that only names the checked shape would have let a payload out of this
 * module by default. It is caught and rewritten here, and ADR-0026 records why.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless and thread-safe: every method's state is its arguments, and the mapper they share is
 * itself thread-safe (see {@link JsonMapper}). Non-instantiable, like every static-only type in
 * this library.
 *
 * @see JsonMapper
 * @see PartialUpdate
 * @see JsonTypeToken
 */
public final class ObjectMapperExtensions {

  private ObjectMapperExtensions() {}

  /**
   * Converts {@code source} into {@code target}, property by property, without going through a
   * document.
   *
   * <p>The conversion is <strong>deep</strong>: nested objects and collections are converted too,
   * by the same rules the mapper reads and writes with. It is the operation behind an
   * entity-to-view mapping, and it inherits every setting {@link JsonMapper} configured — {@code
   * java.time} renders as ISO-8601, an unknown property on the way in is tolerated, and no document
   * can choose a class.
   *
   * @param <T> the target type
   * @param mapper the configured mapper to convert with; must not be {@code null}
   * @param source the object to convert; must not be {@code null}
   * @param target the type to convert it into; must not be {@code null}
   * @return the converted instance; never {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @throws JsonConversionException if the two shapes do not fit, or if the conversion would answer
   *     with {@code null} — the message names the two types and the property path, never a value
   */
  public static <T> T convert(JsonMapper mapper, Object source, Class<T> target) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(target, "target type must not be null");
    return converted(mapper, source, target.getTypeName(), mapper.delegate().constructType(target));
  }

  /**
   * Converts {@code source} into the generic type {@code target} stands for.
   *
   * <p>The overload {@code List<OrderView>.class} cannot express. The token is this library's own
   * type, so a generic target costs a consumer no Jackson import and costs this library no Jackson
   * type in a published signature — see {@link JsonTypeToken} for the compatibility argument.
   *
   * <p><strong>A token is a claim the compiler cannot check for you.</strong> {@code T} comes from
   * the token's type argument, so a token built over the wrong type produces an object that is not
   * a {@code T}, and the failure surfaces at the caller's next use rather than here. That is
   * inherent to every type token, Jackson's included; the two ways of getting it wrong that
   * <em>can</em> be detected are refused by {@code JsonTypeToken}'s own constructor.
   *
   * @param <T> the target type
   * @param mapper the configured mapper to convert with; must not be {@code null}
   * @param source the object to convert; must not be {@code null}
   * @param target the generic type to convert it into; must not be {@code null}
   * @return the converted instance; never {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @throws JsonConversionException if the two shapes do not fit, or if the conversion would answer
   *     with {@code null} — the message names the two types and the property path, never a value
   */
  public static <T> T convert(JsonMapper mapper, Object source, JsonTypeToken<T> target) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(target, "target type must not be null");
    return converted(
        mapper, source, target.toString(), mapper.delegate().constructType(target.capturedType()));
  }

  /**
   * Reads {@code json} as {@code target}, and reports which properties the document actually
   * contained.
   *
   * <p>The operation behind a PATCH endpoint. {@code {"a": null}} and {@code {}} produce the same
   * instance, and {@link PartialUpdate} is what tells them apart — an explicit {@code null} is a
   * client clearing a field, an absence is a client not mentioning it.
   *
   * <p><strong>An unknown property is refused here while {@code FAIL_ON_UNKNOWN_PROPERTIES} stays
   * disabled on the mapper</strong>, and the two are not in tension because they answer different
   * questions. Leniency is for reading a document you do not own, where a producer adding a field
   * must not break you. Strictness is for applying an instruction, where a client that sent {@code
   * emailAddres} believes it changed something. One tolerates an unknown <em>addition</em>; the
   * other refuses an unknown <em>instruction</em>. Doing it per operation is what keeps every other
   * read in the application unchanged, which flipping the mapper-wide flag would not have done.
   *
   * <p>The refusal covers <strong>every depth</strong>, while {@link
   * PartialUpdate#presentProperties()} reports the <strong>top level only</strong>. Reporting is
   * bounded because a nested report would need a path language; validation is not, because a safety
   * property with a shallow end is not one.
   *
   * <p>The document is read twice — once as a tree, for the names it carried, and once as the
   * target type. One pass would need a Jackson API whose shape has moved across the versions in the
   * supported matrix, and no NFR binds this operation (RFC-0003 §Scalability budgets), so the
   * stable form wins over the fast one. A partial-update body is a request payload, not a bulk
   * feed.
   *
   * @param <T> the target type
   * @param mapper the configured mapper to read with; must not be {@code null}
   * @param json the document to read; must not be {@code null}
   * @param target the type to read it as; must not be {@code null}
   * @return the instance and the names the document contained; never {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @throws JsonConversionException if the document is malformed, is not a JSON object, or names a
   *     property {@code target} does not declare — the message names the target type and the
   *     offending property names, bounded, and never a value the document carried
   */
  public static <T> PartialUpdate<T> readPartial(JsonMapper mapper, String json, Class<T> target) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(json, "json must not be null");
    Objects.requireNonNull(target, "target type must not be null");
    ObjectMapper delegate = mapper.delegate();

    JsonNode document;
    try {
      document = delegate.readTree(json);
    } catch (JsonProcessingException failed) {
      throw new JsonConversionException(JsonDiagnostics.describeRead(target, failed), failed);
    }
    if (document == null || document.isNull()) {
      throw new JsonConversionException(JsonDiagnostics.describeNullDocument(target));
    }
    if (!document.isObject()) {
      throw new JsonConversionException(JsonDiagnostics.describeNonObjectDocument(target));
    }
    Set<String> present = new TreeSet<>();
    for (Iterator<String> names = document.fieldNames(); names.hasNext(); ) {
      present.add(names.next());
    }

    T value;
    try {
      value =
          delegate
              .readerFor(target)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(json);
    } catch (UnrecognizedPropertyException refused) {
      throw new JsonConversionException(
          JsonDiagnostics.describeUnknownProperties(target, unknownIn(present, refused)), refused);
    } catch (JsonProcessingException failed) {
      throw new JsonConversionException(JsonDiagnostics.describeRead(target, failed), failed);
    }
    if (value == null) {
      throw new JsonConversionException(JsonDiagnostics.describeNullDocument(target));
    }
    return new PartialUpdate<>(value, present);
  }

  /**
   * The conversion both overloads share, once their target has become a Jackson type.
   *
   * @param <T> the target type
   * @param mapper the configured mapper
   * @param source the object to convert, already checked
   * @param targetName the target's name for a message, built by this library in both overloads
   * @param target the resolved Jackson type
   * @return the converted instance; never {@code null}
   */
  // ErrorProne's TypeParameterUnusedInFormals is right about a published API and wrong about this
  // one: the warning exists because a caller of such a method infers T from nothing, and here every
  // caller is one of the two overloads above, each of which binds T from its own target parameter.
  // This method is private and exists so that the C-01 rewrite of Jackson's message (ADR-0026)
  // happens in exactly one place rather than twice — which is the property worth protecting.
  @SuppressWarnings("TypeParameterUnusedInFormals")
  private static <T> T converted(
      JsonMapper mapper, Object source, String targetName, JavaType target) {
    T converted;
    try {
      converted = mapper.delegate().convertValue(source, target);
    } catch (IllegalArgumentException failed) {
      // Jackson's own message, which quotes the rejected value, dies here — ADR-0026.
      throw new JsonConversionException(
          JsonDiagnostics.describeConvert(source.getClass(), targetName, failed), failed);
    }
    if (converted == null) {
      throw new JsonConversionException(
          JsonDiagnostics.describeNullConversion(source.getClass(), targetName));
    }
    return converted;
  }

  /**
   * Every top-level name the target does not declare, not merely the first one Jackson tripped on.
   *
   * <p>Jackson stops at the first unknown property, which makes a client fixing a payload discover
   * its mistakes one round trip at a time. The exception carries the target's known property ids,
   * so the full set can be computed from the names already read out of the document.
   *
   * <p><strong>Only when the failure is at the top level</strong>, and the qualification is the
   * whole subtlety: the known ids belong to the type Jackson was populating when it tripped, which
   * for a nested offender is the <em>nested</em> type. Subtracting those from the document's
   * top-level names reports every top-level property as unknown — a confident, entirely wrong
   * answer, and one a test caught rather than a review. Below the top level there is one offender
   * and Jackson already named it, so that name is what travels.
   *
   * <p>Either way the name comes from the document, like every other name here, and {@code
   * JsonDiagnostics} bounds it.
   *
   * @param present the document's top-level names, sorted
   * @param refused Jackson's exception, read for its path, its known-property ids and its property
   *     name — never for its message
   * @return the offending names, sorted; never empty
   */
  private static Set<String> unknownIn(Set<String> present, UnrecognizedPropertyException refused) {
    Set<String> unknown = new TreeSet<>();
    if (refused.getPath().size() > 1) {
      unknown.add(String.valueOf(refused.getPropertyName()));
      return unknown;
    }
    Set<String> known = new TreeSet<>();
    if (refused.getKnownPropertyIds() != null) {
      for (Object id : refused.getKnownPropertyIds()) {
        known.add(String.valueOf(id));
      }
    }
    unknown.addAll(present);
    unknown.removeAll(known);
    if (unknown.isEmpty()) {
      unknown.add(String.valueOf(refused.getPropertyName()));
    }
    return unknown;
  }
}
