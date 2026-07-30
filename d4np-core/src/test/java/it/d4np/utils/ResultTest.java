package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The value channel of the ADR-002 error model — ROADMAP item 2.1.
 *
 * <p>Every operation is asserted on <em>both</em> arms, because the interesting half of this
 * contract is what an {@code Err} does <em>not</em> do: it must not invoke the caller's function,
 * and it must carry the original {@link ErrorDetail} rather than a copy of it.
 */
@DisplayName("Result")
class ResultTest {

  private static final ErrorDetail ERROR = new ErrorDetail("ACC-01", "insufficient funds");

  // --- construction -----------------------------------------------------------------------------

  @Test
  @DisplayName("ok() produces an Ok carrying the payload")
  void okCarriesItsPayload() {
    assertThat(Result.ok("payload")).isEqualTo(new Result.Ok<>("payload"));
  }

  @Test
  @DisplayName("err() produces an Err carrying the very ErrorDetail it was given")
  void errCarriesItsDetail() {
    Result<String> result = Result.err(ERROR);

    assertThat(result).isEqualTo(new Result.Err<String>(ERROR));
    assertThat(errorOf(result)).isSameAs(ERROR);
  }

  @Test
  @DisplayName("is sealed over exactly Ok and Err, so an instanceof chain over both is exhaustive")
  void permitsExactlyTwoArms() {
    assertThat(Result.class.isSealed()).isTrue();
    assertThat(Result.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(Result.Ok.class, Result.Err.class);
  }

  @Test
  @DisplayName("both arms are records, which is what makes them immutable values")
  void bothArmsAreRecords() {
    assertThat(Result.Ok.class.isRecord()).isTrue();
    assertThat(Result.Err.class.isRecord()).isTrue();
  }

  // --- map --------------------------------------------------------------------------------------

  @Test
  @DisplayName("map transforms the payload of an Ok")
  void mapTransformsAnOk() {
    assertThat(Result.ok("abc").map(String::length)).isEqualTo(new Result.Ok<>(3));
  }

  @Test
  @DisplayName("map on an Err re-types the failure without invoking the mapper")
  void mapLeavesAnErrUntouched() {
    AtomicBoolean invoked = new AtomicBoolean();
    Result<String> failure = Result.err(ERROR);

    Result<Integer> mapped =
        failure.map(
            value -> {
              invoked.set(true);
              return value.length();
            });

    assertThat(invoked).isFalse();
    assertThat(errorOf(mapped)).isSameAs(ERROR);
  }

  // --- flatMap ----------------------------------------------------------------------------------

  @Test
  @DisplayName("flatMap returns the Result the mapper produced, not a nested one")
  void flatMapChainsAnOk() {
    Result<Integer> chained = Result.ok("abc").flatMap(value -> Result.ok(value.length()));

    assertThat(chained).isEqualTo(new Result.Ok<>(3));
  }

  @Test
  @DisplayName("flatMap propagates a failure the mapper itself returns")
  void flatMapPropagatesTheMappersFailure() {
    Result<Integer> chained = Result.ok("abc").flatMap(value -> Result.err(ERROR));

    assertThat(errorOf(chained)).isSameAs(ERROR);
  }

  @Test
  @DisplayName("flatMap on an Err re-types the failure without invoking the mapper")
  void flatMapLeavesAnErrUntouched() {
    AtomicBoolean invoked = new AtomicBoolean();
    Result<String> failure = Result.err(ERROR);

    Result<Integer> chained =
        failure.flatMap(
            value -> {
              invoked.set(true);
              return Result.ok(value.length());
            });

    assertThat(invoked).isFalse();
    assertThat(errorOf(chained)).isSameAs(ERROR);
  }

  // --- recover ----------------------------------------------------------------------------------

  @Test
  @DisplayName("recover turns an Err into an Ok, and is handed the ErrorDetail")
  void recoverTurnsAnErrIntoAnOk() {
    Result<String> recovered = Result.<String>err(ERROR).recover(ErrorDetail::code);

    assertThat(recovered).isEqualTo(new Result.Ok<>("ACC-01"));
  }

  @Test
  @DisplayName("recover returns an Ok unchanged without invoking the recovery")
  void recoverLeavesAnOkUntouched() {
    AtomicBoolean invoked = new AtomicBoolean();
    Result<String> success = Result.ok("payload");

    Result<String> recovered =
        success.recover(
            detail -> {
              invoked.set(true);
              return detail.code();
            });

    assertThat(invoked).isFalse();
    assertThat(recovered).isSameAs(success);
  }

  // --- orElseThrow ------------------------------------------------------------------------------

  @Test
  @DisplayName("orElseThrow returns the payload of an Ok without invoking the mapper")
  void orElseThrowReturnsAnOkPayload() {
    AtomicBoolean invoked = new AtomicBoolean();

    String value =
        Result.ok("payload")
            .orElseThrow(
                detail -> {
                  invoked.set(true);
                  return new IllegalStateException(detail.code());
                });

    assertThat(value).isEqualTo("payload");
    assertThat(invoked).isFalse();
  }

  @Test
  @DisplayName("orElseThrow bridges to the exception channel — the ADR-002 boundary idiom")
  void orElseThrowThrowsForAnErr() {
    Result<String> failure = Result.err(ERROR);

    Throwable thrown = catchThrowable(() -> failure.orElseThrow(BusinessException::new));

    assertThat(thrown).isInstanceOf(BusinessException.class).hasMessage("insufficient funds");
    assertThat(((BusinessException) thrown).error()).isSameAs(ERROR);
  }

  // --- the null boundary ------------------------------------------------------------------------

  @Test
  @DisplayName("a mapper that returns null is a defect, not an outcome")
  void aMapperReturningNullIsRejected() {
    assertThatNullPointerException().isThrownBy(() -> Result.ok("abc").map(value -> null));
    assertThatNullPointerException()
        .isThrownBy(() -> Result.ok("abc").flatMap(value -> null))
        .withMessageContaining("flatMap");
    assertThatNullPointerException()
        .isThrownBy(() -> Result.<String>err(ERROR).recover(detail -> null));
    assertThatNullPointerException()
        .isThrownBy(() -> Result.<String>err(ERROR).orElseThrow(detail -> null))
        .withMessageContaining("exceptionMapper");
  }

  /**
   * Each operation validates its function argument <em>before</em> inspecting the arm, so the
   * failure mode does not depend on which arm the caller happens to hold — the discipline {@link
   * java.util.Optional} follows. Asserting it on both arms is what makes that real rather than
   * incidental.
   */
  @Nested
  @DisplayName("rejects null arguments on both arms")
  @SuppressWarnings("NullAway")
  class RejectsNulls {

    @Test
    @DisplayName("Ok(null) is forbidden — a null is an absent value, not an outcome")
    void okRejectsANullPayload() {
      assertThatNullPointerException()
          .isThrownBy(() -> Result.ok(null))
          .withMessageContaining("null payload");
      assertThatNullPointerException().isThrownBy(() -> new Result.Ok<>(null));
    }

    @Test
    @DisplayName("an Err must carry an ErrorDetail")
    void errRejectsANullDetail() {
      assertThatNullPointerException()
          .isThrownBy(() -> Result.err(null))
          .withMessageContaining("ErrorDetail");
      assertThatNullPointerException().isThrownBy(() -> new Result.Err<>(null));
    }

    @Test
    @DisplayName("a null function is rejected on an Ok")
    void okRejectsNullFunctions() {
      Result<String> success = Result.ok("payload");

      assertThatNullPointerException().isThrownBy(() -> success.map(null));
      assertThatNullPointerException().isThrownBy(() -> success.flatMap(null));
      assertThatNullPointerException().isThrownBy(() -> success.recover(null));
      assertThatNullPointerException().isThrownBy(() -> success.orElseThrow(null));
    }

    @Test
    @DisplayName("a null function is rejected on an Err just as loudly")
    void errRejectsNullFunctions() {
      Result<String> failure = Result.err(ERROR);

      assertThatNullPointerException().isThrownBy(() -> failure.map(null));
      assertThatNullPointerException().isThrownBy(() -> failure.flatMap(null));
      assertThatNullPointerException().isThrownBy(() -> failure.recover(null));
      assertThatNullPointerException().isThrownBy(() -> failure.orElseThrow(null));
    }
  }

  /** Reads the detail out of a failure, asserting the arm on the way — no cast at the call site. */
  private static ErrorDetail errorOf(Result<?> result) {
    assertThat(result).isInstanceOf(Result.Err.class);
    return ((Result.Err<?>) result).error();
  }
}
