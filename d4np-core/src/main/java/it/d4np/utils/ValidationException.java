package it.d4np.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Constraint violations reported as a defect — thrown by {@link Validator#requireValid}, and
 * mintable by any module that reaches the same verdict without a provider ({@link #of}).
 *
 * <p><strong>Not a {@link BusinessException}, and the reason is the status code.</strong> FR-19
 * maps {@code BusinessException} to <strong>422</strong> and validation to <strong>400</strong>, so
 * inheriting from it would silently give every validation failure the wrong response. It extends
 * {@link RuntimeException} directly and FR-19 maps it on its own row.
 *
 * <p><strong>Not {@code jakarta.validation.ValidationException} either</strong>, despite the shared
 * simple name. The provider's exception is a provider-failure signal — "validation could not be
 * performed" — and it arrives with the API this module only {@code requires static}. This one says
 * the opposite: validation was performed and the object failed it. A call site that catches both
 * should import them by qualified name and mean it.
 *
 * <p><strong>The violations are {@code String}, not the validated type or Jakarta's {@code
 * ConstraintViolation}.</strong> Every {@link Throwable} is {@link java.io.Serializable}, so a
 * payload typed on the consumer's object would make this exception serialisable only when that
 * object happened to be — the failure mode ADR-0015 records, silent and only in the hosts that
 * serialise exceptions. Rendering to {@code String} at the throw site also keeps the C-01 guarantee
 * with the exception: {@link Validator} renders {@code path: message-template}, so the rejected
 * value cannot travel inside this exception either.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries; the violation list is copied at construction.
 *
 * <h2>Two doors, because a provider is not the only thing that can reach this verdict</h2>
 *
 * <p>This class documented itself as constructible only by {@link Validator} until ROADMAP item
 * 4.5, and that claim did not survive the first module outside {@code d4np-core} that needed to
 * throw one. FR-07's sort whitelist is a validation of client input against a caller-supplied
 * allowlist — the set is the repository's knowledge and is not expressible as a Bean Validation
 * annotation — so {@code d4np-jdbc} reaches the verdict with no provider anywhere in the picture,
 * and a package-private constructor put the type it is specified to throw out of reach. {@link #of}
 * is that door; the reasoning, and what the door has to guarantee that the private one got for
 * free, is <a
 * href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/adr/0034-mint-a-validation-failure-from-outside-core.md">ADR-0034</a>.
 *
 * <p><strong>What is bounded here rather than at each caller</strong> (control C-01): every
 * violation and the validated name are truncated at {@value #MAX_TEXT_LENGTH} characters and
 * stripped of ISO control characters, and the message lists at most {@value #MAX_LISTED} of them. A
 * violation about client input necessarily quotes some of it — FR-07's names the rejected sort
 * property — and a name holding {@code \r\n} folds one log line into two. Bounding inside the
 * exception rather than in each thrower is ADR-0022's rule applied to a mint: a guarantee a caller
 * can forget is advisory. It is a no-op on {@link Validator}'s own path, which is asserted rather
 * than assumed.
 *
 * @see Validator#requireValid(Object, Class[])
 * @see #of(String, List)
 */
public final class ValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** How much of one violation, or of the validated name, survives into this exception. */
  static final int MAX_TEXT_LENGTH = 200;

  /** How many violations the message lists before it says how many it did not. */
  static final int MAX_LISTED = 20;

  /** What a truncated fragment ends with, so a reader can tell it was cut. */
  private static final String TRUNCATED = "...";

  /**
   * The rendered violations, in the deterministic order {@link Validator} produced them.
   *
   * <p>{@link List#copyOf(java.util.Collection)} is immutable and serialisable, and every element
   * is a {@code String} — so the list survives the serialisation {@link Throwable} promises,
   * whatever type was being validated.
   */
  private final List<String> violations;

  /**
   * The one constructor, so both doors get the same bounding.
   *
   * @param validated the name of what was validated, already bounded
   * @param violations what was wrong, already bounded; copied, not retained
   */
  private ValidationException(String validated, List<String> violations) {
    super(describe(validated, violations));
    this.violations = List.copyOf(violations);
  }

  /**
   * The verdict {@link Validator} reaches through a Bean Validation provider.
   *
   * <p>Package-private, and it stays that way: this door means "a provider evaluated the
   * annotations on an object and it failed them", which is a sentence only {@link Validator} is in
   * a position to say.
   *
   * @param validated the simple name of the validated type, for the message
   * @param violations what was wrong, rendered as {@code path: template}; copied, not retained
   * @return the exception to throw
   */
  static ValidationException fromProvider(String validated, List<String> violations) {
    return new ValidationException(bounded(validated), bounded(violations));
  }

  /**
   * The verdict a module reaches on its own, with no provider involved.
   *
   * <p><strong>The check this reports must be a check of input against a rule, not a failure of
   * this library.</strong> FR-19 maps this exception to <strong>400</strong>, so throwing one for
   * something the caller could not have supplied differently reports a defect as a client mistake —
   * the mirror of the misattribution {@link StrategyNotFoundException} avoids by staying outside
   * {@link BusinessException}. A wrong argument from a programmer is {@link
   * IllegalArgumentException}; a rejected value from a request is this.
   *
   * <p>The first caller is FR-07's {@code PageRequest.validatedAgainst}, whose allowlist cannot be
   * expressed as an annotation because the allowed set belongs to the repository rather than to the
   * type being validated.
   *
   * <p><strong>Every string handed in is bounded and stripped</strong> — see the class
   * documentation. Naming the rejected input is normally what makes a 400 actionable; naming the
   * <em>rule</em> it failed usually is not, because an allowlist of column names is internal
   * schema, and this exception's message reaches the client where {@code
   * StrategyNotFoundException}'s reaches a 500 with no body.
   *
   * @param validated what was being validated, for the message — a type or field name such as
   *     {@code "PageRequest"}, never a value
   * @param violations what was wrong, one entry per rejected thing, conventionally rendered as
   *     {@code path: reason}; must hold at least one entry, and must not name the rule that was
   *     violated where that rule is internal knowledge
   * @return the exception to throw; never {@code null}
   * @throws NullPointerException if either argument, or any violation, is {@code null}
   * @throws IllegalArgumentException if {@code violations} is empty — an exception saying nothing
   *     was wrong is not one this type can carry
   */
  public static ValidationException of(String validated, List<String> violations) {
    Objects.requireNonNull(validated, "validated must not be null");
    Objects.requireNonNull(violations, "violations must not be null");
    if (violations.isEmpty()) {
      throw new IllegalArgumentException("a ValidationException must carry at least one violation");
    }
    return new ValidationException(bounded(validated), bounded(violations));
  }

  /**
   * Every violation, in the order it was reported.
   *
   * @return an unmodifiable list with at least one element; never {@code null}
   */
  public List<String> violations() {
    return violations;
  }

  private static String describe(String validated, List<String> violations) {
    if (violations.size() == 1) {
      return validated + ": " + violations.get(0);
    }
    List<String> listed = violations.subList(0, Math.min(violations.size(), MAX_LISTED));
    int hidden = violations.size() - listed.size();
    return validated
        + ": "
        + violations.size()
        + " violations: "
        + listed
        + (hidden > 0 ? " and " + hidden + " more" : "");
  }

  private static List<String> bounded(List<String> violations) {
    List<String> safe = new ArrayList<>(violations.size());
    for (String violation : violations) {
      safe.add(bounded(Objects.requireNonNull(violation, "a violation must not be null")));
    }
    return safe;
  }

  /**
   * Truncates at {@link #MAX_TEXT_LENGTH} and removes every ISO control character.
   *
   * <p>Stripping rather than escaping, on {@code ResourceLoaderUtils}' precedent for traversal
   * (control C-04): a refusal or a removal is trivially correct, where an escaping scheme has to be
   * proven airtight against whatever renders the string next — and this text is destined for both a
   * log line and, through FR-19, an HTTP body, which escape differently.
   *
   * @param text the caller's fragment
   * @return the bounded form, possibly ending in {@code ...}
   */
  private static String bounded(String text) {
    StringBuilder stripped = new StringBuilder(text.length());
    text.codePoints()
        .filter(codePoint -> !Character.isISOControl(codePoint))
        .forEach(stripped::appendCodePoint);
    if (stripped.length() <= MAX_TEXT_LENGTH) {
      return stripped.toString();
    }
    // Never cut between a surrogate pair: half of one is not a character, and it survives into an
    // FR-19 response body where it is not valid UTF-8 either.
    int cut =
        Character.isHighSurrogate(stripped.charAt(MAX_TEXT_LENGTH - 1))
            ? MAX_TEXT_LENGTH - 1
            : MAX_TEXT_LENGTH;
    return stripped.substring(0, cut) + TRUNCATED;
  }
}
