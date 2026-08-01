package it.d4np.utils;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * The benchmark NFR-01 is stated against — ROADMAP item 2.2.
 *
 * <p><strong>The budget.</strong> NFR-01 puts {@link Lazy#get()} at {@code <= 2 ns/op} in steady
 * state. That path is one {@code volatile} read of the memoized value plus a branch, so this
 * harness measures it directly — and it runs in the same JMH invocation as {@code
 * PublicationBaselineBenchmark} (item 1.8), whose {@code volatileRead} is the floor the budget is
 * made of. Reading the three numbers together is the point: {@code steadyStateGet} minus {@code
 * volatileRead} is what {@code Lazy} costs over the bare field access it wraps, and if that delta
 * is not close to zero then {@code get()} is not inlining.
 *
 * <p><strong>Why the number is real.</strong> A {@code volatile} read cannot be hoisted out of the
 * measurement loop or constant-folded, even though the value is a final field holding a constant
 * string — which is precisely why NFR-01 can be stated against a trivial initializer. The value is
 * returned rather than assigned to a field, which is what lets JMH consume it and defeat dead-code
 * elimination.
 *
 * <p><strong>What it does not measure.</strong> The first call. Initialization takes a lock, runs
 * caller-supplied code and allocates nothing of ours; it is once-per-instance by construction and
 * no NFR budgets it. Nor is the {@code memoizingFailures} variant measured separately: the policy
 * flag is read only on the slow path, so the two share one steady-state path byte for byte.
 *
 * <p>CI runs one fork and one iteration, which proves the harness executes and produces a number
 * near JMH's noise floor — not a publishable one. Spec §6 asks for 5x10 on the named reference
 * machine, and item 8.3 owns making an absolute gate out of this reproducible at all.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LazyGetBenchmark {

  private final Lazy<String> initialized = Lazy.of(() -> "value");

  /**
   * Forces initialization before measurement, so the benchmark times the steady state rather than
   * one lock acquisition amortised over millions of reads.
   */
  @Setup(Level.Trial)
  public void initialize() {
    // Called for its effect; the value is asserted nowhere because a wrong value would be a Lazy
    // defect, and LazyTest is where that is caught.
    initialized.get();
  }

  /**
   * Measures the steady-state {@code get()} — the path NFR-01 budgets.
   *
   * @return the memoized value, returned so JMH can consume it
   */
  @Benchmark
  public String steadyStateGet() {
    return initialized.get();
  }
}
