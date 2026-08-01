package it.d4np.utils;

import java.util.Optional;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * The named harness behind {@link StrategyRegistry}'s safe-publication claim — ROADMAP item 2.3,
 * FR-04.
 *
 * <p><strong>What it proves.</strong> One thread registers a strategy it has just finished
 * building; another looks it up. A reader that sees the strategy at all must see it <em>fully
 * constructed</em>. The payload's field is deliberately <strong>not</strong> {@code final}, so the
 * Java memory model gives no freeze guarantee and the only thing ordering the write before the read
 * is the happens-before edge {@link java.util.concurrent.ConcurrentHashMap} establishes between a
 * {@code put} and the {@code get} that observes it.
 *
 * <p>Two outcomes are acceptable and the distinction matters: {@code -1} means the reader simply
 * ran first, which is an ordinary race and not a fault, while {@code 42} means it saw the finished
 * object. {@code 0} is the anomaly — the reference published without the field write — and is
 * {@link Expect#FORBIDDEN}, as is anything else.
 *
 * <p>Registration happens once per iteration, so no collision warning is emitted and the default
 * platform logger is left in place; {@code StrategyRegistryRegistrationStress} does need to silence
 * it, and says so.
 *
 * <p>Every string here stays ASCII: a non-ASCII character in a jcstress {@code desc} corrupts the
 * generated {@code META-INF/TestList} (item 1.8).
 */
@JCStressTest
@Description("a strategy registered by one thread is seen fully constructed by another")
@Outcome(
    id = "-1",
    expect = Expect.ACCEPTABLE,
    desc = "the reader ran before the registration became visible, which is an ordinary race")
@Outcome(
    id = "42",
    expect = Expect.ACCEPTABLE,
    desc = "the reader saw the strategy with the write its builder made")
@Outcome(
    expect = Expect.FORBIDDEN,
    desc = "a strategy seen half-built: the reference was published without the field write")
@State
public class StrategyRegistryPublicationStress {

  /** Written into the payload before its reference reaches the registry. */
  private static final int PUBLISHED = 42;

  private static final String KEY = "strategy";

  private final StrategyRegistry<String, Payload> registry = new StrategyRegistry<>();

  /** Builds a strategy, then registers it. */
  @Actor
  public void writer() {
    Payload built = new Payload();
    built.value = PUBLISHED;
    registry.register(KEY, built);
  }

  /**
   * Looks the strategy up while {@link #writer()} is registering it.
   *
   * @param r the observed field, or -1 if the registration was not visible yet
   */
  @Actor
  public void reader(I_Result r) {
    Optional<Payload> found = registry.find(KEY);
    r.r1 = found.isPresent() ? found.get().value : -1;
  }

  /**
   * The registered strategy. Its field is intentionally non-final: a {@code final} field would be
   * frozen by the JMM whatever the registry did, which would make this harness pass without testing
   * anything.
   */
  static final class Payload {
    int value;
  }
}
