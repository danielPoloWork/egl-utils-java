package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code StrategyNotFoundException} — ROADMAP item 2.3 (FR-04, and FR-19's mapping table).
 *
 * <p>Two of these tests guard decisions rather than behaviour, and are the reason this class exists
 * separately from {@code StrategyRegistryTest}: that the exception stays <em>outside</em> the
 * {@code BusinessException} hierarchy, because FR-19 maps the two to different HTTP statuses, and
 * that it survives Java serialisation, because every {@code Throwable} claims to.
 */
@DisplayName("StrategyNotFoundException")
class StrategyNotFoundExceptionTest {

  @Test
  @DisplayName("carries the missing key and every known key")
  void carriesTheKeyAndTheKnownKeys() {
    StrategyNotFoundException thrown =
        new StrategyNotFoundException("missing", List.of("b", "a", "c"));

    assertThat(thrown.key()).isEqualTo("missing");
    assertThat(thrown.knownKeys()).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("sorts the known keys, so two occurrences are comparable by eye")
  void sortsTheKnownKeys() {
    StrategyNotFoundException thrown =
        new StrategyNotFoundException("x", List.of("zebra", "alpha", "middle"));

    assertThat(thrown.knownKeys()).containsExactly("alpha", "middle", "zebra");
    assertThat(thrown).hasMessageContaining("alpha, middle, zebra");
  }

  @Test
  @DisplayName("renders non-String keys rather than requiring them to be String")
  void rendersArbitraryKeys() {
    StrategyNotFoundException thrown = new StrategyNotFoundException(42, List.of(1, 2));

    assertThat(thrown.key()).isEqualTo("42");
    assertThat(thrown.knownKeys()).containsExactly("1", "2");
  }

  @Test
  @DisplayName("says so plainly when nothing is registered at all")
  void reportsAnEmptyRegistry() {
    StrategyNotFoundException thrown = new StrategyNotFoundException("k", List.of());

    assertThat(thrown.knownKeys()).isEmpty();
    assertThat(thrown).hasMessageContaining("nothing is registered");
  }

  @Test
  @DisplayName("truncates the message at the cap but keeps every key in knownKeys()")
  void truncatesTheMessageNotTheData() {
    // NFR-04 sizes this registry at 1000 strategies, so the truncation branch is the normal case
    // at scale rather than an edge case.
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      many.add(String.format("key%04d", i));
    }

    StrategyNotFoundException thrown = new StrategyNotFoundException("nope", many);

    assertThat(thrown.knownKeys()).hasSize(1000);
    assertThat(thrown).hasMessageContaining("1000 known");
    // The truncation is stated, not silent: a reader who sees the count knows to ask for the rest.
    int hidden = 1000 - KeyDiagnostics.MAX_KEYS_IN_MESSAGE;
    assertThat(thrown).hasMessageContaining("and " + hidden + " more");
    assertThat(thrown.getMessage()).contains("key0000").doesNotContain("key0999");
  }

  @Test
  @DisplayName("knownKeys() is unmodifiable, so a handler cannot corrupt the diagnostic")
  void knownKeysIsUnmodifiable() {
    Set<String> keys = new StrategyNotFoundException("k", List.of("a")).knownKeys();

    assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> keys.add("b"));
  }

  @Test
  @DisplayName("copies the key collection, so a later mutation cannot rewrite history")
  void copiesTheKeyCollection() {
    List<String> live = new ArrayList<>(List.of("a"));

    StrategyNotFoundException thrown = new StrategyNotFoundException("k", live);
    live.add("b");

    assertThat(thrown.knownKeys()).containsExactly("a");
  }

  @Test
  @DisplayName("is NOT a BusinessException — FR-19 maps the two to different statuses")
  void isNotABusinessException() {
    StrategyNotFoundException thrown = new StrategyNotFoundException("k", List.of());

    // A missing strategy is a wiring defect (500 + alert), not a rule violation (422). If this ever
    // becomes a subclass, a GlobalExceptionHandler whose catch clauses run in the wrong order will
    // silently report an operations failure as a client error.
    assertThat(thrown)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("survives a serialisation round trip, which every Throwable claims to")
  void isSerializable() throws Exception {
    StrategyNotFoundException original = new StrategyNotFoundException("k", List.of("a", "b"));

    StrategyNotFoundException restored = SerializationSupport.roundTrip(original);

    // Keys are captured as text precisely so this holds for ANY key type the consumer chooses; a
    // Set<K> field would make it hold only when K happened to be serialisable.
    assertThat(restored.key()).isEqualTo("k");
    assertThat(restored.knownKeys()).containsExactly("a", "b");
    assertThat(restored).hasMessage(original.getMessage());
  }
}
