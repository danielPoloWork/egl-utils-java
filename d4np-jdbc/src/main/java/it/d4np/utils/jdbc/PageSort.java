package it.d4np.utils.jdbc;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An ordered list of sort instructions — the {@code ORDER BY} a caller asked for, before anyone has
 * decided it is allowed (FR-07, RFC-0003).
 *
 * <pre>{@code
 * PageSort sort = PageSort.by("orderDate", PageSort.Direction.DESC);
 *
 * PageSort multi = PageSort.of(List.of(
 *     new PageSort.Order("status", PageSort.Direction.ASC),
 *     new PageSort.Order("orderDate", PageSort.Direction.DESC)));
 * }</pre>
 *
 * <h2>Nothing about a property is judged here</h2>
 *
 * <p>A property is a {@link String} and every non-{@code null} {@code String} is accepted —
 * including a blank one, including one holding punctuation, including one 10 000 characters long.
 * That is not laxity, it is where the judgment belongs: {@link
 * PageRequest#validatedAgainst(java.util.Set)} is the <strong>only</strong> thing that decides a
 * property is unacceptable, and it reports the verdict as an {@link
 * it.d4np.utils.ValidationException}, which FR-19 maps to <strong>400</strong>.
 *
 * <p>Refusing a blank property <em>here</em> would raise {@link IllegalArgumentException}, which
 * FR-19 has no row for and which therefore lands on the <strong>500</strong> fallback — reporting
 * {@code ?sort=,asc} from a browser as a server fault. That is the misattribution {@link
 * it.d4np.utils.StrategyNotFoundException} was kept out of {@code BusinessException} to avoid, and
 * RFC-0003 §FR-07 used the same argument to move the whitelist check out of the constructor. One
 * judge, one status code, one place to read.
 *
 * <p><strong>{@code null} is still refused</strong>, and the asymmetry is deliberate: a parser that
 * hands this type a {@code null} property is a defect in the code that built the request, not
 * something a client can send.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>Immutable and therefore thread-safe.</strong> The order list is copied with {@link
 * List#copyOf(java.util.Collection)} at construction and never handed out in a mutable form. No
 * jcstress harness is owed, and spec §6 is why that has to be said rather than left as an absence:
 * this type has no mutable state to stress at all. Items 3.1, 4.1 and 4.3 recorded the same for
 * {@code Validator}, {@code JsonMapper} and {@code SimpleJdbcExecutor}; item 4.4's {@code
 * JdbcTxRunner} is still the only type outside core that owes one.
 *
 * <p><strong>Not {@link java.io.Serializable}</strong>, with {@link PageRequest} and {@link
 * PageResponse}, for the reason ADR-0015 recorded — the wire format this library targets is JSON,
 * which FR-20 owns.
 *
 * @see PageRequest#validatedAgainst(java.util.Set)
 */
public final class PageSort {

  /** The one instance meaning "no ordering was asked for"; there is nothing to distinguish. */
  private static final PageSort UNSORTED = new PageSort(List.of());

  /** The orders, in the sequence they will be applied; empty for {@link #unsorted()}. */
  private final List<Order> orders;

  private PageSort(List<Order> orders) {
    this.orders = orders;
  }

  /**
   * The sort that asks for nothing, leaving the ordering to the query.
   *
   * <p>An <em>absent</em> sort rather than a default one: choosing a column to order by on the
   * caller's behalf would be choosing the meaning of their query, which is the line this module
   * does not cross (see the package documentation).
   *
   * @return the shared unsorted instance; never {@code null}
   */
  public static PageSort unsorted() {
    return UNSORTED;
  }

  /**
   * A sort on one property, which is what a list endpoint asks for almost every time.
   *
   * @param property the property to order by; must not be {@code null}, and is otherwise unjudged
   *     until {@link PageRequest#validatedAgainst(java.util.Set)} sees it
   * @param direction ascending or descending; must not be {@code null}
   * @return the sort; never {@code null}
   * @throws NullPointerException if either argument is {@code null}
   */
  public static PageSort by(String property, Direction direction) {
    return new PageSort(List.of(new Order(property, direction)));
  }

  /**
   * A sort on several properties, applied in the order given.
   *
   * @param orders the instructions, most significant first; must not be {@code null} and must hold
   *     no {@code null}. An empty list produces {@link #unsorted()}
   * @return the sort; never {@code null}
   * @throws NullPointerException if {@code orders} or any element is {@code null}
   */
  public static PageSort of(List<Order> orders) {
    Objects.requireNonNull(orders, "orders must not be null");
    return orders.isEmpty() ? UNSORTED : new PageSort(List.copyOf(orders));
  }

  /**
   * The instructions, in the sequence they apply.
   *
   * <p><strong>Reachable only from a sort you built or one that came back validated.</strong>
   * {@link PageRequest} deliberately publishes no accessor for the sort it is carrying, so the
   * repository that renders an {@code ORDER BY} cannot get to these strings without having supplied
   * an allowlist first (ADR-0033).
   *
   * @return an unmodifiable list, empty for {@link #unsorted()}; never {@code null}
   */
  public List<Order> orders() {
    return orders;
  }

  /**
   * Whether nothing was asked for.
   *
   * @return {@code true} when there are no orders to apply
   */
  public boolean isUnsorted() {
    return orders.isEmpty();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PageSort sort && orders.equals(sort.orders);
  }

  @Override
  public int hashCode() {
    return orders.hashCode();
  }

  /**
   * Renders the orders through {@link PageDiagnostics}, never raw.
   *
   * <p>These property names are client input, so a {@code toString()} is a log-injection primitive
   * unless the names are bounded and stripped — item 4.2 met the same thing from the other side,
   * where {@code PartialUpdate} renders names <em>instead of</em> values because there the names
   * were the target type's own vocabulary (ADR-0027). Here the name <em>is</em> the untrusted
   * value, so it is the name that has to be bounded.
   *
   * @return a bounded rendering such as {@code PageSort[status ASC, orderDate DESC]}
   */
  @Override
  public String toString() {
    return orders.isEmpty()
        ? "PageSort[unsorted]"
        : orders.stream().map(Order::toString).collect(Collectors.joining(", ", "PageSort[", "]"));
  }

  /** Which way a property is ordered. */
  public enum Direction {

    /** Smallest first — SQL's own default, restated so a caller never has to rely on it. */
    ASC,

    /** Largest first. */
    DESC
  }

  /**
   * One instruction: order by {@code property}, in {@code direction}.
   *
   * <p>A record, unlike {@link PageRequest} and {@link PageResponse}, and the difference is what
   * the generated members would say. ADR-0027 made {@code PartialUpdate} a class because a record's
   * {@code toString()} prints its components and one of them was a value that must never be
   * printed. Here both components must be printable for the type to be debuggable at all — so the
   * record is kept and only {@link #toString()} is replaced, with the bounding that makes printing
   * a client-supplied name safe.
   *
   * @param property the property to order by; unjudged here, and judged only by {@link
   *     PageRequest#validatedAgainst(java.util.Set)}
   * @param direction which way to order
   */
  public record Order(String property, Direction direction) {

    /**
     * Refuses {@code null} and nothing else.
     *
     * @throws NullPointerException if either component is {@code null}
     */
    public Order {
      Objects.requireNonNull(property, "sort property must not be null");
      Objects.requireNonNull(direction, "sort direction must not be null");
    }

    /**
     * The bounded rendering, never the raw property.
     *
     * @return for example {@code orderDate DESC}, with the property truncated and stripped
     */
    @Override
    public String toString() {
      return PageDiagnostics.bounded(property) + " " + direction;
    }
  }
}
