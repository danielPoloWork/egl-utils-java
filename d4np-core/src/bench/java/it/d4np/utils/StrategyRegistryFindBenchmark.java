package it.d4np.utils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

/**
 * The benchmark NFR-04 is stated against — ROADMAP item 2.3.
 *
 * <p><strong>The budget.</strong> NFR-04 puts {@link StrategyRegistry#find(Object)} at {@code <= 50
 * ns/op} <em>at 1000 strategies under 8-thread read load</em>, and all three parts of that are
 * reproduced literally: the registry is filled with {@value Registry#SIZE} entries in {@link
 * Setup}, and the benchmarks run at {@link Threads}(8). Measuring a single-threaded lookup against
 * an 8-thread budget would be measuring a different thing and reporting it as this one.
 *
 * <p><strong>Why the keys rotate.</strong> Each thread walks the key space rather than hammering
 * one entry, so the measurement includes cache misses across the table instead of a single hot line
 * that stays in L1 — which is what "at 1000 strategies" is asking about. The cursor is {@link
 * Scope#Thread} state so the rotation costs no shared write; a shared counter would measure
 * contention on the counter rather than on the map.
 *
 * <p><strong>Two benchmarks, and the second one refuted the claim it was written to
 * confirm.</strong> {@code getOrThrow} reads the map directly and allocates no {@link Optional}, so
 * it was documented as the cheaper call. It is not: on JDK 21 {@code getOrThrowHit} measures about
 * <strong>2 ns/op slower</strong> than {@code findHit}, across three multi-fork runs with
 * non-overlapping confidence intervals. The obvious explanation — that constructing the exception
 * inline pushes the method past the inlining size threshold — was tested by moving the throw into
 * its own method and made no difference at all, so it is wrong too.
 *
 * <p><strong>Run it on both toolchains before believing any of that.</strong> On JDK 17 the same
 * two benchmarks are <em>indistinguishable</em> — overlapping intervals, with {@code findHit}'s
 * spread too wide to resolve 2 ns at all — so the gap is a property of one JIT rather than of this
 * code. The portable statement is only that {@code getOrThrow} is never the faster call. ADR-0015
 * keeps the open question. Only {@code findHit} is the NFR-04 figure; this pair exists to stop
 * either lookup regressing relative to the other unnoticed.
 *
 * <p>Both measure a <strong>hit</strong>. The miss path of {@code getOrThrow} builds an exception
 * carrying a sorted, rendered copy of every key, which is deliberately expensive and deliberately
 * unbudgeted: it happens once, at wiring time, and then the application fails.
 *
 * <p>CI runs one fork and one iteration, which proves the harness executes and produces a number in
 * the right range — not a publishable one. Spec §6 asks for 5x10 on the named reference machine,
 * and item 8.3 owns making an absolute gate out of this reproducible at all.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StrategyRegistryFindBenchmark {

  /** The filled registry, shared by every benchmark thread exactly as a real one would be. */
  @State(Scope.Benchmark)
  public static class Registry {

    /** The scale NFR-04 names. */
    static final int SIZE = 1000;

    final StrategyRegistry<String, UnaryOperator<String>> registry = new StrategyRegistry<>();

    String[] keys = new String[SIZE];

    /** Fills the registry once per trial; registration itself is not what is being measured. */
    @Setup(Level.Trial)
    public void fill() {
      UnaryOperator<String> strategy = UnaryOperator.identity();
      for (int i = 0; i < SIZE; i++) {
        // Zero-padded so every key has the same length, and the measurement is not quietly a
        // benchmark of String.hashCode over strings of differing sizes.
        keys[i] = String.format("strategy-%04d", i);
        registry.register(keys[i], strategy);
      }
    }
  }

  /** Per-thread position in the key space; see the class Javadoc. */
  @State(Scope.Thread)
  public static class Cursor {
    int next;

    int advance() {
      int current = next;
      next = current + 1 == Registry.SIZE ? 0 : current + 1;
      return current;
    }
  }

  /**
   * The NFR-04 measurement: a successful {@code find} under 8-thread read load.
   *
   * @param registry the filled registry
   * @param cursor this thread's position in the key space
   * @return the located strategy, returned so JMH can consume it
   */
  @Benchmark
  @Threads(8)
  public Optional<UnaryOperator<String>> findHit(Registry registry, Cursor cursor) {
    return registry.registry.find(registry.keys[cursor.advance()]);
  }

  /**
   * The same lookup through the throwing accessor, which skips the {@link Optional}.
   *
   * @param registry the filled registry
   * @param cursor this thread's position in the key space
   * @return the located strategy, returned so JMH can consume it
   */
  @Benchmark
  @Threads(8)
  public UnaryOperator<String> getOrThrowHit(Registry registry, Cursor cursor) {
    return registry.registry.getOrThrow(registry.keys[cursor.advance()]);
  }
}
