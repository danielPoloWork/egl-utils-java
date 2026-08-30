package it.d4np.utils.concurrent;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.L_Result;

/**
 * The named harness behind NFR-05 — ROADMAP item 5.1, FR-08.
 *
 * <p><strong>Why this one is mandatory where the last four were judgement calls.</strong> Items
 * 3.1, 4.1, 4.3 and 4.5 each recorded that a stateless type owes no harness, and item 4.4 shipped
 * the first one this project owed outside core. NFR-05 is different in kind: it names the harness
 * in the requirement — <em>"CustomThreadPoolFactory pools show 0 jcstress anomalies for
 * rejection/shutdown races"</em> — so spec §6's rule is not being applied here, it is being obeyed.
 *
 * <p><strong>What it proves.</strong> One thread submits while another closes the same pool. Those
 * two operations race on {@code ThreadPoolExecutor}'s run state, and the submitting thread must end
 * up in exactly one of three <em>documented</em> states: the task was accepted, the pool refused it
 * because the queue was full, or the pool refused it because it was shutting down. All three are
 * {@code RejectedExecutionException} or success. <strong>What must never happen is any other
 * throwable</strong> — and in particular not one from {@link ManagedThreadPool#close()}, which this
 * library documents as never throwing.
 *
 * <p>The forbidden outcomes are named individually rather than left to a catch-all, so a red run
 * says which promise broke instead of only that one did.
 *
 * <p><strong>{@code close()} is the actor, not {@code shutdown()}.</strong> The race NFR-05 is
 * about is the one a consumer actually writes — try-with-resources closing a pool while another
 * thread is still submitting — and {@code close()} is the method that drains, interrupts and logs.
 * Racing the raw {@code shutdown()} would exercise the JDK and skip everything this item added.
 *
 * <p>The pool is deliberately tiny: one thread, one queue slot, and a drain budget short enough
 * that jcstress's millions of iterations do not each pay a real timeout. A task that does nothing
 * keeps the harness measuring the state machine rather than the work.
 *
 * <p>Every string stays ASCII (item 1.8's {@code META-INF/TestList} finding).
 */
@JCStressTest
@Description(
    "submitting while another thread closes the pool never produces an undocumented failure")
@Outcome(id = "accepted", expect = Expect.ACCEPTABLE, desc = "the task was taken before the close")
@Outcome(
    id = "rejected",
    expect = Expect.ACCEPTABLE,
    desc = "the pool refused it, which is documented")
@Outcome(
    id = "close threw",
    expect = Expect.FORBIDDEN,
    desc = "close() threw, and it is documented as never throwing")
@Outcome(expect = Expect.FORBIDDEN, desc = "an undocumented failure escaped submit or close")
@State
public class ThreadPoolRejectionShutdownStress {

  private final ManagedThreadPool pool =
      CustomThreadPoolFactory.create(
          ThreadPoolSpec.named("stress")
              .coreThreads(1)
              .maxThreads(1)
              .queueCapacity(1)
              .keepAlive(Duration.ofMillis(1))
              .drainTimeout(Duration.ofMillis(1))
              .rejectionPolicy(new ThreadPoolExecutor.AbortPolicy())
              .build());

  private volatile String submitted = "unset";

  private volatile String closed = "unset";

  /** Submits into a pool the other actor may be closing. */
  @Actor
  public void submitter() {
    try {
      pool.execute(() -> {});
      submitted = "accepted";
    } catch (RejectedExecutionException refused) {
      // Documented: the queue was full, or the pool had begun shutting down. Both are the
      // configured AbortPolicy doing its job.
      submitted = "rejected";
    } catch (RuntimeException undocumented) {
      submitted = undocumented.getClass().getSimpleName();
    }
  }

  /** Closes the pool while the other actor may be submitting. */
  @Actor
  public void closer() {
    try {
      pool.close();
      closed = "ok";
    } catch (RuntimeException thrown) {
      // close() is documented as never throwing, including when the drain does not finish. A
      // throwable here is reported rather than propagated: an actor that throws is a jcstress
      // ERROR instead of an outcome, and a named forbidden outcome says which promise broke.
      closed = "close threw";
    }
  }

  /**
   * Reports the submitting thread's outcome, or the close failure if there was one.
   *
   * <p>The close result dominates when it failed, because "close() threw" is the more serious of
   * the two findings and would otherwise be masked by a perfectly ordinary rejection.
   *
   * @param r the observed outcome
   */
  @Arbiter
  public void arbiter(L_Result r) {
    r.r1 = "close threw".equals(closed) ? closed : submitted;
  }
}
