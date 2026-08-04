package it.d4np.utils;

import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.L_Result;

/**
 * The named harness behind {@link ExecutionTimeMetricAspect}'s at-most-once warning — ROADMAP item
 * 3.2, FR-15.
 *
 * <p><strong>What it proves.</strong> Two threads run measured calls through one aspect whose
 * recorder fails on every call. RFC-0002 states the policy as <em>"the recorder's exception is
 * swallowed and logged at most once per recorder"</em>, and "at most once" is a claim about a race
 * that no sequential test can falsify: on a plain {@code boolean} flag both threads would read
 * {@code false}, both would warn, and the first step of the log flood the rule exists to prevent
 * would ship looking green. Exactly one warning must be emitted, and both measured calls must still
 * complete — the never-propagate rule holds under contention or it does not hold.
 *
 * <p>Acceptable: {@code 1 warning, both ran}. {@code 2 warnings} is the anomaly this exists to
 * forbid, and it is named separately from the catch-all so a red run says which promise broke
 * rather than merely that one did. {@code 0} would mean the failure was swallowed
 * <em>silently</em>, which is the opposite failure and equally forbidden.
 *
 * <p>Every string here stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description("two threads on a failing recorder emit exactly one warning and both still complete")
@Outcome(
    id = "1, both ran",
    expect = Expect.ACCEPTABLE,
    desc = "one warning between the two threads")
@Outcome(
    id = "2, both ran",
    expect = Expect.FORBIDDEN,
    desc = "both threads warned; at-most-once does not hold under a race")
@Outcome(
    id = "0, both ran",
    expect = Expect.FORBIDDEN,
    desc = "the recorder failure was swallowed silently")
@Outcome(expect = Expect.FORBIDDEN, desc = "a measured call did not complete")
@State
public class ExecutionTimeRecorderFailureStress {

  private final Counting warnings = new Counting();

  private final ExecutionTimeMetricAspect aspect =
      ExecutionTimeMetricAspect.using(ExecutionTimeRecorderFailureStress::fail, warnings);

  private volatile boolean ran1;

  private volatile boolean ran2;

  /** Fails the way a metrics backend fails: on every call, for every thread. */
  private static void fail(String name, java.time.Duration elapsed, boolean failed) {
    throw new IllegalStateException("recorder is down");
  }

  /** Runs one measured call through the failing recorder. */
  @Actor
  public void caller1() {
    aspect.run("caller1", () -> {});
    ran1 = true;
  }

  /** Races {@link #caller1()} through the same aspect. */
  @Actor
  public void caller2() {
    aspect.run("caller2", () -> {});
    ran2 = true;
  }

  /**
   * Reports the number of warnings and whether both measured calls returned.
   *
   * @param r the observed outcome, as {@code count, both ran} or {@code count, a call did not
   *     complete}
   */
  @Arbiter
  public void arbiter(L_Result r) {
    r.r1 = warnings.count.get() + (ran1 && ran2 ? ", both ran" : ", a call did not complete");
  }

  /** Counts warnings instead of printing them; a real logger here would measure the console. */
  private static final class Counting implements System.Logger {

    private final AtomicInteger count = new AtomicInteger();

    @Override
    public String getName() {
      return "counting";
    }

    @Override
    public boolean isLoggable(Level level) {
      return true;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Object... params) {
      count.incrementAndGet();
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
      count.incrementAndGet();
    }
  }
}
