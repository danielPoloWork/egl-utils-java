package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-15's fallback (RFC-0002): what {@link LoggingExecutionTimeRecorder} actually writes.
 *
 * <p>Split from {@link ExecutionTimeMetricAspectTest} because these are assertions about a log
 * line, which is a different contract from the timing policy — and the two traps below are both
 * invisible unless the rendered string is inspected.
 */
@DisplayName("LoggingExecutionTimeRecorder")
class LoggingExecutionTimeRecorderTest {

  /** A logger that refuses the level, to prove the recorder asks before it renders. */
  private static final class Deaf implements System.Logger {

    private final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    public String getName() {
      return "deaf";
    }

    @Override
    public boolean isLoggable(Level level) {
      return false;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Object... params) {
      messages.add(msg);
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
      messages.add(msg);
    }
  }

  private final LogRecorder log = new LogRecorder();

  private final ExecutionTimeRecorder recorder = LoggingExecutionTimeRecorder.using(log);

  @Test
  void writesTheNameTheElapsedMicrosecondsAndTheOutcome() {
    recorder.record("OrderService.place", Duration.ofNanos(3_500_000L), false);

    assertThat(log.messages()).hasSize(1);
    assertThat(log.messages().get(0))
        .isEqualTo("DEBUG d4np execution time: OrderService.place took 3500 us (failed: false)");
  }

  @Test
  void distinguishesAFailedExecution() {
    recorder.record("OrderService.place", Duration.ofMillis(1), true);

    assertThat(log.messages().get(0)).endsWith("(failed: true)");
  }

  /**
   * {@code DEBUG}, and it is a decision: one record per invocation at {@code INFO} would be on by
   * default in every host that configured no backend, which is how instrumentation becomes the
   * dominant cost of the method it measures (ADR-0021).
   */
  @Test
  void writesAtDebug() {
    recorder.record("OrderService.place", Duration.ZERO, false);

    assertThat(LoggingExecutionTimeRecorder.LEVEL).isEqualTo(Level.DEBUG);
    assertThat(log.messages().get(0)).startsWith("DEBUG ");
  }

  /**
   * The {@link java.text.MessageFormat} trap ADR-0014 records: a single quote in the format escapes
   * the placeholder that follows, so an apostrophe anywhere in this line would print {@code {0}}
   * verbatim instead of the method name. Asserted rather than reviewed.
   */
  @Test
  void rendersEveryPlaceholder() {
    recorder.record("OrderService.place", Duration.ofNanos(1_000L), true);

    assertThat(log.messages().get(0)).doesNotContain("{0}", "{1}", "{2}");
  }

  /**
   * The same locale hazard C-03 records for identifiers, in a different shape. {@code
   * MessageFormat} renders a {@code Long} through the <em>default</em> locale, so a raw {@code
   * 1234567} would print as {@code 1.234.567} on an Italian JVM and {@code 1,234,567} on a US one —
   * the same measurement, two log lines that cannot be diffed or parsed by one expression. Passing
   * the number pre-rendered is what makes the output host-independent, and this test reproduces the
   * hazard rather than reasoning about it.
   */
  @Test
  void rendersTheDurationIdenticallyOnEveryLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.ITALY);
      recorder.record("OrderService.place", Duration.ofNanos(1_234_567_000L), false);
      Locale.setDefault(Locale.US);
      recorder.record("OrderService.place", Duration.ofNanos(1_234_567_000L), false);
    } finally {
      Locale.setDefault(original);
    }

    assertThat(log.messages().get(0)).isEqualTo(log.messages().get(1)).contains("1234567 us");
  }

  /**
   * Rendering costs allocations on every call of every measured method, so the recorder asks the
   * logger first — the production case is a host that never enabled {@code DEBUG}.
   */
  @Test
  void rendersNothingWhenTheLevelIsOff() {
    Deaf deaf = new Deaf();

    LoggingExecutionTimeRecorder.using(deaf).record("OrderService.place", Duration.ZERO, false);

    assertThat(deaf.messages).isEmpty();
  }

  @Test
  void writesToThePlatformLoggerByDefault() {
    // Nothing to assert about a platform logger's output from inside this module (ADR-0014); what
    // is
    // assertable is that the default path is wired and records without throwing.
    LoggingExecutionTimeRecorder.create().record("OrderService.place", Duration.ofMillis(2), false);
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsAnAbsentNameDurationOrLogger() {
    assertThatNullPointerException().isThrownBy(() -> recorder.record(null, Duration.ZERO, false));
    assertThatNullPointerException()
        .isThrownBy(() -> recorder.record("OrderService.place", null, false));
    assertThatNullPointerException().isThrownBy(() -> LoggingExecutionTimeRecorder.using(null));

    assertThat(log.messages()).isEmpty();
  }
}
