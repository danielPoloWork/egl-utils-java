package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Unit} and {@code Result.ok()} — ROADMAP item 3.0 (FR-17, ADR-0019).
 *
 * <p>This closes the hole ADR-0012 recorded: a successful {@code Result} with no payload could not
 * be constructed at all, because {@code Void} is uninhabited and {@code Ok} rejects {@code null}.
 * The tests that matter here are the ones asserting the closure did not open a different hole —
 * that {@code Ok}'s null rejection is untouched, and that the singleton survives serialisation,
 * which it must because {@code Result} arms travel inside a {@code Serializable} {@code
 * BusinessException}.
 */
@DisplayName("Unit")
class UnitTest {

  @Test
  @DisplayName("Result.ok() produces an Ok carrying the single Unit value")
  void okProducesAnOkCarryingUnit() {
    Result<Unit> result = Result.ok();

    assertThat(result).isInstanceOf(Result.Ok.class);
    assertThat(((Result.Ok<Unit>) result).value()).isSameAs(Unit.INSTANCE);
  }

  @Test
  @DisplayName("the type has exactly one value")
  void hasExactlyOneValue() {
    assertThat(Unit.values()).containsExactly(Unit.INSTANCE);
  }

  @Test
  @DisplayName("it renders as () rather than INSTANCE, so a log line says something")
  void rendersAsUnitNotation() {
    assertThat(Unit.INSTANCE).hasToString("()");
    assertThat(String.valueOf(Result.ok())).contains("()");
  }

  @Test
  @DisplayName("the singleton survives serialisation — the reason it is an enum")
  void survivesSerialisation() throws Exception {
    // A final class with a public constant would need readResolve to hold this, and Result arms
    // travel inside BusinessException, which is Serializable by inheritance from Throwable. The
    // enum gets it from the language instead of from a method somebody has to remember to write.
    BusinessException original =
        new BusinessException(new ErrorDetail("deleted", "nothing to return"));

    BusinessException restored = SerializationSupport.roundTrip(original);

    assertThat(SerializationSupport.roundTrip(Unit.INSTANCE)).isSameAs(Unit.INSTANCE);
    assertThat(restored.error().code()).isEqualTo("deleted");
  }

  @Test
  @DisplayName("Unit does NOT make null sayable — Ok's rejection is untouched")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void doesNotMakeNullSayable() {
    // The point of the closure is that there is one way to say "nothing", not two. Result.ok()
    // goes through the same canonical constructor as every other payload.
    org.assertj.core.api.Assertions.assertThatNullPointerException()
        .isThrownBy(() -> Result.ok(null));
  }

  @Test
  @DisplayName("a Result<Unit> composes like any other Result")
  void composesLikeAnyOtherResult() {
    // If the unit success needed special handling in map/flatMap/recover it would not be a payload
    // at all, and the asymmetry ADR-0019 set out to remove would still be there in another form.
    Result<String> mapped = Result.ok().map(unit -> "done " + unit);

    assertThat(mapped).isEqualTo(Result.ok("done ()"));
  }
}
