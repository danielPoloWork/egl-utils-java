package it.d4np.utils;

/**
 * The one value an operation returns when it succeeded and has nothing to hand back.
 *
 * <pre>{@code
 * Result<Unit> deleted = repository.delete(id);   // may fail; succeeds with nothing
 * if (deleted instanceof Result.Err<Unit> err) {
 *   return problem(err.error());
 * }
 * }</pre>
 *
 * <p><strong>This exists because {@code Result<Void>} cannot be built.</strong> {@link Void} is
 * uninhabited — its only value <em>is</em> {@code null} — and {@link Result.Ok} rejects a {@code
 * null} payload unconditionally, so the construction FR-17 originally recommended is impossible for
 * any caller, including a factory inside this library. ADR-0012 recorded the proof; ADR-0019 chose
 * this answer over the two alternatives it had costed.
 *
 * <p><strong>Why the gap was worth closing.</strong> Without a unit value the error model is
 * asymmetric: an expected failure can travel as a value only when the operation <em>also</em> has
 * something to return, so every no-payload operation is pushed back into the exception channel —
 * the outcome ADR-002 adopted {@code Result} to avoid. "Delete this; it may not exist" is an
 * expected outcome with no payload, and it is not an exotic shape.
 *
 * <p><strong>An enum with one constant, deliberately.</strong> A {@code final class} with a private
 * constructor and a public constant would need {@code readResolve} to stay a singleton across
 * deserialisation — and it can be deserialised, because {@code Result} arms travel inside {@link
 * BusinessException}, which inherits {@link java.io.Serializable} from {@link Throwable}. An enum
 * gets that from the language rather than from a method somebody has to remember to write and test.
 *
 * <p><strong>It does not make {@code null} sayable.</strong> {@link Result#ok()} passes {@link
 * #INSTANCE} through the same canonical constructor as every other payload, so there is no second
 * code path and no back door around {@code Ok}'s null rejection.
 *
 * @see Result#ok()
 */
public enum Unit {

  /** The only value of this type. */
  INSTANCE;

  /**
   * Renders as {@code ()} rather than {@code INSTANCE}.
   *
   * <p>The default enum {@code toString} would put the word {@code INSTANCE} into every log line
   * and error message that prints a {@code Result<Unit>}, which says nothing. {@code ()} is the
   * notation the languages this borrows from use for the same value.
   *
   * @return {@code "()"}
   */
  @Override
  public String toString() {
    return "()";
  }
}
