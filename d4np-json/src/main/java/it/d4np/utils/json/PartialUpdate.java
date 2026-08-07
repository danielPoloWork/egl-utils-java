package it.d4np.utils.json;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A parsed instance <em>plus</em> the properties the document actually contained (FR-21, RFC-0003).
 *
 * <pre>{@code
 * PartialUpdate<Order> patch = ObjectMapperExtensions.readPartial(json, body, Order.class);
 *
 * if (patch.isPresent("quantity")) {
 *   order.setQuantity(patch.value().quantity());   // the client said so, even if it said null
 * }
 * }</pre>
 *
 * <p><strong>The problem this solves is that {@code {"a": null}} and {@code {}} are different
 * documents that produce the same object.</strong> A PATCH client that sends the first is clearing
 * a field; one that sends the second is not mentioning it. Nothing in the parsed instance can tell
 * them apart, because both leave the field {@code null}.
 *
 * <p><strong>So the distinction is carried beside the value rather than inside it</strong>, and
 * RFC-0003 chose that shape because every encoding <em>inside</em> the value collides with a rule
 * this project already holds. {@code Optional} as a field type is an anti-pattern under ADR-0011,
 * which also left {@code @Nullable} a declaration annotation, so {@code Map<String, @Nullable
 * Object>} is not even expressible without widening its {@code @Target}; and ADR-0012 forbids
 * {@code null} inside a {@code Result}. Carrying the name set beside the value widens nothing,
 * changes no field's type, and composes with records — which is what both rejected encodings could
 * not do.
 *
 * <p>{@link #isPresent(String)} over a {@code null} value is an <strong>explicit null</strong>;
 * {@code !isPresent} is an <strong>absence</strong>. That is the whole vocabulary.
 *
 * <h2>Scope: top-level names only</h2>
 *
 * <p>{@link #presentProperties()} holds the names at the document's top level. Nested partial
 * semantics would need a path language, and a library that invents one has acquired a query syntax
 * nobody asked for. Adding it later is MINOR under RFC-0001 §Versioning.
 *
 * <p>The bound on the <em>set</em> is not a bound on the <em>check</em>: {@link
 * ObjectMapperExtensions#readPartial} refuses an unknown property at every depth. Reporting one
 * level and validating all of them is deliberate — the report is a vocabulary the caller branches
 * on, the refusal is a safety property that should not have a shallow end.
 *
 * <h2>Why {@code toString()} does not render the value</h2>
 *
 * <p>It names the value's <em>type</em> and lists the present property names. The value was built
 * from a document this library treats as untrusted, so rendering it into a log line is the same
 * disclosure control <strong>C-01</strong> forbids in an exception message — and a {@code
 * toString()} reaches a log far more casually than an exception does. {@link #equals} does read the
 * value, because comparing is not disclosing.
 *
 * <p>The names it does render are <strong>bounded</strong>, for a case that is easy to miss: with a
 * {@code Map} target every key in the document is a known property, so the set is client input
 * rather than the target type's vocabulary. A key holding a newline would fold one log line into
 * two, which is a log-injection primitive rather than a formatting problem — the same bound {@code
 * JsonMapper} already applies to a map key reaching an exception message.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and thread-safe <em>as far as it can promise</em>: the name set is copied and
 * unmodifiable, and the reference to the value never changes. Whether the value itself is safe to
 * share is the property of the caller's own type, which this class cannot know and does not claim.
 *
 * @param <T> the type the document was read as
 * @see ObjectMapperExtensions#readPartial(JsonMapper, String, Class)
 */
public final class PartialUpdate<T> {

  private final T value;

  /** Sorted and unmodifiable; see {@link #presentProperties()} for why sorted is load-bearing. */
  private final Set<String> presentProperties;

  /**
   * Package-private: only this module's own {@code readPartial} knows which names a document
   * carried, and a {@code PartialUpdate} a caller could assemble by hand would be a claim about a
   * document rather than a reading of one.
   *
   * @param value the parsed instance, already checked to be non-{@code null}
   * @param presentProperties the document's top-level names; copied and sorted here
   */
  PartialUpdate(T value, Set<String> presentProperties) {
    this.value = value;
    this.presentProperties = Collections.unmodifiableSet(new TreeSet<>(presentProperties));
  }

  /**
   * The parsed instance.
   *
   * <p>Fields the document did not mention hold whatever the target type's own construction leaves
   * there — {@code null} for a reference component, {@code 0} for an {@code int}. That is why the
   * name set exists: the instance alone cannot say which of those were chosen and which were merely
   * not mentioned.
   *
   * @return the value; never {@code null}
   */
  public T value() {
    return value;
  }

  /**
   * The top-level property names the document contained, sorted and unmodifiable.
   *
   * <p><strong>Sorted for the reason item 3.1 sorted its violations</strong>: an unordered report
   * makes a message assertion flaky and a log line undiffable, and this set is exactly the kind of
   * thing that ends up in both.
   *
   * <p>Every name here is known to the target type, because {@code readPartial} refuses the
   * document otherwise. For an ordinary POJO or record target that makes the set the target's own
   * vocabulary; for a {@code Map} target it does not, since every key is known by construction —
   * which is why {@link #toString()} bounds what it renders and this accessor does not. The names
   * are returned verbatim because they have to match what the caller passes to {@link
   * #isPresent(String)}.
   *
   * @return the present names; never {@code null}, possibly empty for the document {@code {}}
   */
  public Set<String> presentProperties() {
    return presentProperties;
  }

  /**
   * Whether the document mentioned {@code property} at all.
   *
   * @param property the top-level property name to ask about; must not be {@code null}
   * @return {@code true} if the document contained it, whatever value it carried — including {@code
   *     null}
   * @throws NullPointerException if {@code property} is {@code null}
   */
  public boolean isPresent(String property) {
    Objects.requireNonNull(property, "property must not be null");
    return presentProperties.contains(property);
  }

  /**
   * Equal when the values are equal and the same properties were present.
   *
   * <p>Both halves matter: two readings that produced an equal instance from different documents
   * are not the same update, which is the entire premise of this type.
   *
   * @param other the object to compare with
   * @return {@code true} when both the value and the present-name set are equal
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof PartialUpdate<?> update
        && value.equals(update.value)
        && presentProperties.equals(update.presentProperties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value, presentProperties);
  }

  /**
   * The value's type and the present names — never the value itself; see the class documentation.
   *
   * @return a rendering safe to put in a log line
   */
  @Override
  public String toString() {
    return "PartialUpdate["
        + value.getClass().getTypeName()
        + " present="
        + JsonDiagnostics.renderNames(presentProperties)
        + "]";
  }
}
