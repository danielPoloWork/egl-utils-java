package it.d4np.utils;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The reactor's first jcstress harness — ROADMAP item 1.8.
 *
 * <p><strong>What it proves.</strong> The safe-publication idiom {@code Lazy<T>} will be built on
 * (FR-03, NFR-01): a writer publishes a payload and then flips a {@code volatile} flag; a reader
 * that observes the flag must observe the payload. The {@code volatile} write/read pair orders the
 * two, so exactly one interleaving is <em>forbidden</em> — flag seen, payload not — and this file
 * says so as {@link Expect#FORBIDDEN}. jcstress fails the build if it ever observes it.
 *
 * <p>That makes this harness a real assertion rather than a smoke test: delete the {@code volatile}
 * modifier on {@link #ready} and the test is permitted to fail on a weakly ordered machine. It is
 * the same discipline item 1.3 applied to the JUnit smoke test — assert the contract, not that the
 * runner runs.
 *
 * <p><strong>Why it is here rather than in d4np-concurrent.</strong> The claim being modelled is
 * core's (FR-03 {@code Lazy}). NFR-05's pool rejection/shutdown races are d4np-concurrent's and
 * arrive with item 5.1.
 *
 * <p><strong>Item 2.2 kept this harness rather than replacing it, and this note used to predict
 * otherwise.</strong> The plan was for 2.2 to swap the subject for the real {@code Lazy} while
 * keeping the outcome table; what it actually did was add {@code LazyPublicationStress}
 * <em>beside</em> this one. They are different experiments rather than two versions of one: this
 * file exercises the bare {@code volatile} write/read pair with no lock, which makes it the
 * <strong>control</strong> — if both fail, the platform or the JDK is the suspect; if only the
 * {@code Lazy} harness fails, the fault is in our code. That is the role {@code
 * PublicationBaselineBenchmark} keeps on the JMH side, and it costs about a second of a
 * profile-gated job. Deleting a passing correctness test to satisfy a note's wording would have
 * been the worse trade.
 *
 * <p>The unnamed trailing {@link Outcome} is a catch-all: an interleaving nobody enumerated is a
 * finding, not a pass, and without it jcstress would report the result as merely unmatched.
 */
@JCStressTest
@Description("volatile flag orders the publication of a plain payload (FR-03 / NFR-01 idiom)")
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "publication not yet observed")
@Outcome(id = "1, 42", expect = Expect.ACCEPTABLE, desc = "flag and payload both observed")
@Outcome(
    id = "1, 0",
    expect = Expect.FORBIDDEN,
    desc = "flag observed without its payload: the volatile write/read pair failed to order them")
@Outcome(expect = Expect.FORBIDDEN, desc = "unenumerated interleaving")
@State
public class VolatilePublicationStress {

  /** The payload, deliberately NOT volatile: the flag below is what orders it. */
  private int payload;

  /** The publication flag; {@code volatile} is the entire point of the test. */
  private volatile int ready;

  /** Publishes the payload, then the flag. Order matters and is not reordered across the write. */
  @Actor
  public void writer() {
    payload = 42;
    ready = 1;
  }

  /**
   * Reads the flag and, only if it was set, the payload.
   *
   * <p>Reading {@code payload} unconditionally would make {@code "0, 42"} a legal outcome and
   * dilute the assertion: the interesting question is what a reader that <em>acted on</em> the flag
   * sees.
   *
   * @param r the two observed values — flag first, payload second
   */
  @Actor
  public void reader(II_Result r) {
    r.r1 = ready;
    r.r2 = r.r1 == 1 ? payload : 0;
  }
}
