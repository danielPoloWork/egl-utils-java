package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-14 (RFC-0002): the contract of {@link ValidationException}. */
@DisplayName("ValidationException")
class ValidationExceptionTest {

  @Test
  void readsNaturallyForOneViolation() {
    ValidationException thrown =
        new ValidationException("Account", List.of("name: must not be blank"));

    assertThat(thrown).hasMessage("Account: name: must not be blank");
  }

  @Test
  void countsTheViolationsWhenThereAreSeveral() {
    ValidationException thrown =
        new ValidationException("Account", List.of("name: blank", "age: too small"));

    assertThat(thrown)
        .hasMessageContaining("Account: 2 violations")
        .hasMessageContaining("name: blank")
        .hasMessageContaining("age: too small");
    assertThat(thrown.violations()).containsExactly("name: blank", "age: too small");
  }

  @Test
  void copiesTheViolationList() {
    List<String> mutable = new ArrayList<>(List.of("name: blank"));
    ValidationException thrown = new ValidationException("Account", mutable);

    mutable.add("added after the throw");

    assertThat(thrown.violations()).containsExactly("name: blank");
  }

  @Test
  void violationsIsUnmodifiable() {
    ValidationException thrown = new ValidationException("Account", List.of("name: blank"));

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
    ValidationException thrown = new ValidationException("Account", List.of("name: blank"));

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
        new ValidationException("Account", List.of("name: blank", "age: too small"));

    ValidationException restored = SerializationSupport.roundTrip(thrown);

    assertThat(restored).hasMessage(thrown.getMessage());
    assertThat(restored.violations()).containsExactly("name: blank", "age: too small");
  }
}
