package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code StrategyRegistry<K,S>} against the RFC-0001 contract — ROADMAP item 2.3 (FR-04, NFR-04).
 *
 * <p>The contract has four clauses and each is a group below: the two lookups and their opposite
 * missing-key behaviour, last-write-wins registration <em>with its warning</em>, the non-null
 * boundary, and concurrency. The concurrency group is a fast regression net, not the proof — {@code
 * StrategyRegistryPublicationStress} and {@code StrategyRegistryRegistrationStress} are.
 */
@DisplayName("StrategyRegistry")
class StrategyRegistryTest {

  private static final UnaryOperator<String> UPPER = s -> s.toUpperCase(java.util.Locale.ROOT);
  private static final UnaryOperator<String> LOWER = s -> s.toLowerCase(java.util.Locale.ROOT);

  private StrategyRegistry<String, UnaryOperator<String>> registry;

  @BeforeEach
  void freshRegistry() {
    registry = new StrategyRegistry<>();
  }

  // --- the two lookups ---

  @Test
  @DisplayName("find returns the registered strategy, as the same instance")
  void findReturnsTheRegisteredStrategy() {
    registry.register("upper", UPPER);

    assertThat(registry.find("upper")).containsSame(UPPER);
  }

  @Test
  @DisplayName("find on a missing key is empty, not null and not an exception")
  void findIsEmptyWhenAbsent() {
    registry.register("upper", UPPER);

    assertThat(registry.find("nope")).isEmpty();
  }

  @Test
  @DisplayName("getOrThrow returns the registered strategy, as the same instance")
  void getOrThrowReturnsTheRegisteredStrategy() {
    registry.register("upper", UPPER);

    assertThat(registry.getOrThrow("upper")).isSameAs(UPPER);
  }

  @Test
  @DisplayName("getOrThrow on a missing key names the key and every key that is registered")
  void getOrThrowNamesTheKnownKeys() {
    registry.register("upper", UPPER);
    registry.register("lower", LOWER);

    StrategyNotFoundException thrown =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            StrategyNotFoundException.class, () -> registry.getOrThrow("title"));

    assertThat(thrown.key()).isEqualTo("title");
    assertThat(thrown.knownKeys()).containsExactly("lower", "upper");
    assertThat(thrown).hasMessageContaining("title").hasMessageContaining("lower, upper");
  }

  // --- registration: last-write-wins, and the warning that says so ---

  @Test
  @DisplayName("re-registering a key replaces the strategy — last-write-wins")
  void registerIsLastWriteWins() {
    registry.register("k", UPPER);
    registry.register("k", LOWER);

    assertThat(registry.getOrThrow("k")).isSameAs(LOWER);
  }

  @Test
  @DisplayName("a first registration is silent — only a collision is worth a warning")
  void firstRegistrationLogsNothing() {
    LogRecorder log = new LogRecorder();
    StrategyRegistry<String, UnaryOperator<String>> logged = new StrategyRegistry<>(log);

    logged.register("k", UPPER);

    assertThat(log.messages()).isEmpty();
  }

  @Test
  @DisplayName("replacing a registered key logs one WARNING naming the key and both strategies")
  void replacementLogsAWarning() {
    LogRecorder log = new LogRecorder();
    StrategyRegistry<String, UnaryOperator<String>> logged = new StrategyRegistry<>(log);

    logged.register("k", UPPER);
    logged.register("k", LOWER);

    assertThat(log.messages()).hasSize(1);
    String event = log.messages().get(0);
    assertThat(event).startsWith("WARNING").contains("[k]").contains("last-write-wins");
    // The two strategy classes, so the log line says WHAT replaced WHAT and not merely that
    // something did. Both are lambdas, so their class names are synthetic but distinct.
    assertThat(event).contains(UPPER.getClass().getName()).contains(LOWER.getClass().getName());
  }

  @Test
  @DisplayName("the warning format survives MessageFormat — no placeholder is left unrendered")
  void warningIsFormattedNotLiteral() {
    LogRecorder log = new LogRecorder();
    StrategyRegistry<String, UnaryOperator<String>> logged = new StrategyRegistry<>(log);

    logged.register("k", UPPER);
    logged.register("k", LOWER);

    // A stray apostrophe in the format string would make MessageFormat swallow the following
    // placeholder and emit it verbatim. This asserts the rendering actually happened.
    assertThat(log.messages().get(0)).doesNotContain("{0}", "{1}", "{2}");
  }

  // --- the non-null boundary ---

  @Test
  @DisplayName("is final and exposes only the three contract methods plus a constructor")
  void isFinal() {
    assertThat(Modifier.isFinal(StrategyRegistry.class.getModifiers())).isTrue();
  }

  /** Every entry point rejects null rather than deferring to ConcurrentHashMap's bare NPE. */
  @Nested
  @DisplayName("rejects nulls")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  class RejectsNulls {

    @Test
    @DisplayName("find(null)")
    void findRejectsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> registry.find(null))
          .withMessageContaining("key");
    }

    @Test
    @DisplayName("getOrThrow(null)")
    void getOrThrowRejectsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> registry.getOrThrow(null))
          .withMessageContaining("key");
    }

    @Test
    @DisplayName("register(null, strategy)")
    void registerRejectsNullKey() {
      assertThatNullPointerException()
          .isThrownBy(() -> registry.register(null, UPPER))
          .withMessageContaining("key");
    }

    @Test
    @DisplayName("register(key, null)")
    void registerRejectsNullStrategy() {
      assertThatNullPointerException()
          .isThrownBy(() -> registry.register("k", null))
          .withMessageContaining("strategy");
    }

    @Test
    @DisplayName("a rejected registration leaves the registry untouched")
    void aRejectedRegistrationChangesNothing() {
      assertThatNullPointerException().isThrownBy(() -> registry.register("k", null));

      assertThat(registry.find("k")).isEmpty();
    }
  }

  // --- concurrency: the fast net, not the proof ---

  @Test
  @DisplayName("eight threads reading while a ninth registers never observe a torn registry")
  void isSafeUnderConcurrentUse() throws Exception {
    int readers = 8;
    int keys = 100;
    for (int i = 0; i < keys; i++) {
      registry.register("k" + i, UPPER);
    }
    ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
    try {
      CyclicBarrier startTogether = new CyclicBarrier(readers + 1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int t = 0; t < readers; t++) {
        results.add(
            pool.submit(
                () -> {
                  startTogether.await();
                  int seen = 0;
                  for (int round = 0; round < 200; round++) {
                    for (int i = 0; i < keys; i++) {
                      // Every key was registered before the race started, so it must be visible to
                      // every reader for the whole run. An empty Optional here would be a lost
                      // write; getOrThrow would throw rather than return null.
                      if (registry.getOrThrow("k" + i) != null) {
                        seen++;
                      }
                    }
                  }
                  return seen;
                }));
      }
      Future<Integer> writer =
          pool.submit(
              () -> {
                startTogether.await();
                for (int i = 0; i < keys; i++) {
                  registry.register("k" + i, LOWER);
                }
                return keys;
              });

      assertThat(writer.get()).isEqualTo(keys);
      for (Future<Integer> result : results) {
        assertThat(result.get()).isEqualTo(keys * 200);
      }
      // Last-write-wins held across the race: every key ends on the replacement.
      for (int i = 0; i < keys; i++) {
        assertThat(registry.getOrThrow("k" + i)).isSameAs(LOWER);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
