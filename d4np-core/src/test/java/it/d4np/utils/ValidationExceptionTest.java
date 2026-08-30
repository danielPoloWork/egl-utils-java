package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FR-14 (RFC-0002) and FR-07 (RFC-0003, ADR-0034): the contract of {@link ValidationException}. */
@DisplayName("ValidationException")
class ValidationExceptionTest {

  @Test
  void readsNaturallyForOneViolation() {
    ValidationException thrown =
        ValidationException.of("Account", List.of("name: must not be blank"));

    assertThat(thrown).hasMessage("Account: name: must not be blank");
  }

  @Test
  void countsTheViolationsWhenThereAreSeveral() {
    ValidationException thrown =
        ValidationException.of("Account", List.of("name: blank", "age: too small"));

    assertThat(thrown)
        .hasMessageContaining("Account: 2 violations")
        .hasMessageContaining("name: blank")
        .hasMessageContaining("age: too small");
    assertThat(thrown.violations()).containsExactly("name: blank", "age: too small");
  }

  @Test
  void copiesTheViolationList() {
    List<String> mutable = new ArrayList<>(List.of("name: blank"));
    ValidationException thrown = ValidationException.of("Account", mutable);

    mutable.add("added after the throw");

    assertThat(thrown.violations()).containsExactly("name: blank");
  }

  @Test
  void violationsIsUnmodifiable() {
    ValidationException thrown = ValidationException.of("Account", List.of("name: blank"));

    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> thrown.violations().add("smuggled in"));
  }

  /**
   * FR-19 maps {@code BusinessException} to 422 and validation to 400, so the two hierarchies must
   * stay apart — an accidental {@code extends BusinessException} would silently relabel every
   * validation failure.
   */
  @Test
  void isNotABusinessException() {
    ValidationException thrown = ValidationException.of("Account", List.of("name: blank"));

    assertThat(thrown)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(BusinessException.class);
  }

  /**
   * The reason the violations are {@code String}: this round trip has to succeed whatever type was
   * being validated, including a type that is not itself serialisable.
   */
  @Test
  void isSerializable() throws Exception {
    ValidationException thrown =
        ValidationException.of("Account", List.of("name: blank", "age: too small"));

    ValidationException restored = SerializationSupport.roundTrip(thrown);

    assertThat(restored).hasMessage(thrown.getMessage());
    assertThat(restored.violations()).containsExactly("name: blank", "age: too small");
  }

  /**
   * ADR-0034: the public mint that item 4.5 had to open, because {@code d4np-jdbc} reaches FR-07's
   * verdict with no Bean Validation provider anywhere in the picture and the constructor was
   * package-private.
   */
  @Nested
  @DisplayName("the door a module outside core comes through")
  class TheMint {

    @Test
    void refusesAnExceptionThatSaysNothingWasWrong() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> ValidationException.of("PageRequest", List.of()))
          .withMessageContaining("at least one violation");
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void refusesNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> ValidationException.of(null, List.of("x: bad")));
      assertThatNullPointerException().isThrownBy(() -> ValidationException.of("Account", null));
      assertThatNullPointerException()
          .isThrownBy(() -> ValidationException.of("Account", singletonListOfNull()));
    }

    /**
     * Control C-01 on the mint. A violation about client input necessarily quotes some of it —
     * FR-07's names the rejected sort property — and a name holding {@code \r\n} folds one log line
     * into two, which is forgery rather than formatting. Bounding inside the exception is
     * ADR-0022's rule applied to a mint: a guarantee each caller has to remember is advisory.
     */
    @Test
    void stripsControlCharactersFromEveryString() {
      ValidationException thrown =
          ValidationException.of(
              "Page\nRequest",
              List.of("sort: 'a\r\n2026-01-01 INFO admin logged in' is not sortable"));

      assertThat(thrown.getMessage()).doesNotContain("\n").doesNotContain("\r");
      assertThat(thrown.violations().get(0)).doesNotContain("\n").doesNotContain("\r");
      assertThat(thrown.getMessage()).contains("PageRequest");
    }

    @Test
    void truncatesAViolationThatIsTooLong() {
      String enormous = "x".repeat(5_000);

      ValidationException thrown = ValidationException.of("Account", List.of(enormous));

      assertThat(thrown.violations().get(0))
          .hasSize(ValidationException.MAX_TEXT_LENGTH + 3)
          .endsWith("...");
    }

    /** Half a surrogate pair is not a character, and is not valid UTF-8 in an FR-19 body either. */
    @Test
    void neverCutsASurrogatePairInHalf() {
      String rocket = "🚀";
      String tooLong = "a".repeat(ValidationException.MAX_TEXT_LENGTH - 1) + rocket + "tail";

      ValidationException thrown = ValidationException.of("Account", List.of(tooLong));

      String violation = thrown.violations().get(0);
      assertThat(violation.chars().anyMatch(unit -> Character.isSurrogate((char) unit)))
          .as("a lone surrogate survived the truncation")
          .isFalse();
      assertThat(violation).isEqualTo("a".repeat(ValidationException.MAX_TEXT_LENGTH - 1) + "...");
    }

    /** A caller with ten thousand violations must not mint a two-megabyte message. */
    @Test
    void listsAtMostTwentyViolationsInTheMessage() {
      List<String> many =
          IntStream.range(0, 100).mapToObj(index -> "field" + index + ": bad").toList();

      ValidationException thrown = ValidationException.of("Account", many);

      assertThat(thrown).hasMessageContaining("100 violations").hasMessageContaining("and 80 more");
      assertThat(thrown.getMessage()).contains("field19: bad").doesNotContain("field20: bad");
      assertThat(thrown.violations()).hasSize(100);
    }

    /**
     * The two doors must not drift: bounding was added for the mint and applies to both, so the
     * provider path has to be unchanged by it rather than assumed to be.
     */
    @Test
    void producesTheSameExceptionTheProviderDoorDoes() {
      List<String> violations = List.of("name: {jakarta.validation.constraints.NotBlank.message}");

      ValidationException minted = ValidationException.of("Account", violations);
      ValidationException fromProvider = ValidationException.fromProvider("Account", violations);

      assertThat(minted.getMessage()).isEqualTo(fromProvider.getMessage());
      assertThat(minted.violations()).isEqualTo(fromProvider.violations());
      assertThat(fromProvider.violations()).isEqualTo(violations);
    }
  }

  private static List<String> singletonListOfNull() {
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    return withNull;
  }
}
