package it.d4np.utils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The "here are the keys that <em>do</em> exist" half of a failed keyed lookup, shared by {@link
 * StrategyNotFoundException} and {@link FactoryKeyNotFoundException}.
 *
 * <p><strong>Why this is extracted rather than written twice.</strong> FR-04 and FR-01
 * independently require an exception whose message lists the known keys, and the behaviour is
 * subtler than it looks: the keys are rendered to text, sorted, truncated at a cap, and snapshotted
 * into a serialisable set — four decisions ({@link StrategyNotFoundException} documents why each)
 * that two copies would drift apart on. The first divergence would be silent, because each
 * exception has its own tests and both would still pass.
 *
 * <p>Package-private and non-instantiable: this is an implementation detail of two exceptions, not
 * a utility offered to consumers.
 */
final class KeyDiagnostics {

  /** How many known keys a message lists before it truncates. */
  static final int MAX_KEYS_IN_MESSAGE = 20;

  private KeyDiagnostics() {}

  /**
   * Renders, sorts and freezes a key collection.
   *
   * <p>Sorted on the <em>rendered</em> form rather than on the key type: keys are unconstrained, so
   * {@link Comparable} cannot be assumed, and a deterministic message is worth more here than the
   * key type's own ordering would be. {@link Collections#unmodifiableSet(Set)} over a {@link
   * LinkedHashSet} rather than {@link Set#copyOf(Collection)} because both are serialisable and
   * immutable to the caller, but only the former keeps the sort order that makes two log lines
   * comparable by eye.
   *
   * @param keys the keys present at the moment of the failure; copied, not retained
   * @return an unmodifiable, sorted set of rendered keys
   */
  static Set<String> snapshot(Collection<?> keys) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(render(keys)));
  }

  /**
   * Builds the exception message eagerly, because {@link Throwable} has no lazy message.
   *
   * <p>Truncation is stated rather than silent — a reader who sees "and 980 more" knows to ask the
   * exception for the rest, whereas a list that simply stopped at the cap would look complete.
   *
   * @param subject what was being looked up, singular and lower-case ("strategy", "supplier")
   * @param key the key that missed
   * @param knownKeys the keys that were present
   * @return the full message
   */
  static String describe(String subject, Object key, Collection<?> knownKeys) {
    List<String> rendered = render(knownKeys);
    StringBuilder message =
        new StringBuilder("no ")
            .append(subject)
            .append(" registered for key [")
            .append(String.valueOf(key))
            .append(']');
    if (rendered.isEmpty()) {
      return message.append("; nothing is registered").toString();
    }
    message.append("; ").append(rendered.size()).append(" known: ");
    message.append(
        rendered.stream().limit(MAX_KEYS_IN_MESSAGE).collect(Collectors.joining(", ", "[", "")));
    int hidden = rendered.size() - MAX_KEYS_IN_MESSAGE;
    return message.append(hidden > 0 ? ", and " + hidden + " more]" : "]").toString();
  }

  private static List<String> render(Collection<?> keys) {
    return Objects.requireNonNull(keys, "knownKeys").stream()
        .map(String::valueOf)
        .sorted()
        .collect(Collectors.toList());
  }
}
