package it.d4np.utils;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.LL_Result;

/**
 * The named harness behind the immutability claim of {@link Result} and {@link ErrorDetail} —
 * ROADMAP item 2.1.
 *
 * <p><strong>Why it exists.</strong> Spec §6 is explicit: a thread-safety claim without a named
 * jcstress test is not a claim. {@code Result} and {@code ErrorDetail} both document that they are
 * safely publishable without synchronisation, and this is what makes that assertable rather than
 * asserted.
 *
 * <p><strong>What it proves.</strong> The publication here is deliberately unsynchronised — a
 * plain, non-volatile field, written by one actor and read by another with nothing ordering them.
 * The Java memory model's final-field freeze is what makes that safe: an immutable object's
 * components are guaranteed visible to any thread that observes the reference. So a reader that
 * sees the {@code Result} must see the {@code ErrorDetail} inside it, and must see that detail's
 * {@code code}. "Reference visible, component not" is therefore {@link Expect#FORBIDDEN}, and
 * jcstress fails the build if it is ever observed.
 *
 * <p>Two freezes are exercised in one read, which is the point of publishing an {@code Err} rather
 * than a bare detail: {@code Err.error} and {@code ErrorDetail.code} must both be visible, so a
 * partially initialised nested value would show up as the forbidden outcome rather than as a
 * passing test on a simpler object graph.
 *
 * <p><strong>Where it differs from {@code VolatilePublicationStress}.</strong> That harness proves
 * the {@code volatile} write/read pair orders a mutable payload — the idiom {@code Lazy} is built
 * on (FR-03, item 2.2). This one removes the {@code volatile} entirely, because an immutable value
 * needs no flag; the guarantee comes from the fields being final. Delete {@code final} from a
 * component and the guarantee goes with it, which is precisely why the arms are records.
 *
 * <p>Every string here stays ASCII: item 1.8 recorded that a non-ASCII character in a jcstress
 * {@code desc} corrupts the generated {@code META-INF/TestList} — lengths are counted in characters
 * and consumed as bytes — and one em dash is enough to kill the whole run.
 */
@JCStressTest
@Description(
    "an immutable Result.Err and its ErrorDetail are safely published through a plain field")
@Outcome(id = "absent, absent", expect = Expect.ACCEPTABLE, desc = "publication not yet observed")
@Outcome(
    id = "err, ACC-01",
    expect = Expect.ACCEPTABLE,
    desc = "the reader saw the failure and the code inside it")
@Outcome(
    id = "err, absent",
    expect = Expect.FORBIDDEN,
    desc = "reference visible without its final fields: the freeze guarantee failed")
@Outcome(expect = Expect.FORBIDDEN, desc = "unenumerated interleaving")
@State
public class ImmutablePublicationStress {

  /** A value the reader can never legitimately produce; it exists to make a gap observable. */
  private static final String ABSENT = "absent";

  /**
   * The published value, deliberately NOT volatile: final-field semantics are what order this, and
   * a {@code volatile} here would make the test prove the flag rather than the immutability.
   */
  @Nullable private Result<String> published;

  /** Publishes a fully constructed failure through the unsynchronised field. */
  @Actor
  public void writer() {
    published = Result.err(new ErrorDetail("ACC-01", "insufficient funds"));
  }

  /**
   * Reads the reference and, only if it was visible, the code nested two levels inside it.
   *
   * @param r the two observed values — which arm was seen, then the code carried by it
   */
  @Actor
  public void reader(LL_Result r) {
    Result<String> seen = published;
    if (seen == null) {
      r.r1 = ABSENT;
      r.r2 = ABSENT;
    } else if (seen instanceof Result.Err<String> err) {
      r.r1 = "err";
      ErrorDetail detail = err.error();
      r.r2 = detail == null ? ABSENT : detail.code();
    } else {
      r.r1 = "unexpected arm";
      r.r2 = ABSENT;
    }
  }
}
