package it.d4np.utils.concurrent;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * One acquisition of a {@link DistributedLock} (FR-10, RFC-0004 §FR-10).
 *
 * <p>A handle represents <strong>an acquisition, not a lock</strong>, and almost everything below
 * follows from that distinction.
 *
 * <h2>The lease can expire while you are still holding it</h2>
 *
 * <p>FR-10's mandatory lease bounds the threat that a crashed holder keeps a lock forever. It does
 * nothing about the converse, which is the one that corrupts data: a stop-the-world pause, a slow
 * disk or a network partition is enough for the lease to expire while the holder is still running.
 * The backend then grants the lock to someone else, and <strong>two processes each believe they
 * hold it</strong>.
 *
 * <p>No lease-based lock can prevent that; it is a property of leases rather than a defect in any
 * particular backend. The only structural mitigation is {@link #fencingToken()}, and using it is
 * the caller's job — see that method.
 *
 * @see DistributedLock
 */
public interface LockHandle extends AutoCloseable {

  /**
   * The key this acquisition was made for.
   *
   * @return the key, exactly as it was passed to {@link DistributedLock#tryAcquire(String,
   *     java.time.Duration, java.time.Duration)}
   */
  String key();

  /**
   * When the backend will release this lock if the holder has not.
   *
   * <p><strong>An estimate on the local clock, and not authoritative.</strong> The backend decides
   * when a lease ends, using its own clock; this value is what the acquiring process computed from
   * its own. Clock skew between the two is precisely the condition that makes leases unsafe, so a
   * caller must not use this to decide it is still safe to write — that is what {@link
   * #fencingToken()} is for. It is honest for logging, for metrics, and for deciding whether to
   * bother starting a long unit of work.
   *
   * @return the estimated expiry
   */
  Instant leaseExpiry();

  /**
   * A value the protected resource can use to reject a write from a stale holder.
   *
   * <p><strong>When present, it strictly increases per key.</strong> If two processes ever hold the
   * same key at once — which a lease expiry makes possible — their tokens differ, and the larger
   * one belongs to the current holder. The mitigation only works if the <em>resource</em>
   * cooperates: it must record the highest token it has accepted for a given subject and refuse any
   * write carrying a lower one. <strong>This library cannot enforce that</strong>, and says so
   * rather than implying the token is protective on its own.
   *
   * <p><strong>Empty is a real and sometimes required answer.</strong> An implementation that
   * cannot keep the sequence strictly increasing must return empty rather than a best effort —
   * including across its own restart or failover, because <em>a counter that restarts is more
   * dangerous than no counter at all</em>: it looks like a guarantee, and a resource that trusts it
   * will accept a stale writer whose token happens to exceed the current one. This is {@code
   * JdbcAccessException} refusing to fabricate a SQLState no driver raised, applied to a field
   * whose whole value is that it means what it says.
   *
   * <p>Read empty as: <em>this implementation cannot keep mutual exclusion across a lease expiry;
   * do not use it to guard a non-idempotent write to an external system.</em>
   *
   * @return the token, or empty if this implementation cannot provide a monotonic one
   */
  OptionalLong fencingToken();

  /**
   * Whether this acquisition still looks live.
   *
   * <p><strong>Best-effort and local:</strong> it compares {@link #leaseExpiry()} against the clock
   * and does <em>not</em> consult the backend. A round-trip would be a different and far more
   * expensive contract, and a method that looked authoritative while racing the network would be
   * worse than one that admits what it is.
   *
   * <p>{@code true} therefore means "not known to be over"; it never means "safe to write". Use
   * {@link #fencingToken()} for that.
   *
   * @return {@code false} once {@link #close()} has run or the estimated lease has passed
   */
  boolean isHeld();

  /**
   * Releases <strong>this acquisition</strong>, and never simply the key.
   *
   * <p>The classic distributed-lock defect is releasing by key: a holder's lease expires, another
   * process acquires the same key, the first finishes its work and deletes <em>the second's</em>
   * lock. An implementation must release only what it can prove it acquired — an ownership token
   * compared atomically, not a bare delete — and if the lease has already expired it must release
   * <strong>nothing</strong>.
   *
   * <p><strong>Idempotent, and never throws.</strong> The signature narrows {@link
   * AutoCloseable#close()}'s {@code throws Exception} away deliberately. A failed release is
   * reported as a {@code WARNING} log line and nothing more, because the lock will expire on its
   * own and because throwing here would suppress the body's exception inside a try-with-resources —
   * the one place a caller is least able to react. That is the reasoning {@link
   * ManagedThreadPool#close()} follows and the reason FR-06's transaction runner does not hold its
   * connection in a try-with-resources.
   */
  @Override
  void close();
}
