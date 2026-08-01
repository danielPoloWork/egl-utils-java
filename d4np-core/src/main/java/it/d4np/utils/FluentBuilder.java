package it.d4np.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * The template-method base for a fluent domain-object builder: validate everything, then construct.
 *
 * <pre>{@code
 * public final class OrderBuilder extends FluentBuilder<Order> {
 *   private String customer;
 *   private final List<Line> lines = new ArrayList<>();
 *
 *   public OrderBuilder customer(String customer) { this.customer = customer; return this; }
 *   public OrderBuilder line(Line line)           { this.lines.add(line);     return this; }
 *
 *   protected void validate() {          // @Override
 *     require(customer, "customer");
 *     if (lines.isEmpty()) {
 *       reject("at least one line is required");
 *     }
 *   }
 *
 *   protected Order construct() {        // @Override
 *     return new Order(customer, List.copyOf(lines));   // NOTE the defensive copy
 *   }
 * }
 * }</pre>
 *
 * <p>(The overrides are shown as trailing comments because an {@code @}-token at the start of a
 * Javadoc line is parsed as a block tag even inside {@code {@code}}, which Checkstyle reports as an
 * unknown tag.)
 *
 * <p><strong>{@link #build()} reports every violation at once</strong>, never the first one it
 * hits. A fail-fast builder turns filling a ten-field object into ten round trips; this one runs
 * the whole of {@code validate()}, collects what it recorded, and throws a single {@link
 * BuilderValidationException} listing all of it. Two methods record violations: {@link
 * #require(Object, String)} for a field that must be set, and {@link #reject(String)} for anything
 * else.
 *
 * <p><strong>{@code build()} is repeatable, and returns a distinct instance every time.</strong>
 * The builder is <em>not</em> reset and stays mutable afterwards, which makes a partly-configured
 * builder a legitimate prototype:
 *
 * <pre>{@code
 * OrderBuilder template = new OrderBuilder().customer("ACME");
 * Order first  = template.line(a).build();
 * Order second = template.line(b).build();   // still has customer and line a
 * }</pre>
 *
 * <p><strong>That convenience is what makes the defensive-copy rule mandatory rather than
 * advisory.</strong> Because the builder keeps its state, a collection handed straight to the
 * constructed object would still be reachable — and mutable — through the builder, so the
 * "immutable" value object would change under its owner the next time anyone touched the builder.
 * {@code construct()} <strong>must</strong> copy every collection and array it takes from the
 * builder. This class cannot enforce it, which is exactly why it is stated here, in the subclass's
 * Javadoc, and in ADR-0017.
 *
 * <p><strong>Thread safety: none, deliberately.</strong> A builder is a local, single-threaded
 * object; synchronising it would pay for a scenario that is a design error anyway — two threads
 * configuring one builder cannot agree on what they are building. Documented rather than defended,
 * which is the same choice RFC-0001 pins. The objects it produces are as thread-safe as {@code
 * construct()} makes them.
 *
 * @param <T> the type this builder builds
 * @see BuilderValidationException
 */
public abstract class FluentBuilder<T> {

  /**
   * Violations recorded by the current {@link #build()}.
   *
   * <p>Cleared at the start of every {@code build()} rather than at the end, so a {@code
   * validate()} that throws cannot leave stale findings to be reported against the next call.
   */
  private final List<String> violations = new ArrayList<>();

  /** For subclasses. */
  protected FluentBuilder() {
    // Nothing to initialise; declared so the constructor is documented rather than implicit.
  }

  /**
   * Validates, then constructs.
   *
   * <p>Final by contract (RFC-0001): the ordering — collect all violations, throw once, only then
   * call {@code construct()} — is the invariant this class exists to guarantee, and a subclass that
   * could override it could quietly reintroduce fail-fast or build an invalid object.
   *
   * @return a new instance; never {@code null}
   * @throws BuilderValidationException if {@code validate()} recorded one or more violations
   * @throws IllegalStateException if {@code construct()} returns {@code null}
   */
  public final T build() {
    violations.clear();
    validate();
    if (!violations.isEmpty()) {
      throw new BuilderValidationException(getClass().getSimpleName(), violations);
    }
    T instance = construct();
    if (instance == null) {
      // The package contract is that nothing here returns null (see package-info); a builder that
      // did would push the failure to whatever the caller does with the result. Same rule Lazy and
      // GenericFactory apply to caller-supplied code.
      throw new IllegalStateException(
          getClass().getSimpleName() + ".construct() returned null; build() never returns null");
    }
    return instance;
  }

  /**
   * Builds the instance. Called only after validation has passed.
   *
   * <p><strong>Must defensively copy</strong> every collection or array taken from the builder —
   * see the class Javadoc for why this is a rule rather than a suggestion.
   *
   * @return the constructed instance; must not be {@code null}
   */
  protected abstract T construct();

  /**
   * Asserts this builder's invariants, recording violations rather than throwing.
   *
   * <p>Record with {@link #require(Object, String)} and {@link #reject(String)}. Throwing from here
   * works but defeats the point: the caller then learns about one problem instead of all of them.
   */
  protected abstract void validate();

  /**
   * Records a violation if {@code value} was never set.
   *
   * @param value the field's current value
   * @param field the field's name, as it should read in the message
   */
  protected final void require(@Nullable Object value, String field) {
    if (value == null) {
      violations.add(field + " is required");
    }
  }

  /**
   * Records an arbitrary violation — the accumulating alternative to throwing from {@code
   * validate()}.
   *
   * <p>{@link #require(Object, String)} covers a missing field; this covers everything else, and
   * cross-field rules are the common case: <em>"endDate must be after startDate"</em> cannot be
   * expressed as a null check on either field. Without this, such a rule could only throw, which
   * would drop the caller back to one-violation-per-round-trip for exactly the invariants that are
   * hardest to get right. ADR-0017 records that this method is an addition to the four members
   * RFC-0001 sketched, and why the RFC's own "collect every violation" goal requires it.
   *
   * @param problem what is wrong, as it should read in the message
   */
  protected final void reject(String problem) {
    violations.add(problem);
  }
}
