package it.d4np.utils;

import java.util.Collection;
import java.util.Set;

/**
 * No supplier is registered under the requested key — thrown by {@link
 * GenericFactory#create(Object)}.
 *
 * <p><strong>A wiring defect, not a business outcome</strong>, which is why it extends {@link
 * RuntimeException} directly and never {@link BusinessException}. The reasoning is the same one
 * {@link StrategyNotFoundException} records: a `BusinessException` is a rule the caller broke and
 * FR-19 maps it to <strong>422</strong>, while an unbound factory key means a module never
 * registered itself and belongs in FR-19's <strong>500</strong> fallback. No end user can act on it
 * and no caller can sensibly branch on it.
 *
 * <p><strong>It is a sibling of {@code StrategyNotFoundException}, not a subclass.</strong> The two
 * describe different failures of different types and a consumer catching one should not silently
 * catch the other; sharing a supertype only to share four lines of accessor would put that coupling
 * in the published hierarchy forever. What they <em>do</em> share is the diagnostic itself, through
 * the package-private {@link KeyDiagnostics}, so the message format, the sort, the truncation and
 * the serialisable snapshot cannot drift between them.
 *
 * <p><strong>Keys are captured as text</strong>, for the reason {@link StrategyNotFoundException}
 * spells out: every {@link Throwable} is {@link java.io.Serializable}, so a field of the factory's
 * key type would make this exception serialisable only when the consumer's key type happened to be
 * — failing silently, and only in the hosts that serialise. {@link #knownKeys()} carries the
 * complete set while {@link #getMessage()} truncates.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries. The key set it is handed is copied at construction.
 *
 * @see GenericFactory#create(Object)
 * @see StrategyNotFoundException
 */
public final class FactoryKeyNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The rendered key that missed; never null. */
  private final String key;

  /** Every key bound when this was thrown, rendered, sorted and unmodifiable. */
  private final Set<String> knownKeys;

  /**
   * Package-private on purpose: consumers <em>catch</em> this, they do not throw it.
   *
   * @param key the key that had no supplier; must not be {@code null}
   * @param knownKeys the factory's keys at the moment of the failure; must not be {@code null}, may
   *     be empty, and is copied rather than retained
   */
  FactoryKeyNotFoundException(Object key, Collection<?> knownKeys) {
    super(KeyDiagnostics.describe("supplier", key, knownKeys));
    this.key = String.valueOf(key);
    this.knownKeys = KeyDiagnostics.snapshot(knownKeys);
  }

  /**
   * The key that had no supplier registered.
   *
   * @return the key rendered with {@link String#valueOf(Object)}; never {@code null}
   */
  public String key() {
    return key;
  }

  /**
   * Every key that <em>was</em> bound when the lookup failed.
   *
   * <p>Complete and untruncated, unlike {@link #getMessage()}, and sorted so that two occurrences
   * are comparable by eye.
   *
   * @return an unmodifiable, sorted set of rendered keys; never {@code null}, possibly empty
   */
  public Set<String> knownKeys() {
    return knownKeys;
  }
}
