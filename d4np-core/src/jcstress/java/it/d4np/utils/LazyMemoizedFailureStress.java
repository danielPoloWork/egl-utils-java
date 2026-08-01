package it.d4np.utils;

import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.LL_Result;

/**
 * The named harness behind {@link Lazy#memoizingFailures(java.util.function.Supplier)}'s concurrent
 * promise — ROADMAP item 2.2, FR-03.
 *
 * <p><strong>Why this needs its own harness.</strong> The memoizing policy promises callers "the
 * <em>first</em> failure", singular, and that is a claim about a race: two threads reaching an
 * uninitialized {@code Lazy} together must not each run the initializer and walk away with
 * different exceptions. Whichever one wins the lock records its failure; the other must find that
 * record rather than repeat the work. {@code LazyTest} can only check this sequentially, where the
 * ordering it depends on is the one thing that cannot go wrong.
 *
 * <p>Exactly one outcome is acceptable: {@code same, 1} — both callers received the identical
 * {@code Throwable} instance, and the initializer ran once. The failure modes are named separately
 * in the diagnosis rather than lumped together, so a red run says which promise broke: {@code
 * different} means each caller got its own exception, and {@code value returned} means a caller
 * somehow got a value out of an initializer that always throws.
 *
 * <p>Every string here stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description("two threads racing on a memoizingFailures Lazy receive one remembered failure")
@Outcome(
    id = "same, 1",
    expect = Expect.ACCEPTABLE,
    desc = "both callers got the identical remembered exception, and the initializer ran once")
@Outcome(
    expect = Expect.FORBIDDEN,
    desc = "the failure was not shared: two exceptions, a second initialization, or a value")
@State
public class LazyMemoizedFailureStress {

  /** Counts initializer invocations: the memoizing policy must not retry. */
  private final AtomicInteger initializations = new AtomicInteger();

  private final Lazy<String> lazy =
      Lazy.memoizingFailures(
          () -> {
            initializations.incrementAndGet();
            throw new IllegalStateException("synthetic initialization failure");
          });

  @Nullable private Throwable seenByFirst;

  @Nullable private Throwable seenBySecond;

  /** Calls {@code get()} and keeps whatever came out. */
  @Actor
  public void caller1() {
    seenByFirst = capture();
  }

  /** Races {@link #caller1()} on the same {@code Lazy}. */
  @Actor
  public void caller2() {
    seenBySecond = capture();
  }

  /**
   * Runs after both callers, when both observations and the counter are stable.
   *
   * @param r how the two failures relate, then the number of initializer invocations
   */
  @Arbiter
  public void arbiter(LL_Result r) {
    r.r1 = describe();
    r.r2 = String.valueOf(initializations.get());
  }

  // Reference equality IS the assertion: the contract is that both callers receive the SAME
  // Throwable, not an equal one. ErrorProne's ReferenceEquality suggests `first.equals(second)`,
  // which for Throwable is identity anyway and would only hide what the line means.
  @SuppressWarnings("ReferenceEquality")
  private String describe() {
    Throwable first = seenByFirst;
    Throwable second = seenBySecond;
    if (first == null || second == null) {
      return "value returned";
    }
    return first == second ? "same" : "different";
  }

  @Nullable
  private Throwable capture() {
    try {
      lazy.get();
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }
}
