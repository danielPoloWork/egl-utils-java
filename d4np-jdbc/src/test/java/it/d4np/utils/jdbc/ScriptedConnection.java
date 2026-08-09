package it.d4np.utils.jdbc;

import it.d4np.utils.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Connection} that records what was asked of it and can be told to refuse one call — the
 * instrument FR-06's ordering and failure-while-failing claims are measured with.
 *
 * <p><strong>Every claim in RFC-0003's §Failure while failing table is about a call that
 * fails</strong>, and a real database does not fail its own {@code rollback} on request. Neither is
 * "auto-commit is restored <em>before</em> the connection is closed" observable after the fact: the
 * connection is gone by the time a test could look. Both become checkable the moment the calls
 * themselves are the evidence, which is what this records.
 *
 * <p>It wraps a <em>real</em> connection rather than faking one, so the body under test still runs
 * real SQL and the transaction really commits or really does not. Only the named method is
 * intercepted.
 *
 * <p>Written over {@link Proxy} rather than as a hand-written stub for one reason worth stating:
 * {@code Connection} has around fifty methods, and a stub would have to grow every time the runner
 * called one more. A proxy that delegates by default cannot fall behind the class it observes.
 */
final class ScriptedConnection {

  private final Connection real;

  private final List<String> calls = new ArrayList<>();

  /** The method that must fail, or {@code null} when every call goes through. */
  @Nullable private String failing;

  /** The first occurrence of {@link #failing} that fails; earlier ones go through. */
  private int failFrom = 1;

  /** How many times {@link #failing} has been reached. */
  private int seen;

  /** The SQLState the scripted failure reports, so a test can recognise it. */
  static final String SCRIPTED_STATE = "08007";

  ScriptedConnection(Connection real) {
    this.real = real;
  }

  /**
   * Makes one method raise a {@link SQLException} instead of running.
   *
   * @param method the method name — {@code rollback}, {@code commit}, {@code setAutoCommit}, …
   * @return this, so a test reads as one statement
   */
  ScriptedConnection failingOn(String method) {
    this.failing = method;
    return this;
  }

  /**
   * Lets the first {@code n - 1} calls through and fails from the {@code n}-th.
   *
   * <p>Needed because the runner calls {@code setAutoCommit} twice — once to begin, once to restore
   * — and RFC-0003's table has a row about the <em>second</em> one failing. Failing both would test
   * a different sentence.
   *
   * @param n the 1-based occurrence to start failing at
   * @return this, so a test reads as one statement
   */
  ScriptedConnection fromCall(int n) {
    this.failFrom = n;
    return this;
  }

  /**
   * The calls made so far, in order, as {@code name} or {@code name(arg)}.
   *
   * @return a snapshot; never {@code null}
   */
  List<String> calls() {
    return List.copyOf(calls);
  }

  /**
   * Whether a method was called at all.
   *
   * @param method the method name
   * @return {@code true} if any recorded call names it
   */
  boolean called(String method) {
    return calls.stream().anyMatch(call -> call.equals(method) || call.startsWith(method + "("));
  }

  /**
   * The proxy to hand to a {@link JdbcTxRunner}.
   *
   * @return a connection that records and, where scripted, refuses
   */
  Connection connection() {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              calls.add(args == null || args.length == 0 ? name : name + "(" + args[0] + ")");
              if (name.equals(failing) && ++seen >= failFrom) {
                throw new SQLException("scripted failure in " + name, SCRIPTED_STATE);
              }
              try {
                return method.invoke(real, args);
              } catch (InvocationTargetException raised) {
                throw raised.getCause();
              }
            });
  }
}
