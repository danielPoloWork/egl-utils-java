package it.d4np.utils.concurrent;

import java.lang.System.Logger;

/**
 * Builds pools that keep the configuration they were given (FR-08, RFC-0004 §FR-08).
 *
 * <p>The interesting decisions are not here — they are in {@link ThreadPoolSpec}, which makes the
 * queue bound and the rejection policy mandatory, and in {@link ManagedThreadPool}, which owns the
 * drain budget and publishes no way to undo any of it. This class is the one entry point that turns
 * the first into the second.
 *
 * <p>Usage, with the pool closed deterministically:
 *
 * <pre>{@code
 * ThreadPoolSpec spec = ThreadPoolSpec.named("ingest")
 *     .coreThreads(4)
 *     .maxThreads(8)
 *     .queueCapacity(256)
 *     .drainTimeout(Duration.ofSeconds(10))
 *     .rejectionPolicy(new ThreadPoolExecutor.CallerRunsPolicy())
 *     .build();
 *
 * try (ManagedThreadPool pool = CustomThreadPoolFactory.create(spec)) {
 *     pool.execute(job);
 * }   // stops accepting, drains for up to 10s, then interrupts what is left
 * }</pre>
 *
 * <p><strong>Thread safety.</strong> Stateless, so trivially thread-safe. It holds no registry of
 * the pools it created: a factory that tracked them would be a second lifecycle owner competing
 * with {@link ManagedThreadPool#close()}, and the caller already holds the only reference that
 * matters.
 */
public final class CustomThreadPoolFactory {

  private CustomThreadPoolFactory() {
    throw new AssertionError("no instances");
  }

  /**
   * Creates and starts a pool.
   *
   * <p>The returned pool is already running — {@code ThreadPoolExecutor} starts threads on demand,
   * so nothing is allocated until the first task arrives.
   *
   * @param spec the configuration; every mandatory parameter has already been validated by {@link
   *     ThreadPoolSpec.Builder#build()}
   * @return a running pool the caller owns and must {@link ManagedThreadPool#close() close}
   */
  public static ManagedThreadPool create(ThreadPoolSpec spec) {
    return ManagedThreadPool.from(spec);
  }

  /**
   * Creates a pool that logs through a caller-supplied logger.
   *
   * <p>Package-private for the reason ADR-0014 records: a {@code System.LoggerFinder} cannot be
   * installed inside a surefire fork, so the tests that read this module's two warning lines have
   * to be handed the logger rather than intercept it.
   *
   * @param spec the configuration
   * @param logger where the drain and thread-failure lines go
   * @return a running pool
   */
  static ManagedThreadPool create(ThreadPoolSpec spec, Logger logger) {
    return ManagedThreadPool.from(spec, logger);
  }
}
