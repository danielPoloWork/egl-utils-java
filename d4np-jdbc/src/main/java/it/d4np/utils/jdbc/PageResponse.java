package it.d4np.utils.jdbc;

import java.util.List;
import java.util.Objects;

/**
 * One page of results, plus how many there are in total (FR-07, RFC-0003).
 *
 * <pre>{@code
 * List<Order> rows = executor.query(pagedSql, Order::from, request.size(), request.offset());
 * long total = executor.queryOne("select count(*) from orders", r -> r.getLong(1)).orElse(0L);
 *
 * PageResponse<Order> page = PageResponse.of(rows, request, total);
 * page.totalPages();   // derived
 * page.hasNext();      // derived
 * }</pre>
 *
 * <h2>Derived, never stored</h2>
 *
 * <p>{@link #totalPages()} and {@link #hasNext()} are computed on every call. A stored derived
 * value can disagree with its inputs after any refactor — and it disagrees silently, because
 * nothing recomputes it to notice. Deriving makes disagreement impossible rather than unlikely.
 *
 * <p>Both return {@code long} arithmetic, and the reason is not theoretical. {@link
 * #totalElements()} is a {@code long} because a row count is exactly the quantity that grows past
 * 2.1 billion, so {@code totalPages()} must be one too: a table of 3 billion rows paged at 1 does
 * not have an {@code int} number of pages. The ceiling division is written as {@code (total - 1) /
 * size + 1} rather than the familiar {@code (total + size - 1) / size}, because the familiar one
 * overflows to a <em>negative</em> page count when {@code totalElements} is near {@link
 * Long#MAX_VALUE}. {@code Math.ceilDiv(long, long)} would say it plainly and arrived in Java 18,
 * which the {@code --release 17} baseline (NFR-07) puts out of reach — and the link is written as
 * code rather than as {@code @link} for the same reason, since ErrorProne's {@code InvalidLink}
 * check resolves references against the release classpath and fails the build on one that is not
 * there.
 *
 * <h2>{@code totalElements} is a snapshot, and this type does not pretend otherwise</h2>
 *
 * <p>The count almost always comes from a second query, so a concurrent insert or delete between
 * the two makes it disagree with {@link #content()} — and {@link #hasNext()}, derived from it,
 * inherits the disagreement. Refusing to construct such a page would be refusing an ordinary
 * production state, so the only contradictions rejected are the ones no race can produce: a
 * negative count, and a page holding more rows than its own {@code size}. A caller that needs the
 * two to agree runs both queries in one FR-06 transaction at {@code REPEATABLE_READ}, which is a
 * decision only the caller can make.
 *
 * <h2>Thread safety</h2>
 *
 * <p><strong>Immutable and therefore thread-safe</strong>, to the depth the element type allows:
 * {@code content} is copied with {@link List#copyOf(java.util.Collection)}, which is unmodifiable
 * and — usefully here — <strong>rejects a {@code null} element</strong>, so control C-02 comes free
 * on this path. A mutable {@code T} is the caller's own to publish safely, which no wrapper can
 * fix.
 *
 * <p><strong>Not {@link java.io.Serializable}</strong>, and for this type the reason is sharper
 * than for the other two: a {@code PageResponse<T>} would be serialisable only when {@code T}
 * happened to be, so it would work in every test and fail in the host that actually serialises —
 * ADR-0015's failure mode exactly. The wire format this library targets is JSON, which FR-20 owns.
 *
 * @param <T> what a row was mapped to
 * @see PageRequest
 */
public final class PageResponse<T> {

  /** The rows of this page, unmodifiable and never {@code null}. */
  private final List<T> content;

  /** Which page this is, zero-based. */
  private final int page;

  /** How many rows a full page holds; not how many this one has. */
  private final int size;

  /** How many rows the whole query matches, across every page. */
  private final long totalElements;

  private PageResponse(List<T> content, int page, int size, long totalElements) {
    this.content = content;
    this.page = page;
    this.size = size;
    this.totalElements = totalElements;
  }

  /**
   * A page assembled from its parts.
   *
   * @param <T> what a row was mapped to
   * @param content the rows of this page, in query order; copied, not retained, and must hold no
   *     {@code null}
   * @param page the zero-based page index; must be {@code >= 0}
   * @param size how many rows a full page holds; must be {@code >= 1} and at least {@code
   *     content.size()}
   * @param totalElements how many rows the query matches in total; must be {@code >= 0}
   * @return the page; never {@code null}
   * @throws NullPointerException if {@code content} is {@code null} or holds a {@code null}
   * @throws IllegalArgumentException if {@code page}, {@code size} or {@code totalElements} is out
   *     of range, or if {@code content} holds more rows than {@code size}
   */
  public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
    List<T> rows = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
    if (page < 0) {
      throw new IllegalArgumentException("page must not be negative, but was " + page);
    }
    if (size < 1) {
      throw new IllegalArgumentException("size must be at least 1, but was " + size);
    }
    if (rows.size() > size) {
      throw new IllegalArgumentException(
          "a page of size " + size + " cannot hold " + rows.size() + " rows");
    }
    if (totalElements < 0) {
      throw new IllegalArgumentException(
          "totalElements must not be negative, but was " + totalElements);
    }
    return new PageResponse<>(rows, page, size, totalElements);
  }

  /**
   * The same, taking the page and size from the request that produced it.
   *
   * <p>The form worth preferring: it makes the page's own {@code page}/{@code size} incapable of
   * disagreeing with what was asked for, which is the mistake the four-argument form leaves
   * available.
   *
   * @param <T> what a row was mapped to
   * @param content the rows of this page, in query order; copied, not retained
   * @param request the request this page answers; must not be {@code null}
   * @param totalElements how many rows the query matches in total; must be {@code >= 0}
   * @return the page; never {@code null}
   * @throws NullPointerException if {@code content} or {@code request} is {@code null}, or {@code
   *     content} holds a {@code null}
   * @throws IllegalArgumentException if {@code totalElements} is negative or {@code content} holds
   *     more rows than the request's size
   */
  public static <T> PageResponse<T> of(List<T> content, PageRequest request, long totalElements) {
    Objects.requireNonNull(request, "request must not be null");
    return of(content, request.page(), request.size(), totalElements);
  }

  /**
   * The rows of this page.
   *
   * @return an unmodifiable list, possibly empty; never {@code null}
   */
  public List<T> content() {
    return content;
  }

  /**
   * Which page this is.
   *
   * @return the zero-based index, {@code >= 0}
   */
  public int page() {
    return page;
  }

  /**
   * How many rows a full page holds — not how many this one has, which is {@code content().size()}.
   *
   * @return {@code >= 1}
   */
  public int size() {
    return size;
  }

  /**
   * How many rows the query matches in total, across every page.
   *
   * @return {@code >= 0}
   */
  public long totalElements() {
    return totalElements;
  }

  /**
   * How many pages the total divides into, derived.
   *
   * @return {@code 0} when nothing matched, otherwise the ceiling of {@code totalElements / size}
   */
  public long totalPages() {
    return totalElements == 0 ? 0 : (totalElements - 1) / size + 1;
  }

  /**
   * Whether another page follows this one, derived.
   *
   * <p>Derived from {@link #totalPages()} rather than recomputed from {@code totalElements}, so the
   * two cannot answer differently — which is the point of deriving in the first place.
   *
   * @return {@code true} when a page after this one exists
   */
  public boolean hasNext() {
    return page + 1L < totalPages();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PageResponse<?> response
        && page == response.page
        && size == response.size
        && totalElements == response.totalElements
        && content.equals(response.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(content, page, size, totalElements);
  }

  /**
   * Renders the shape of the page and never its rows.
   *
   * <p>The rows are whatever the caller's query returned — customer names, addresses, whatever the
   * table holds — so rendering them turns one {@code log.debug} into a bulk disclosure of up to
   * {@code size} records. ADR-0027 reached the same conclusion for one value; a page is the same
   * decision multiplied. That is also why this type is a class rather than a record: a record's
   * generated {@code toString()} would print {@code content} and there would be no moment at which
   * anyone decided it should. {@link #equals(Object)} still reads the rows, because comparing is
   * not disclosing.
   *
   * @return for example {@code PageResponse[page=2, size=50, rows=50, totalElements=1204]}
   */
  @Override
  public String toString() {
    return "PageResponse[page="
        + page
        + ", size="
        + size
        + ", rows="
        + content.size()
        + ", totalElements="
        + totalElements
        + "]";
  }
}
