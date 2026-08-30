package it.d4np.utils.jdbc;

import it.d4np.utils.ValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What page a caller asked for, and the sorting they asked for — which nothing can read without
 * first saying what sorting is allowed (FR-07, RFC-0003).
 *
 * <pre>{@code
 * // at the HTTP edge, from query parameters
 * PageRequest request = PageRequest.of(page, size, PageSort.by(sortBy, Direction.DESC));
 *
 * // in the repository, which is the only place that knows what is sortable
 * PageSort sort = request.validatedAgainst(Set.of("orderDate", "total", "status"));
 * String orderBy = sort.isUnsorted() ? "" : renderOrderBy(sort.orders());
 *
 * List<Order> rows = executor.query(
 *     "select id, total from orders " + orderBy + " limit ? offset ?",
 *     Order::from, request.size(), request.offset());
 * }</pre>
 *
 * <h2>There is no {@code sort()} accessor, and that is the whole guarantee</h2>
 *
 * <p>A column name cannot be a bind parameter in any database, so the {@code ORDER BY} clause is
 * the one place a repository <em>has</em> to build SQL by concatenation — the residual {@link
 * SimpleJdbcExecutor} states and cannot close. What closes it is not advice to remember the
 * allowlist: it is that <strong>the only way to obtain the sort out of a request is {@link
 * #validatedAgainst(Set)}</strong>, which takes the allowlist as its argument. A repository cannot
 * reach the client's strings without having named the columns it is prepared to order by, because
 * there is no other method that returns them.
 *
 * <p>This is FR-20's shape applied to FR-07: {@code JsonMapper} hardens Jackson by never handing
 * out the {@code ObjectMapper}, so there is nothing to call {@code activateDefaultTyping} on. Here
 * there is nothing to interpolate but a validated name. ADR-0022's rule is the same in both — a
 * guarantee a consumer can switch off is advisory — and ADR-0033 records the trade, including what
 * a {@code sort()} accessor would have been convenient for.
 *
 * <p><strong>The residual, stated:</strong> a component that both parses the request and builds the
 * SQL still holds the {@link PageSort} it constructed and can interpolate that. What this type
 * removes is the case where the two are different layers, which is every application that has a
 * repository at all — and it removes it structurally rather than by review.
 *
 * <h2>Two bounds checked here, and one exception shape that is not this item's to fix</h2>
 *
 * <p>{@code page >= 0} and {@code 1 <= size <= maxSize} are checked at construction and raise
 * {@link IllegalArgumentException}, which is what spec §2 FR-07 states normatively. Everything
 * about the <em>sort</em> is checked by {@link #validatedAgainst(Set)} instead and raises {@link
 * ValidationException}, because RFC-0003 §FR-07 showed the allowlist cannot be known at
 * construction.
 *
 * <p>The asymmetry that leaves is real and is not this item's to resolve. FR-19's mapping table has
 * a row for {@code ValidationException} (<strong>400</strong>) and none for {@code
 * IllegalArgumentException}, so an out-of-range {@code ?page=-1} reaching {@link #of(int, int,
 * PageSort)} unmediated lands on the <strong>500</strong> fallback and reports a client's typo as a
 * server fault. Deviating would put an implementation quietly at odds with both the specification
 * and an approved RFC, so the obligation is stated on the factory and filed on item 7.1, which owns
 * the mapping table.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>Immutable and therefore thread-safe.</strong> Three final fields over an immutable
 * {@link PageSort}; {@link #validatedAgainst(Set)} reads them and mutates nothing, so two threads
 * validating one request against two different allowlists cannot interfere.
 *
 * @see PageSort
 * @see PageResponse
 */
public final class PageRequest {

  /**
   * The ceiling {@link #of(int, int, PageSort)} applies, from spec §2 FR-07.
   *
   * <p>A page is materialised in memory by whatever runs the query, so an unbounded {@code size} is
   * the memory-exhaustion row of the threat model. 200 is the specification's number and is
   * deliberately not tuned here — {@link #of(int, int, PageSort, int)} is how a caller who knows
   * their rows are small says so.
   */
  public static final int DEFAULT_MAX_SIZE = 200;

  /**
   * How many properties one sort may name.
   *
   * <p>Checked at validation rather than at construction, so a client that repeats {@code ?sort=} a
   * hundred times gets a 400 rather than a 500. The cap exists because the allowlist alone does not
   * bound the clause: a repository with fifty sortable columns would otherwise accept a
   * fifty-column {@code ORDER BY}, which is a query plan nobody asked for. Real clauses have one or
   * two properties; eight is well past useful and well short of expensive.
   */
  public static final int MAX_SORT_PROPERTIES = 8;

  /** Zero-based, so page 0 is the first page. */
  private final int page;

  /** How many rows the page holds at most; at least 1. */
  private final int size;

  /** What the caller asked to order by — unreadable except through {@link #validatedAgainst}. */
  private final PageSort sort;

  private PageRequest(int page, int size, PageSort sort) {
    this.page = page;
    this.size = size;
    this.sort = sort;
  }

  /**
   * A request for {@code page}, of at most {@code size} rows, sorted as {@code sort} asks.
   *
   * @param page the zero-based page index; must be {@code >= 0}
   * @param size how many rows the page holds; must be {@code >= 1} and {@code <=} {@value
   *     #DEFAULT_MAX_SIZE}
   * @param sort what to order by, or {@link PageSort#unsorted()}; must not be {@code null}
   * @return the request; never {@code null}
   * @throws NullPointerException if {@code sort} is {@code null}
   * @throws IllegalArgumentException if {@code page} or {@code size} is out of range
   * @apiNote <strong>Bind and range-check {@code page} and {@code size} at the edge, before calling
   *     this.</strong> FR-19 maps validation to <strong>400</strong> and has no row for {@code
   *     IllegalArgumentException}, so an out-of-range {@code ?page=-1} arriving here unmediated
   *     lands on the <strong>500</strong> fallback and reports a client's typo as a server fault.
   *     Every framework in the compatibility matrix can make the check declaratively —
   *     {@code @Min(0)} on the parameter — and this API cannot make it for them without
   *     contradicting the specification's own sentence, which is why the sort's allowlist check
   *     went the other way and these two bounds did not.
   */
  public static PageRequest of(int page, int size, PageSort sort) {
    return of(page, size, sort, DEFAULT_MAX_SIZE);
  }

  /**
   * The same, with the caller's own ceiling on {@code size} — which is what FR-07 means by
   * "configurable".
   *
   * <p><strong>A parameter, never a system property or a static field.</strong> A host-global
   * default cannot be tested in parallel and makes one call return different results in two modules
   * of one application. It is also not clamped to {@value #DEFAULT_MAX_SIZE}: a caller who knows
   * their rows are two columns wide may legitimately want more, and a library that silently
   * overrode them would be the second-guessing this parameter exists to avoid.
   *
   * @param page the zero-based page index; must be {@code >= 0}
   * @param size how many rows the page holds; must be {@code >= 1} and {@code <= maxSize}
   * @param sort what to order by, or {@link PageSort#unsorted()}; must not be {@code null}
   * @param maxSize the caller's ceiling; must be {@code >= 1}
   * @return the request; never {@code null}
   * @throws NullPointerException if {@code sort} is {@code null}
   * @throws IllegalArgumentException if {@code maxSize < 1}, {@code page < 0}, or {@code size} is
   *     outside {@code [1, maxSize]}
   */
  public static PageRequest of(int page, int size, PageSort sort, int maxSize) {
    Objects.requireNonNull(sort, "sort must not be null; use PageSort.unsorted()");
    if (maxSize < 1) {
      throw new IllegalArgumentException("maxSize must be at least 1, but was " + maxSize);
    }
    if (page < 0) {
      throw new IllegalArgumentException("page must not be negative, but was " + page);
    }
    if (size < 1 || size > maxSize) {
      throw new IllegalArgumentException(
          "size must be between 1 and " + maxSize + ", but was " + size);
    }
    return new PageRequest(page, size, sort);
  }

  /**
   * The zero-based page index.
   *
   * @return {@code >= 0}
   */
  public int page() {
    return page;
  }

  /**
   * How many rows the page holds at most.
   *
   * @return {@code >= 1}
   */
  public int size() {
    return size;
  }

  /**
   * How many rows precede this page — the {@code offset} of a {@code limit}/{@code offset} query.
   *
   * <p><strong>Derived here rather than left to the caller, because the obvious expression is
   * wrong.</strong> {@code page * size} is an {@code int} multiplication that overflows silently at
   * roughly ten million pages of 200 and returns a <em>negative</em> offset, which a database then
   * rejects with a syntax error naming neither cause. The multiplication is done in {@code long},
   * which cannot overflow: both operands are bounded by {@link Integer#MAX_VALUE}, so the product
   * is bounded by 2<sup>62</sup>.
   *
   * <p>It is an addition to what RFC-0003's table lists, in the reversible direction — a method
   * added later is MINOR and one removed is MAJOR — and it earns the widening precisely because the
   * caller's version of it has a bug in it.
   *
   * @return {@code page * size} as a {@code long}; never negative
   */
  public long offset() {
    return (long) page * size;
  }

  /**
   * The sort, once {@code allowedProperties} has vouched for every property it names.
   *
   * <p><strong>The allowlist is compared exactly and case-sensitively, with no
   * normalisation.</strong> RFC-0003 gave one reason — SQL identifier folding is vendor-specific,
   * so normalising would pick a vendor: PostgreSQL folds unquoted names to lower case, Oracle to
   * upper, MySQL depends on the filesystem. Building this type surfaced a second, and it is the
   * security-relevant one: because the comparison is exact, the string that survives validation is
   * {@link String#equals equal} to the one the repository listed, so interpolating the client's
   * string into an {@code ORDER BY} is indistinguishable from interpolating the repository's own.
   * Under case folding it would <em>not</em> be — {@code "ORDERDATE"} would pass a check against
   * {@code "orderDate"} and then be the text that reaches the SQL. The vendor-neutrality argument
   * and the injection argument happen to point the same way, which is worth knowing if anyone is
   * ever tempted to fold.
   *
   * <p>Three things are refused, and every one of them is a {@link ValidationException} so that
   * FR-19 answers <strong>400</strong>:
   *
   * <table border="1">
   *   <caption>What validation refuses</caption>
   *   <tr><th>Refused</th><th>Why it is not left to the allowlist</th></tr>
   *   <tr>
   *     <td>more than {@value #MAX_SORT_PROPERTIES} properties</td>
   *     <td>the allowlist bounds <em>which</em> columns, never <em>how many</em>; reported alone,
   *         because a hundred violations is the flood the cap exists to prevent</td>
   *   </tr>
   *   <tr>
   *     <td>the same property twice</td>
   *     <td>{@code ORDER BY name ASC, name DESC} is legal SQL in which the second clause is dead —
   *         it does nothing, and looking like it does something is the problem</td>
   *   </tr>
   *   <tr>
   *     <td>a property not in {@code allowedProperties}</td>
   *     <td>this is the control itself</td>
   *   </tr>
   * </table>
   *
   * <p><strong>Every rejected property is reported, not the first.</strong> Fixing one name at a
   * time across a round trip each is the experience item 4.2 refused for FR-21's unknown
   * properties, and there is no reason for this door to be worse.
   *
   * <p><strong>The message names what was rejected and never what was allowed</strong> (control
   * C-01). The rejected name is the client's own string, bounded and stripped, and it tells them
   * nothing they did not send. The allowlist is the repository's column vocabulary — internal
   * schema — and this exception's message reaches the client through an FR-19 <em>body</em>, where
   * {@code StrategyNotFoundException} may list its known keys only because FR-19 maps that one to a
   * 500 with no body at all. Two exceptions, two lists, opposite answers, for the same reason.
   *
   * @param allowedProperties the properties this repository is prepared to order by, spelled
   *     exactly as its schema spells them; must not be {@code null} or hold {@code null}. May be
   *     empty, which is how a repository says nothing is sortable
   * @return the sort, unchanged, when every property it names is allowed; {@link
   *     PageSort#unsorted()} validates against any allowlist including an empty one
   * @throws NullPointerException if {@code allowedProperties} or any element is {@code null}
   * @throws ValidationException if the sort names too many properties, repeats one, or names one
   *     that is not allowed
   */
  public PageSort validatedAgainst(Set<String> allowedProperties) {
    Set<String> allowed = Set.copyOf(allowedProperties);
    List<PageSort.Order> orders = sort.orders();
    if (orders.size() > MAX_SORT_PROPERTIES) {
      throw ValidationException.of(
          "PageRequest",
          List.of(
              "sort: at most "
                  + MAX_SORT_PROPERTIES
                  + " sort properties are allowed, but "
                  + orders.size()
                  + " were given"));
    }
    List<String> violations = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < orders.size(); index++) {
      String property = orders.get(index).property();
      String reported = PageDiagnostics.bounded(property);
      if (!seen.add(property)) {
        violations.add("sort[" + index + "].property: '" + reported + "' is named more than once");
      } else if (!allowed.contains(property)) {
        violations.add("sort[" + index + "].property: '" + reported + "' is not sortable");
      }
    }
    if (!violations.isEmpty()) {
      throw ValidationException.of("PageRequest", violations);
    }
    return sort;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PageRequest request
        && page == request.page
        && size == request.size
        && sort.equals(request.sort);
  }

  @Override
  public int hashCode() {
    return Objects.hash(page, size, sort);
  }

  /**
   * Renders the page and the size, and the sort only as a count.
   *
   * <p>The one place this type would otherwise contradict itself. If {@code toString()} rendered
   * the sort, the property names a repository is not allowed to read would be one {@code
   * log.debug("{}", request)} away from the log the same repository writes — the accessor removed
   * for the reason in the class documentation, restored by a formatting method. {@link PageSort}'s
   * own {@code toString()} does render them, bounded, because whoever holds that object built it or
   * received it validated.
   *
   * @return for example {@code PageRequest[page=2, size=50, sortProperties=1]}
   */
  @Override
  public String toString() {
    return "PageRequest[page="
        + page
        + ", size="
        + size
        + ", sortProperties="
        + sort.orders().size()
        + "]";
  }
}
