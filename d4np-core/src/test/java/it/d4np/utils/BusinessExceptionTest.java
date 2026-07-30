package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The exception channel of the ADR-002 error model — ROADMAP item 2.1. */
@DisplayName("BusinessException")
class BusinessExceptionTest {

  private static final ErrorDetail ERROR = new ErrorDetail("ACC-01", "insufficient funds");

  @Test
  @DisplayName("carries the detail, and derives its message and cause from it")
  void derivesMessageAndCauseFromTheDetail() {
    Throwable cause = new IllegalStateException("balance snapshot stale");
    ErrorDetail detail = new ErrorDetail("ACC-01", "insufficient funds", cause);

    BusinessException exception = new BusinessException(detail);

    assertThat(exception.error()).isSameAs(detail);
    assertThat(exception).hasMessage("insufficient funds").hasCause(cause);
  }

  @Test
  @DisplayName("a detail without a cause yields an exception without one")
  void noCauseInTheDetailMeansNoCauseOnTheException() {
    assertThat(new BusinessException(ERROR)).hasNoCause();
  }

  @Test
  @DisplayName("is unchecked, so it composes through streams and CompletableFuture chains (FR-18)")
  void isUnchecked() {
    assertThat(RuntimeException.class).isAssignableFrom(BusinessException.class);
  }

  @Test
  @DisplayName("is not final: FR-18 specifies a base a consuming domain subclasses")
  void isExtensible() {
    assertThat(Modifier.isFinal(BusinessException.class.getModifiers())).isFalse();
  }

  @Test
  @DisplayName("a subclass inherits the detail contract unchanged")
  void aSubclassInheritsTheDetailContract() {
    InsufficientFunds subclassed = new InsufficientFunds(ERROR);

    assertThat(subclassed).isInstanceOf(BusinessException.class).hasMessage("insufficient funds");
    assertThat(subclassed.error()).isSameAs(ERROR);
  }

  @Test
  @DisplayName(
      "survives Java serialisation with its detail — the reason ErrorDetail is Serializable")
  void roundTripsThroughJavaSerialization() throws Exception {
    BusinessException original = new BusinessException(ERROR);

    BusinessException restored = SerializationSupport.roundTrip(original);

    assertThat(restored).hasMessage("insufficient funds");
    assertThat(restored.error()).isEqualTo(ERROR).isNotSameAs(ERROR);
  }

  @Test
  @DisplayName("must carry an ErrorDetail")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsANullDetail() {
    assertThatNullPointerException()
        .isThrownBy(() -> new BusinessException(null))
        .withMessageContaining("ErrorDetail");
  }

  /** Stands in for a consuming domain's rule family — the FR-18 "base" claim, compiled. */
  private static final class InsufficientFunds extends BusinessException {

    private static final long serialVersionUID = 1L;

    InsufficientFunds(ErrorDetail error) {
      super(error);
    }
  }
}
