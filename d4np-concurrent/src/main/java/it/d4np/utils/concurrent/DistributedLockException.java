package it.d4np.utils.concurrent;

import java.util.Objects;

/**
 * A lock backend was unreachable or answered incorrectly (FR-10, RFC-0004 §FR-10).
 *
 * <p>Unchecked and extending {@link RuntimeException} <strong>directly, not {@code
 * BusinessException}</strong>: this is an infrastructure fault, and FR-19 maps it to a <strong>500
 * plus an alert</strong> where {@code BusinessException} means 422. It is {@code
 * JdbcAccessException}'s shape ported to a second backend.
 *
 * <p><strong>Failing to acquire a lock is not this.</strong> That is {@link
 * java.util.Optional#empty()} — an ordinary outcome the caller branches on. This type is for the
 * backend being broken.
 *
 * <h2>The key is carried beside the message, never inside it</h2>
 *
 * <p>A lock key is very often an identifier — {@code order:tenant-42:user-7} is the shape every
 * tutorial uses — so it is exactly the input compliance control <strong>C-01</strong> governs.
 * {@link #getMessage()} therefore names only the <em>operation</em> and the backend failure's
 * <em>type</em>, both drawn from a closed set this library controls, and never the key or the
 * backend's own text. The key is available from {@link #key()} for a handler that deliberately
 * wants it, which is the same split RFC-0001 draws between {@code ErrorDetail.message}
 * (caller-facing) and {@code ErrorDetail.cause} (in-process): correlating a log line is a decision,
 * not an accident.
 *
 * <p><strong>The bounding lives in this type rather than in each thrower</strong>, and that is not
 * a stylistic choice. Every thrower is in <em>another module</em> — {@code d4np-lock-redisson}
 * today, anyone's implementation tomorrow — so a rule that each of them must remember is advisory
 * by construction (ADR-0022). Both factories funnel through one private constructor that strips ISO
 * control characters and truncates, so a key containing {@code \r\n} cannot fold one log line into
 * two no matter who threw it. This is ADR-0034's shape: a public mint whose obligations are
 * enforced inside the type.
 *
 * @see DistributedLock
 */
public final class DistributedLockException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Long enough to identify a lock in a log, short enough that it cannot flood one. */
  private static final int MAX_KEY_LENGTH = 64;

  /** What was being attempted. A closed set, so the label can never be caller-supplied text. */
  private static final String ACQUIRE = "acquire";

  private static final String RELEASE = "release";

  private final String key;

  private DistributedLockException(String operation, String key, Throwable cause) {
    // The cause is attached so it reaches a log through the standard mechanisms, and its message is
    // deliberately NOT read into ours -- item 4.3 measured a driver putting an entire statement in
    // there, and a lock backend is no more careful.
    super("distributed lock " + operation + " failed: " + cause.getClass().getName(), cause);
    this.key = bound(key);
  }

  /**
   * The lock backend refused or failed an acquisition attempt.
   *
   * <p>Public because every thrower is in another module; see the class Javadoc.
   *
   * @param key the lock key; bounded and stripped inside this type
   * @param cause what the backend client threw
   * @return the exception to throw
   */
  public static DistributedLockException acquireFailed(String key, Throwable cause) {
    return new DistributedLockException(
        ACQUIRE, Objects.requireNonNull(key, "key"), Objects.requireNonNull(cause, "cause"));
  }

  /**
   * A release failed.
   *
   * <p><strong>Exists to be logged, not thrown.</strong> {@link LockHandle#close()} must not throw,
   * so an implementation whose release fails constructs one of these and logs it — a typed object
   * with a bounded key beats a hand-assembled string at each call site, and it keeps the C-01
   * guarantee on a path where nobody is watching for it.
   *
   * @param key the lock key; bounded and stripped inside this type
   * @param cause what the backend client threw
   * @return the exception to log
   */
  public static DistributedLockException releaseFailed(String key, Throwable cause) {
    return new DistributedLockException(
        RELEASE, Objects.requireNonNull(key, "key"), Objects.requireNonNull(cause, "cause"));
  }

  /**
   * The lock key, bounded and stripped of control characters.
   *
   * <p>Deliberately not in {@link #getMessage()} — see the class Javadoc.
   *
   * @return the key, never {@code null}
   */
  public String key() {
    return key;
  }

  /**
   * Strips ISO control characters and truncates.
   *
   * <p>The fourth place in this repository doing exactly this, after {@code KeyDiagnostics}, {@code
   * JsonDiagnostics} and {@code PageDiagnostics}. Item 4.5 deferred the extraction with the rule
   * that a fourth call site should reopen it on its own terms; that has happened, and the answer is
   * recorded in the ROADMAP rather than taken here, because a shared helper has to be exported from
   * {@code d4np-core} and is then MAJOR-locked at 1.0 — a decision that outlives this item.
   *
   * @param value the caller-supplied key
   * @return a value safe to put in a log line
   */
  private static String bound(String value) {
    StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_KEY_LENGTH));
    value
        .codePoints()
        .filter(codePoint -> !Character.isISOControl(codePoint))
        .limit(MAX_KEY_LENGTH)
        .forEach(safe::appendCodePoint);
    return value.length() > safe.length() ? safe + "..." : safe.toString();
  }
}
