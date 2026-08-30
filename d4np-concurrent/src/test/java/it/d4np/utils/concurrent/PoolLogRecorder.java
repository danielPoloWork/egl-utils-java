package it.d4np.utils.concurrent;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link System.Logger} that keeps what it is told, so FR-08's two log lines can be asserted.
 *
 * <p>Handed in rather than installed as a {@code System.LoggerFinder}, for the reason ADR-0014
 * records: a finder registered through {@code META-INF/services} can never win inside a surefire
 * fork, because the platform logger is resolved before the test classpath exists.
 *
 * <p><strong>Rendering happens here on purpose.</strong> {@code System.Logger}'s default methods
 * hand the implementation the raw format and its parameters, and substituting them with {@link
 * MessageFormat} is what a real backend does — so a test can catch a format string {@code
 * MessageFormat} would mangle. An unescaped apostrophe swallows the placeholder after it and emits
 * {@code {0}} verbatim, which is the trap ADR-0014 recorded and item 3.2 met again.
 *
 * <p>{@link CopyOnWriteArrayList} rather than an {@code ArrayList}: the uncaught-exception handler
 * logs from a <em>pool</em> thread while the test asserts from the main one.
 *
 * <p>Named without a {@code Test} prefix or suffix so surefire does not offer it to the JUnit
 * Platform as a test class.
 */
final class PoolLogRecorder implements System.Logger {

  private final List<String> messages = new CopyOnWriteArrayList<>();

  /**
   * Everything logged so far, rendered as {@code LEVEL message}.
   *
   * @return one entry per call, in order
   */
  List<String> messages() {
    return List.copyOf(messages);
  }

  @Override
  public String getName() {
    return "recording";
  }

  @Override
  public boolean isLoggable(Level level) {
    return true;
  }

  @Override
  public void log(Level level, ResourceBundle bundle, String msg, Object... params) {
    messages.add(
        level
            + " "
            + (params == null || params.length == 0 ? msg : MessageFormat.format(msg, params)));
  }

  @Override
  public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
    messages.add(level + " " + msg + " / " + thrown);
  }
}
