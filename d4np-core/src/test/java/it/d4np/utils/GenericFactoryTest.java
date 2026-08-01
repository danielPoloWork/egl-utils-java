package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code GenericFactory<T,K>} against the RFC-0001 FR-01 table — ROADMAP item 2.4.
 *
 * <p>One group per row of that table, plus the two things the table implies rather than states:
 * that every call constructs a <em>fresh</em> instance, and that duplicate rejection survives a
 * race (proven properly by {@code GenericFactoryRegistrationStress}; the test here is a fast net).
 */
@DisplayName("GenericFactory")
class GenericFactoryTest {

  private GenericFactory<List<String>, String> factory;

  @BeforeEach
  void freshFactory() {
    factory = new GenericFactory<>();
  }

  // --- create / tryCreate ---

  @Test
  @DisplayName("create invokes the bound supplier")
  void createInvokesTheSupplier() {
    factory.register("list", ArrayList::new);

    List<String> created = factory.create("list");

    assertThat(created).isInstanceOf(ArrayList.class);
    assertThat(created).isEmpty();
  }

  @Test
  @DisplayName("every create returns a distinct instance — a factory, not a registry")
  void createReturnsAFreshInstanceEveryTime() {
    factory.register("list", ArrayList::new);

    List<String> first = factory.create("list");
    List<String> second = factory.create("list");

    assertThat(first).isNotSameAs(second);
  }

  @Test
  @DisplayName("create on an unbound key names the key and every key that is bound")
  void createNamesTheKnownKeys() {
    factory.register("alpha", ArrayList::new);
    factory.register("beta", ArrayList::new);

    FactoryKeyNotFoundException thrown =
        catchThrowableOfType(FactoryKeyNotFoundException.class, () -> factory.create("gamma"));

    assertThat(thrown.key()).isEqualTo("gamma");
    assertThat(thrown.knownKeys()).containsExactly("alpha", "beta");
    assertThat(thrown).hasMessageContaining("gamma").hasMessageContaining("alpha, beta");
  }

  @Test
  @DisplayName("tryCreate is empty on an unbound key rather than throwing")
  void tryCreateIsEmptyWhenUnbound() {
    factory.register("list", ArrayList::new);

    assertThat(factory.tryCreate("nope")).isEmpty();
  }

  @Test
  @DisplayName("tryCreate constructs when the key is bound")
  void tryCreateConstructsWhenBound() {
    factory.register("list", ArrayList::new);

    assertThat(factory.tryCreate("list")).isPresent().get().isInstanceOf(ArrayList.class);
  }

  // --- register rejects duplicates; replace overrides ---

  @Test
  @DisplayName("register rejects a duplicate key rather than silently overwriting")
  void registerRejectsADuplicate() {
    factory.register("list", ArrayList::new);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> factory.register("list", ArrayList::new))
        .withMessageContaining("already registered")
        .withMessageContaining("replace()");
  }

  @Test
  @DisplayName("a rejected duplicate leaves the original binding intact")
  void aRejectedDuplicateChangesNothing() {
    Supplier<List<String>> original = ArrayList::new;
    factory.register("list", original);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> factory.register("list", () -> List.of("replacement")));

    assertThat(factory.create("list")).isEmpty();
  }

  @Test
  @DisplayName("replace overrides a bound key, which is the explicit escape hatch")
  void replaceOverridesABoundKey() {
    factory.register("list", ArrayList::new);

    factory.replace("list", () -> new ArrayList<>(List.of("replaced")));

    assertThat(factory.create("list")).containsExactly("replaced");
  }

  @Test
  @DisplayName("replace also binds a key that was never registered")
  void replaceBindsAnUnboundKey() {
    factory.replace("fresh", () -> new ArrayList<>(List.of("v")));

    assertThat(factory.create("fresh")).containsExactly("v");
  }

  // --- keys() ---

  @Test
  @DisplayName("keys() reports every bound key")
  void keysReportsEveryBoundKey() {
    factory.register("a", ArrayList::new);
    factory.register("b", ArrayList::new);

    assertThat(factory.keys()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  @DisplayName("keys() is an unmodifiable snapshot, not a live view")
  void keysIsAnUnmodifiableSnapshot() {
    factory.register("a", ArrayList::new);
    Set<String> snapshot = factory.keys();

    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> snapshot.add("b"));

    // "Snapshot" is the contract word: a later registration must not appear in a set already handed
    // out, which an unmodifiable VIEW over the live key set would do.
    factory.register("b", ArrayList::new);
    assertThat(snapshot).containsExactly("a");
    assertThat(factory.keys()).containsExactlyInAnyOrder("a", "b");
  }

  // --- a supplier returning null is a defect ---

  @Test
  @DisplayName("create rejects a supplier that returns null, naming the key")
  void createRejectsANullFromTheSupplier() {
    factory.register("broken", () -> null);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> factory.create("broken"))
        .withMessageContaining("broken")
        .withMessageContaining("returned null");
  }

  @Test
  @DisplayName("tryCreate also rejects it — a broken supplier is not an absent key")
  void tryCreateRejectsANullFromTheSupplier() {
    factory.register("broken", () -> null);

    // Optional.empty() here would make a defect indistinguishable from an unbound key, which is the
    // one thing tryCreate's return type is supposed to tell the caller.
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> factory.tryCreate("broken"))
        .withMessageContaining("returned null");
  }

  // --- the non-null boundary ---

  @Test
  @DisplayName("is final")
  void isFinal() {
    assertThat(Modifier.isFinal(GenericFactory.class.getModifiers())).isTrue();
  }

  /** Every entry point rejects null rather than deferring to ConcurrentHashMap's bare NPE. */
  @Nested
  @DisplayName("rejects nulls")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  class RejectsNulls {

    @Test
    @DisplayName("register(null, supplier) and register(key, null)")
    void registerRejectsNulls() {
      assertThatNullPointerException()
          .isThrownBy(() -> factory.register(null, ArrayList::new))
          .withMessageContaining("key");
      assertThatNullPointerException()
          .isThrownBy(() -> factory.register("k", null))
          .withMessageContaining("supplier");
    }

    @Test
    @DisplayName("replace(null, supplier) and replace(key, null)")
    void replaceRejectsNulls() {
      assertThatNullPointerException()
          .isThrownBy(() -> factory.replace(null, ArrayList::new))
          .withMessageContaining("key");
      assertThatNullPointerException()
          .isThrownBy(() -> factory.replace("k", null))
          .withMessageContaining("supplier");
    }

    @Test
    @DisplayName("create(null) and tryCreate(null)")
    void lookupsRejectNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> factory.create(null))
          .withMessageContaining("key");
      assertThatNullPointerException()
          .isThrownBy(() -> factory.tryCreate(null))
          .withMessageContaining("key");
    }
  }

  // --- concurrency: the fast net, not the proof ---

  @Test
  @DisplayName("when eight threads register one key, exactly one wins and seven are rejected")
  void duplicateRejectionSurvivesARace() throws Exception {
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier startTogether = new CyclicBarrier(threads);
      AtomicInteger accepted = new AtomicInteger();
      AtomicInteger rejected = new AtomicInteger();
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  try {
                    factory.register("contested", ArrayList::new);
                    accepted.incrementAndGet();
                  } catch (IllegalStateException rejection) {
                    rejected.incrementAndGet();
                  }
                  return null;
                }));
      }
      for (Future<?> result : results) {
        result.get();
      }

      // A containsKey-then-put implementation would let several threads believe they won.
      assertThat(accepted).as("exactly one registration succeeds").hasValue(1);
      assertThat(rejected).hasValue(threads - 1);
      assertThat(factory.keys()).containsExactly("contested");
    } finally {
      pool.shutdownNow();
    }
  }
}
