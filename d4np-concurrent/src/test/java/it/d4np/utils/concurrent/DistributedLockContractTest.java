package it.d4np.utils.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-10's contract, exercised against a reference implementation.
 *
 * <p>The module ships no implementation (ADR-001), so these assertions run against {@link
 * InMemoryDistributedLock} in test scope. They are written as statements about <em>the
 * contract</em> rather than about that class, because every one of them is a requirement {@code
 * d4np-lock-redisson} and every later implementation inherits.
 */
class DistributedLockContractTest {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration NO_WAIT = Duration.ZERO;
  private static final String KEY = "orders:rebuild";

  /** A clock the tests advance by hand, so a lease can expire without sleeping. */
  private MovableClock clock;

  private DistributedLock lock;

  @BeforeEach
  void setUp() {
    clock = new MovableClock(Instant.parse("2026-08-30T12:00:00Z"));
    lock = InMemoryDistributedLock.on(clock);
  }

  private LockHandle acquire() {
    return lock.tryAcquire(KEY, LEASE, NO_WAIT)
        .orElseThrow(() -> new AssertionError("not acquired"));
  }

  @Nested
  @DisplayName("acquiring")
  class Acquiring {

    @Test
    @DisplayName("succeeds when the lock is free, and hands back a handle the caller owns")
    void succeedsWhenFree() {
      try (LockHandle handle = acquire()) {
        assertThat(handle.key()).isEqualTo(KEY);
        assertThat(handle.isHeld()).isTrue();
        assertThat(handle.leaseExpiry()).isEqualTo(clock.instant().plus(LEASE));
      }
    }

    @Test
    @DisplayName("reports a lock someone else holds as empty, which is an outcome and not an error")
    void reportsAHeldLockAsEmpty() {
      // RFC-0001's error model: an expected outcome the caller branches on is a value. An exception
      // is reserved for the backend being broken.
      try (LockHandle ignored = acquire()) {
        assertThat(lock.tryAcquire(KEY, LEASE, NO_WAIT)).isEmpty();
      }
    }

    @Test
    @DisplayName("lets the next caller in once the holder closes")
    void letsTheNextCallerInAfterClose() {
      acquire().close();

      try (LockHandle second = acquire()) {
        assertThat(second.isHeld()).isTrue();
      }
    }

    @Test
    @DisplayName("lets the next caller in once the lease expires, even if nobody closed")
    void letsTheNextCallerInAfterTheLeaseExpires() {
      // The mandatory lease is what bounds the starvation threat: a crashed holder does not keep
      // the
      // lock forever, because the BACKEND releases it rather than the holder.
      LockHandle abandoned = acquire();
      clock.advance(LEASE.plusSeconds(1));

      assertThat(abandoned.isHeld()).isFalse();
      try (LockHandle second = acquire()) {
        assertThat(second.isHeld()).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("releasing an acquisition, never a key")
  class Releasing {

    @Test
    @DisplayName("a stale holder's close() does NOT release the current holder's lock")
    void aStaleHolderDoesNotReleaseTheCurrentHolder() {
      // THE contract's load-bearing property, and the classic defect it forbids: the first holder's
      // lease expires, a second acquires the same key, and the first then finishes and calls
      // close(). A `DEL key` implementation deletes the SECOND holder's lock here.
      LockHandle stale = acquire();
      clock.advance(LEASE.plusSeconds(1));
      LockHandle current = acquire();

      stale.close();

      assertThat(current.isHeld())
          .as("the current holder still holds it after a stale holder closed")
          .isTrue();
      assertThat(lock.tryAcquire(KEY, LEASE, NO_WAIT)).as("and nobody else can take it").isEmpty();
      current.close();
    }

    @Test
    @DisplayName("is idempotent")
    void isIdempotent() {
      LockHandle handle = acquire();

      handle.close();
      assertThatCode(handle::close).doesNotThrowAnyException();
      assertThat(handle.isHeld()).isFalse();
    }

    @Test
    @DisplayName("never throws, so it cannot suppress a body's exception in try-with-resources")
    void neverThrows() {
      // Narrowed away AutoCloseable's `throws Exception` in the interface, so this is a
      // compile-time
      // property as much as a runtime one -- the test records that the narrowing is deliberate.
      assertThatThrownBy(
              () -> {
                try (LockHandle ignored = acquire()) {
                  throw new IllegalStateException("the body's exception must survive");
                }
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("the body's exception must survive")
          .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
    }
  }

  @Nested
  @DisplayName("the fencing token")
  class Fencing {

    @Test
    @DisplayName("strictly increases across acquisitions of the same key")
    void strictlyIncreasesPerKey() {
      // What makes it usable: if two processes ever hold the same key at once, the larger token
      // belongs to the current holder, so a resource that remembers the highest it accepted can
      // refuse a stale writer.
      List<Long> observed = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        LockHandle handle = acquire();
        observed.add(handle.fencingToken().orElseThrow());
        handle.close();
      }

      assertThat(observed).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("distinguishes a stale holder from the current one, which is the whole point")
    void distinguishesAStaleHolderFromTheCurrent() {
      LockHandle stale = acquire();
      clock.advance(LEASE.plusSeconds(1));
      try (LockHandle current = acquire()) {
        assertThat(current.fencingToken().orElseThrow())
            .as("a resource comparing tokens can reject the stale writer")
            .isGreaterThan(stale.fencingToken().orElseThrow());
      }
      stale.close();
    }
  }

  @Nested
  @DisplayName("reentrancy: refuse, do not block")
  class Reentrancy {

    @Test
    @DisplayName("a non-reentrant lock refuses its own holder PROMPTLY rather than waiting")
    void refusesItsOwnHolderPromptly() {
      // The requirement leaves the failing case unspecified and the unspecified case is the harmful
      // one: waiting for a lock this thread already owns burns the whole `wait` and then fails,
      // with nothing in a log to read. ADR-0031's argument at longer range.
      Duration generousWait = Duration.ofSeconds(30);

      try (LockHandle ignored = acquire()) {
        long startedAt = System.nanoTime();
        Optional<LockHandle> nested = lock.tryAcquire(KEY, LEASE, generousWait);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(nested).isEmpty();
        assertThat(elapsedMillis)
            .as("refused immediately, not after the 30s wait")
            .isLessThan(5_000L);
      }
    }

    @Test
    @DisplayName("a reentrant implementation may let its holder back in, and that is also legal")
    void aReentrantImplementationMayAdmitItsHolder() {
      // FR-10 promises no reentrancy "unless an implementation documents it", so the contract must
      // accommodate both. What it forbids is the third behaviour: blocking.
      DistributedLock reentrant = InMemoryDistributedLock.reentrantOn(clock);

      try (LockHandle first = reentrant.tryAcquire(KEY, LEASE, NO_WAIT).orElseThrow()) {
        assertThat(reentrant.tryAcquire(KEY, LEASE, NO_WAIT)).isPresent();
        assertThat(first.key()).isEqualTo(KEY);
      }
    }
  }

  @Nested
  @DisplayName("under contention")
  class Contention {

    @Test
    @DisplayName("exactly one of many racing threads acquires the lock")
    void exactlyOneWinnerAmongRacers() throws InterruptedException {
      int racers = 16;
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(racers);
      AtomicInteger winners = new AtomicInteger();

      try (ManagedThreadPool pool =
          CustomThreadPoolFactory.create(
              ThreadPoolSpec.named("racers")
                  .coreThreads(racers)
                  .maxThreads(racers)
                  .queueCapacity(racers)
                  .drainTimeout(Duration.ofSeconds(5))
                  .rejectionPolicy(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
                  .build())) {

        for (int i = 0; i < racers; i++) {
          pool.execute(
              () -> {
                try {
                  start.await(5, TimeUnit.SECONDS);
                  lock.tryAcquire(KEY, LEASE, NO_WAIT)
                      .ifPresent(handle -> winners.incrementAndGet());
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  finished.countDown();
                }
              });
        }
        start.countDown();
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
      }

      assertThat(winners)
          .as("mutual exclusion is the one property every implementation must have")
          .hasValue(1);
    }
  }

  @Nested
  @DisplayName("what it refuses at the boundary")
  class Refusals {

    @Test
    @DisplayName("rejects a blank key, a non-positive lease and a negative wait")
    void rejectsBadArguments() {
      assertThatThrownBy(() -> lock.tryAcquire("  ", LEASE, NO_WAIT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("key must not be blank");
      assertThatThrownBy(() -> lock.tryAcquire(KEY, Duration.ZERO, NO_WAIT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("lease must be positive");
      assertThatThrownBy(() -> lock.tryAcquire(KEY, LEASE, Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("wait must not be negative");
    }

    @Test
    @DisplayName("rejects null arguments")
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNullArguments() {
      assertThatThrownBy(() -> lock.tryAcquire(null, LEASE, NO_WAIT))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> lock.tryAcquire(KEY, null, NO_WAIT))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> lock.tryAcquire(KEY, LEASE, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("the surface every implementation inherits")
  class Surface {

    @Test
    @DisplayName("DistributedLock publishes exactly one abstract method")
    void publishesExactlyOneAbstractMethod() {
      // "The interface IS the deliverable": every abstract method here is one that every
      // implementation must write. Asserted structurally so a convenience method added later has to
      // be a `default` -- which costs implementers nothing -- rather than a new obligation.
      List<String> abstractMethods =
          List.of(DistributedLock.class.getMethods()).stream()
              .filter(m -> java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
              .map(java.lang.reflect.Method::getName)
              .toList();

      assertThat(abstractMethods).containsExactly("tryAcquire");
    }

    @Test
    @DisplayName("LockHandle.close() declares no checked exception")
    void closeDeclaresNoCheckedException() throws NoSuchMethodException {
      // Narrowing AutoCloseable's `throws Exception` away is the contract, not an oversight.
      assertThat(LockHandle.class.getMethod("close").getExceptionTypes()).isEmpty();
      assertThat(LockHandle.class).isAssignableTo(AutoCloseable.class);
    }
  }

  /** A {@link Clock} the tests move by hand. */
  private static final class MovableClock extends Clock {

    private Instant now;

    private MovableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
