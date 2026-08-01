package it.d4np.utils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Strategies looked up by key, registered at startup and read concurrently forever after.
 *
 * <pre>{@code
 * private static final StrategyRegistry<String, PaymentMethod> METHODS = new StrategyRegistry<>();
 * ...
 * METHODS.register("card", new CardPayment());
 * METHODS.getOrThrow(order.method()).charge(order);   // lock-free read
 * }</pre>
 *
 * <p><strong>Two lookups, because "missing" means two different things.</strong> Choosing between
 * them is the whole contract:
 *
 * <table border="1">
 *   <caption>Choosing a lookup</caption>
 *   <tr><th>Call</th><th>A missing key means</th><th>Use when</th></tr>
 *   <tr>
 *     <td>{@link #find(Object)}</td>
 *     <td>an empty {@link Optional} — an ordinary answer</td>
 *     <td>the key came from outside and absence is expected; you have a fallback</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #getOrThrow(Object)}</td>
 *     <td>{@link StrategyNotFoundException}, carrying the known keys</td>
 *     <td>the key is internal, so absence is a wiring defect and continuing is worse than failing</td>
 *   </tr>
 * </table>
 *
 * <p>Neither ever returns {@code null}, and {@code find} is not "the safe one" — reaching for it to
 * silence a failure only moves the {@code NullPointerException} to whatever the caller does with
 * the empty {@code Optional}, minus the known-key list that would have explained it.
 *
 * <p><strong>Registration is last-write-wins, and says so out loud.</strong> A second {@link
 * #register(Object, Object)} under a live key replaces the strategy and emits a {@code WARNING}
 * naming both classes. This is the deliberate opposite of FR-01's {@code GenericFactory}, which
 * rejects duplicates: a strategy registry is built for runtime reconfiguration, where replacement
 * is the feature, whereas a factory is wired once at startup, where a duplicate key is two modules
 * claiming the same discriminator. RFC-0001 §Alternatives records the asymmetry; ADR-0015 records
 * why the collision is logged rather than thrown or silently swallowed.
 *
 * <p><strong>Thread safety.</strong> Fully thread-safe, and asserted rather than claimed (spec §6):
 * {@code StrategyRegistryPublicationStress} proves a strategy registered by one thread is seen
 * fully constructed by another, and {@code StrategyRegistryRegistrationStress} proves two threads
 * racing on the same key leave exactly one winner and never an empty slot. Reads are lock-free —
 * {@link ConcurrentHashMap#get(Object)} takes no monitor — which is what NFR-04's budget is stated
 * against.
 *
 * <p>What this class does <em>not</em> promise is anything about the strategies it holds: a
 * strategy with mutable state is exactly as thread-safe as its own type. The registry guarantees
 * only that every thread sees the same, fully constructed instance.
 *
 * <p><strong>Performance (NFR-04, {@code <=} 50 ns/op at 1000 strategies under 8-thread read
 * load).</strong> {@link #find(Object)} measures <strong>12.8 ns/op on JDK 21</strong> and
 * <strong>17.8 ns/op on JDK 17</strong> at exactly that scale and load, so the budget holds with
 * 2.8x to 4x headroom. It allocates one {@link Optional} per call and that is accepted, not
 * overlooked — RFC-0001 states the budget against {@code find} at scale precisely so the number
 * includes it.
 *
 * <p><strong>Do not choose between the two lookups on speed.</strong> {@link #getOrThrow(Object)}
 * reads the map directly and allocates no {@code Optional}, and is <em>still</em> never the faster
 * call: on JDK 21 it measures about <strong>2 ns/op slower</strong>, reproducibly and with
 * non-overlapping intervals, while on JDK 17 the two are indistinguishable. Avoiding an allocation
 * is not the same as being faster, and the reason has been narrowed but not settled (ADR-0015).
 * Both sit far enough inside the budget that the choice should be made on what a missing key
 * <em>means</em>, per the table above. Measured by {@code StrategyRegistryFindBenchmark}.
 *
 * @param <K> the key type; must be usable as a {@link ConcurrentHashMap} key, so {@code equals} and
 *     {@code hashCode} must agree and be stable for as long as the strategy is registered
 * @param <S> the strategy type
 * @see StrategyNotFoundException
 */
public final class StrategyRegistry<K, S> {

  /**
   * The JDK's own logging facade, which is the only one a zero-dependency module may use.
   *
   * <p>ADR-001 fixes {@code d4np-core} at zero third-party dependencies, so SLF4J is unavailable
   * here however conventional it is. {@link System#getLogger(String)} routes through whatever
   * backend the consumer already installed — SLF4J, Log4j2 and {@code java.util.logging} all
   * publish a {@link System.LoggerFinder} — so this warning lands in the application's real log
   * rather than in a second one nobody reads. See ADR-0014.
   */
  private static final Logger DEFAULT_LOGGER = System.getLogger(StrategyRegistry.class.getName());

  /**
   * Where the collision warning goes; {@link #DEFAULT_LOGGER} unless a test supplied its own.
   *
   * <p>The seam exists because the alternatives were tried and lost, which is recorded in ADR-0014
   * rather than left as a smell for a reviewer to find. A {@link System.LoggerFinder} on the test
   * classpath <em>cannot</em> win under surefire — the JDK resolves the finder once per VM on the
   * first {@code System.getLogger} call and caches it, and something in the fork has already
   * triggered platform logging by then. Attaching a {@code java.util.logging.Handler} instead would
   * work, but {@code java.util.logging} lives in module {@code java.logging}, and these tests
   * compile <em>inside</em> {@code it.d4np.utils}, whose descriptor deliberately requires nothing
   * but {@code java.base} — so it would mean either advertising a dependency core does not have or
   * threading {@code --add-reads} through two build phases. {@link System.Logger} is in {@code
   * java.base}, so injecting one costs a single reference per registry and no build configuration
   * at all.
   */
  private final Logger logger;

  /**
   * The strategies, keyed as registered.
   *
   * <p>{@link ConcurrentHashMap} rather than a synchronized map or a copy-on-write structure: reads
   * take no lock at all, which is what makes NFR-04 reachable, and its {@code put} publishes safely
   * without the caller doing anything. A copy-on-write map would make reads even cheaper and
   * registration O(n); the workload is read-mostly but registration is not rare enough at startup
   * to pay that, and 1000 strategies would mean 1000 array copies.
   */
  private final ConcurrentMap<K, S> strategies = new ConcurrentHashMap<>();

  /** Creates an empty registry that warns through the platform logger. */
  public StrategyRegistry() {
    this(DEFAULT_LOGGER);
  }

  /**
   * Creates an empty registry that warns through {@code logger} — the seam described on {@link
   * #logger}, package-private so it is not public surface.
   *
   * @param logger where a re-registration warning goes
   */
  StrategyRegistry(Logger logger) {
    this.logger = logger;
  }

  /**
   * Registers {@code strategy} under {@code key}, replacing any strategy already there.
   *
   * <p>A replacement is legal — this registry exists to be reconfigured — but it is never silent:
   * it emits a {@code WARNING} naming the key and both strategy classes, because the same call is
   * also what two modules accidentally claiming one discriminator looks like.
   *
   * @param key the lookup key; must not be {@code null}
   * @param strategy the strategy to register; must not be {@code null}
   * @throws NullPointerException if {@code key} or {@code strategy} is {@code null}
   */
  public void register(K key, S strategy) {
    Objects.requireNonNull(key, "StrategyRegistry key must not be null");
    Objects.requireNonNull(strategy, "StrategyRegistry strategy must not be null");
    // put() returns the displaced value atomically, so the collision is detected without a
    // containsKey() race that could miss a concurrent registration or report a phantom one.
    S previous = strategies.put(key, strategy);
    if (previous != null) {
      // No apostrophe anywhere in this format string: System.Logger formats with MessageFormat,
      // where a single quote escapes the placeholder that follows and would print "{0}" verbatim.
      logger.log(
          Level.WARNING,
          "StrategyRegistry: key [{0}] re-registered, replacing {1} with {2} (last-write-wins)",
          key,
          previous.getClass().getName(),
          strategy.getClass().getName());
    }
  }

  /**
   * The strategy registered under {@code key}, if any.
   *
   * @param key the lookup key; must not be {@code null}
   * @return the strategy, or {@link Optional#empty()} if nothing is registered; never {@code null}
   * @throws NullPointerException if {@code key} is {@code null}
   */
  public Optional<S> find(K key) {
    Objects.requireNonNull(key, "StrategyRegistry key must not be null");
    return Optional.ofNullable(strategies.get(key));
  }

  /**
   * The strategy registered under {@code key}, or a failure naming every key that <em>is</em>
   * registered.
   *
   * @param key the lookup key; must not be {@code null}
   * @return the registered strategy; never {@code null}
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws StrategyNotFoundException if no strategy is registered under {@code key}
   */
  public S getOrThrow(K key) {
    Objects.requireNonNull(key, "StrategyRegistry key must not be null");
    // Deliberately not find(key).orElseThrow(...): that would allocate an Optional on the hot path
    // only to unwrap it, and this method is as hot as find().
    S strategy = strategies.get(key);
    if (strategy == null) {
      // keySet() is a live view, and the exception copies it immediately. A registration racing
      // this throw may or may not appear in the list, which is inherent to reporting on a
      // concurrent structure and is why the message says "known", not "all".
      //
      // Left inline deliberately. Moving the throw into a private method — the fast/slow split that
      // Lazy.get() needs for NFR-01 (ADR-0013) — was tried here and measured: it changed nothing
      // (14.57 ns/op before, 14.57 after), so the split would have been ceremony imported from a
      // sibling type rather than an optimisation. ADR-0015 records the experiment.
      throw new StrategyNotFoundException(key, strategies.keySet());
    }
    return strategy;
  }
}
