package it.d4np.utils;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Jakarta Bean Validation 3.x, reached programmatically and reported in this library's error model
 * (FR-14, RFC-0002).
 *
 * <pre>{@code
 * Validator validator = Validator.create();          // once, at start-up
 *
 * Result<Order> checked = validator.validate(order); // an expected outcome, as a value
 * Order valid = validator.requireValid(order);       // a programming error, as an exception
 * List<String> problems = validator.violations(order);
 * }</pre>
 *
 * <p><strong>A violation never carries the rejected value, and that is a security contract rather
 * than a formatting preference (compliance control C-01).</strong> Jakarta's {@link
 * ConstraintViolation} exposes both {@code getInvalidValue()} and an <em>interpolated</em> {@code
 * getMessage()}, and interpolation resolves {@code ${validatedValue}} — so the natural rendering of
 * a constraint whose message quotes the value produces <em>"'hunter2' is not a valid
 * password"</em>. Under FR-19 that string becomes an RFC 7807 {@code problem+json} body and reaches
 * the HTTP client, which is a credential in an error response. Every rendering here is therefore
 * {@code property path} + {@link ConstraintViolation#getMessageTemplate() message template}: the
 * template is what the developer <em>wrote</em>, never what the caller <em>sent</em>. ADR-0020
 * records the trade — an uninterpolated template can show a literal {@code {max}} — and why it is
 * the right side to err on.
 *
 * <p><strong>Two channels for two different failures.</strong> {@link #validate} answers with a
 * {@link Result}, because data arriving from outside the process being invalid is an expected
 * outcome the caller branches on (ADR-002). {@link #requireValid} throws {@link
 * ValidationException} for the call sites where invalid input means the program itself is wrong and
 * there is nothing to branch to.
 *
 * <p><strong>A missing provider fails here, at construction, naming what is absent.</strong> Core
 * declares {@code jakarta.validation-api} at {@code provided} scope and {@code requires static
 * jakarta.validation} (ADR-001, NFR-08), so the compile-time edge always exists and the runtime one
 * may not. Without this check the first validated call would fail somewhere inside the JDK with a
 * {@link NoClassDefFoundError} naming an internal type, which is a considerably worse diagnostic
 * than a start-up failure naming the two artifacts the host has to supply.
 *
 * <p><strong>Lifecycle.</strong> {@link #create()} builds the default {@code ValidatorFactory},
 * takes its validator and does not retain the factory: this type is meant to be built once and held
 * for the life of the process, and a {@code close()} on a shared validator is a use-after-free
 * waiting to happen. A host that manages factory lifecycle itself — a Spring context, a test
 * harness — builds its own and passes the delegate to {@link #using}.
 *
 * <p><strong>Thread safety.</strong> Immutable, and a Bean Validation {@code Validator} is required
 * by its own specification to be thread-safe, so one instance serves every thread.
 *
 * @see ValidationException
 * @see Result
 */
public final class Validator {

  /**
   * The {@link ErrorDetail#code()} every failed {@link #validate} carries.
   *
   * <p>Stable and machine-readable: a caller may switch on it, and FR-19's handler maps it to
   * <strong>400</strong> rather than the {@code BusinessException} 422.
   */
  public static final String VALIDATION_FAILED = "validation.failed";

  /** What a caller is told when the API or the provider is not on the runtime image. */
  private static final String PROVIDER_ABSENT =
      "Jakarta Bean Validation is not available at runtime. d4np-core declares"
          + " jakarta.validation-api as `provided` (requires static jakarta.validation), so the"
          + " host supplies both halves: add jakarta.validation:jakarta.validation-api and a"
          + " provider such as org.hibernate.validator:hibernate-validator to the runtime image,"
          + " or pass an existing jakarta.validation.Validator to Validator.using(..).";

  /** Rendered in place of an empty property path, which is what a class-level constraint has. */
  private static final String BEAN_LEVEL = "<bean>";

  private final jakarta.validation.Validator delegate;

  private Validator(jakarta.validation.Validator delegate) {
    this.delegate = delegate;
  }

  /**
   * Resolves the default provider through {@link Validation#buildDefaultValidatorFactory()}.
   *
   * @return a validator over the provider found on the runtime image; never {@code null}
   * @throws IllegalStateException if the API or a provider is absent, naming both artifacts
   */
  public static Validator create() {
    return fromProvider(() -> Validation.buildDefaultValidatorFactory().getValidator());
  }

  /**
   * Wraps a validator the host already configured — a Spring {@code LocalValidatorFactoryBean}, a
   * factory with a custom message interpolator, or a test double.
   *
   * @param delegate the configured Bean Validation validator; must not be {@code null}
   * @return a validator delegating to {@code delegate}; never {@code null}
   * @throws NullPointerException if {@code delegate} is {@code null}
   */
  public static Validator using(jakarta.validation.Validator delegate) {
    return new Validator(Objects.requireNonNull(delegate, "delegate validator must not be null"));
  }

  /**
   * The seam {@link #create()} is written on, so the absent-provider path is reachable from a test
   * on a classpath that does have a provider.
   *
   * <p>{@link LinkageError} is caught beside {@link RuntimeException} because the two ways this
   * fails are genuinely different: {@code NoProviderFoundException} when the API is present and no
   * implementation is, {@code NoClassDefFoundError} when neither is. Both mean the same thing to
   * the caller, so both produce the same message.
   *
   * @param provider supplies the delegate, or fails trying
   * @return a validator over whatever {@code provider} produced
   * @throws IllegalStateException if {@code provider} fails or produces {@code null}
   */
  static Validator fromProvider(Supplier<jakarta.validation.Validator> provider) {
    jakarta.validation.Validator resolved;
    try {
      resolved = provider.get();
    } catch (RuntimeException | LinkageError absent) {
      throw new IllegalStateException(PROVIDER_ABSENT, absent);
    }
    if (resolved == null) {
      throw new IllegalStateException(PROVIDER_ABSENT);
    }
    return new Validator(resolved);
  }

  /**
   * Validates {@code candidate}, reporting the outcome as a value.
   *
   * @param <T> the validated type
   * @param candidate the object to validate; must not be {@code null}
   * @param groups the validation groups to apply, or none for {@code Default}
   * @return {@code Ok(candidate)} when clean, otherwise {@code Err} of an {@link ErrorDetail} coded
   *     {@link #VALIDATION_FAILED} and listing the violations
   * @throws NullPointerException if {@code candidate}, {@code groups}, or any group is {@code null}
   * @throws IllegalStateException if the provider cannot validate {@code candidate} — an
   *     unannotated or inaccessible type
   */
  public <T> Result<T> validate(T candidate, Class<?>... groups) {
    List<String> violations = violations(candidate, groups);
    return violations.isEmpty()
        ? Result.ok(candidate)
        : Result.err(new ErrorDetail(VALIDATION_FAILED, describe(violations)));
  }

  /**
   * Validates {@code candidate}, treating invalidity as a defect.
   *
   * @param <T> the validated type
   * @param candidate the object to validate; must not be {@code null}
   * @param groups the validation groups to apply, or none for {@code Default}
   * @return {@code candidate} itself, so the call composes into an assignment
   * @throws NullPointerException if {@code candidate}, {@code groups}, or any group is {@code null}
   * @throws ValidationException if any constraint is violated
   * @throws IllegalStateException if the provider cannot validate {@code candidate}
   */
  public <T> T requireValid(T candidate, Class<?>... groups) {
    List<String> violations = violations(candidate, groups);
    if (!violations.isEmpty()) {
      throw ValidationException.fromProvider(candidate.getClass().getSimpleName(), violations);
    }
    return candidate;
  }

  /**
   * The violations of {@code candidate}, rendered and ordered.
   *
   * <p><strong>Sorted, because the provider's {@code Set} is unordered.</strong> Two runs over the
   * same object would otherwise produce the same violations in different orders, which turns a
   * message assertion into a flaky test and a log line into something that cannot be diffed.
   *
   * @param <T> the validated type
   * @param candidate the object to validate; must not be {@code null}
   * @param groups the validation groups to apply, or none for {@code Default}
   * @return the rendered violations in lexicographic order, empty when {@code candidate} is clean;
   *     never {@code null}, always unmodifiable
   * @throws NullPointerException if {@code candidate}, {@code groups}, or any group is {@code null}
   * @throws IllegalStateException if the provider cannot validate {@code candidate}
   */
  public <T> List<String> violations(T candidate, Class<?>... groups) {
    Objects.requireNonNull(candidate, "validation candidate must not be null");
    Objects.requireNonNull(groups, "validation groups must not be null");
    for (Class<?> group : groups) {
      Objects.requireNonNull(group, "validation group must not be null");
    }
    Set<ConstraintViolation<T>> violations = delegate.validate(candidate, groups);
    return violations.stream().map(Validator::render).sorted().toList();
  }

  /**
   * Renders one violation as {@code path: template} — never the invalid value (C-01).
   *
   * @param violation the provider's violation
   * @return the rendered line
   */
  private static String render(ConstraintViolation<?> violation) {
    String path = violation.getPropertyPath().toString();
    return (path.isEmpty() ? BEAN_LEVEL : path) + ": " + violation.getMessageTemplate();
  }

  /**
   * The {@link ErrorDetail#message()} for a failed validation.
   *
   * @param violations the rendered violations, at least one
   * @return caller-facing text carrying the same lines and no values
   */
  private static String describe(List<String> violations) {
    return violations.size() == 1
        ? "validation failed: " + violations.get(0)
        : "validation failed with " + violations.size() + " violations: " + violations;
  }
}
