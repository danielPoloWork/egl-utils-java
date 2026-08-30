package it.d4np.utils.concurrent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A reference {@link DistributedLock} for one JVM, used to exercise FR-10's contract.
 *
 * <p><strong>Test scope, and that is a rule rather than a convenience.</strong> FR-10 says
 * INTERFACE ONLY in this module and ADR-001 keeps implementations out so no consumer of the
 * concurrency utilities drags a backend client. Nothing here reaches {@code src/main}. What it
 * exists for is the thing an interface-only item otherwise cannot do: <em>demonstrate that the
 * contract is satisfiable</em>, and give the contract tests something real to run against. Item 4.3
 * used a hand-written {@code DataSource} the same way, for the same reason.
 *
 * <p><strong>The clock is injected</strong> so a lease can be expired without sleeping. That is
 * what makes the load-bearing test — a stale holder must not release the current holder's lock —
 * deterministic instead of timing-dependent.
 *
 * <p>Named without a {@code Test} prefix or suffix so surefire does not offer it to the JUnit
 * Platform as a test class.
 */
final class InMemoryDistributedLock implements DistributedLock {

  /** One acquisition the backend knows about: who owns it, and until when. */
  private record Entry(long ownerToken, Instant expiry, long ownerThreadId) {}

  private final ConcurrentHashMap<String, Entry> held = new ConcurrentHashMap<>();

  /** Strictly increasing, which is what {@link LockHandle#fencingToken()} requires. */
  private final AtomicLong tokens = new AtomicLong();

  private final Clock clock;

  private final boolean reentrant;

  private InMemoryDistributedLock(Clock clock, boolean reentrant) {
    this.clock = clock;
    this.reentrant = reentrant;
  }

  /**
   * A non-reentrant lock over the given clock.
   *
   * @param clock the clock leases are measured against
   * @return the lock
   */
  static InMemoryDistributedLock on(Clock clock) {
    return new InMemoryDistributedLock(clock, false);
  }

  /**
   * A lock that permits its own holder to re-acquire, so the contract's <em>other</em> legal
   * behaviour has something to be tested against.
   *
   * @param clock the clock leases are measured against
   * @return the lock
   */
  static InMemoryDistributedLock reentrantOn(Clock clock) {
    return new InMemoryDistributedLock(clock, true);
  }

  @Override
  public Optional<LockHandle> tryAcquire(String key, Duration lease, Duration wait) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(wait, "wait");
    if (key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (lease.isZero() || lease.isNegative()) {
      throw new IllegalArgumentException("lease must be positive; was " + lease);
    }
    if (wait.isNegative()) {
      throw new IllegalArgumentException("wait must not be negative; was " + wait);
    }

    Instant deadline = clock.instant().plus(wait);
    do {
      Optional<LockHandle> acquired = attempt(key, lease);
      if (acquired.isPresent()) {
        return acquired;
      }
      if (!reentrant && heldByThisThread(key)) {
        // FR-10's requirement: refuse a nested acquisition PROMPTLY rather than block on a lock
        // this
        // thread already owns. Waiting here is the defect the contract exists to forbid.
        return Optional.empty();
      }
      Thread.onSpinWait();
    } while (clock.instant().isBefore(deadline));
    return attempt(key, lease);
  }

  private boolean heldByThisThread(String key) {
    Entry entry = held.get(key);
    return entry != null && entry.ownerThreadId() == Thread.currentThread().getId();
  }

  private Optional<LockHandle> attempt(String key, Duration lease) {
    Instant now = clock.instant();
    long candidate = tokens.incrementAndGet();
    Entry mine = new Entry(candidate, now.plus(lease), Thread.currentThread().getId());

    Entry winner =
        held.compute(
            key,
            (ignored, current) -> {
              boolean free = current == null || !current.expiry().isAfter(now);
              boolean mayReenter =
                  reentrant && current != null && current.ownerThreadId() == mine.ownerThreadId();
              return free || mayReenter ? mine : current;
            });

    // Compared by token rather than by reference: the token is unique per attempt, so this says
    // "the entry now recorded is the one I just made" without depending on record identity.
    return winner.ownerToken() == mine.ownerToken()
        ? Optional.of(new InMemoryHandle(key, mine))
        : Optional.empty();
  }

  /** Releases only if this acquisition is still the one recorded — never by key alone. */
  private void release(String key, Entry mine) {
    held.computeIfPresent(
        key, (ignored, current) -> current.ownerToken() == mine.ownerToken() ? null : current);
  }

  /** A handle over one {@link Entry}. */
  private final class InMemoryHandle implements LockHandle {

    private final String key;
    private final Entry entry;
    private volatile boolean closed;

    private InMemoryHandle(String key, Entry entry) {
      this.key = key;
      this.entry = entry;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public Instant leaseExpiry() {
      return entry.expiry();
    }

    @Override
    public OptionalLong fencingToken() {
      return OptionalLong.of(entry.ownerToken());
    }

    @Override
    public boolean isHeld() {
      return !closed && entry.expiry().isAfter(clock.instant());
    }

    @Override
    public void close() {
      // Idempotent, never throws, and -- the property the contract turns on -- scoped to THIS
      // acquisition. release() compares the owner token, so a handle whose lease expired while
      // another holder took the key deletes nothing.
      closed = true;
      release(key, entry);
    }
  }
}
