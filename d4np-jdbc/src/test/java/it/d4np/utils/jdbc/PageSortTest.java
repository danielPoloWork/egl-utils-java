package it.d4np.utils.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import it.d4np.utils.jdbc.PageSort.Direction;
import it.d4np.utils.jdbc.PageSort.Order;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-07 (RFC-0003): what {@link PageSort} carries, and what it deliberately declines to judge. */
@DisplayName("PageSort")
class PageSortTest {

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    void unsortedIsEmptyAndShared() {
      assertThat(PageSort.unsorted().isUnsorted()).isTrue();
      assertThat(PageSort.unsorted().orders()).isEmpty();
      assertThat(PageSort.unsorted()).isSameAs(PageSort.unsorted());
    }

    @Test
    void carriesTheOrdersInTheSequenceGiven() {
      PageSort sort =
          PageSort.of(
              List.of(new Order("status", Direction.ASC), new Order("orderDate", Direction.DESC)));

      assertThat(sort.orders())
          .containsExactly(
              new Order("status", Direction.ASC), new Order("orderDate", Direction.DESC));
      assertThat(sort.isUnsorted()).isFalse();
    }

    @Test
    void anEmptyListIsTheUnsortedInstance() {
      assertThat(PageSort.of(List.of())).isSameAs(PageSort.unsorted());
    }

    @Test
    void copiesTheOrderList() {
      List<Order> mutable = new ArrayList<>(List.of(new Order("status", Direction.ASC)));
      PageSort sort = PageSort.of(mutable);

      mutable.add(new Order("smuggled", Direction.ASC));

      assertThat(sort.orders()).hasSize(1);
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void refusesNull() {
      assertThatNullPointerException().isThrownBy(() -> PageSort.of(null));
      assertThatNullPointerException().isThrownBy(() -> PageSort.by(null, Direction.ASC));
      assertThatNullPointerException().isThrownBy(() -> PageSort.by("status", null));
      assertThatNullPointerException().isThrownBy(() -> new Order(null, Direction.ASC));
    }

    @Test
    void ordersIsUnmodifiable() {
      PageSort sort = PageSort.by("status", Direction.ASC);

      assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> sort.orders().add(new Order("smuggled", Direction.ASC)));
    }
  }

  /**
   * The decision this type exists to <em>not</em> make. Refusing a bad property here would raise
   * {@link IllegalArgumentException}, which FR-19 has no row for and which therefore lands on the
   * 500 fallback — reporting {@code ?sort=,asc} from a browser as a server fault. The allowlist is
   * the only judge, and its verdict is a 400.
   */
  @Nested
  @DisplayName("judges no property")
  class JudgesNoProperty {

    @Test
    void acceptsABlankProperty() {
      assertThat(PageSort.by("", Direction.ASC).orders()).hasSize(1);
      assertThat(PageSort.by("   ", Direction.ASC).orders()).hasSize(1);
    }

    @Test
    void acceptsAPropertyThatCouldNeverBeAColumn() {
      String injection = "orderDate; drop table orders --";

      assertThat(PageSort.by(injection, Direction.ASC).orders().get(0).property())
          .isEqualTo(injection);
    }

    @Test
    void acceptsAPropertyOfAnyLength() {
      assertThat(PageSort.by("x".repeat(10_000), Direction.ASC).orders()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("rendering")
  class Rendering {

    @Test
    void namesTheUnsortedCaseRatherThanRenderingNothing() {
      assertThat(PageSort.unsorted()).hasToString("PageSort[unsorted]");
    }

    @Test
    void rendersEveryOrderInSequence() {
      PageSort sort =
          PageSort.of(
              List.of(new Order("status", Direction.ASC), new Order("orderDate", Direction.DESC)));

      assertThat(sort).hasToString("PageSort[status ASC, orderDate DESC]");
    }

    /**
     * Control C-01. These names come from a query string, so an unbounded {@code toString()} is a
     * log-forgery primitive rather than a formatting problem — the finding item 4.1 recorded when
     * RFC-0003's own length bound turned out to say nothing about control characters.
     */
    @Test
    void stripsControlCharactersFromAPropertyName() {
      String forged = "id\r\n2026-01-01 WARNING admin logged in";

      String rendered = PageSort.by(forged, Direction.ASC).toString();

      assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
      assertThat(rendered).contains("2026-01-01 WARNING admin logged in");
    }

    @Test
    void truncatesAPropertyNameThatIsTooLong() {
      String enormous = "x".repeat(1_000);

      String rendered = PageSort.by(enormous, Direction.ASC).toString();

      assertThat(rendered)
          .contains("x".repeat(PageDiagnostics.MAX_PROPERTY_LENGTH) + "... ASC")
          .hasSizeLessThan(200);
    }
  }

  @Nested
  @DisplayName("value semantics")
  class ValueSemantics {

    @Test
    void twoSortsWithTheSameOrdersAreEqual() {
      assertThat(PageSort.by("status", Direction.ASC))
          .isEqualTo(PageSort.of(List.of(new Order("status", Direction.ASC))))
          .hasSameHashCodeAs(PageSort.of(List.of(new Order("status", Direction.ASC))));
    }

    @Test
    void directionIsPartOfIdentity() {
      assertThat(PageSort.by("status", Direction.ASC))
          .isNotEqualTo(PageSort.by("status", Direction.DESC));
    }

    @Test
    void orderIsPartOfIdentity() {
      List<Order> forward = List.of(new Order("a", Direction.ASC), new Order("b", Direction.ASC));
      List<Order> reversed = List.of(new Order("b", Direction.ASC), new Order("a", Direction.ASC));

      assertThat(PageSort.of(forward)).isNotEqualTo(PageSort.of(reversed));
    }

    @Test
    void isNotEqualToAnotherType() {
      assertThat(PageSort.unsorted()).isNotEqualTo("PageSort[unsorted]");
    }
  }
}
