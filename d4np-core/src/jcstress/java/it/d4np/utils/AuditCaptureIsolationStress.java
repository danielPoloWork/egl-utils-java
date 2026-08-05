package it.d4np.utils;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.L_Result;

/**
 * The named harness behind {@link AuditLog}'s thread-safety claim — ROADMAP item 3.3, FR-16.
 *
 * <p><strong>What it proves.</strong> Two threads capture two <em>different</em> objects through
 * one shared {@link AuditLog}, and each must get its own event back. That is a claim about state,
 * not about memory ordering: capture walks a reflective graph while accumulating a path prefix, a
 * depth, a cycle trail and a change list, and every one of those is a field somebody could
 * reasonably hoist onto the instance to avoid re-allocating it. Hoisted, the two captures
 * interleave and produce records that name one object and describe another — which is the worst
 * failure this feature has, because the record still looks complete. No sequential test can falsify
 * it.
 *
 * <p>The two fixtures deliberately have <em>different component names</em>, so contamination shows
 * up in the path as well as in the value: an event reporting {@code label} for the left capture is
 * evidence on its own.
 *
 * <p>Acceptable: {@code name=alice, label=bob}. Everything else is forbidden, and the two most
 * likely shapes are named separately so a red run says which promise broke — a swapped or
 * duplicated value, and a capture that threw.
 *
 * <p>Every string here stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description("two threads capturing different objects through one AuditLog get their own events")
@Outcome(
    id = "name=alice, label=bob",
    expect = Expect.ACCEPTABLE,
    desc = "each capture returned its own object's state")
@Outcome(
    id = "name=bob, label=alice",
    expect = Expect.FORBIDDEN,
    desc = "the two captures observed each other's state")
@Outcome(
    id = "threw, threw",
    expect = Expect.FORBIDDEN,
    desc = "capture is not reentrant from two threads")
@Outcome(expect = Expect.FORBIDDEN, desc = "a capture produced the wrong path or did not complete")
@State
public class AuditCaptureIsolationStress {

  private final AuditLog audit = AuditLog.using(event -> {});

  private volatile String left = "unset";

  private volatile String right = "unset";

  /** Captures the left fixture, whose only component is named {@code name}. */
  @Actor
  public void capturer1() {
    left = describe(audit, new Left("alice"));
  }

  /** Races {@link #capturer1()} through the same audit log with a differently shaped fixture. */
  @Actor
  public void capturer2() {
    right = describe(audit, new Right("bob"));
  }

  /**
   * Reports both captures as {@code path=value} pairs.
   *
   * @param r the observed outcome
   */
  @Arbiter
  public void arbiter(L_Result r) {
    r.r1 = left + ", " + right;
  }

  /**
   * Captures one object and renders the single change it must produce.
   *
   * <p>A thrown exception is reported rather than propagated: an actor that throws is a jcstress
   * error instead of an outcome, and a named forbidden outcome is more useful than an error.
   */
  private static String describe(AuditLog audit, Object state) {
    try {
      AuditEvent event = audit.capture("actor", "CAPTURED", null, state);
      if (event.changes().size() != 1) {
        return "size=" + event.changes().size();
      }
      AuditEvent.Change change = event.changes().get(0);
      return change.path() + "=" + change.after();
    } catch (RuntimeException broken) {
      return "threw";
    }
  }

  /**
   * One audited component, named so that a mix-up is visible in the path.
   *
   * @param name the captured value
   */
  @Audited
  public record Left(String name) {}

  /**
   * The other audited component, deliberately not named {@code name}.
   *
   * @param label the captured value
   */
  @Audited
  public record Right(String label) {}
}
