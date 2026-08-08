package it.d4np.utils.jdbc;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link System.Logger} that keeps what it is told, so FR-06's two log lines can be asserted.
 *
 * <p>FR-06 states them as part of the contract — <em>a rollback is logged at {@code DEBUG}, and a
 * rollback that itself failed at {@code WARNING}</em> — and spec §6 does not accept a contract
 * clause without a test.
 *
 * <p><strong>A second copy of core's {@code LogRecorder}, deliberately.</strong> That one is a
 * package-private test class in {@code it.d4np.utils}, and a test source root is not published: to
 * share it this module would need a test-jar dependency on core, which is a build edge — and a real
 * one, visible to every consumer reading the POM — bought to save forty lines. This is the same
 * trade item 4.1 made when {@code JsonDiagnostics} duplicated core's {@code KeyDiagnostics} rather
 * than exporting an implementation detail across a module boundary.
 *
 * <p>Core's version records the two findings worth carrying, and they are why this one exists at
 * all rather than a {@code System.LoggerFinder}: a finder registered through {@code
 * META-INF/services} <strong>can never win under surefire</strong>, because the JDK resolves the
 * finder once per VM on the first {@code System.getLogger} call and something has already triggered
 * platform logging by then; and attaching a {@code java.util.logging.Handler} works but drags in
 * module {@code java.logging}, which this module's descriptor does not require.
 *
 * <p><strong>Rendering happens here on purpose.</strong> {@code System.Logger}'s default methods
 * hand the implementation the raw format and its parameters, and substituting them with {@link
 * MessageFormat} is what a real backend does — so a test can catch a format string {@code
 * MessageFormat} would mangle. An unescaped apostrophe swallows the placeholder after it and emits
 * {@code {0}} verbatim, which is the trap ADR-0014 recorded and item 3.2 met again.
 *
 * <p>Named without a {@code Test} prefix or suffix so surefire does not offer it to the JUnit
 * Platform as a test class.
 */
final class TxLogRecorder implements System.Logger {

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
