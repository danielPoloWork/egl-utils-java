package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code FactoryKeyNotFoundException} — ROADMAP item 2.4 (FR-01, and FR-19's mapping table).
 *
 * <p>The behaviour it shares with {@code StrategyNotFoundException} lives in {@code KeyDiagnostics}
 * and is tested once, there and through the strategy exception. What is asserted here is what is
 * specific to <em>this</em> type: its two hierarchy decisions, and that the shared diagnostic is
 * actually wired in with the right noun.
 */
@DisplayName("FactoryKeyNotFoundException")
class FactoryKeyNotFoundExceptionTest {

  @Test
  @DisplayName("carries the missing key and every bound key, sorted")
  void carriesTheKeyAndTheKnownKeys() {
    FactoryKeyNotFoundException thrown =
        new FactoryKeyNotFoundException("missing", List.of("b", "a"));

    assertThat(thrown.key()).isEqualTo("missing");
    assertThat(thrown.knownKeys()).containsExactly("a", "b");
    assertThat(thrown).hasMessageContaining("supplier").hasMessageContaining("a, b");
  }

  @Test
  @DisplayName("says so plainly when nothing is bound")
  void reportsAnEmptyFactory() {
    FactoryKeyNotFoundException thrown = new FactoryKeyNotFoundException("k", List.of());

    assertThat(thrown.knownKeys()).isEmpty();
    assertThat(thrown).hasMessageContaining("nothing is registered");
  }

  @Test
  @DisplayName("truncates the message at the shared cap but keeps every key")
  void truncatesTheMessageNotTheData() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      many.add(String.format("key%04d", i));
    }

    FactoryKeyNotFoundException thrown = new FactoryKeyNotFoundException("nope", many);

    assertThat(thrown.knownKeys()).hasSize(500);
    assertThat(thrown).hasMessageContaining("and " + (500 - KeyDiagnostics.MAX_KEYS_IN_MESSAGE));
  }

  @Test
  @DisplayName("knownKeys() is unmodifiable")
  void knownKeysIsUnmodifiable() {
    Set<String> keys = new FactoryKeyNotFoundException("k", List.of("a")).knownKeys();

    assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> keys.add("b"));
  }

  @Test
  @DisplayName("is NOT a BusinessException — an unbound key is a wiring defect, not a 422")
  void isNotABusinessException() {
    FactoryKeyNotFoundException thrown = new FactoryKeyNotFoundException("k", List.of());

    assertThat(thrown)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("is a SIBLING of StrategyNotFoundException, not a subclass of it")
  void isNotAStrategyNotFoundException() {
    // The two describe different failures of different types. Sharing a supertype merely to share
    // two accessors would put that coupling in the published hierarchy forever, and a consumer
    // catching one would silently start catching the other.
    assertThat(new FactoryKeyNotFoundException("k", List.of()))
        .isNotInstanceOf(StrategyNotFoundException.class);
    assertThat(StrategyNotFoundException.class.isAssignableFrom(FactoryKeyNotFoundException.class))
        .isFalse();
  }

  @Test
  @DisplayName("survives a serialisation round trip, which every Throwable claims to")
  void isSerializable() throws Exception {
    FactoryKeyNotFoundException original = new FactoryKeyNotFoundException("k", List.of("a", "b"));

    FactoryKeyNotFoundException restored = SerializationSupport.roundTrip(original);

    assertThat(restored.key()).isEqualTo("k");
    assertThat(restored.knownKeys()).containsExactly("a", "b");
    assertThat(restored).hasMessage(original.getMessage());
  }
}
