package it.d4np.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The annotated types FR-16's tests capture — one file, because three test classes need the same
 * shapes and a second copy of an audited record is a second contract to keep in step.
 *
 * <p>Every nested type is <strong>public</strong> on purpose: {@link AuditLog} reads values through
 * public accessors and refuses anything else, so a package-private fixture would test the refusal
 * rather than the feature. {@link Hidden} is the deliberate exception.
 *
 * <p>Named without a {@code Test} prefix or suffix, like {@code SerializationSupport} and {@code
 * LogRecorder}: surefire would otherwise offer it to the JUnit Platform as a test class.
 */
final class AuditFixtures {

  private AuditFixtures() {}

  /** Layer 3 — one component asks for capture and the other is invisible to the trail. */
  public record Customer(@Audited String email, String internalNote) {}

  /** Layer 4 with a layer 2 opt-out — the shape RFC-0002 says {@code @Sensitive} exists for. */
  @Audited
  public record Account(String owner, @Sensitive String password, int loginCount) {}

  /**
   * Layer 1 — nothing here is marked {@code @Sensitive} and two components are redacted anyway.
   *
   * <p>{@code tokenCount} is the over-redaction RFC-0002 accepts deliberately: it normalises to
   * {@code [token, count]} and contains the run {@code [token]}.
   */
  @Audited
  public record Integration(String name, String apiKey, int tokenCount) {}

  /**
   * {@code @Sensitive} is an opt-out, so on its own it requests nothing and the type is not
   * auditable.
   */
  public record OnlySensitive(@Sensitive String password) {}

  /**
   * The record whose generated {@code toString()} would publish a password if capture rendered it.
   */
  @Audited
  public record Credentials(String user, @Sensitive String password) {}

  /** A composite component, captured by recursing into {@link Credentials}. */
  public record Login(@Audited String host, @Audited Credentials principal) {}

  /**
   * The same composite under a component name layer 1 blocks — {@code credentials} is a base-list
   * entry, so the whole subtree is redacted and never walked into.
   *
   * <p>Worth a fixture of its own because RFC-0002 illustrates the recursion rule with exactly this
   * field name, and its own never-capture list outranks the illustration.
   */
  public record NamedCredentials(@Audited Credentials credentials) {}

  /** A composite component whose type carries no marker at all — the loud half of the trap. */
  public record Unmarked(String user, String password) {}

  /** Holds an {@link Unmarked}, which capture must refuse rather than render. */
  public record HasUnmarked(@Audited Unmarked nested) {}

  /** A collection component: not a simple value, and not something {@code @Audited} can go on. */
  public record HasList(@Audited List<String> roles) {}

  /** An {@code Object}-declared component, for the declared-type-versus-runtime-type rule. */
  public record HasObject(@Audited Object value) {}

  /** Simple values beyond text and numbers, all of which render on their own. */
  @Audited
  public record Assorted(Status status, UUID id, Instant at, Duration every, char grade) {}

  /**
   * A status whose {@code toString()} is a display label, so {@code name()} is the stable identity.
   */
  public enum Status {
    ACTIVE("live"),
    SUSPENDED("paused");

    private final String label;

    Status(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** Depth: the leaf, three levels below {@link Level2}'s root. */
  @Audited
  public record Level4(String leaf) {}

  /** Depth: three levels below the root is legal. */
  @Audited
  public record Level3(Level4 next) {}

  /** Depth: legal as a root, since its deepest component sits exactly three levels down. */
  @Audited
  public record Level2(Level3 next) {}

  /** Depth: illegal as a root, because {@link Level4}'s components would be a fourth level. */
  @Audited
  public record Level1(Level2 next) {}

  /**
   * A JavaBean rather than a record, and the only fixture whose accessor does not return its field.
   *
   * <p>{@code getGreeting()} returns something the private field does not hold, which is how the
   * test proves the value came from the accessor rather than from the field — the FR-16 rule that
   * keeps a consuming module from ever needing an {@code opens} clause.
   */
  public static final class Bean {

    private final String stored;

    /**
     * A bean whose one property is derived.
     *
     * @param stored what the private field holds, which no assertion should ever see
     */
    public Bean(String stored) {
      this.stored = stored;
    }

    /**
     * The derived property, deliberately not the field.
     *
     * @return the field's value with a marker showing the accessor ran
     */
    @Audited
    public String getGreeting() {
      return "via-accessor:" + stored;
    }
  }

  /** A bean carrying its markers on the fields, which is what a JavaBean codebase does. */
  @Audited
  public static final class MarkedFields {

    @Audited private final String owner;

    @Sensitive private final String token;

    /**
     * A bean with field-level markers and unannotated accessors.
     *
     * @param owner the audited property
     * @param token the property blocked by a marker the accessor does not carry
     */
    public MarkedFields(String owner, String token) {
      this.owner = owner;
      this.token = token;
    }

    /**
     * The audited property.
     *
     * @return the owner
     */
    public String getOwner() {
      return owner;
    }

    /**
     * The blocked property, marked on the field rather than here.
     *
     * @return the token
     */
    public String getToken() {
      return token;
    }
  }

  /** A marker attached to nothing: the field is annotated and no accessor exposes it. */
  public static final class MarkerWithoutAccessor {

    @Audited private final String orphan;

    /**
     * A bean whose marked field has no accessor.
     *
     * @param orphan the value nothing can read
     */
    public MarkerWithoutAccessor(String orphan) {
      this.orphan = orphan;
    }

    /**
     * A property that exists so the type has one at all.
     *
     * @return the length of the orphaned field
     */
    @Audited
    public int getSize() {
      return orphan.length();
    }
  }

  /** Two accessors, one property, and no way to know which one the markers meant. */
  @Audited
  public static final class AmbiguousAccessors {

    /**
     * One spelling of the property.
     *
     * @return always {@code true}
     */
    public boolean isActive() {
      return true;
    }

    /**
     * The other spelling of the same property.
     *
     * @return always {@code true}
     */
    public boolean getActive() {
      return true;
    }
  }

  /** An accessor that throws, which makes the object un-auditable in that state. */
  @Audited
  public static final class BrokenAccessor {

    /**
     * Fails the way a lazily-loaded association fails outside its session.
     *
     * @return never
     */
    // Always throwing is the whole fixture: @DoNotCall would be the suggested fix and would forbid
    // the reflective call the test exists to make.
    @SuppressWarnings("DoNotCallSuggester")
    public String getDetail() {
      throw new IllegalStateException("association is not loaded");
    }
  }

  /** A mutable node, so a cycle can be built; self-linked at construction. */
  @Audited
  public static final class Node {

    private final String name;

    private Node link;

    /**
     * A node linked to itself, which is the shortest cycle there is.
     *
     * @param name the node's name
     */
    public Node(String name) {
      this.name = name;
      this.link = this;
    }

    /**
     * Points this node at another.
     *
     * @param other where to link
     */
    public void linkTo(Node other) {
      this.link = other;
    }

    /**
     * The node's name.
     *
     * @return the name
     */
    public String getName() {
      return name;
    }

    /**
     * Where this node points.
     *
     * @return the linked node, possibly this one
     */
    public Node getLink() {
      return link;
    }
  }

  /** Not public, so its accessors are out of reach without the deep reflection FR-16 forbids. */
  @Audited
  record Hidden(String value) {}
}
