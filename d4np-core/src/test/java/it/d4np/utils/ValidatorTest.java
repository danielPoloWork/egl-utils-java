package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-14 (RFC-0002): the contract of {@link Validator}.
 *
 * <p>Exercised against <strong>Hibernate Validator, the reference implementation</strong>, rather
 * than a stub: the one claim this type exists to make — a rendered violation never carries the
 * rejected value — is a claim about what a real provider produces from a real constraint, and a
 * stub would only assert the test's own idea of a violation. The two cases no annotation-driven
 * fixture can reach — a bean-level constraint's empty property path, and an absent provider — are
 * in {@link ValidatorProviderTest}.
 */
@DisplayName("Validator")
class ValidatorTest {

  /**
   * The password constraint's message deliberately interpolates {@code ${validatedValue}}: that is
   * the exact shape C-01 forbids from reaching a client, and a constraint that would leak under
   * {@code getMessage()} is the only honest way to prove the rendering blocks it.
   */
  record Account(
      @NotBlank String name,
      @Size(min = 3, max = 30) String nickname,
      @Pattern(regexp = "\\d{8,}", message = "${validatedValue} is not a valid password")
          String password) {}

  /** A group nothing reaches by default, so the group argument is proven to do something. */
  interface OnRegistration {}

  record Signup(@NotBlank(groups = OnRegistration.class) String email) {}

  private final Validator validator = Validator.create();

  @Test
  void resolvesTheProviderOnTheClasspath() {
    assertThat(Validator.create()).isNotNull();
  }

  @Test
  void answersOkWithTheCandidateItself() {
    Account account = new Account("Ada", "ada", "12345678");

    Result<Account> result = validator.validate(account);

    assertThat(result).isInstanceOf(Result.Ok.class);
    assertThat(((Result.Ok<Account>) result).value()).isSameAs(account);
    assertThat(validator.violations(account)).isEmpty();
  }

  @Test
  void answersErrCodedForValidation() {
    Result<Account> result = validator.validate(new Account("", "ada", "12345678"));

    assertThat(result).isInstanceOf(Result.Err.class);
    ErrorDetail error = ((Result.Err<Account>) result).error();
    assertThat(error.code()).isEqualTo(Validator.VALIDATION_FAILED);
    assertThat(error.message()).contains("name");
    assertThat(error.cause()).isNull();
  }

  /**
   * Compliance control C-01. {@code hunter2} is the invalid value and the constraint's own message
   * asks for it by name, so a rendering built on {@code ConstraintViolation.getMessage()} would put
   * it into an {@link ErrorDetail} — and from there into an RFC 7807 body under FR-19.
   */
  @Test
  void neverCarriesTheRejectedValue() {
    Account account = new Account("Ada", "ada", "hunter2");

    List<String> violations = validator.violations(account);
    Result<Account> result = validator.validate(account);

    assertThat(violations).hasSize(1);
    assertThat(violations.get(0)).doesNotContain("hunter2").contains("${validatedValue}");
    assertThat(((Result.Err<Account>) result).error().message()).doesNotContain("hunter2");
    assertThatExceptionOfType(ValidationException.class)
        .isThrownBy(() -> validator.requireValid(account))
        .satisfies(
            thrown -> {
              assertThat(thrown).hasMessageNotContaining("hunter2");
              assertThat(thrown.violations())
                  .allSatisfy(line -> assertThat(line).doesNotContain("hunter2"));
            });
  }

  @Test
  void rendersThePropertyPathAndTheMessageTemplate() {
    List<String> violations = validator.violations(new Account("", "ada", "12345678"));

    assertThat(violations)
        .containsExactly("name: {jakarta.validation.constraints.NotBlank.message}");
  }

  /**
   * The provider hands back an unordered {@code Set}; a fixed order is what makes a message
   * assertion or a log diff mean anything.
   */
  @Test
  void reportsEveryViolationInLexicographicOrder() {
    List<String> violations = validator.violations(new Account("", "x", "12345678"));

    assertThat(violations)
        .containsExactly(
            "name: {jakarta.validation.constraints.NotBlank.message}",
            "nickname: {jakarta.validation.constraints.Size.message}");
  }

  @Test
  void appliesOnlyTheRequestedGroups() {
    Signup signup = new Signup("");

    assertThat(validator.violations(signup)).isEmpty();
    assertThat(validator.violations(signup, OnRegistration.class))
        .containsExactly("email: {jakarta.validation.constraints.NotBlank.message}");
  }

  @Test
  void requireValidReturnsTheCandidate() {
    Account account = new Account("Ada", "ada", "12345678");

    assertThat(validator.requireValid(account)).isSameAs(account);
  }

  @Test
  void requireValidThrowsCarryingEveryViolation() {
    assertThatExceptionOfType(ValidationException.class)
        .isThrownBy(() -> validator.requireValid(new Account("", "x", "12345678")))
        .satisfies(
            thrown -> {
              assertThat(thrown.violations()).hasSize(2);
              assertThat(thrown)
                  .hasMessageContaining("Account")
                  .hasMessageContaining("2 violations");
            });
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsNullOnEveryEntryPoint() {
    Signup valid = new Signup("someone@example.com");

    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> validator.validate((Object) null));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> validator.requireValid((Object) null));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> validator.violations((Object) null));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> validator.violations(valid, (Class<?>[]) null));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> validator.violations(valid, OnRegistration.class, null));
    assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> Validator.using(null));
  }
}
