package it.d4np.utils.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * A generic target type, captured at the call site, without a Jackson type in the signature (FR-21,
 * RFC-0003).
 *
 * <pre>{@code
 * List<Order> orders =
 *     ObjectMapperExtensions.convert(json, rows, new JsonTypeToken<List<Order>>() {});
 * }</pre>
 *
 * <p><strong>Why this exists rather than Jackson's {@code TypeReference}.</strong> {@code
 * List<Order>.class} cannot be written, so a generic target needs a token, and Jackson ships one.
 * Using it would put a Jackson type in a <em>published</em> signature, which is the one dependency
 * this module cannot keep to itself: it would force {@code requires transitive} on the module
 * descriptor and it would put a Jackson type under japicmp's guard, so a Jackson major release that
 * moved or renamed {@code TypeReference} would become a <strong>MAJOR</strong> version of this
 * library. RFC-0003 records the trade in full, and ADR-0011 recorded the general form of the
 * argument for annotations. A {@code TypeReference} overload can be added later and would be MINOR.
 *
 * <p>The counter-argument is real and is not hidden: {@code d4np-json} exists <em>in order to</em>
 * depend on Jackson, so hiding one Jackson type can be read as ceremony. It loses on the
 * compatibility consequence, which is a cost the module cannot pay back once it is published.
 *
 * <h2>How to create one</h2>
 *
 * <p>Subclass it anonymously with the type argument filled in — {@code new
 * JsonTypeToken<Map<String, List<Order>>>() {}}. The type argument is read from the anonymous
 * class's own generic signature, which the compiler records in the class file; that is why the
 * braces are required and why a bare {@code new JsonTypeToken<>()} does not compile.
 *
 * <h2>Two mistakes are refused at construction rather than at conversion</h2>
 *
 * <ul>
 *   <li><strong>A raw token</strong> — a subclass that fills in no type argument has nothing to
 *       capture.
 *   <li><strong>A token over a type variable</strong> — {@code new JsonTypeToken<T>() {}} inside a
 *       generic method captures {@code T} itself, which erasure has already discarded. Jackson
 *       would resolve it to the variable's bound and quietly hand back a {@code LinkedHashMap}
 *       where the caller's code expects an {@code Order}, failing at a cast somewhere else
 *       entirely. Refusing it here is the same reasoning that makes {@code JsonMapper.readValue}
 *       refuse the literal {@code null} document: a wrong answer that fails later is worse than a
 *       refusal that fails now.
 * </ul>
 *
 * <p>Both throw {@link IllegalArgumentException}, and the choice of exception is deliberate. A
 * malformed <em>document</em> is client input and gets {@link JsonConversionException}, which FR-19
 * maps to <strong>400</strong>; a malformed <em>token</em> is a line of the host's own source that
 * would have been wrong on every call, so it is a programming error and belongs to the unchecked
 * shape RFC-0001's table assigns to one.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and thread-safe: the captured type is read once at construction and never changes. A
 * token is cheap to create per call and equally safe to hold in a {@code static final} field.
 *
 * @param <T> the type this token stands for
 * @see ObjectMapperExtensions#convert(JsonMapper, Object, JsonTypeToken)
 */
public abstract class JsonTypeToken<T> {

  /**
   * The type argument this token was created with; a {@code java.lang.reflect} type, never ours.
   */
  private final Type captured;

  /**
   * Captures the type argument of the anonymous subclass being constructed.
   *
   * @throws IllegalArgumentException if the subclass fills in no type argument, or fills it in with
   *     a type variable that erasure has already discarded
   */
  protected JsonTypeToken() {
    Type superclass = getClass().getGenericSuperclass();
    if (!(superclass instanceof ParameterizedType parameterized)) {
      throw new IllegalArgumentException(
          "JsonTypeToken must be created with a type argument, as in"
              + " new JsonTypeToken<List<Order>>() {}");
    }
    Type argument = parameterized.getActualTypeArguments()[0];
    if (argument instanceof TypeVariable) {
      throw new IllegalArgumentException(
          "JsonTypeToken cannot capture the type variable "
              + argument.getTypeName()
              + ": erasure discarded it before this constructor ran, so the conversion would"
              + " silently produce the variable's bound");
    }
    this.captured = argument;
  }

  /**
   * The captured type, for this package's conversions only.
   *
   * <p>Package-private, like {@code JsonMapper.delegate()}: {@code it.d4np.utils.json} is exported
   * but not open, so a consumer cannot reach it and this class publishes no way to unwrap a token
   * into a reflective type it did not already have.
   *
   * @return the type argument this token was created with; never {@code null}
   */
  final Type capturedType() {
    return captured;
  }

  /**
   * Two tokens are equal when they capture the same type, whatever anonymous class holds it.
   *
   * @param other the object to compare with
   * @return {@code true} when {@code other} is a token over an equal type
   */
  @Override
  public final boolean equals(Object other) {
    return other instanceof JsonTypeToken<?> token && captured.equals(token.captured);
  }

  @Override
  public final int hashCode() {
    return captured.hashCode();
  }

  /**
   * The captured type's name — {@code java.util.List<it.example.Order>}.
   *
   * <p>Safe to log and safe to put in a message: a type name is the host's own source, never any
   * part of a document, which is what control <strong>C-01</strong> is about.
   *
   * @return the captured type's name; never {@code null}
   */
  @Override
  public final String toString() {
    return captured.getTypeName();
  }
}
