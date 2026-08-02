package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.NoProviderFoundException;
import jakarta.validation.Path;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two {@link Validator} paths a real provider cannot produce here.
 *
 * <p><strong>Provider absence</strong> cannot be reproduced by removing Hibernate Validator from
 * the test classpath — the rest of {@link ValidatorTest} needs it — so {@link
 * Validator#fromProvider} is the seam the failure is injected through. Both shapes are covered:
 * {@code NoProviderFoundException} when the API is present without an implementation, and {@code
 * NoClassDefFoundError} when neither is on the runtime image.
 *
 * <p><strong>A bean-level constraint</strong> — the one thing that produces an empty property path
 * — would need a custom class-level annotation whose {@code ConstraintValidator} the provider
 * instantiates reflectively, which means opening this package to an automatic module for the sake
 * of one rendered token. A stub delegate says the same thing without that privilege.
 */
@DisplayName("Validator — provider resolution and bean-level rendering")
class ValidatorProviderTest {

  @Test
  void refusesAtConstructionWhenTheProviderIsAbsent() {
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                Validator.fromProvider(
                    () -> {
                      throw new NoProviderFoundException("no implementation");
                    }))
        .withMessageContaining("jakarta.validation-api")
        .withMessageContaining("hibernate-validator")
        .withCauseInstanceOf(NoProviderFoundException.class);
  }

  @Test
  void refusesAtConstructionWhenTheApiItselfIsAbsent() {
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () ->
                Validator.fromProvider(
                    () -> {
                      throw new NoClassDefFoundError("jakarta/validation/Validation");
                    }))
        .withCauseInstanceOf(NoClassDefFoundError.class);
  }

  @Test
  void refusesAProviderThatSuppliesNothing() {
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> Validator.fromProvider(ValidatorProviderTest::noProvider))
        .withMessageContaining("jakarta.validation-api")
        .withNoCause();
  }

  @Test
  void rendersABeanLevelViolationWithoutAPropertyPath() {
    Validator validator = Validator.using(new SingleViolationValidator());

    List<String> violations = validator.violations("anything");

    assertThat(violations).containsExactly("<bean>: {test.BeanLevel.message}");
  }

  /**
   * The {@code null} is the fixture: a delegate factory that hands back nothing is the third way
   * provider resolution fails, and it has to be written down to be tested.
   */
  @SuppressWarnings("NullAway")
  private static jakarta.validation.Validator noProvider() {
    return null;
  }

  /** A delegate that reports exactly one violation, at bean level. */
  private static final class SingleViolationValidator implements jakarta.validation.Validator {

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
      return Set.of(new BeanLevelViolation<>());
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(
        T object, String propertyName, Class<?>... groups) {
      throw new UnsupportedOperationException("not part of the FR-14 surface");
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(
        Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
      throw new UnsupportedOperationException("not part of the FR-14 surface");
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
      throw new UnsupportedOperationException("not part of the FR-14 surface");
    }

    @Override
    public <T> T unwrap(Class<T> type) {
      throw new UnsupportedOperationException("not part of the FR-14 surface");
    }

    @Override
    public ExecutableValidator forExecutables() {
      throw new UnsupportedOperationException("not part of the FR-14 surface");
    }
  }

  /** A violation whose property path is empty, which is what a class-level constraint produces. */
  private static final class BeanLevelViolation<T> implements ConstraintViolation<T> {

    private static final Path EMPTY_PATH =
        new Path() {
          @Override
          public Iterator<Node> iterator() {
            return Collections.emptyIterator();
          }

          @Override
          public String toString() {
            return "";
          }
        };

    @Override
    public String getMessage() {
      return "the interpolated message, which FR-14 never renders";
    }

    @Override
    public String getMessageTemplate() {
      return "{test.BeanLevel.message}";
    }

    @Override
    public Path getPropertyPath() {
      return EMPTY_PATH;
    }

    @Override
    @SuppressWarnings("NullAway") // the contract permits null for every bean accessor below
    public T getRootBean() {
      return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<T> getRootBeanClass() {
      return (Class<T>) Object.class;
    }

    @Override
    @SuppressWarnings("NullAway") // "null when the violation is not on a leaf bean"
    public Object getLeafBean() {
      return null;
    }

    @Override
    @SuppressWarnings("NullAway") // "null when the violation is not on an executable"
    public Object[] getExecutableParameters() {
      return null;
    }

    @Override
    @SuppressWarnings("NullAway") // "null when the violation is not on a return value"
    public Object getExecutableReturnValue() {
      return null;
    }

    @Override
    @SuppressWarnings("NullAway") // no descriptor exists for a hand-built violation
    public Object getInvalidValue() {
      return null;
    }

    @Override
    @SuppressWarnings("NullAway") // no descriptor exists for a hand-built violation
    public ConstraintDescriptor<?> getConstraintDescriptor() {
      return null;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
      throw new UnsupportedOperationException("not part of the FR-14 surface");
    }
  }
}
