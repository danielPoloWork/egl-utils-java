package it.d4np.utils.concurrent;

/**
 * The propagator an {@link AsyncExecutor} uses until a host supplies one.
 *
 * <p>Package-private and a singleton: it holds no state, so one instance serves every executor, and
 * publishing the class would add a public type whose only purpose is to be the absence of another.
 * {@link ContextPropagator#none()} is the published door.
 *
 * <p><strong>It is a real no-op, not a reflective lookup.</strong> Reaching for {@code
 * org.slf4j.MDC} by reflection would propagate when SLF4J happened to be present and do nothing
 * otherwise — the same implicit configuration item 4.1 refused when it declined {@code
 * findAndRegisterModules()} for {@code JsonMapper}. A propagator that silently does nothing is
 * discovered in production; one that explicitly does nothing is a documented default.
 *
 * <p>Immutable and therefore thread-safe.
 */
enum NoContext implements ContextPropagator {

  /** The only instance. */
  INSTANCE;

  /** Closes nothing, because nothing was installed. */
  private static final ContextSnapshot.Scope NOTHING_TO_RESTORE = () -> {};

  /** Installs nothing, and hands back a scope that restores nothing. */
  private static final ContextSnapshot EMPTY = () -> NOTHING_TO_RESTORE;

  @Override
  public ContextSnapshot capture() {
    return EMPTY;
  }

  @Override
  public String toString() {
    return "ContextPropagator.none()";
  }
}
