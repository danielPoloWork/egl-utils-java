package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import it.d4np.utils.AuditFixtures.Account;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-16's fallback sink (RFC-0002): the line an audit record turns into when no store was supplied.
 *
 * <p>{@code writesThePasswordChangedLineWithoutThePassword} is the line the whole feature exists to
 * be able to write, so it is asserted verbatim rather than by fragments; {@code
 * rendersIdenticallyOnEveryLocale} closes the trap item 3.2 found one class along — {@link
 * System.Logger} substitutes with {@link java.text.MessageFormat}, which renders numbers and dates
 * through the default locale.
 */
@DisplayName("LoggingAuditSink")
class LoggingAuditSinkTest {

  /** A logger that refuses the level, to prove the sink asks before it renders. */
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

  private final AuditLog audit = AuditLog.using(LoggingAuditSink.using(log));

  @Test
  void writesThePasswordChangedLineWithoutThePassword() {
    audit.record(
        audit.capture(
            "alice",
            "PASSWORD_ROTATED",
            new Account("alice", "hunter2", 41),
            new Account("alice", "correct-horse", 41)));

    assertThat(log.messages()).hasSize(1);
    assertThat(log.messages().get(0))
        .startsWith("INFO d4np audit: alice performed PASSWORD_ROTATED on ")
        .contains(Account.class.getName())
        .endsWith(
            "changes: loginCount: 41 -> 41, owner: alice -> alice,"
                + " password: [REDACTED] -> [REDACTED] (changed)")
        .doesNotContain("hunter2")
        .doesNotContain("correct-horse");
  }

  @Test
  void writesAtInfoBecauseAnAuditRecordIsNotOptional() {
    // The deliberate opposite of LoggingExecutionTimeRecorder's DEBUG: instrumentation a host never
    // asked for should be invisible by default, an audit record should not be.
    assertThat(LoggingAuditSink.LEVEL).isEqualTo(Level.INFO);
    assertThat(LoggingExecutionTimeRecorder.LEVEL).isEqualTo(Level.DEBUG);
  }

  @Test
  void marksAnAbsentValueRatherThanPrintingNull() {
    audit.record(audit.capture("alice", "CREATED", null, new Account("alice", "hunter2", 0)));

    assertThat(log.messages().get(0))
        .contains("owner: <absent> -> alice (changed)")
        .contains("password: [REDACTED] -> [REDACTED] (changed)");
  }

  @Test
  void rendersIdenticallyOnEveryLocale() {
    // One event written twice, not two captures: two captures carry two timestamps, and the
    // assertion
    // is about the rendering rather than about the clock.
    AuditEvent event = audit.capture("alice", "USED", null, new Account("alice", "p", 1234567));
    AuditSink sink = LoggingAuditSink.using(log);
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.ITALY);
      sink.write(event);
      Locale.setDefault(Locale.US);
      sink.write(event);
    } finally {
      Locale.setDefault(original);
    }

    // An unrendered Long would print 1.234.567 here and 1,234,567 there, and one audit line would
    // stop
    // being comparable with another. Every parameter reaches the logger already text.
    assertThat(log.messages().get(0)).contains("loginCount: <absent> -> 1234567");
    assertThat(log.messages().get(0)).isEqualTo(log.messages().get(1));
  }

  @Test
  void asksTheLevelBeforeRendering() {
    Deaf deaf = new Deaf();
    AuditLog quiet = AuditLog.using(LoggingAuditSink.using(deaf));

    quiet.record(quiet.capture("alice", "CREATED", null, new Account("alice", "p", 0)));

    assertThat(deaf.messages).isEmpty();
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void refusesNulls() {
    assertThatNullPointerException().isThrownBy(() -> LoggingAuditSink.using(null));
    assertThatNullPointerException().isThrownBy(() -> LoggingAuditSink.create().write(null));
  }
}
