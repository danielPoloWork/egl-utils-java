package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code BuilderValidationException} — ROADMAP item 2.4 (FR-02, and FR-19's mapping table).
 *
 * <p>The hierarchy test here is the one worth reading: a builder violation looks like "validation",
 * which FR-19 maps to 400, and is not — the caller is this application's own code, not a client.
 */
@DisplayName("BuilderValidationException")
class BuilderValidationExceptionTest {

  @Test
  @DisplayName("lists every violation, in the order they were recorded")
  void listsEveryViolation() {
    BuilderValidationException thrown =
        new BuilderValidationException("OrderBuilder", List.of("a is required", "b is required"));

    assertThat(thrown.violations()).containsExactly("a is required", "b is required");
    assertThat(thrown)
        .hasMessageContaining("OrderBuilder")
        .hasMessageContaining("2 violations")
        .hasMessageContaining("a is required");
  }

  @Test
  @DisplayName("reads naturally for a single violation, without the count")
  void readsNaturallyForOneViolation() {
    BuilderValidationException thrown =
        new BuilderValidationException("OrderBuilder", List.of("customer is required"));

    assertThat(thrown).hasMessage("OrderBuilder: customer is required");
  }

  @Test
  @DisplayName("violations() is unmodifiable")
  void violationsIsUnmodifiable() {
    List<String> violations = new BuilderValidationException("B", List.of("x")).violations();

    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> violations.add("y"));
  }

  @Test
  @DisplayName("copies the list, so a reused builder cannot rewrite the report")
  void copiesTheViolationList() {
    List<String> live = new ArrayList<>(List.of("x"));

    BuilderValidationException thrown = new BuilderValidationException("B", live);
    live.clear();

    assertThat(thrown.violations()).containsExactly("x");
  }

  @Test
  @DisplayName("is NOT a BusinessException — a missing field is our bug, not the client's")
  void isNotABusinessException() {
    // FR-19 maps "validation" to 400, but that is FR-14's Validator, checking data from a client.
    // A builder violation means this application's own code forgot to set a field before build().
    // Reporting it as 400 would misattribute the fault.
    BuilderValidationException thrown = new BuilderValidationException("B", List.of("x"));

    assertThat(thrown)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("survives a serialisation round trip")
  void isSerializable() throws Exception {
    BuilderValidationException original =
        new BuilderValidationException("OrderBuilder", List.of("a is required"));

    BuilderValidationException restored = SerializationSupport.roundTrip(original);

    assertThat(restored.violations()).containsExactly("a is required");
    assertThat(restored).hasMessage(original.getMessage());
  }
}
