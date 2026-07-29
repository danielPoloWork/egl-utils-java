package it.d4np.utils;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * The reactor's first JMH harness — ROADMAP item 1.8.
 *
 * <p><strong>What it measures and why it is not a placeholder.</strong> NFR-01 budgets {@code
 * Lazy.get()} at {@code <= 2 ns/op} in steady state, and that call is a volatile read of the
 * memoized value plus a branch. {@code Lazy} does not exist yet (item 2.2 owns it), so this harness
 * measures the two costs that budget is <em>made of</em>: a {@code volatile} field read and a
 * {@code final} field read on the same object. The delta is the price of safe publication on the
 * machine running it, and the volatile number is the floor of NFR-01 — if it already exceeds 2
 * ns/op on the reference machine, the budget is unreachable and the NFR needs revising rather than
 * the code.
 *
 * <p>That is deliberately different from inventing a placeholder benchmark over a placeholder type.
 * Item 1.6 established the discipline: harness machinery lands now, speculative API does not.
 *
 * <p><strong>Reading the numbers.</strong> {@code AverageTime} in nanoseconds, so lower is better,
 * and both methods return their value rather than assigning it to a field — returning is what lets
 * JMH consume the result and defeat dead-code elimination. Values at this scale sit near JMH's own
 * measurement noise floor: a single-fork, single-iteration CI run proves the harness executes, it
 * does not produce a publishable number. Fork and iteration counts come from the {@code jmh.*}
 * properties in the parent POM; spec §6 asks for 5x10 on the named reference machine.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PublicationBaselineBenchmark {

  /** Read through the same memory barrier a safely published value costs (NFR-01's path). */
  private volatile int volatileValue = 42;

  /** The unsynchronized comparison point — the cost of the read with no publication guarantee. */
  private final int finalValue = 42;

  /**
   * Measures a single {@code volatile} field read.
   *
   * @return the field value, returned so JMH can consume it
   */
  @Benchmark
  public int volatileRead() {
    return volatileValue;
  }

  /**
   * Measures a single {@code final} field read — the baseline the volatile read is compared
   * against.
   *
   * @return the field value, returned so JMH can consume it
   */
  @Benchmark
  public int finalRead() {
    return finalValue;
  }
}
