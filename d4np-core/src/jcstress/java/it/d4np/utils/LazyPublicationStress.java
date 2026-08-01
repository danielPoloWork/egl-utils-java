package it.d4np.utils;

import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * The named harness behind {@link Lazy}'s thread-safety claim — ROADMAP item 2.2, NFR-01.
 *
 * <p><strong>What it proves, in one run.</strong> Two threads race on their very first {@link
 * Lazy#get()}, and three things must hold together:
 *
 * <ul>
 *   <li><strong>safe publication</strong> — a reader that sees the payload reference sees the
 *       writes the initializer made to it. The payload's field is deliberately <em>not</em> {@code
 *       final}, so the Java memory model gives no freeze guarantee here and the {@code volatile}
 *       write inside {@code Lazy} is the only thing ordering it. A reader observing {@code 0}
 *       instead of {@code 42} is the anomaly NFR-01 forbids;
 *   <li><strong>at most once</strong> — the arbiter reports how many times the initializer ran, so
 *       a double-checked-locking mistake shows up as {@code 2} rather than as a passing test;
 *   <li><strong>agreement</strong> — both readers get the same value, because they read the same
 *       field.
 * </ul>
 *
 * <p>Exactly one outcome is acceptable: {@code 42, 42, 1}. Everything else is {@link
 * Expect#FORBIDDEN} and fails the build — there is no interleaving of two {@code get()} calls that
 * may legitimately produce anything else, which is what makes this an assertion rather than an
 * observation.
 *
 * <p><strong>Why it is not the same test as {@code VolatilePublicationStress}.</strong> That
 * harness (item 1.8) exercises the bare {@code volatile} write/read idiom with no lock, and is kept
 * as the control: if both fail, the platform or the JDK is the suspect; if only this one fails, the
 * fault is in {@code Lazy}. Item 1.8's note anticipated replacing its subject with the real type —
 * that is what this file does, but beside it rather than in place of it, for the same reason {@code
 * PublicationBaselineBenchmark} is kept as the JMH floor.
 *
 * <p>Every string here stays ASCII: a non-ASCII character in a jcstress {@code desc} corrupts the
 * generated {@code META-INF/TestList} (item 1.8).
 */
@JCStressTest
@Description("two threads racing on Lazy.get() see one fully initialized value, computed once")
@Outcome(
    id = "42, 42, 1",
    expect = Expect.ACCEPTABLE,
    desc = "both readers saw the published payload, and the initializer ran exactly once")
@Outcome(
    expect = Expect.FORBIDDEN,
    desc = "a payload seen half-built, a second initialization, or readers that disagree")
@State
public class LazyPublicationStress {

  /** The value written into the payload before its reference is published. */
  private static final int PUBLISHED = 42;

  /** Counts initializer invocations, so "at most once" is measured rather than assumed. */
  private final AtomicInteger initializations = new AtomicInteger();

  private final Lazy<Payload> lazy =
      Lazy.of(
          () -> {
            initializations.incrementAndGet();
            Payload built = new Payload();
            built.value = PUBLISHED;
            return built;
          });

  /**
   * Reads the lazily computed payload.
   *
   * @param r the observed payload field goes in the first slot
   */
  @Actor
  public void reader1(III_Result r) {
    r.r1 = lazy.get().value;
  }

  /**
   * Races {@link #reader1(III_Result)} on the same {@code Lazy}.
   *
   * @param r the observed payload field goes in the second slot
   */
  @Actor
  public void reader2(III_Result r) {
    r.r2 = lazy.get().value;
  }

  /**
   * Runs after both readers, when the initializer count is stable.
   *
   * @param r the number of initializer invocations goes in the third slot
   */
  @Arbiter
  public void arbiter(III_Result r) {
    r.r3 = initializations.get();
  }

  /**
   * The published object. Its field is intentionally non-final: a {@code final} field would be
   * frozen by the JMM whatever {@code Lazy} did, which would make this harness pass without testing
   * anything.
   */
  static final class Payload {
    int value;
  }
}
