package it.d4np.utils;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link System.Logger} that keeps what it is told, so FR-04's warning can be asserted.
 *
 * <p>FR-04 states the warning as part of the contract — <em>"register is last-write-wins with a
 * warning log"</em> — and spec §6 does not accept a contract clause without a test.
 *
 * <p><strong>Two more obvious approaches were built first and both lost</strong>, which is why this
 * one is worth explaining rather than just reading:
 *
 * <ul>
 *   <li>A test {@link System.LoggerFinder} registered through {@code META-INF/services} <strong>can
 *       never win under surefire</strong>. The JDK resolves the finder once per VM, on the first
 *       {@code System.getLogger} call, and caches it forever; inside a surefire fork something has
 *       already triggered platform logging by then, so the finder is fixed at {@code
 *       sun.util.logging.internal.LoggingProviderImpl} and a provider on the test classpath is
 *       never consulted. Established by running it — the identical service file works in a plain
 *       {@code java} launch, which is exactly what makes the failure confusing.
 *   <li>Attaching a {@code java.util.logging.Handler} to the backing JUL logger does work, but
 *       {@code java.util.logging} lives in module {@code java.logging}, and these tests compile
 *       <em>inside</em> {@code it.d4np.utils} — whose descriptor deliberately requires nothing but
 *       {@code java.base}. It would mean either advertising a dependency core does not have, or
 *       threading {@code --add-reads} through test compilation and the surefire fork.
 * </ul>
 *
 * <p>{@link System.Logger} is itself in {@code java.base}, so recording one and handing it to the
 * package-private {@code StrategyRegistry} constructor needs no build configuration and no platform
 * cooperation — and it tests the call this library actually makes, rather than whichever backend
 * happens to be installed.
 *
 * <p><strong>Rendering happens here on purpose.</strong> {@code System.Logger}'s default methods
 * hand the implementation the raw format and its parameters; substituting them with {@link
 * MessageFormat} is what a real backend does, so a test can catch a format string {@code
 * MessageFormat} would mangle — an unescaped apostrophe swallows the placeholder after it and emits
 * {@code {0}} verbatim.
 *
 * <p>Named without a {@code Test} prefix or suffix on purpose, like {@code SerializationSupport}:
 * surefire would otherwise offer it to the JUnit Platform as a test class.
 */
final class LogRecorder implements System.Logger {

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
