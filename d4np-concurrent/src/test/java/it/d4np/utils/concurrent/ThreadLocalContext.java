package it.d4np.utils.concurrent;

import java.util.Objects;

/**
 * A stand-in for SLF4J's {@code MDC}: one thread-local string, with the same
 * capture/install/restore shape a real binding has.
 *
 * <p>Written here rather than depending on SLF4J because this module may not — its enforcer
 * allowlist has no third-party entry at any scope, which is the whole reason {@link
 * ContextPropagator} exists. A thread-local holding one value reproduces every property the
 * contract is about: it is per-thread, it survives a task, and it is what the next task on a pooled
 * thread would see.
 *
 * <p>Named without a {@code Test} prefix or suffix so surefire does not offer it to the JUnit
 * Platform as a test class.
 */
final class ThreadLocalContext {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private ThreadLocalContext() {
    throw new AssertionError("no instances");
  }

  /**
   * What this thread is currently carrying.
   *
   * @return the value, or {@code "<none>"} when nothing is set
   */
  static String get() {
    String value = CURRENT.get();
    return value == null ? "<none>" : value;
  }

  /**
   * Sets this thread's value.
   *
   * @param value the value
   */
  static void set(String value) {
    CURRENT.set(Objects.requireNonNull(value));
  }

  /** Clears this thread's value. */
  static void clear() {
    CURRENT.remove();
  }

  /**
   * A propagator over {@link #CURRENT}, written exactly as the Javadoc tells a host to write the
   * SLF4J one — capture a copy, install it, and restore <em>the previous value</em> on close.
   *
   * @return the propagator
   */
  static ContextPropagator propagator() {
    return () -> {
      String captured = CURRENT.get();
      return () -> {
        String previous = CURRENT.get();
        apply(captured);
        return () -> apply(previous);
      };
    };
  }

  /**
   * A propagator that installs but <strong>never restores</strong> — the defect the {@code Scope}
   * exists to prevent, kept so the leak test can show the difference rather than assert an absence.
   *
   * @return a leaky propagator
   */
  static ContextPropagator leakyPropagator() {
    return () -> {
      String captured = CURRENT.get();
      return () -> {
        apply(captured);
        return () -> {
          // Deliberately nothing. This is what "clear afterwards" and "do nothing afterwards" both
          // look like from the next task's point of view.
        };
      };
    };
  }

  private static void apply(String value) {
    if (value == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(value);
    }
  }
}
