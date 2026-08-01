package it.d4np.utils;

import java.util.Optional;
import java.util.ResourceBundle;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.L_Result;

/**
 * The named harness behind {@link StrategyRegistry}'s last-write-wins claim — ROADMAP item 2.3,
 * FR-04.
 *
 * <p><strong>What it proves.</strong> Two threads register different strategies under the <em>same
 * key</em> at the same moment. "Last write wins" is a claim about a race, and it has a failure mode
 * a sequential test cannot reach: a registry that read-modify-wrote its map could lose both values
 * and leave the key <strong>empty</strong>, so a later lookup would throw for a key two threads had
 * just successfully registered. Exactly one of the two must survive, and the slot must never be
 * empty afterwards.
 *
 * <p>Acceptable: {@code A} or {@code B}. {@code absent} is the anomaly this exists to forbid, and
 * is named separately from the catch-all so a red run says which promise broke rather than merely
 * that one did.
 *
 * <p><strong>The logger is silenced here, and that is not cosmetic.</strong> Every iteration is a
 * deliberate key collision, which is precisely the event {@code register} logs at {@code WARNING};
 * left on the platform logger, a run of this harness would emit millions of log lines through
 * {@code java.util.logging} and measure the console rather than the registry. The package-private
 * constructor that makes this possible is the same seam the unit tests use, and jcstress sources
 * sit in this package, so no visibility is widened for it.
 *
 * <p>Every string here stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description("two threads registering one key leave exactly one winner and never an empty slot")
@Outcome(id = "A", expect = Expect.ACCEPTABLE, desc = "the first registration won")
@Outcome(id = "B", expect = Expect.ACCEPTABLE, desc = "the second registration won")
@Outcome(
    id = "absent",
    expect = Expect.FORBIDDEN,
    desc = "both registrations were lost and the key ended up empty")
@Outcome(expect = Expect.FORBIDDEN, desc = "a value neither actor registered")
@State
public class StrategyRegistryRegistrationStress {

  private static final String KEY = "strategy";

  private final StrategyRegistry<String, String> registry = new StrategyRegistry<>(Silent.INSTANCE);

  /** Registers one strategy under the contested key. */
  @Actor
  public void registrar1() {
    registry.register(KEY, "A");
  }

  /** Races {@link #registrar1()} on the same key. */
  @Actor
  public void registrar2() {
    registry.register(KEY, "B");
  }

  /**
   * Reads the surviving strategy once both registrations have completed.
   *
   * @param r the winning strategy, or {@code absent} if the key ended up empty
   */
  @Arbiter
  public void arbiter(L_Result r) {
    Optional<String> found = registry.find(KEY);
    r.r1 = found.isPresent() ? found.get() : "absent";
  }

  /** Discards every record; see the note on logging in the class Javadoc. */
  private enum Silent implements System.Logger {
    INSTANCE;

    @Override
    public String getName() {
      return "silent";
    }

    @Override
    public boolean isLoggable(Level level) {
      return false;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Object... params) {
      // Intentionally discarded.
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
      // Intentionally discarded.
    }
  }
}
