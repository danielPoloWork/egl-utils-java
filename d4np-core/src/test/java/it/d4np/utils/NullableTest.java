package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The nullability marker item 1.11 deferred to "the first nullable member" — ROADMAP item 2.1,
 * ADR-0011.
 *
 * <p>These are not tautologies about an annotation declaration. NullAway recognises a nullability
 * annotation by its fully-qualified name and reads it from the <em>declaration sites</em> javac
 * propagates it to; a target set missing one of those sites would leave the marker invisible
 * exactly where it matters, and the build would stay green because nothing else would then be
 * nullable. So the propagation is asserted rather than assumed.
 */
@DisplayName("Nullable")
class NullableTest {

  @Test
  @DisplayName("is retained at runtime so reflective consumer tooling can see it")
  void isRetainedAtRuntime() {
    Retention retention = Nullable.class.getAnnotation(Retention.class);

    assertThat(retention).isNotNull();
    assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
  }

  @Test
  @DisplayName("targets exactly the declaration sites a record component propagates to")
  void targetsTheDeclarationSites() {
    Target target = Nullable.class.getAnnotation(Target.class);

    assertThat(target).isNotNull();
    assertThat(target.value())
        .containsExactlyInAnyOrder(
            ElementType.METHOD,
            ElementType.PARAMETER,
            ElementType.FIELD,
            ElementType.RECORD_COMPONENT);
  }

  @Test
  @DisplayName("propagates from ErrorDetail's cause component to accessor, field and parameter")
  void propagatesToEveryDeclarationSite() throws Exception {
    Constructor<ErrorDetail> canonical =
        ErrorDetail.class.getDeclaredConstructor(String.class, String.class, Throwable.class);

    assertThat(ErrorDetail.class.getRecordComponents()[2].isAnnotationPresent(Nullable.class))
        .as("the record component itself")
        .isTrue();
    assertThat(ErrorDetail.class.getMethod("cause").isAnnotationPresent(Nullable.class))
        .as("the accessor NullAway reads at a call site")
        .isTrue();
    assertThat(ErrorDetail.class.getDeclaredField("cause").isAnnotationPresent(Nullable.class))
        .as("the field")
        .isTrue();
    assertThat(canonical.getParameters()[2].isAnnotationPresent(Nullable.class))
        .as("the canonical constructor parameter")
        .isTrue();
  }

  @Test
  @DisplayName("is absent from the non-null components, so its presence means something")
  void isAbsentFromNonNullComponents() throws Exception {
    assertThat(ErrorDetail.class.getMethod("code").isAnnotationPresent(Nullable.class)).isFalse();
    assertThat(ErrorDetail.class.getMethod("message").isAnnotationPresent(Nullable.class))
        .isFalse();
  }
}
