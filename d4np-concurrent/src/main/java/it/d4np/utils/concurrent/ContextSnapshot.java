package it.d4np.utils.concurrent;

/**
 * A context captured on one thread, ready to be installed on another (FR-09, RFC-0004 §FR-09).
 *
 * <p>A snapshot is a <em>value</em>: it is captured once on the submitting thread and may be
 * installed on a worker thread later, possibly after the submitting thread has moved on. It must
 * therefore hold a copy rather than a reference to anything the submitting thread can still mutate
 * — SLF4J's {@code MDC.getCopyOfContextMap()} is named the way it is for this reason.
 *
 * <p><strong>Installing returns a {@link Scope} that restores, and never one that clears.</strong>
 * This is the whole design and it is worth stating why, because clearing is the obvious
 * implementation and it is wrong in both directions. A worker thread is pooled and reused, so:
 *
 * <ul>
 *   <li>A task that installs a context and <em>clears</em> afterwards destroys context that was
 *       already on that thread — the nested-submission case, where an async task submits another.
 *   <li>A task that installs and does <em>nothing</em> afterwards leaves its context visible to the
 *       next task on the same worker: a different request, often a different user or tenant, whose
 *       log lines then carry someone else's identifiers.
 * </ul>
 *
 * <p>Restoring the previous value is the only behaviour that is correct in both. The threat model
 * carries this as an information-disclosure row against B1.
 *
 * @see ContextPropagator
 * @see AsyncExecutor
 */
@FunctionalInterface
public interface ContextSnapshot {

  /**
   * Installs this snapshot on the calling thread.
   *
   * <p>Called on the <strong>worker</strong> thread, immediately before the task body runs. The
   * returned {@link Scope} is always closed on the same thread, including when the body throws.
   *
   * @return the handle that puts back whatever was on this thread before; never {@code null}
   */
  Scope install();

  /**
   * What was on the thread before a snapshot was installed, and the ability to put it back.
   *
   * <p>Nested inside {@code ContextSnapshot} rather than published as a top-level {@code Scope},
   * which would be a very general name in a shared package for a very specific thing.
   */
  @FunctionalInterface
  interface Scope extends AutoCloseable {

    /**
     * Restores the thread to the state it was in before {@link #install()}.
     *
     * <p><strong>Must not throw</strong>, and the signature says so by narrowing {@link
     * AutoCloseable#close()}'s {@code throws Exception} away. A restore that failed would run
     * inside {@link AsyncExecutor}'s own {@code finally}, where an exception would replace whatever
     * the task body was reporting — the same reasoning that keeps {@link ManagedThreadPool#close()}
     * quiet, and the reason FR-06's transaction runner does not hold its connection in a
     * try-with-resources.
     *
     * <p>Must be idempotent: closing twice restores once.
     */
    @Override
    void close();
  }
}
