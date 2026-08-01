package it.d4np.utils;

import java.util.ArrayList;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.LL_Result;

/**
 * The named harness behind {@link GenericFactory}'s duplicate-rejection claim — ROADMAP item 2.4,
 * FR-01.
 *
 * <p><strong>What it proves, and why a unit test cannot.</strong> FR-01 says a duplicate {@code
 * register} is rejected. That is a claim about a race, and it has a failure mode that only a race
 * reaches: implemented as {@code containsKey} then {@code put}, two threads registering the same
 * key can <em>both</em> observe it absent and <em>both</em> believe they won, so the duplicate the
 * method exists to reject is silently accepted — exactly when it matters, during parallel module
 * initialisation. Sequentially that implementation passes every test.
 *
 * <p>Exactly one outcome is acceptable: {@code 1, 1} — one registration accepted, one rejected. The
 * two ways it can break are named separately from the catch-all so a red run says which happened:
 * {@code 2, 0} is the check-then-act bug above, and {@code 0, 2} would mean both callers were
 * rejected and the key was left unbound.
 *
 * <p>Every string here stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description("two threads registering one factory key leave exactly one winner and one rejection")
@Outcome(
    id = "1, 1",
    expect = Expect.ACCEPTABLE,
    desc = "one registration won, the other was rejected")
@Outcome(
    id = "2, 0",
    expect = Expect.FORBIDDEN,
    desc = "both registrations were accepted: the duplicate check is not atomic")
@Outcome(
    id = "0, 2",
    expect = Expect.FORBIDDEN,
    desc = "both registrations were rejected and the key was left unbound")
@Outcome(expect = Expect.FORBIDDEN, desc = "an outcome nobody enumerated")
@State
public class GenericFactoryRegistrationStress {

  private static final String KEY = "contested";

  private final GenericFactory<Object, String> factory = new GenericFactory<>();

  private int accepted;
  private int rejected;

  /** Registers the contested key. */
  @Actor
  public void registrar1() {
    attemptRegistration();
  }

  /** Races {@link #registrar1()} on the same key. */
  @Actor
  public void registrar2() {
    attemptRegistration();
  }

  /**
   * Reports how the two attempts were resolved.
   *
   * @param r the number accepted, then the number rejected
   */
  @Arbiter
  public void arbiter(LL_Result r) {
    r.r1 = String.valueOf(accepted);
    r.r2 = String.valueOf(rejected);
  }

  /**
   * Both counters are plain fields incremented under a genuine race, which would normally be a lost
   * update waiting to happen. It is safe here because the two are only ever touched by one actor
   * each in the accepted interleaving, and because jcstress guarantees the arbiter sees both
   * actors' writes — and if a count were ever lost, the result would be an outcome no {@code
   * ACCEPTABLE} row matches, which fails the test rather than hiding.
   */
  private void attemptRegistration() {
    try {
      factory.register(KEY, ArrayList::new);
      accepted++;
    } catch (IllegalStateException duplicate) {
      rejected++;
    }
  }
}
