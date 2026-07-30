package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The shared failure vocabulary of RFC-0001 / ADR-002 — ROADMAP item 2.1. */
@DisplayName("ErrorDetail")
class ErrorDetailTest {

  private static final String CODE = "ACC-01";
  private static final String MESSAGE = "insufficient funds";

  @Test
  @DisplayName("carries the code, the message and the cause it was given")
  void carriesItsThreeComponents() {
    Throwable cause = new IllegalStateException("balance snapshot stale");

    ErrorDetail detail = new ErrorDetail(CODE, MESSAGE, cause);

    assertThat(detail.code()).isEqualTo(CODE);
    assertThat(detail.message()).isEqualTo(MESSAGE);
    assertThat(detail.cause()).isSameAs(cause);
  }

  @Test
  @DisplayName("the two-argument constructor leaves the cause absent")
  void twoArgumentConstructorLeavesTheCauseAbsent() {
    ErrorDetail detail = new ErrorDetail(CODE, MESSAGE);

    assertThat(detail.code()).isEqualTo(CODE);
    assertThat(detail.message()).isEqualTo(MESSAGE);
    assertThat(detail.cause()).isNull();
  }

  @Test
  @DisplayName("is a value: two details with equal components are equal")
  void isAValue() {
    assertThat(new ErrorDetail(CODE, MESSAGE))
        .isEqualTo(new ErrorDetail(CODE, MESSAGE))
        .hasSameHashCodeAs(new ErrorDetail(CODE, MESSAGE))
        .isNotEqualTo(new ErrorDetail("ACC-02", MESSAGE));
  }

  @Test
  @DisplayName("toString names the code and message without unrolling the cause's stack trace")
  void toStringDoesNotUnrollTheStackTrace() {
    ErrorDetail detail = new ErrorDetail(CODE, MESSAGE, new IllegalStateException("boom"));

    String rendered = detail.toString();

    assertThat(rendered).contains(CODE, MESSAGE, "IllegalStateException", "boom");
    // A stack frame here would put internal paths into any log line that prints a detail.
    assertThat(rendered).doesNotContain("at it.d4np");
  }

  @Test
  @DisplayName("survives Java serialisation, which is why it implements Serializable at all")
  void roundTripsThroughJavaSerialization() throws Exception {
    ErrorDetail original = new ErrorDetail(CODE, MESSAGE);

    ErrorDetail restored = SerializationSupport.roundTrip(original);

    assertThat(restored).isEqualTo(original).isNotSameAs(original);
  }

  @Test
  @DisplayName("carries a serialisable cause through the round trip")
  void roundTripsWithACause() throws Exception {
    ErrorDetail original = new ErrorDetail(CODE, MESSAGE, new IllegalStateException("boom"));

    ErrorDetail restored = SerializationSupport.roundTrip(original);

    assertThat(restored.code()).isEqualTo(CODE);
    assertThat(restored.cause()).isInstanceOf(IllegalStateException.class).hasMessage("boom");
  }

  /**
   * RFC-0001 pins {@code cause} as the one nullable component. These tests pass {@code null}
   * deliberately, so NullAway is suppressed for the group rather than for the file — the narrowest
   * scope that still lets the contract be asserted (AGENTS.md §9).
   */
  @Nested
  @DisplayName("rejects a null code or message")
  @SuppressWarnings("NullAway")
  class RejectsNulls {

    @Test
    @DisplayName("a null code is rejected and the message names it")
    void nullCode() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ErrorDetail(null, MESSAGE))
          .withMessageContaining("code");
    }

    @Test
    @DisplayName("a null message is rejected and the message names it")
    void nullMessage() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ErrorDetail(CODE, null))
          .withMessageContaining("message");
    }

    @Test
    @DisplayName("the canonical constructor rejects them too, not only the convenience overload")
    void canonicalConstructorRejectsThemAsWell() {
      Throwable cause = new IllegalStateException("boom");

      assertThatNullPointerException().isThrownBy(() -> new ErrorDetail(null, MESSAGE, cause));
      assertThatNullPointerException().isThrownBy(() -> new ErrorDetail(CODE, null, cause));
    }

    @Test
    @DisplayName("a null cause is accepted — it is the one nullable component")
    void nullCauseIsAccepted() {
      assertThat(new ErrorDetail(CODE, MESSAGE, null).cause()).isNull();
    }
  }
}
