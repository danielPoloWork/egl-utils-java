package it.d4np.utils;

import java.util.Collection;
import java.util.Set;

/**
 * No strategy is registered under the requested key — thrown by {@link
 * StrategyRegistry#getOrThrow(Object)}.
 *
 * <p><strong>This is a wiring defect, not a business outcome</strong>, and the distinction is the
 * reason it does <em>not</em> extend {@link BusinessException}. A missing strategy means some
 * module failed to register itself, or a caller passed a key nobody owns; no end user can act on it
 * and no caller can sensibly branch on it. FR-19's mapping table makes the same split visible at
 * the boundary: {@code BusinessException} becomes <strong>422</strong>, this becomes <strong>500
 * plus an alert</strong>. Were it a subclass, a handler whose {@code catch} clauses ran in the
 * wrong order would quietly report an operations failure as a client error.
 *
 * <p><strong>The known-key list is the whole point.</strong> A bare "not found" sends the reader to
 * the debugger; the list usually ends the investigation at the log line, because the intended key
 * is almost always visible next to the one that missed — a typo, a case difference, a module that
 * never registered. {@link #knownKeys()} carries the complete set, while {@link #getMessage()}
 * shows at most 20 of them: NFR-04 sizes this registry at 1000 strategies, and a thousand-key
 * exception message is not a diagnostic but an incident of its own.
 *
 * <p><strong>Keys are captured as text, deliberately.</strong> Every {@link Throwable} is {@link
 * java.io.Serializable}, so a field of the registry's key type {@code K} would make this exception
 * serialisable only when the consumer's key type happens to be — failing silently, and only in the
 * hosts that serialise (session replication, JMS, RMI). This is the same trap item 2.1 recorded for
 * {@code ErrorDetail}; rendering with {@link String#valueOf(Object)} at construction removes it,
 * costs nothing on a path that is already exceptional, and gives exactly what a diagnostic needs.
 * Adding a typed accessor later is a MINOR change under RFC-0001 §Versioning; removing one is not.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries (stack trace, suppressed exceptions). The key set it is handed is copied and sorted at
 * construction, so a registration racing this throw cannot mutate the captured list.
 *
 * @see StrategyRegistry#getOrThrow(Object)
 * @see BusinessException
 */
public final class StrategyNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The rendered key that missed; never null. */
  private final String key;

  /**
   * Every key registered when this was thrown, rendered, sorted and unmodifiable.
   *
   * <p>Built by {@link KeyDiagnostics#snapshot(Collection)}, which is shared with {@link
   * FactoryKeyNotFoundException} so the two cannot drift apart.
   */
  private final Set<String> knownKeys;

  /**
   * Package-private on purpose: consumers <em>catch</em> this, they do not throw it.
   *
   * <p>{@link StrategyRegistry} is the only thing that can decide a key is absent, so widening this
   * constructor would let a caller report a registry failure the registry never had. Publishing it
   * later is a MINOR change; retracting it would not be.
   *
   * @param key the key that had no strategy; must not be {@code null}
   * @param knownKeys the registry's keys at the moment of the failure; must not be {@code null},
   *     may be empty, and is copied rather than retained
   */
  StrategyNotFoundException(Object key, Collection<?> knownKeys) {
    super(KeyDiagnostics.describe("strategy", key, knownKeys));
    this.key = String.valueOf(key);
    this.knownKeys = KeyDiagnostics.snapshot(knownKeys);
  }

  /**
   * The key that had no strategy registered.
   *
   * @return the key rendered with {@link String#valueOf(Object)}; never {@code null}
   */
  public String key() {
    return key;
  }

  /**
   * Every key that <em>was</em> registered when the lookup failed.
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
