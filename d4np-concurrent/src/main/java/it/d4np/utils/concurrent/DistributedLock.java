package it.d4np.utils.concurrent;

import java.time.Duration;
import java.util.Optional;

/**
 * Mutual exclusion across processes (FR-10, RFC-0004 §FR-10).
 *
 * <p><strong>This interface is the deliverable, not a step toward one.</strong> ADR-001 keeps the
 * implementation out of this module — {@code d4np-lock-redisson} ships it, so a consumer of the
 * concurrency utilities never drags a Redis client — which means every clause below is inherited by
 * every implementation that will ever exist, and correcting one later is a MAJOR break. It is
 * written accordingly: the hard parts are stated as requirements on the implementer rather than
 * left as things a good one would probably do.
 *
 * <pre>{@code
 * Optional<LockHandle> acquired = lock.tryAcquire("orders:rebuild", Duration.ofSeconds(30),
 *                                                 Duration.ofSeconds(5));
 * if (acquired.isEmpty()) {
 *     return;                       // someone else holds it; this is an ordinary outcome
 * }
 * try (LockHandle handle = acquired.get()) {
 *     rebuild(handle.fencingToken());   // pass the token to whatever you are protecting
 * }
 * }</pre>
 *
 * <h2>What an implementation must do</h2>
 *
 * <ul>
 *   <li><strong>Honour the lease.</strong> A lock acquired for {@code lease} is released by the
 *       backend no later than {@code lease} after acquisition, whether or not the holder ever calls
 *       {@link LockHandle#close()}. This is what bounds the *starvation* threat, and it is why the
 *       parameter is mandatory rather than defaulted.
 *   <li><strong>Refuse a nested acquisition rather than block on it.</strong> FR-10 promises no
 *       reentrancy unless an implementation documents it, which leaves the failing case unspecified
 *       — and the unspecified case is the harmful one: a non-reentrant lock re-acquired by its own
 *       holder waits for a lock that holder already owns, until {@code wait} at best and until the
 *       lease expires at worst, with nothing in a log to read. A non-reentrant implementation must
 *       return {@link Optional#empty()} promptly instead. This is <a
 *       href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/adr/0031-one-nesting-detector-for-the-whole-jvm.md">ADR-0031</a>'s
 *       argument at longer range.
 *   <li><strong>Be thread-safe.</strong> One {@code DistributedLock} is shared; {@code tryAcquire}
 *       is called from many threads at once and each caller gets its own {@link LockHandle}.
 *   <li><strong>Never release a lock it cannot prove is the one it acquired</strong> — see {@link
 *       LockHandle#close()}.
 * </ul>
 *
 * <h2>What it must not do</h2>
 *
 * <p>An implementation must not report a fencing token it cannot keep monotonic, must not let a
 * backend's own message escape in a {@link DistributedLockException}, and must not treat {@code
 * close()} as an opportunity to throw. Each is stated where it belongs.
 *
 * @see LockHandle
 * @see DistributedLockException
 */
@FunctionalInterface
public interface DistributedLock {

  /**
   * Tries to acquire the lock named by {@code key}, waiting up to {@code wait} for it.
   *
   * <p><strong>Failing to acquire is an ordinary outcome, not an error.</strong> It is reported as
   * {@link Optional#empty()} so the caller branches on a value (RFC-0001's error model); an
   * exception is reserved for the backend being unreachable or answering incorrectly.
   *
   * @param key names the lock; compared by exact string equality by every implementation, so two
   *     callers agree on a lock exactly when they agree on this string
   * @param lease how long the backend holds the lock before releasing it unilaterally. Mandatory
   *     and must be positive: a lock with no lease is a lock a crashed holder keeps forever
   * @param wait how long to wait for a lock someone else holds. {@link Duration#ZERO} means try
   *     once and return; must not be negative
   * @return a handle the caller owns and must {@link LockHandle#close() close}, or empty if the
   *     lock was not acquired within {@code wait}
   * @throws IllegalArgumentException if {@code key} is blank, {@code lease} is not positive, or
   *     {@code wait} is negative — each is a defect in the calling code rather than something a
   *     client can send
   * @throws NullPointerException if any argument is {@code null}
   * @throws DistributedLockException if the backend is unreachable or answers incorrectly
   */
  Optional<LockHandle> tryAcquire(String key, Duration lease, Duration wait);
}
