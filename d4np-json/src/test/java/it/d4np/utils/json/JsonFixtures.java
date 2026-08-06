package it.d4np.utils.json;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The shapes FR-20's tests read and write — one file, because two test classes need the same types
 * and a second copy of a fixture is a second contract to keep in step (core's {@code AuditFixtures}
 * precedent).
 *
 * <p>Every nested type is <strong>public</strong> on purpose, and under the module system that is
 * load-bearing rather than stylistic: Jackson reaches a type reflectively from its own module,
 * which the JDK permits only for a public member of a public class in an <em>exported</em> package.
 * A package-private fixture would fail with {@code InaccessibleObjectException} and would be
 * testing the module system rather than the mapper — the same obligation a consumer carries,
 * described in this package's documentation.
 *
 * <p>Named without a {@code Test} prefix or suffix, like core's {@code AuditFixtures}, so surefire
 * does not offer it to the JUnit Platform as a test class.
 */
final class JsonFixtures {

  private JsonFixtures() {}

  /** The ordinary round-trip shape. */
  public record Order(String sku, int quantity) {}

  /**
   * A credential document, for the control C-01 assertions.
   *
   * <p>{@code hunter2} is the value that must not appear in any message this library produces, and
   * the truncated document below is the shape that makes Jackson want to quote it.
   */
  public record Credentials(String user, String password) {}

  /**
   * An {@code Object}-typed component, which is what default typing would act on.
   *
   * <p>The declared type has to be non-final for {@code DefaultTyping.NON_FINAL} to apply, which is
   * why the payload is {@code Object} rather than a record type.
   */
  public record Envelope(Object payload) {}

  /** What a gadget-shaped payload names. Nothing here is dangerous — being instantiated is. */
  public record Marker(boolean armed) {}

  /**
   * {@code java.time} on both sides of the round trip, for the {@code JavaTimeModule} assertions.
   */
  public record Timed(Instant at, LocalDate on) {}

  /**
   * A typed {@code Map} inside a record, so the value type survives erasure.
   *
   * <p>This is the only shape in which a <strong>document-supplied</strong> name reaches an
   * exception message: a record's own component names are ours, a map's keys are the client's.
   */
  public record Bag(Map<String, List<Integer>> entries) {}

  /** Self-referential, so a test can build a path of any depth without a fixture per level. */
  public record Node(Node child, int value) {}

  /**
   * A base type the <em>host</em> annotated, which is the case disabling default typing does not
   * and should not cover.
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
  public interface Command {}

  /** The subtype {@link Command}'s type id names. */
  public record Reboot(String host) implements Command {}

  /** Carries a {@link Command}, so the annotation is what decides the concrete type. */
  public record Dispatch(Command command) {}
}
