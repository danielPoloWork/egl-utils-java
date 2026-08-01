package it.d4np.utils;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Construction by key, without the caller ever naming a concrete type.
 *
 * <pre>{@code
 * private static final GenericFactory<Report, String> REPORTS = new GenericFactory<>();
 * ...
 * REPORTS.register("pdf", PdfReport::new);
 * REPORTS.register("csv", CsvReport::new);
 * Report report = REPORTS.create(request.format());   // a new instance per call
 * }</pre>
 *
 * <p><strong>A duplicate key is rejected, and that is the deliberate opposite of {@link
 * StrategyRegistry}.</strong> The two look alike and behave differently on purpose, because their
 * lifecycles differ:
 *
 * <table border="1">
 *   <caption>Why the two disagree about duplicates</caption>
 *   <tr><th></th><th>{@code GenericFactory}</th><th>{@code StrategyRegistry}</th></tr>
 *   <tr>
 *     <td>Wired</td>
 *     <td>once, at startup</td>
 *     <td>and re-wired at runtime</td>
 *   </tr>
 *   <tr>
 *     <td>A second {@code register} on a live key</td>
 *     <td>{@link IllegalStateException}</td>
 *     <td>replaces it, with a {@code WARNING}</td>
 *   </tr>
 *   <tr>
 *     <td>Because a duplicate usually means</td>
 *     <td>two modules claiming one discriminator — so the winner would depend on classpath order</td>
 *     <td>reconfiguration, which is the feature</td>
 *   </tr>
 * </table>
 *
 * <p>Overriding is still available, through {@link #replace(Object, Supplier)} — the point is that
 * the <em>intent</em> is visible at the call site rather than inferred from which module happened
 * to load first. RFC-0001 §Alternatives records the asymmetry; ADR-0016 records what implementing
 * it added.
 *
 * <p><strong>Two lookups, as with the registry.</strong> {@link #create(Object)} throws {@link
 * FactoryKeyNotFoundException} naming every bound key, for the case where an unknown key is a
 * wiring defect; {@link #tryCreate(Object)} returns {@link Optional#empty()}, for the case where
 * absence is an ordinary answer the caller has a fallback for.
 *
 * <p><strong>A supplier that returns {@code null} is a programming error</strong>, not a way to say
 * "no instance": {@code create} throws {@link IllegalStateException} naming the key rather than
 * handing the caller a {@code null} that fails somewhere else. {@code tryCreate} rejects it the
 * same way, deliberately — returning {@code Optional.empty()} there would make a broken supplier
 * indistinguishable from an unbound key.
 *
 * <p><strong>Thread safety.</strong> Fully thread-safe, and the guarantee is asserted rather than
 * claimed (spec §6): {@code GenericFactoryRegistrationStress} proves that when two threads register
 * the same key exactly one wins and the other is rejected — never both, and never neither. Neither
 * RFC-0001 nor the specification stated a thread-safety contract for this type; ADR-0016 decides
 * it, and the decision is what makes the atomic duplicate check below necessary rather than
 * incidental.
 *
 * <p>What this class does <em>not</em> promise is anything about the instances it hands out: they
 * come from caller-supplied suppliers, and their thread safety is their own. The factory guarantees
 * only that registration and lookup are safe.
 *
 * <p>There is no performance budget on this type — no NFR names it — so, unlike {@code
 * StrategyRegistry.find}, it carries no benchmark. {@code tryCreate} allocating an {@link Optional}
 * is noted in RFC-0001 §Performance and is not a measured concern here.
 *
 * @param <T> the type this factory constructs
 * @param <K> the key type; must be usable as a {@link ConcurrentHashMap} key, so {@code equals} and
 *     {@code hashCode} must agree and be stable for as long as the key is bound
 * @see StrategyRegistry
 * @see FactoryKeyNotFoundException
 */
public final class GenericFactory<T, K> {

  /**
   * The bound suppliers.
   *
   * <p>{@link ConcurrentHashMap} for the same reason {@code StrategyRegistry} uses one — lock-free
   * reads — and for one this type needs more: {@link ConcurrentMap#putIfAbsent} makes "reject a
   * duplicate" a single atomic operation. See {@link #register(Object, Supplier)}.
   */
  private final ConcurrentMap<K, Supplier<? extends T>> suppliers = new ConcurrentHashMap<>();

  /** Creates an empty factory. */
  public GenericFactory() {
    // Nothing to initialise; declared so the Javadoc above has somewhere to live.
  }

  /**
   * Binds {@code supplier} to {@code key}, and fails if the key is already bound.
   *
   * <p>Use {@link #replace(Object, Supplier)} when overriding is the intent.
   *
   * @param key the key to bind; must not be {@code null}
   * @param supplier constructs an instance per {@link #create(Object)} call; must not be {@code
   *     null}
   * @throws NullPointerException if {@code key} or {@code supplier} is {@code null}
   * @throws IllegalStateException if {@code key} is already bound
   */
  public void register(K key, Supplier<? extends T> supplier) {
    Objects.requireNonNull(key, "GenericFactory key must not be null");
    Objects.requireNonNull(supplier, "GenericFactory supplier must not be null");
    // putIfAbsent, NOT containsKey-then-put: the two-step version is a check-then-act race in which
    // two threads registering the same key can both see it absent and both believe they won, so the
    // duplicate this method exists to reject would be silently accepted exactly when it matters
    // (parallel module initialisation). GenericFactoryRegistrationStress forbids that outcome.
    Supplier<? extends T> existing = suppliers.putIfAbsent(key, supplier);
    if (existing != null) {
      throw new IllegalStateException(
          "GenericFactory key ["
              + key
              + "] is already registered to "
              + existing.getClass().getName()
              + "; call replace() if overriding is intended");
    }
  }

  /**
   * Binds {@code supplier} to {@code key}, whether or not the key is already bound.
   *
   * <p>The explicit override. Unlike {@link #register(Object, Supplier)} this never fails on a
   * duplicate, and unlike {@code StrategyRegistry.register} it needs no warning — a call to a
   * method named {@code replace} <em>is</em> the statement of intent that the warning would
   * otherwise have to reconstruct.
   *
   * @param key the key to bind; must not be {@code null}
   * @param supplier constructs an instance per {@link #create(Object)} call; must not be {@code
   *     null}
   * @throws NullPointerException if {@code key} or {@code supplier} is {@code null}
   */
  public void replace(K key, Supplier<? extends T> supplier) {
    Objects.requireNonNull(key, "GenericFactory key must not be null");
    Objects.requireNonNull(supplier, "GenericFactory supplier must not be null");
    suppliers.put(key, supplier);
  }

  /**
   * Constructs an instance from the supplier bound to {@code key}.
   *
   * @param key the key to construct for; must not be {@code null}
   * @return a fresh instance, as produced by the bound supplier; never {@code null}
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws FactoryKeyNotFoundException if no supplier is bound to {@code key}
   * @throws IllegalStateException if the bound supplier returns {@code null}
   */
  public T create(K key) {
    Objects.requireNonNull(key, "GenericFactory key must not be null");
    Supplier<? extends T> supplier = suppliers.get(key);
    if (supplier == null) {
      // keySet() is a live view and the exception copies it immediately; a registration racing this
      // throw may or may not appear, which is inherent to reporting on a concurrent structure.
      throw new FactoryKeyNotFoundException(key, suppliers.keySet());
    }
    return construct(key, supplier);
  }

  /**
   * Constructs an instance if {@code key} is bound.
   *
   * <p>Note the asymmetry with {@link #create(Object)}: an <em>unbound key</em> yields {@link
   * Optional#empty()}, but a bound supplier that returns {@code null} still throws. Collapsing the
   * two would make a broken supplier look exactly like a key nobody registered.
   *
   * @param key the key to construct for; must not be {@code null}
   * @return the constructed instance, or {@link Optional#empty()} if {@code key} is unbound
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalStateException if the bound supplier returns {@code null}
   */
  public Optional<T> tryCreate(K key) {
    Objects.requireNonNull(key, "GenericFactory key must not be null");
    Supplier<? extends T> supplier = suppliers.get(key);
    return supplier == null ? Optional.empty() : Optional.of(construct(key, supplier));
  }

  /**
   * Every key currently bound.
   *
   * @return an unmodifiable snapshot, detached from this factory; never {@code null}
   */
  public Set<K> keys() {
    // A copy, not Collections.unmodifiableSet over the live keySet: "snapshot" is the contract
    // (RFC-0001 FR-01), and an unmodifiable VIEW would keep changing under a caller who reasonably
    // read the word "snapshot" as meaning it would not.
    return Set.copyOf(suppliers.keySet());
  }

  /** Invokes a bound supplier and enforces the non-null result contract. */
  private T construct(K key, Supplier<? extends T> supplier) {
    T instance = supplier.get();
    if (instance == null) {
      throw new IllegalStateException(
          "GenericFactory supplier for key ["
              + key
              + "] returned null; create() never returns null");
    }
    return instance;
  }
}
