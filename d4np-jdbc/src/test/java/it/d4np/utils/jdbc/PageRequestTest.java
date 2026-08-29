package it.d4np.utils.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import it.d4np.utils.ValidationException;
import it.d4np.utils.jdbc.PageSort.Direction;
import it.d4np.utils.jdbc.PageSort.Order;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-07 (RFC-0003, ADR-0033, ADR-0034): the bounds {@link PageRequest} checks at construction, and
 * the allowlist that is the only way to read the sort back out.
 */
@DisplayName("PageRequest")
class PageRequestTest {

  /** What a repository is prepared to order by; the client never sees it. */
  private static final Set<String> SORTABLE = Set.of("id", "sku", "quantity");

  @Nested
  @DisplayName("bounds, checked at construction")
  class Bounds {

    @Test
    void carriesWhatItWasGiven() {
      PageRequest request = PageRequest.of(2, 50, PageSort.by("sku", Direction.ASC));

      assertThat(request.page()).isEqualTo(2);
      assertThat(request.size()).isEqualTo(50);
    }

    @Test
    void acceptsTheFirstPageAndTheSmallestSize() {
      assertThat(PageRequest.of(0, 1, PageSort.unsorted()).page()).isZero();
      assertThat(PageRequest.of(0, 1, PageSort.unsorted()).size()).isEqualTo(1);
    }

    @Test
    void acceptsTheSpecifiedCeilingAndRefusesOneMore() {
      assertThat(PageRequest.of(0, PageRequest.DEFAULT_MAX_SIZE, PageSort.unsorted()).size())
          .isEqualTo(200);

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(
              () -> PageRequest.of(0, PageRequest.DEFAULT_MAX_SIZE + 1, PageSort.unsorted()))
          .withMessageContaining("between 1 and 200");
    }

    @Test
    void refusesANegativePageAndAnEmptyPage() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageRequest.of(-1, 10, PageSort.unsorted()))
          .withMessageContaining("must not be negative");

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageRequest.of(0, 0, PageSort.unsorted()))
          .withMessageContaining("between 1 and 200");
    }

    /** "Configurable" in FR-07 means a parameter — never a system property or a static field. */
    @Test
    void takesTheCallersOwnCeiling() {
      assertThat(PageRequest.of(0, 1_000, PageSort.unsorted(), 1_000).size()).isEqualTo(1_000);

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageRequest.of(0, 11, PageSort.unsorted(), 10))
          .withMessageContaining("between 1 and 10");
    }

    @Test
    void refusesACeilingNothingCouldSatisfy() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> PageRequest.of(0, 1, PageSort.unsorted(), 0))
          .withMessageContaining("maxSize must be at least 1");
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void refusesANullSortRatherThanTreatingItAsUnsorted() {
      assertThatNullPointerException()
          .isThrownBy(() -> PageRequest.of(0, 10, null))
          .withMessageContaining("PageSort.unsorted()");
    }
  }

  @Nested
  @DisplayName("offset")
  class Offset {

    @Test
    void countsTheRowsBeforeThisPage() {
      assertThat(PageRequest.of(0, 50, PageSort.unsorted()).offset()).isZero();
      assertThat(PageRequest.of(3, 50, PageSort.unsorted()).offset()).isEqualTo(150L);
    }

    /**
     * The reason this method exists rather than being left to the caller. The obvious expression is
     * an {@code int} multiplication, and it does not fail — it returns a <em>negative</em> offset
     * the database then rejects with a syntax error naming neither cause.
     */
    @Test
    void doesNotOverflowWhereTheIntExpressionWould() {
      PageRequest deep = PageRequest.of(20_000_000, 200, PageSort.unsorted());

      assertThat(deep.offset()).isEqualTo(4_000_000_000L);

      int asTheCallerWouldHaveWrittenIt = deep.page() * deep.size();
      assertThat(asTheCallerWouldHaveWrittenIt).isNegative();
    }

    @Test
    void staysPositiveAtTheExtreme() {
      PageRequest extreme = PageRequest.of(Integer.MAX_VALUE, 200, PageSort.unsorted());

      assertThat(extreme.offset()).isEqualTo((long) Integer.MAX_VALUE * 200).isPositive();
    }
  }

  @Nested
  @DisplayName("validatedAgainst")
  class ValidatedAgainst {

    @Test
    void returnsTheSortUnchangedWhenEveryPropertyIsAllowed() {
      PageSort asked =
          PageSort.of(List.of(new Order("sku", Direction.ASC), new Order("id", Direction.DESC)));
      PageRequest request = PageRequest.of(0, 10, asked);

      assertThat(request.validatedAgainst(SORTABLE)).isSameAs(asked);
    }

    @Test
    void anUnsortedRequestValidatesAgainstAnythingIncludingAnEmptyAllowlist() {
      PageRequest request = PageRequest.of(0, 10, PageSort.unsorted());

      assertThat(request.validatedAgainst(Set.of())).isSameAs(PageSort.unsorted());
    }

    @Test
    void anEmptyAllowlistMeansNothingIsSortable() {
      PageRequest request = PageRequest.of(0, 10, PageSort.by("sku", Direction.ASC));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(Set.of()))
          .withMessageContaining("'sku' is not sortable");
    }

    @Test
    void rejectsAPropertyThatIsNotAllowed() {
      PageRequest request = PageRequest.of(0, 10, PageSort.by("passwordHash", Direction.ASC));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE))
          .satisfies(
              thrown ->
                  assertThat(thrown.violations())
                      .containsExactly("sort[0].property: 'passwordHash' is not sortable"));
    }

    /**
     * RFC-0003 refused normalisation because SQL identifier folding is vendor-specific. Building
     * this surfaced a second reason and it is the security-relevant one: because the comparison is
     * exact, the surviving string is {@code equals} to the one the repository listed, so
     * interpolating the client's string is indistinguishable from interpolating our own. Under case
     * folding it would not be.
     */
    @Test
    void comparesExactlyAndCaseSensitively() {
      PageRequest request = PageRequest.of(0, 10, PageSort.by("SKU", Direction.ASC));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(
              () ->
                  PageRequest.of(0, 10, PageSort.by(" sku", Direction.ASC))
                      .validatedAgainst(SORTABLE));
    }

    /** Item 4.2's rule for FR-21's unknown properties: fixing one name per round trip is worse. */
    @Test
    void reportsEveryRejectedPropertyRatherThanTheFirst() {
      PageRequest request =
          PageRequest.of(
              0,
              10,
              PageSort.of(
                  List.of(
                      new Order("nope", Direction.ASC),
                      new Order("sku", Direction.ASC),
                      new Order("alsoNope", Direction.DESC))));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE))
          .satisfies(
              thrown ->
                  assertThat(thrown.violations())
                      .containsExactly(
                          "sort[0].property: 'nope' is not sortable",
                          "sort[2].property: 'alsoNope' is not sortable"));
    }

    /** {@code ORDER BY sku ASC, sku DESC} is legal SQL whose second clause does nothing. */
    @Test
    void rejectsAPropertyNamedTwice() {
      PageRequest request =
          PageRequest.of(
              0,
              10,
              PageSort.of(
                  List.of(new Order("sku", Direction.ASC), new Order("sku", Direction.DESC))));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE))
          .satisfies(
              thrown ->
                  assertThat(thrown.violations())
                      .containsExactly("sort[1].property: 'sku' is named more than once"));
    }

    /**
     * The allowlist bounds <em>which</em> columns and never <em>how many</em>, so a repository with
     * fifty sortable columns would otherwise accept a fifty-column {@code ORDER BY}.
     */
    @Test
    void rejectsMoreSortPropertiesThanTheCapAndReportsThatAlone() {
      Set<String> wide =
          IntStream.range(0, 20).mapToObj(index -> "c" + index).collect(Collectors.toSet());
      PageSort tooMany =
          PageSort.of(
              IntStream.range(0, 20)
                  .mapToObj(index -> new Order("c" + index, Direction.ASC))
                  .toList());

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> PageRequest.of(0, 10, tooMany).validatedAgainst(wide))
          .satisfies(
              thrown ->
                  assertThat(thrown.violations())
                      .containsExactly(
                          "sort: at most 8 sort properties are allowed, but 20 were given"));
    }

    @Test
    void acceptsExactlyTheCap() {
      Set<String> wide =
          IntStream.range(0, PageRequest.MAX_SORT_PROPERTIES)
              .mapToObj(index -> "c" + index)
              .collect(Collectors.toSet());
      PageSort atTheCap =
          PageSort.of(
              IntStream.range(0, PageRequest.MAX_SORT_PROPERTIES)
                  .mapToObj(index -> new Order("c" + index, Direction.ASC))
                  .toList());

      assertThat(PageRequest.of(0, 10, atTheCap).validatedAgainst(wide)).isSameAs(atTheCap);
    }

    /**
     * The structural half of the "one judge, one status code" rule: a blank property never reaches
     * an {@code IllegalArgumentException} at construction, it reaches the 400 door here.
     */
    @Test
    void aBlankPropertyIsRejectedAsClientInputRatherThanAsADefect() {
      PageRequest request = PageRequest.of(0, 10, PageSort.by("", Direction.ASC));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE))
          .satisfies(thrown -> assertThat(thrown).isNotInstanceOf(IllegalArgumentException.class));
    }

    /**
     * Control C-01, and the difference from {@code StrategyNotFoundException} is the whole point:
     * that one may list its known keys because FR-19 maps it to a 500 with no body, where this
     * message reaches the client inside a 400 that has one. An allowlist of column names is
     * internal schema.
     */
    @Test
    void namesWhatWasRejectedAndNeverWhatWasAllowed() {
      PageRequest request = PageRequest.of(0, 10, PageSort.by("nope", Direction.ASC));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage()).contains("nope");
                for (String allowed : SORTABLE) {
                  assertThat(thrown.getMessage())
                      .as("the allowlist entry '%s' leaked into a 400 body", allowed)
                      .doesNotContain(allowed);
                }
              });
    }

    @Test
    void stripsControlCharactersFromTheRejectedName() {
      String forged = "sku\r\n2026-01-01 WARNING admin logged in";
      PageRequest request = PageRequest.of(0, 10, PageSort.by(forged, Direction.ASC));

      assertThatExceptionOfType(ValidationException.class)
          .isThrownBy(() -> request.validatedAgainst(SORTABLE))
          .satisfies(
              thrown -> assertThat(thrown.getMessage()).doesNotContain("\n").doesNotContain("\r"));
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void refusesANullAllowlist() {
      PageRequest request = PageRequest.of(0, 10, PageSort.unsorted());

      assertThatNullPointerException().isThrownBy(() -> request.validatedAgainst(null));
    }
  }

  /**
   * ADR-0033, asserted structurally rather than editorially. The guarantee is not "remember to
   * validate": it is that no public member of this class hands back the sort, so a repository
   * cannot reach the client's strings without having supplied an allowlist. A convenience accessor
   * added later would compile and pass every other test in this file.
   */
  @Nested
  @DisplayName("the sort is unreachable without an allowlist")
  class TheSortIsUnreachable {

    @Test
    void publishesNoAccessorThatReturnsTheSort() {
      for (Method method : PageRequest.class.getMethods()) {
        if (!PageSort.class.isAssignableFrom(method.getReturnType())) {
          continue;
        }
        assertThat(method.getParameterTypes())
            .as("public method %s returns a PageSort without taking an allowlist", method.getName())
            .containsExactly(Set.class);
        assertThat(method.getName()).isEqualTo("validatedAgainst");
      }
    }

    @Test
    void publishesNoAccessorThatReturnsASortOrder() {
      for (Method method : PageRequest.class.getMethods()) {
        assertThat(Order.class.isAssignableFrom(method.getReturnType()))
            .as("public method %s hands back a sort order", method.getName())
            .isFalse();
        assertThat(method.getName())
            .as("public method %s looks like a sort accessor", method.getName())
            .isNotEqualTo("sort")
            .isNotEqualTo("getSort")
            .isNotEqualTo("orders");
      }
    }

    @Test
    void hasNoPublicConstructorEither() {
      assertThat(PageRequest.class.getConstructors()).isEmpty();
    }

    /**
     * The formatting method is the one place this type would otherwise contradict itself — a {@code
     * log.debug("{}", request)} would put the names back within reach of the same repository that
     * is not allowed to read them.
     */
    @Test
    void doesNotRenderTheSortPropertiesInToString() {
      PageRequest request =
          PageRequest.of(
              7,
              25,
              PageSort.of(
                  List.of(
                      new Order("passwordHash", Direction.ASC), new Order("sku", Direction.DESC))));

      assertThat(request).hasToString("PageRequest[page=7, size=25, sortProperties=2]");
      assertThat(request.toString()).doesNotContain("passwordHash").doesNotContain("sku");
    }
  }

  /**
   * The threat model's <em>injection via {@code ORDER BY}</em> row, run against a real database
   * rather than argued. The companion half is what stops the assertion going vacuous: the same
   * payload interpolated <em>without</em> validating does exactly what it says.
   */
  @Nested
  @DisplayName("against a real database")
  class AgainstARealDatabase {

    @Test
    void aValidatedSortRendersIntoAnOrderByThatWorks() throws SQLException {
      String url = JdbcFixtures.freshUrl();
      try (Connection connection = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor executor = SimpleJdbcExecutor.on(connection);
        JdbcFixtures.createSchema(executor);
        executor.update("insert into orders (id, sku, quantity) values (?, ?, ?)", 1, "b", 5);
        executor.update("insert into orders (id, sku, quantity) values (?, ?, ?)", 2, "a", 9);

        PageRequest request = PageRequest.of(0, 10, PageSort.by("sku", Direction.ASC));
        PageSort validated = request.validatedAgainst(SORTABLE);

        List<JdbcFixtures.Order> rows =
            executor.query(
                "select id, sku, quantity from orders order by "
                    + renderOrderBy(validated)
                    + " limit ? offset ?",
                JdbcFixtures.TO_ORDER,
                request.size(),
                request.offset());

        assertThat(rows).extracting(JdbcFixtures.Order::sku).containsExactly("a", "b");
      }
    }

    @Test
    void aPropertyOutsideTheAllowlistNeverReachesTheClause() throws SQLException {
      String url = JdbcFixtures.freshUrl();
      try (Connection connection = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor executor = SimpleJdbcExecutor.on(connection);
        seedTwoOrdersWithSecretNotes(executor);

        PageRequest request = PageRequest.of(0, 10, PageSort.by("note", Direction.ASC));

        assertThatExceptionOfType(ValidationException.class)
            .isThrownBy(() -> request.validatedAgainst(SORTABLE));
      }
    }

    /**
     * The payload is not inert, which is the assertion the test above is worth nothing without —
     * and this is what a repository that skipped {@code validatedAgainst} would have shipped.
     *
     * <p>{@code note} is a column the allowlist deliberately omits and no query here ever selects.
     * Ordering by it still <strong>reorders the rows by its values</strong>, so a client who can
     * name a column can read a column: one comparison per request is exactly how blind extraction
     * works, and it leaves a perfectly ordinary-looking query in the log.
     */
    @Test
    void theSamePropertyLeaksAHiddenColumnWhenNobodyValidatedIt() throws SQLException {
      String url = JdbcFixtures.freshUrl();
      try (Connection connection = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor executor = SimpleJdbcExecutor.on(connection);
        seedTwoOrdersWithSecretNotes(executor);

        PageSort unvalidated = PageSort.by("note", Direction.ASC);
        List<JdbcFixtures.Order> leaked =
            executor.query(
                "select id, sku, quantity from orders order by " + renderOrderBy(unvalidated),
                JdbcFixtures.TO_ORDER);

        assertThat(leaked).extracting(JdbcFixtures.Order::id).containsExactly(2, 1);
        assertThat(
                executor.query(
                    "select id, sku, quantity from orders order by id", JdbcFixtures.TO_ORDER))
            .extracting(JdbcFixtures.Order::id)
            .containsExactly(1, 2);
      }
    }

    /**
     * The loud half of the same demonstration, and it survives the objection that a {@code
     * PreparedStatement} would stop it. It does not: a statement <em>string</em> holding a
     * semicolon is still one string, and H2 executes both halves of it through {@code
     * executeQuery}. The table is gone afterwards.
     *
     * <p>This is the residual item 4.3 stated and could not demonstrate — a caller who concatenates
     * before passing the SQL in is beyond what any Java API can stop — met at the one place FR-05
     * named as the reason it could not be closed: the {@code ORDER BY}, where a column name can
     * never be a bind parameter.
     *
     * <p><strong>H2's own error message named only the first statement</strong> while executing
     * both, which cost this test a wrong conclusion before the assertion corrected it. It is the
     * same lesson C-01 keeps producing from the other direction: a driver's text is evidence about
     * the driver, not about what happened.
     */
    @Test
    void theSamePayloadDropsTheTableWhenNobodyValidatedIt() throws SQLException {
      String url = JdbcFixtures.freshUrl();
      try (Connection connection = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor executor = SimpleJdbcExecutor.on(connection);
        seedTwoOrdersWithSecretNotes(executor);

        PageSort unvalidated = PageSort.by("sku; drop table orders --", Direction.ASC);
        executor.query(
            "select id, sku, quantity from orders order by " + renderOrderBy(unvalidated),
            JdbcFixtures.TO_ORDER);

        assertThatExceptionOfType(JdbcAccessException.class)
            .isThrownBy(
                () -> executor.query("select id, sku, quantity from orders", JdbcFixtures.TO_ORDER))
            .withMessageContaining("cannot run a query");
      }
    }

    /** The same payload, validated: it is refused and the table is still there. */
    @Test
    void theSemicolonPayloadIsRefusedByTheAllowlist() throws SQLException {
      String url = JdbcFixtures.freshUrl();
      try (Connection connection = JdbcFixtures.connect(url)) {
        SimpleJdbcExecutor executor = SimpleJdbcExecutor.on(connection);
        seedTwoOrdersWithSecretNotes(executor);

        PageRequest request =
            PageRequest.of(0, 10, PageSort.by("sku; drop table orders --", Direction.ASC));

        assertThatExceptionOfType(ValidationException.class)
            .isThrownBy(() -> request.validatedAgainst(SORTABLE));

        assertThat(executor.query("select id, sku, quantity from orders", JdbcFixtures.TO_ORDER))
            .hasSize(2);
      }
    }

    /** {@code note} is the hidden column; its values order the opposite way from {@code id}. */
    private static void seedTwoOrdersWithSecretNotes(SimpleJdbcExecutor executor) {
      JdbcFixtures.createSchema(executor);
      executor.update(
          "insert into orders (id, sku, quantity, note) values (?, ?, ?, ?)", 1, "a", 5, "zebra");
      executor.update(
          "insert into orders (id, sku, quantity, note) values (?, ?, ?, ?)", 2, "b", 9, "apple");
    }

    /** What a repository writes, and the only thing the whole design is protecting. */
    private static String renderOrderBy(PageSort sort) {
      StringBuilder clause = new StringBuilder();
      for (Order order : sort.orders()) {
        clause
            .append(clause.length() == 0 ? "" : ", ")
            .append(order.property())
            .append(' ')
            .append(order.direction());
      }
      return clause.toString();
    }
  }

  @Nested
  @DisplayName("value semantics")
  class ValueSemantics {

    @Test
    void twoRequestsForTheSameThingAreEqual() {
      PageRequest one = PageRequest.of(2, 50, PageSort.by("sku", Direction.ASC));
      PageRequest other = PageRequest.of(2, 50, PageSort.by("sku", Direction.ASC));

      assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }

    @Test
    void everyComponentIsPartOfIdentity() {
      PageRequest base = PageRequest.of(2, 50, PageSort.by("sku", Direction.ASC));

      assertThat(base).isNotEqualTo(PageRequest.of(3, 50, PageSort.by("sku", Direction.ASC)));
      assertThat(base).isNotEqualTo(PageRequest.of(2, 51, PageSort.by("sku", Direction.ASC)));
      assertThat(base).isNotEqualTo(PageRequest.of(2, 50, PageSort.by("id", Direction.ASC)));
      assertThat(base).isNotEqualTo("PageRequest[page=2, size=50, sortProperties=1]");
    }

    /** The ceiling is not part of what a request <em>is</em>; it is how it was checked. */
    @Test
    void theCeilingIsNotCarried() {
      assertThat(PageRequest.of(0, 10, PageSort.unsorted(), 500))
          .isEqualTo(PageRequest.of(0, 10, PageSort.unsorted()));
    }
  }
}
