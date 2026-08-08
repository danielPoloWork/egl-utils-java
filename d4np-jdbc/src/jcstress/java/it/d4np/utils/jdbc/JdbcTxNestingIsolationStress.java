package it.d4np.utils.jdbc;

import it.d4np.utils.Nullable;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.L_Result;

/**
 * The named harness behind {@link JdbcTxRunner}'s thread-safety claim — ROADMAP item 4.4, FR-06.
 *
 * <p><strong>Why RFC-0003 asked for this one by name.</strong> Every other type in this module is
 * stateless, and items 3.1, 4.1 and 4.3 each recorded that a harness over one would be measuring
 * somebody else's library. {@code JdbcTxRunner} is the first type here with <em>real per-thread
 * state</em>: the nesting detector. Spec §6's rule — a thread-safety claim without a named jcstress
 * test is not a claim — finally has something to bite on.
 *
 * <p><strong>What it proves.</strong> Two threads run a transaction through <em>one shared
 * runner</em>, and both must complete. The detector is a {@code static} field, so a single mistake
 * makes this fail: writing it as a plain {@code boolean} — or as a {@code ThreadLocal} whose value
 * is read once and cached in an instance field — lets one thread see the other's mark and refuse a
 * transaction that was never nested. No sequential test can falsify that, because on one thread the
 * shared and the per-thread version behave identically.
 *
 * <p>Refusing is the <em>safe</em> direction of the detector's failure, which is exactly why it
 * needs a harness: a false refusal is a correct-looking {@code IllegalStateException} that an
 * on-call engineer would read as a caller's bug.
 *
 * <p><strong>The pool is a stateless stand-in, deliberately.</strong> jcstress runs an actor
 * millions of times, and a real connection per invocation would measure H2's connection setup
 * rather than this library's detector — it would also make the harness fail for reasons that have
 * nothing to do with what it is asking. The stand-in answers every call with a no-op and holds no
 * state, so nothing here is shared except the thing under test.
 *
 * <p>Every string stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description("two threads transacting through one runner never observe each other's nesting flag")
@Outcome(id = "ok, ok", expect = Expect.ACCEPTABLE, desc = "both transactions ran")
@Outcome(
    id = "refused, ok",
    expect = Expect.FORBIDDEN,
    desc = "the first thread was refused because the second held the flag")
@Outcome(
    id = "ok, refused",
    expect = Expect.FORBIDDEN,
    desc = "the second thread was refused because the first held the flag")
@Outcome(
    id = "refused, refused",
    expect = Expect.FORBIDDEN,
    desc = "the detector is shared across threads rather than scoped to one")
@Outcome(expect = Expect.FORBIDDEN, desc = "a transaction did not complete")
@State
public class JdbcTxNestingIsolationStress {

  /**
   * A pool whose connections do nothing, shared by both actors.
   *
   * <p>Stateless on purpose: if the stand-in carried state, a red run would not distinguish a
   * broken detector from a connection two threads were fighting over.
   */
  private static final DataSource POOL = statelessPool();

  private final JdbcTxRunner runner = JdbcTxRunner.on(POOL);

  private volatile String left = "unset";

  private volatile String right = "unset";

  /** Runs a transaction on one thread. */
  @Actor
  public void transactor1() {
    left = transact();
  }

  /** Races {@link #transactor1()} through the same runner. */
  @Actor
  public void transactor2() {
    right = transact();
  }

  /**
   * Reports what each thread saw.
   *
   * @param r the observed outcome
   */
  @Arbiter
  public void arbiter(L_Result r) {
    r.r1 = left + ", " + right;
  }

  /**
   * Runs one empty transaction and says how it went.
   *
   * <p>A refusal is reported rather than thrown: an actor that throws is a jcstress <em>error</em>
   * instead of an outcome, and a named forbidden outcome says which promise broke.
   *
   * @return {@code ok}, {@code refused}, or the failure's simple name
   */
  private String transact() {
    try {
      runner.inTransactionWithoutResult(connection -> {});
      return "ok";
    } catch (IllegalStateException refused) {
      return "refused";
    } catch (RuntimeException broken) {
      return broken.getClass().getSimpleName();
    }
  }

  /**
   * What a no-op connection answers a call with, by return type.
   *
   * <p>{@code getAutoCommit} has to say something, and {@code true} is the honest answer for a
   * connection that never left auto-commit — it is also what makes the runner exercise the restore
   * path rather than skip it.
   *
   * @param returned the method's return type
   * @return a value assignable to it, or {@code null} for a reference type
   */
  @Nullable
  private static Object answerFor(Class<?> returned) {
    if (returned == boolean.class) {
      return true;
    }
    if (returned == int.class) {
      return 0;
    }
    return null;
  }

  /**
   * A {@code DataSource} handing out a connection that answers every call with a no-op.
   *
   * <p>Written over {@link Proxy} because {@code Connection} has around fifty methods and this
   * harness calls five of them; a hand-written stub would be four hundred lines of nothing.
   *
   * @return the stand-in pool
   */
  private static DataSource statelessPool() {
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> answerFor(method.getReturnType()));
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);
  }
}
