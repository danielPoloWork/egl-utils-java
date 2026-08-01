package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code FluentBuilder<T>} against the RFC-0001 FR-02 contract — ROADMAP item 2.4.
 *
 * <p>The contract has four clauses and each is a group below: validate-then-construct as a template
 * method, accumulate-all-violations, a repeatable {@code build()} over a builder that is not reset,
 * and the non-null boundary. The defensive-copy rule is the one clause this class <em>cannot</em>
 * enforce, so it is tested from the other side: a subclass that forgets it is shown leaking, which
 * is what makes the Javadoc rule a demonstrated hazard rather than an assertion.
 */
@DisplayName("FluentBuilder")
class FluentBuilderTest {

  // --- template method ---

  @Test
  @DisplayName("build() validates before constructing")
  void validatesBeforeConstructing() {
    List<String> order = new ArrayList<>();
    FluentBuilder<String> builder =
        new FluentBuilder<>() {
          @Override
          protected void validate() {
            order.add("validate");
          }

          @Override
          protected String construct() {
            order.add("construct");
            return "built";
          }
        };

    assertThat(builder.build()).isEqualTo("built");
    assertThat(order).containsExactly("validate", "construct");
  }

  @Test
  @DisplayName("construct() is never reached when validation fails")
  void doesNotConstructWhenInvalid() {
    List<String> order = new ArrayList<>();
    FluentBuilder<String> builder =
        new FluentBuilder<>() {
          @Override
          protected void validate() {
            reject("nope");
          }

          @Override
          protected String construct() {
            order.add("construct");
            return "built";
          }
        };

    assertThatExceptionOfType(BuilderValidationException.class).isThrownBy(builder::build);
    assertThat(order).as("an invalid builder must not construct anything").isEmpty();
  }

  @Test
  @DisplayName("build() is final, so a subclass cannot reintroduce fail-fast")
  void buildIsFinal() throws Exception {
    Method build = FluentBuilder.class.getDeclaredMethod("build");

    assertThat(Modifier.isFinal(build.getModifiers())).isTrue();
  }

  // --- accumulate every violation ---

  @Test
  @DisplayName("collects EVERY missing field, not the first")
  void collectsEveryMissingField() {
    BuilderValidationException thrown =
        catchThrowableOfType(BuilderValidationException.class, new OrderBuilder()::build);

    assertThat(thrown.violations())
        .containsExactly("customer is required", "at least one line is required");
  }

  @Test
  @DisplayName("require() records only when the field is unset")
  void requireRecordsOnlyWhenUnset() {
    BuilderValidationException thrown =
        catchThrowableOfType(
            BuilderValidationException.class, new OrderBuilder().customer("ACME")::build);

    assertThat(thrown.violations()).containsExactly("at least one line is required");
  }

  @Test
  @DisplayName("reject() carries a cross-field rule that no null check could express")
  void rejectCarriesCrossFieldRules() {
    // The reason reject() exists: "b must follow a" is not a null check on either field, so without
    // it this rule could only throw, dropping the caller back to one violation per round trip.
    BuilderValidationException thrown =
        catchThrowableOfType(
            BuilderValidationException.class,
            new OrderBuilder().customer("ACME").line("b").line("a")::build);

    assertThat(thrown.violations()).containsExactly("lines must be in order");
  }

  // --- repeatable build over a builder that is not reset ---

  @Test
  @DisplayName("build() is repeatable and returns a distinct instance per call")
  void buildIsRepeatable() {
    OrderBuilder builder = new OrderBuilder().customer("ACME").line("a");

    Order first = builder.build();
    Order second = builder.build();

    assertThat(first).isNotSameAs(second);
    assertThat(first.customer()).isEqualTo("ACME");
    assertThat(second.customer()).isEqualTo("ACME");
  }

  @Test
  @DisplayName("the builder keeps its state, so a partly-configured builder is a prototype")
  void theBuilderIsNotReset() {
    OrderBuilder template = new OrderBuilder().customer("ACME");

    Order first = template.line("a").build();
    Order second = template.line("b").build();

    assertThat(first.lines()).containsExactly("a");
    assertThat(second.lines()).containsExactly("a", "b");
  }

  @Test
  @DisplayName("a builder made valid after a failure builds on the next call")
  void violationsDoNotLeakBetweenBuilds() {
    OrderBuilder builder = new OrderBuilder();

    assertThatExceptionOfType(BuilderValidationException.class).isThrownBy(builder::build);

    // If violations were not cleared per build, this would still report the earlier findings.
    Order built = builder.customer("ACME").line("a").build();
    assertThat(built.customer()).isEqualTo("ACME");
  }

  // --- the defensive-copy rule, demonstrated rather than asserted ---

  @Test
  @DisplayName("construct() that copies survives later builder mutation")
  void aCopyingConstructIsSafe() {
    OrderBuilder builder = new OrderBuilder().customer("ACME").line("a");

    Order built = builder.build();
    builder.line("b");

    assertThat(built.lines())
        .as("OrderBuilder copies, so the built order is frozen")
        .containsExactly("a");
  }

  @Test
  @DisplayName("construct() that does NOT copy leaks the builder's list — the documented hazard")
  void aNonCopyingConstructLeaks() {
    LeakyBuilder builder = new LeakyBuilder();
    builder.line("a");

    List<String> leaked = builder.build();
    builder.line("b");

    // This is the failure mode the class Javadoc and ADR-0017 warn about: the "immutable" value
    // object changes under its owner because build() does not reset the builder. Asserted here so
    // the rule is a demonstrated consequence rather than a style note.
    assertThat(leaked).containsExactly("a", "b");
  }

  // --- the non-null boundary ---

  @Test
  @DisplayName("build() rejects a construct() that returns null")
  // The suppression IS part of the finding, so it is not silently applied: NullAway rejects this
  // subclass outright, because construct() declares a non-null return. So on the JDK 21+ cells an
  // ANNOTATED subclass cannot reach build()'s runtime check at all -- the check exists for
  // consumers whose own build runs no NullAway, which is every consumer by default (ADR-0009).
  @SuppressWarnings("NullAway")
  void rejectsANullFromConstruct() {
    FluentBuilder<String> builder =
        new FluentBuilder<>() {
          @Override
          protected void validate() {
            // valid
          }

          @Override
          protected String construct() {
            return null;
          }
        };

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(builder::build)
        .withMessageContaining("returned null");
  }

  // --- fixtures ---

  /** A record standing in for a domain object. */
  record Order(String customer, List<String> lines) {}

  /** A well-behaved builder: accumulates violations and copies defensively. */
  static final class OrderBuilder extends FluentBuilder<Order> {
    @Nullable private String customer;
    private final List<String> lines = new ArrayList<>();

    OrderBuilder customer(String customer) {
      this.customer = customer;
      return this;
    }

    OrderBuilder line(String line) {
      this.lines.add(line);
      return this;
    }

    @Override
    protected void validate() {
      require(customer, "customer");
      if (lines.isEmpty()) {
        reject("at least one line is required");
      } else if (!lines.stream().sorted().toList().equals(lines)) {
        reject("lines must be in order");
      }
    }

    @Override
    protected Order construct() {
      return new Order(customer == null ? "" : customer, List.copyOf(lines));
    }
  }

  /** Deliberately violates the defensive-copy rule, to demonstrate the consequence. */
  static final class LeakyBuilder extends FluentBuilder<List<String>> {
    private final List<String> lines = new ArrayList<>();

    LeakyBuilder line(String line) {
      this.lines.add(line);
      return this;
    }

    @Override
    protected void validate() {
      // no invariants
    }

    @Override
    protected List<String> construct() {
      return lines; // NOT List.copyOf(lines) -- this is the bug being demonstrated
    }
  }
}
