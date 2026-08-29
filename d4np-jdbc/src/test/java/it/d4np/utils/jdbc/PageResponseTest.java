package it.d4np.utils.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-07 (RFC-0003): what a {@link PageResponse} stores, and what it insists on deriving. */
@DisplayName("PageResponse")
class PageResponseTest {

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    void carriesWhatItWasGiven() {
      PageResponse<String> page = PageResponse.of(List.of("a", "b"), 1, 2, 7);

      assertThat(page.content()).containsExactly("a", "b");
      assertThat(page.page()).isEqualTo(1);
      assertThat(page.size()).isEqualTo(2);
      assertThat(page.totalElements()).isEqualTo(7L);
    }

    @Test
    void takesThePageAndSizeFromTheRequestItAnswers() {
      PageRequest request = PageRequest.of(3, 25, PageSort.unsorted());

      PageResponse<String> page = PageResponse.of(List.of("a"), request, 76);

      assertThat(page.page()).isEqualTo(3);
      assertThat(page.size()).isEqualTo(25);
    }

    @Test
    void copiesTheContent() {
      List<String> mutable = new ArrayList<>(List.of("a"));
      PageResponse<String> page = PageResponse.of(mutable, 0, 10, 1);

      mutable.add("added after the fact");

      assertThat(page.content()).containsExactly("a");
    }

    @Test
    void contentIsUnmodifiable() {
      PageResponse<String> page = PageResponse.of(List.of("a"), 0, 10, 1);

      assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> page.content().add("smuggled in"));
    }

    /** Control C-02, free from {@link List#copyOf}: a null row in a page means nothing. */
    @Test
    void refusesANullRow() {
      List<String> withNull = new ArrayList<>();
      withNull.add(null);

      assertThatNullPointerException().isThrownBy(() -> PageResponse.of(withNull, 0, 10, 1));
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void refusesNull() {
      assertThatNullPointerException().isThrownBy(() -> PageResponse.of(null, 0, 10, 0));
      assertThatNullPointerException()
          .isThrownBy(() -> PageResponse.of(List.of("a"), (PageRequest) null, 1));
    }

    @Test
    void refusesTheRangesNothingCouldProduce() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageResponse.of(List.of(), -1, 10, 0))
          .withMessageContaining("must not be negative");

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageResponse.of(List.of(), 0, 0, 0))
          .withMessageContaining("size must be at least 1");

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageResponse.of(List.of(), 0, 10, -1))
          .withMessageContaining("totalElements must not be negative");
    }

    @Test
    void refusesAPageHoldingMoreRowsThanItsOwnSize() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageResponse.of(List.of("a", "b", "c"), 0, 2, 3))
          .withMessageContaining("a page of size 2 cannot hold 3 rows");
    }

    /**
     * The count almost always comes from a second query, so a concurrent write between the two
     * makes it disagree with the content. Refusing to construct that page would be refusing an
     * ordinary production state — only the contradictions no race can produce are rejected.
     */
    @Test
    void acceptsACountThatDisagreesWithTheContentBecauseARaceCanProduceOne() {
      PageResponse<String> stale = PageResponse.of(List.of("a", "b"), 0, 10, 1);

      assertThat(stale.content()).hasSize(2);
      assertThat(stale.totalElements()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("derived, never stored")
  class Derived {

    @Test
    void countsThePagesTheTotalDividesInto() {
      assertThat(PageResponse.of(List.of("a"), 0, 10, 0).totalPages()).isZero();
      assertThat(PageResponse.of(List.of("a"), 0, 10, 1).totalPages()).isEqualTo(1L);
      assertThat(PageResponse.of(List.of("a"), 0, 10, 10).totalPages()).isEqualTo(1L);
      assertThat(PageResponse.of(List.of("a"), 0, 10, 11).totalPages()).isEqualTo(2L);
      assertThat(PageResponse.of(List.of("a"), 0, 10, 20).totalPages()).isEqualTo(2L);
    }

    /**
     * A row count is exactly the quantity that grows past 2.1 billion, so a page count over it is
     * reachable — and the familiar ceiling division {@code (total + size - 1) / size} overflows to
     * a <em>negative</em> page count here, which is why it is not the expression used.
     */
    @Test
    void doesNotOverflowNearTheMaximumRowCount() {
      PageResponse<String> enormous = PageResponse.of(List.of("a"), 0, 10, Long.MAX_VALUE);

      assertThat(enormous.totalPages()).isEqualTo(922_337_203_685_477_581L).isPositive();

      long asTheFamiliarIdiomWouldHaveIt = (Long.MAX_VALUE + 10L - 1L) / 10L;
      assertThat(asTheFamiliarIdiomWouldHaveIt).isNegative();
    }

    @Test
    void countsMorePagesThanAnIntCouldHold() {
      PageResponse<String> enormous = PageResponse.of(List.of("a"), 0, 1, 3_000_000_000L);

      assertThat(enormous.totalPages()).isEqualTo(3_000_000_000L).isGreaterThan(Integer.MAX_VALUE);
    }

    @Test
    void knowsWhetherAPageFollows() {
      assertThat(PageResponse.of(List.of("a"), 0, 10, 25).hasNext()).isTrue();
      assertThat(PageResponse.of(List.of("a"), 1, 10, 25).hasNext()).isTrue();
      assertThat(PageResponse.of(List.of("a"), 2, 10, 25).hasNext()).isFalse();
      assertThat(PageResponse.of(List.of(), 0, 10, 0).hasNext()).isFalse();
    }

    @Test
    void hasNextDoesNotOverflowOnTheLastRepresentablePage() {
      PageResponse<String> extreme =
          PageResponse.of(List.of("a"), Integer.MAX_VALUE, 10, Long.MAX_VALUE);

      assertThat(extreme.hasNext()).isTrue();
    }

    /** The reason for deriving at all: two values that cannot be recomputed cannot disagree. */
    @Test
    void hasNextAgreesWithTotalPages() {
      for (int page = 0; page < 5; page++) {
        PageResponse<String> response = PageResponse.of(List.of("a"), page, 10, 25);

        assertThat(response.hasNext()).isEqualTo(page + 1L < response.totalPages());
      }
    }
  }

  @Nested
  @DisplayName("what it will not disclose")
  class WhatItWillNotDisclose {

    /**
     * The rows are whatever the caller's query returned, so rendering them turns one {@code
     * log.debug} into a bulk disclosure of up to {@code size} records. That is also why this is a
     * class rather than a record — a record's generated {@code toString()} would print the content
     * and nobody would ever have decided that it should (ADR-0027's reasoning, multiplied).
     */
    @Test
    void rendersTheShapeOfThePageAndNeverItsRows() {
      PageResponse<String> page =
          PageResponse.of(List.of("ada@example.com", "hunter2"), 2, 50, 1204);

      assertThat(page).hasToString("PageResponse[page=2, size=50, rows=2, totalElements=1204]");
      assertThat(page.toString()).doesNotContain("ada@example.com").doesNotContain("hunter2");
    }

    /**
     * Comparing is not disclosing — the asymmetry ADR-0027 stated rather than left to be noticed.
     */
    @Test
    void stillComparesTheRows() {
      assertThat(PageResponse.of(List.of("a"), 0, 10, 1))
          .isEqualTo(PageResponse.of(List.of("a"), 0, 10, 1))
          .isNotEqualTo(PageResponse.of(List.of("b"), 0, 10, 1));
    }

    /**
     * ADR-0015's failure mode: a {@code PageResponse<T>} would be serialisable only when {@code T}
     * happened to be, so it would work in every test and fail in the host that actually serialises.
     */
    @Test
    void isNotSerializable() {
      for (Class<?> type :
          List.of(PageResponse.class, PageRequest.class, PageSort.class, PageSort.Order.class)) {
        assertThat(Serializable.class.isAssignableFrom(type))
            .as("%s became Serializable", type.getSimpleName())
            .isFalse();
      }
    }
  }

  @Nested
  @DisplayName("value semantics")
  class ValueSemantics {

    @Test
    void twoIdenticalPagesAreEqual() {
      PageResponse<String> one = PageResponse.of(List.of("a", "b"), 1, 2, 7);
      PageResponse<String> other = PageResponse.of(List.of("a", "b"), 1, 2, 7);

      assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }

    @Test
    void everyComponentIsPartOfIdentity() {
      PageResponse<String> base = PageResponse.of(List.of("a"), 1, 2, 7);

      assertThat(base).isNotEqualTo(PageResponse.of(List.of("a"), 0, 2, 7));
      assertThat(base).isNotEqualTo(PageResponse.of(List.of("a"), 1, 3, 7));
      assertThat(base).isNotEqualTo(PageResponse.of(List.of("a"), 1, 2, 8));
      assertThat(base).isNotEqualTo("PageResponse[page=1, size=2, rows=1, totalElements=7]");
    }
  }
}
