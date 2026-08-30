package it.d4np.utils.concurrent;

/**
 * Captures whatever a host calls "context" so {@link AsyncExecutor} can carry it across a thread
 * boundary (FR-09, RFC-0004 §FR-09).
 *
 * <p><strong>This interface exists because the requirement's stated mechanism is unreachable from
 * this module.</strong> FR-09 asks for <em>"MDC context propagation"</em>, and {@code MDC} is
 * SLF4J's. {@code d4np-concurrent}'s {@code maven-enforcer} allowlist has no third-party entry at
 * any scope — no {@code provided} exemption of the kind {@code d4np-core} holds for Bean Validation
 * — so an SLF4J dependency fails {@code mvn validate} rather than review. This library does not log
 * through SLF4J either: it uses {@code java.lang.System.Logger}, which has no MDC at all.
 *
 * <p>ADR-0014 predicted this moment when it chose the platform logger — <em>"a module that later
 * needs structured context will find {@code System.Logger} thin, and that is the moment to revisit,
 * not now"</em> — and the revisit's answer is this SPI rather than a dependency. RFC-0004 pins the
 * contract under ADR-0010 rung 1, so FR-09's spec sentence is superseded rather than edited.
 *
 * <h2>Binding it to SLF4J's MDC, which is the host's four lines</h2>
 *
 * <p>This module ships no implementation that reads a logging framework, and deliberately does not
 * reach for {@code org.slf4j.MDC} reflectively: that would work when SLF4J happened to be on the
 * classpath and silently propagate nothing otherwise — implicit configuration inside the one type
 * whose value is that its behaviour is explicit. A host writes:
 *
 * <pre>{@code
 * ContextPropagator mdc = () -> {
 *     Map<String, String> captured = MDC.getCopyOfContextMap();   // on the submitting thread
 *     return () -> {                                              // install(), on the worker
 *         Map<String, String> previous = MDC.getCopyOfContextMap();
 *         if (captured == null) { MDC.clear(); } else { MDC.setContextMap(captured); }
 *         return () -> {                                          // Scope.close(), restores
 *             if (previous == null) { MDC.clear(); } else { MDC.setContextMap(previous); }
 *         };
 *     };
 * };
 *
 * AsyncExecutor executor = AsyncExecutor.over(pool).withContext(mdc);
 * }</pre>
 *
 * <p>It is not shipped in {@code d4np-spring-adapter} either, although that module's allowlist
 * would permit SLF4J: <strong>MDC is SLF4J, not Spring</strong>, so putting it there would make a
 * Jakarta EE host — which spec §1 names as a first-class target — take a Spring dependency to get
 * context propagation, in a library whose stated objective is framework independence.
 *
 * <p><strong>Thread safety.</strong> An implementation must be safe to call from many threads at
 * once, because one {@code AsyncExecutor} is shared. {@link #capture()} runs on the submitting
 * thread and must not mutate anything.
 *
 * @see ContextSnapshot
 * @see AsyncExecutor#withContext(ContextPropagator)
 */
@FunctionalInterface
public interface ContextPropagator {

  /**
   * Captures the calling thread's context.
   *
   * <p>Called on the <strong>submitting</strong> thread, before the task is handed to the executor.
   *
   * @return a snapshot that can be installed on another thread; never {@code null}
   */
  ContextSnapshot capture();

  /**
   * A propagator that carries nothing, which is what an {@link AsyncExecutor} uses until a host
   * supplies one.
   *
   * <p>The default is a no-op rather than a reflective MDC lookup, for the reason in the class
   * Javadoc: a fallback that works only when a particular library happens to be present fails
   * <em>silently</em>, and is discovered in production by a log line missing a correlation id.
   *
   * @return a propagator whose snapshots install and restore nothing
   */
  static ContextPropagator none() {
    return NoContext.INSTANCE;
  }
}
