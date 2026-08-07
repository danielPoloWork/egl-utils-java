package it.d4np.utils.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Objects;

/**
 * Jackson, configured once against the settings that decide whether a JSON boundary is safe (FR-20,
 * RFC-0003).
 *
 * <pre>{@code
 * JsonMapper json = JsonMapper.create();        // once, at start-up
 *
 * String document = json.writeValueAsString(order);
 * Order parsed = json.readValue(document, Order.class);
 * }</pre>
 *
 * <p><strong>The configured mapper is not reachable, and that is the whole guarantee.</strong>
 * There is no getter, and no {@code ObjectMapper} appears in any signature of this class. A single
 * {@code activateDefaultTyping} call on a handed-out mapper re-opens the
 * polymorphic-deserialization CVE class (CWE-502) that FR-20 exists to close, so the guarantee has
 * to be a property of <em>the type</em> rather than of the call path that built it — ADR-0022's
 * rule that a guarantee a consumer can switch off is advisory. It is also what makes the
 * thread-safety claim below true: an {@code ObjectMapper} is thread-safe once configured and never
 * reconfigured, and "never reconfigured" is exactly what an absent getter buys.
 *
 * <h2>What is configured, and why each setting is written out</h2>
 *
 * <table border="1">
 *   <caption>The hardened profile</caption>
 *   <tr><th>Setting</th><th>Why</th></tr>
 *   <tr>
 *     <td>{@code deactivateDefaultTyping()}</td>
 *     <td>FR-20's headline. Default typing lets the <em>document</em> name the class to instantiate,
 *         which is the gadget-chain class of vulnerability. Off is Jackson's default too; it is
 *         stated anyway, so that the intent is in the code rather than in a version's default</td>
 *   </tr>
 *   <tr>
 *     <td>{@code JavaTimeModule} registered</td>
 *     <td>FR-20. Without it {@code java.time} types are not serializable at all</td>
 *   </tr>
 *   <tr>
 *     <td>{@code FAIL_ON_UNKNOWN_PROPERTIES} disabled</td>
 *     <td>FR-20. Reading a document you do not own must tolerate a producer adding a field.
 *         <strong>Not</strong> a licence for a partial update to ignore a misspelling — FR-21's
 *         {@code readPartial} refuses an unknown property per operation (RFC-0003)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code INCLUDE_SOURCE_IN_LOCATION} disabled</td>
 *     <td>RFC-0003's addition, and control <strong>C-01</strong>. Jackson embeds a snippet of the
 *         source document in a parse error's location, so a malformed body holding a password or a
 *         card number travels inside an exception message toward FR-19's RFC 7807 response</td>
 *   </tr>
 *   <tr>
 *     <td>{@code WRITE_DATES_AS_TIMESTAMPS} disabled</td>
 *     <td>The one setting beyond the four the requirement and the RFC name, recorded in
 *         ADR-0025: registering the {@code JavaTimeModule} and leaving this on emits an epoch
 *         number where every host framework in the compatibility matrix emits ISO-8601, so one
 *         service would put two wire formats on the same field</td>
 *   </tr>
 * </table>
 *
 * <p><strong>An explicit setting survives a default that moves, and that is measured rather than
 * assumed.</strong> {@code INCLUDE_SOURCE_IN_LOCATION} defaults to <em>enabled</em> in Jackson
 * 2.15.3 and to <em>disabled</em> in 2.22.1 — the version this module pins. Both are in the
 * supported matrix, because a Spring Boot 3.2 host's own dependency management downgrades this
 * library to its managed Jackson. Only the explicit {@code disable} makes the two hosts behave the
 * same way, which is the reasoning RFC-0001 used when it wrote UTF-8 out instead of calling {@code
 * Charset.defaultCharset()}.
 *
 * <h2>What this does not protect against</h2>
 *
 * <p>Default typing is the mechanism where the <em>document</em> chooses a type. An annotated base
 * type on the consumer's own class — {@code @JsonTypeInfo} — is the opposite: the <em>host</em>
 * chose, deliberately, in code it wrote and reviewed. Disabling default typing does not and should
 * not override that, and {@code JsonMapperTest.annotationDrivenPolymorphismStaysTheHostsDecision}
 * pins the boundary so nobody has to infer it. A host that annotates with {@code
 * JsonTypeInfo.Id.CLASS} over a base type reachable from untrusted input owns that decision and
 * should reach for Jackson's {@code PolymorphicTypeValidator}.
 *
 * <h2>Customisation</h2>
 *
 * <p>{@link #withModules(List)} takes Jackson {@code Module}s and is <strong>additive only</strong>
 * — the shape RFC-0002 gave {@code AuditPolicy.withAdditionalNeverCapture}, where entries can be
 * added and never removed. The residual is stated rather than hidden: a {@code Module} can register
 * a deserializer that does something dangerous, but that is the host's own code, deliberately
 * written and registered, not a configuration flag flipped by accident.
 *
 * <p>It is also the one place a Jackson type appears in a published signature, and ADR-0024 records
 * why the module descriptor still requires Jackson <em>non</em>-transitively. The rule the module
 * system actually applies is narrower than it looks: a consumer needs {@code requires
 * com.fasterxml.jackson.databind} where <em>its own source names a Jackson type</em>, not where an
 * invoked signature mentions one. So {@link #create()}, {@link #readValue} and {@link
 * #writeValueAsString} cost a consumer no declaration at all — measured, by compiling and running a
 * consumer module that never names Jackson — and the one that builds a {@code Module} was always
 * going to name it.
 *
 * <h2>Two types share this simple name</h2>
 *
 * <p>Jackson ships {@code com.fasterxml.jackson.databind.json.JsonMapper} for a related job, so an
 * editor's auto-import can pick the wrong one. It cannot diverge silently: that type has no {@code
 * create()}, so the mistake fails to compile rather than handing back an unhardened mapper — the
 * naming test ADR-001 states, which is also why FR-20's name is kept. The cost is paid inside this
 * file, which names Jackson's builder by its fully qualified name, exactly as {@code Validator}
 * names {@code jakarta.validation.Validator}.
 *
 * <h2>Scope</h2>
 *
 * <p>{@code String} in, {@code String} out, and a {@link Class} target. A generic target needs a
 * type token and deep conversion needs a second operation; both belong to FR-21 and arrive with
 * {@code ObjectMapperExtensions} (item 4.2). Overloads for {@code byte[]}, a stream or a reader are
 * omitted in the reversible direction — adding one later is MINOR under RFC-0001 §Versioning,
 * removing one is MAJOR.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and thread-safe: one instance serves every thread, and it is meant to be built once
 * and held for the life of the process. <strong>No jcstress harness is owed</strong> — the claim
 * reduces to Jackson's own guarantee over an object this class never mutates, and a harness would
 * be measuring Jackson (RFC-0003 §FR-20, and item 3.1's reasoning for {@code Validator} over a Bean
 * Validation provider).
 *
 * @see JsonConversionException
 */
public final class JsonMapper {

  /**
   * The configured mapper. Private, and never returned: see the class documentation.
   *
   * <p>Package-private access is offered through {@link #delegate()} for FR-21's helpers, which
   * cannot do their job without it and cannot be reached from outside this module's exported
   * package.
   */
  private final ObjectMapper delegate;

  private JsonMapper(ObjectMapper delegate) {
    this.delegate = delegate;
  }

  /**
   * The hardened mapper with no host modules beyond the {@code JavaTimeModule}.
   *
   * @return a configured mapper; never {@code null}
   */
  public static JsonMapper create() {
    return new JsonMapper(configure(List.of()));
  }

  /**
   * The hardened mapper plus the caller's modules, registered after the built-in ones.
   *
   * <p>Registered last so that a host module wins where it overlaps — a custom serializer for a
   * {@code java.time} type is the ordinary reason to reach for this. Nothing a module can do
   * re-enables default typing, because activation is a mapper call and no mapper is reachable.
   *
   * @param modules the Jackson modules to add; copied, not retained, and may be empty
   * @return a configured mapper; never {@code null}
   * @throws NullPointerException if {@code modules} or any element is {@code null}
   */
  public static JsonMapper withModules(List<com.fasterxml.jackson.databind.Module> modules) {
    Objects.requireNonNull(modules, "modules must not be null");
    return new JsonMapper(configure(List.copyOf(modules)));
  }

  /**
   * Renders {@code value} as a JSON document.
   *
   * @param value the object to serialize; must not be {@code null}
   * @return the rendered document; never {@code null}
   * @throws NullPointerException if {@code value} is {@code null}
   * @throws JsonConversionException if Jackson cannot serialize {@code value} — the message names
   *     the source type and the property path, never the value
   */
  public String writeValueAsString(Object value) {
    Objects.requireNonNull(value, "value must not be null");
    try {
      return delegate.writeValueAsString(value);
    } catch (JsonProcessingException failed) {
      throw new JsonConversionException(
          JsonDiagnostics.describeWrite(value.getClass(), failed), failed);
    }
  }

  /**
   * Reads {@code json} as an instance of {@code type}.
   *
   * <p><strong>A document that is the literal {@code null} is refused rather than
   * returned.</strong> It is valid JSON and Jackson answers it with a {@code null} reference, which
   * is the one thing this library never hands back across a boundary (control C-02): a caller that
   * received it would discover the absence at the next dereference, somewhere else entirely.
   *
   * @param <T> the target type
   * @param json the document to read; must not be {@code null}
   * @param type the type to read it as; must not be {@code null}
   * @return the parsed instance; never {@code null}
   * @throws NullPointerException if {@code json} or {@code type} is {@code null}
   * @throws JsonConversionException if the document is malformed, does not fit {@code type}, or is
   *     the literal {@code null} — the message names the target type and the property path, never
   *     any part of the document
   */
  public <T> T readValue(String json, Class<T> type) {
    Objects.requireNonNull(json, "json must not be null");
    Objects.requireNonNull(type, "target type must not be null");
    T parsed;
    try {
      parsed = delegate.readValue(json, type);
    } catch (JsonProcessingException failed) {
      throw new JsonConversionException(JsonDiagnostics.describeRead(type, failed), failed);
    }
    if (parsed == null) {
      throw new JsonConversionException(JsonDiagnostics.describeNullDocument(type));
    }
    return parsed;
  }

  /**
   * The configured mapper, for FR-21's helpers in this package only.
   *
   * <p>Package-private, and the package is the unit of trust: {@code it.d4np.utils.json} is
   * exported but not open, so nothing outside this module can reach a package-private member of it
   * — which is what keeps "the mapper is not reachable" true while {@code ObjectMapperExtensions}
   * still works over it.
   *
   * @return the mapper this instance was built with; never {@code null}
   */
  ObjectMapper delegate() {
    return delegate;
  }

  /**
   * Builds the hardened mapper.
   *
   * <p>Every call is a decision the class documentation explains; none of them is a default being
   * restated for tidiness.
   *
   * @param modules the host's modules, already copied
   * @return a fresh, fully configured mapper
   */
  private static ObjectMapper configure(List<com.fasterxml.jackson.databind.Module> modules) {
    com.fasterxml.jackson.databind.json.JsonMapper.Builder builder =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .deactivateDefaultTyping()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION);
    for (com.fasterxml.jackson.databind.Module module : modules) {
      builder.addModule(module);
    }
    return builder.build();
  }
}
