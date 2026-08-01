package it.d4np.utils;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * The null-safety helpers {@link java.util.Objects} does <em>not</em> already provide.
 *
 * <p><strong>That sentence is the whole selection rule, and it is a rule rather than a
 * wishlist.</strong> RFC-0001 fixed the set this way because the specification never enumerated
 * one, and "a utility class of null helpers" grows without a boundary. Anything the JDK ships —
 * {@link Objects#equals}, {@link Objects#deepEquals}, {@link Objects#hashCode}, {@link
 * Objects#toString(Object)}, {@link Objects#requireNonNull}, {@link Objects#requireNonNullElse},
 * {@link Objects#requireNonNullElseGet}, {@link Objects#isNull}, {@link Objects#nonNull}, {@link
 * Objects#compare} — is deliberately <strong>not</strong> re-exported here. Use {@code
 * java.util.Objects} for those; a second spelling of an existing method is a maintenance cost and a
 * code-review argument, not a feature.
 *
 * <p><strong>{@code isEmpty} is offered as typed overloads and never as {@code
 * isEmpty(Object)}.</strong> An {@code Object} parameter defers to run time what the compiler can
 * settle, and — worse — silently answers {@code false} for any type nobody thought to handle, so a
 * caller passing an {@code Optional} or a {@code String[]} through the wrong overload gets a
 * plausible wrong answer instead of a compile error.
 *
 * <p><strong>Thread safety.</strong> Stateless and static; safe from any thread.
 *
 * @see java.util.Objects
 */
public final class ObjectUtils {

  private ObjectUtils() {}

  /**
   * Whether any of {@code values} is {@code null}.
   *
   * @param values the values to check; the array itself must not be {@code null}
   * @return {@code true} if at least one element is {@code null}; {@code false} for an empty array
   * @throws NullPointerException if {@code values} itself is {@code null}
   */
  public static boolean anyNull(@Nullable Object... values) {
    Objects.requireNonNull(values, "values array must not be null");
    for (Object value : values) {
      if (value == null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether every one of {@code values} is non-null.
   *
   * <p>The exact negation of {@link #anyNull(Object...)}, offered separately so a call site reads
   * as the condition it is testing rather than as a negated one.
   *
   * @param values the values to check; the array itself must not be {@code null}
   * @return {@code true} if no element is {@code null}; {@code true} for an empty array
   * @throws NullPointerException if {@code values} itself is {@code null}
   */
  public static boolean allNonNull(@Nullable Object... values) {
    return !anyNull(values);
  }

  /**
   * Returns {@code value} if it holds a non-whitespace character, and fails otherwise.
   *
   * <p>The blank-string counterpart of {@link Objects#requireNonNull(Object, String)}, and the
   * argument check most often written by hand: a required configuration value that arrives as
   * {@code ""} is as absent as one that arrives as {@code null}, but only the second is caught by
   * {@code requireNonNull}.
   *
   * @param value the value to check
   * @param name what to call it in the failure message
   * @return {@code value}, unchanged and not trimmed
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws IllegalArgumentException if {@code value} is empty or only whitespace
   */
  public static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, () -> name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /**
   * Compares two values, ordering {@code null} before everything else.
   *
   * @param <T> the compared type
   * @param a the first value, possibly {@code null}
   * @param b the second value, possibly {@code null}
   * @return a negative number, zero or a positive number as {@code a} sorts before, with, or after
   *     {@code b}
   */
  public static <T extends Comparable<T>> int compareNullsFirst(@Nullable T a, @Nullable T b) {
    if (a == null) {
      return b == null ? 0 : -1;
    }
    return b == null ? 1 : a.compareTo(b);
  }

  /**
   * Compares two values, ordering {@code null} after everything else.
   *
   * @param <T> the compared type
   * @param a the first value, possibly {@code null}
   * @param b the second value, possibly {@code null}
   * @return a negative number, zero or a positive number as {@code a} sorts before, with, or after
   *     {@code b}
   */
  public static <T extends Comparable<T>> int compareNullsLast(@Nullable T a, @Nullable T b) {
    if (a == null) {
      return b == null ? 0 : 1;
    }
    return b == null ? -1 : a.compareTo(b);
  }

  /**
   * Whether {@code value} is {@code null} or holds no characters.
   *
   * <p>Note that a whitespace-only string is <strong>not</strong> empty; {@link
   * #requireNonBlank(String, String)} is the check that treats it as absent.
   *
   * @param value the sequence to check, possibly {@code null}
   * @return {@code true} if {@code null} or zero-length
   */
  public static boolean isEmpty(@Nullable CharSequence value) {
    return value == null || value.isEmpty();
  }

  /**
   * Whether {@code value} is {@code null} or holds no elements.
   *
   * @param value the collection to check, possibly {@code null}
   * @return {@code true} if {@code null} or empty
   */
  public static boolean isEmpty(@Nullable Collection<?> value) {
    return value == null || value.isEmpty();
  }

  /**
   * Whether {@code value} is {@code null} or holds no entries.
   *
   * @param value the map to check, possibly {@code null}
   * @return {@code true} if {@code null} or empty
   */
  public static boolean isEmpty(@Nullable Map<?, ?> value) {
    return value == null || value.isEmpty();
  }

  /**
   * Whether {@code value} is {@code null} or holds no elements.
   *
   * @param value the array to check, possibly {@code null}
   * @return {@code true} if {@code null} or zero-length
   */
  public static boolean isEmpty(@Nullable Object[] value) {
    return value == null || value.length == 0;
  }

  /**
   * The negation of {@link #isEmpty(CharSequence)}.
   *
   * @param value the sequence to check, possibly {@code null}
   * @return {@code true} if non-null and non-empty
   */
  public static boolean isNotEmpty(@Nullable CharSequence value) {
    return !isEmpty(value);
  }

  /**
   * The negation of {@link #isEmpty(Collection)}.
   *
   * @param value the collection to check, possibly {@code null}
   * @return {@code true} if non-null and non-empty
   */
  public static boolean isNotEmpty(@Nullable Collection<?> value) {
    return !isEmpty(value);
  }

  /**
   * The negation of {@link #isEmpty(Map)}.
   *
   * @param value the map to check, possibly {@code null}
   * @return {@code true} if non-null and non-empty
   */
  public static boolean isNotEmpty(@Nullable Map<?, ?> value) {
    return !isEmpty(value);
  }

  /**
   * The negation of {@link #isEmpty(Object[])}.
   *
   * @param value the array to check, possibly {@code null}
   * @return {@code true} if non-null and non-empty
   */
  public static boolean isNotEmpty(@Nullable Object[] value) {
    return !isEmpty(value);
  }
}
