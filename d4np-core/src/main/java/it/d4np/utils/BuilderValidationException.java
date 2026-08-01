package it.d4np.utils;

import java.util.List;

/**
 * Everything wrong with a builder, reported at once — thrown by {@link FluentBuilder#build()}.
 *
 * <p><strong>The plural is the whole point.</strong> A builder that failed on the first missing
 * field would turn filling a ten-field object into ten edit-compile-run cycles, each revealing
 * exactly one more problem. {@link FluentBuilder#build()} runs the subclass's whole {@code
 * validate()} and collects every violation before throwing, so one round trip reports all of them.
 *
 * <p><strong>Not a {@link BusinessException}, and this one is easy to get wrong.</strong> FR-19
 * maps validation failures to <strong>400</strong> — but that is FR-14's {@code Validator}, which
 * checks data that arrived <em>from a client</em>. A builder violation is different in kind: the
 * caller is this application's own code, and it failed to set a field before calling {@code
 * build()}. That is a programming error, and reporting it to an end user as "bad request" would be
 * a lie about whose fault it is. It therefore stays outside the {@code BusinessException}
 * hierarchy, and FR-19's fallback maps it to 500 — the same reasoning {@link
 * StrategyNotFoundException} and {@link FactoryKeyNotFoundException} record.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries. The violation list is copied at construction, so a builder reused after the throw cannot
 * rewrite the report.
 *
 * @see FluentBuilder#build()
 */
public final class BuilderValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The violations, in the order {@code validate()} recorded them.
   *
   * <p>{@link List#copyOf(java.util.Collection)} is both immutable and serialisable, and every
   * element is a {@code String} — so unlike a builder-typed payload this survives the serialisation
   * every {@link Throwable} promises, whatever the builder was building.
   */
  private final List<String> violations;

  /**
   * Package-private: {@link FluentBuilder} is the only thing that can decide a builder is invalid.
   *
   * @param builder the simple name of the builder class, for the message
   * @param violations what was wrong; copied, not retained
   */
  BuilderValidationException(String builder, List<String> violations) {
    super(describe(builder, violations));
    this.violations = List.copyOf(violations);
  }

  /**
   * Everything that was wrong, in the order it was recorded.
   *
   * @return an unmodifiable list with at least one element; never {@code null}
   */
  public List<String> violations() {
    return violations;
  }

  private static String describe(String builder, List<String> violations) {
    return violations.size() == 1
        ? builder + ": " + violations.get(0)
        : builder + ": " + violations.size() + " violations: " + violations;
  }
}
