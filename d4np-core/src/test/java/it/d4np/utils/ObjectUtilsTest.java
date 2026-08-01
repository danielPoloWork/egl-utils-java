package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code ObjectUtils} against RFC-0001's FR-23 set — ROADMAP item 2.5.
 *
 * <p>FR-23 was fixed by a <em>rule</em> rather than a wishlist — "only what {@code
 * java.util.Objects} does not have" — so the last test here asserts the rule itself, not just the
 * members. Without it the class has nothing stopping it from growing a second spelling of {@code
 * requireNonNull}.
 */
@DisplayName("ObjectUtils")
class ObjectUtilsTest {

  @Nested
  @DisplayName("anyNull / allNonNull")
  @SuppressWarnings("NullAway") // the whole point of these helpers is to be handed nulls
  class NullChecks {

    @Test
    @DisplayName("anyNull is true when at least one value is null")
    void anyNullFindsANull() {
      assertThat(ObjectUtils.anyNull("a", null, "c")).isTrue();
      assertThat(ObjectUtils.anyNull("a", "b")).isFalse();
    }

    @Test
    @DisplayName("an empty argument list has no nulls, so anyNull is false and allNonNull is true")
    void emptyIsVacuouslyNonNull() {
      assertThat(ObjectUtils.anyNull()).isFalse();
      assertThat(ObjectUtils.allNonNull()).isTrue();
    }

    @Test
    @DisplayName("allNonNull is the exact negation of anyNull")
    void allNonNullNegatesAnyNull() {
      assertThat(ObjectUtils.allNonNull("a", "b")).isTrue();
      assertThat(ObjectUtils.allNonNull("a", null)).isFalse();
    }

    @Test
    @DisplayName("a null ARRAY is a defect, not an empty argument list")
    void rejectsANullArray() {
      // (Object[]) null is a different thing from no arguments, and answering "false" for it would
      // report a broken call site as a passing check.
      assertThatNullPointerException().isThrownBy(() -> ObjectUtils.anyNull((Object[]) null));
      assertThatNullPointerException().isThrownBy(() -> ObjectUtils.allNonNull((Object[]) null));
    }
  }

  @Nested
  @DisplayName("requireNonBlank")
  class RequireNonBlank {

    @Test
    @DisplayName("returns the value unchanged, without trimming it")
    void returnsTheValueUntrimmed() {
      assertThat(ObjectUtils.requireNonBlank("  padded  ", "name")).isEqualTo("  padded  ");
    }

    @Test
    @DisplayName("rejects empty and whitespace-only, naming the field")
    void rejectsBlank() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> ObjectUtils.requireNonBlank("", "username"))
          .withMessageContaining("username");
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> ObjectUtils.requireNonBlank("   \t\n ", "username"))
          .withMessageContaining("username");
    }

    @Test
    @DisplayName("rejects null with NullPointerException, not IllegalArgumentException")
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> ObjectUtils.requireNonBlank(null, "username"))
          .withMessageContaining("username");
    }
  }

  @Nested
  @DisplayName("compareNullsFirst / compareNullsLast")
  class Comparisons {

    @Test
    @DisplayName("nulls sort before values, and two nulls are equal")
    void nullsFirst() {
      assertThat(ObjectUtils.compareNullsFirst(null, "a")).isNegative();
      assertThat(ObjectUtils.compareNullsFirst("a", null)).isPositive();
      assertThat(ObjectUtils.compareNullsFirst((String) null, null)).isZero();
      assertThat(ObjectUtils.compareNullsFirst("a", "b")).isNegative();
    }

    @Test
    @DisplayName("nulls sort after values, and two nulls are equal")
    void nullsLast() {
      assertThat(ObjectUtils.compareNullsLast(null, "a")).isPositive();
      assertThat(ObjectUtils.compareNullsLast("a", null)).isNegative();
      assertThat(ObjectUtils.compareNullsLast((String) null, null)).isZero();
      assertThat(ObjectUtils.compareNullsLast("a", "b")).isNegative();
    }

    @Test
    @DisplayName("both are usable as a Comparator and actually sort a list with nulls in it")
    void sortAList() {
      List<String> first = Arrays.asList("b", null, "a");
      List<String> last = Arrays.asList("b", null, "a");

      first.sort(ObjectUtils::compareNullsFirst);
      last.sort(ObjectUtils::compareNullsLast);

      assertThat(first).containsExactly(null, "a", "b");
      assertThat(last).containsExactly("a", "b", null);
    }
  }

  @Nested
  @DisplayName("isEmpty / isNotEmpty")
  @SuppressWarnings("NullAway") // these helpers exist to accept nulls
  class Emptiness {

    @Test
    @DisplayName("CharSequence: null and \"\" are empty, whitespace is NOT")
    void charSequences() {
      assertThat(ObjectUtils.isEmpty((CharSequence) null)).isTrue();
      assertThat(ObjectUtils.isEmpty("")).isTrue();
      // A blank string is deliberately not "empty" -- requireNonBlank is the check that treats it
      // as absent, and conflating the two would make the pair of helpers ambiguous.
      assertThat(ObjectUtils.isEmpty("   ")).isFalse();
      assertThat(ObjectUtils.isNotEmpty("a")).isTrue();
    }

    @Test
    @DisplayName("Collection, Map and array: null or no elements")
    void collectionsMapsAndArrays() {
      assertThat(ObjectUtils.isEmpty((List<?>) null)).isTrue();
      assertThat(ObjectUtils.isEmpty(List.of())).isTrue();
      assertThat(ObjectUtils.isNotEmpty(List.of("a"))).isTrue();

      assertThat(ObjectUtils.isEmpty((Map<?, ?>) null)).isTrue();
      assertThat(ObjectUtils.isEmpty(Map.of())).isTrue();
      assertThat(ObjectUtils.isNotEmpty(Map.of("k", "v"))).isTrue();

      assertThat(ObjectUtils.isEmpty((Object[]) null)).isTrue();
      assertThat(ObjectUtils.isEmpty(new Object[0])).isTrue();
      assertThat(ObjectUtils.isNotEmpty(new Object[] {"a"})).isTrue();
    }

    @Test
    @DisplayName("the overloads are typed, so there is no isEmpty(Object) to bind to by accident")
    void offersNoObjectOverload() {
      // An Object overload would silently answer false for any type nobody considered -- an
      // Optional, a Stream, a primitive array -- turning a compile error into a wrong answer.
      Set<String> parameterTypes =
          Arrays.stream(ObjectUtils.class.getDeclaredMethods())
              .filter(m -> m.getName().equals("isEmpty") || m.getName().equals("isNotEmpty"))
              .map(m -> m.getParameterTypes()[0].getName())
              .collect(Collectors.toSet());

      assertThat(parameterTypes).doesNotContain("java.lang.Object");
      assertThat(parameterTypes)
          .containsExactlyInAnyOrder(
              "java.lang.CharSequence",
              "java.util.Collection",
              "java.util.Map",
              "[Ljava.lang.Object;");
    }
  }

  @Test
  @DisplayName("re-exports nothing java.util.Objects already provides — the FR-23 selection rule")
  void reExportsNothingFromObjects() {
    // FR-23 was fixed by a rule rather than a list, so the rule is what is asserted. Without this,
    // nothing stops the class growing a second spelling of requireNonNull.
    Set<String> jdkMethods =
        Arrays.stream(Objects.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
    Set<String> ourMethods =
        Arrays.stream(ObjectUtils.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertThat(ourMethods).isNotEmpty().doesNotContainAnyElementsOf(jdkMethods);
  }
}
