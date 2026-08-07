package it.d4np.utils.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-20 (RFC-0003): the contract of {@link JsonMapper}.
 *
 * <p><strong>Every setting is asserted through behaviour, and the ones that matter are asserted
 * against a mapper that does not have them.</strong> "Default typing is off" is not a property of a
 * configuration call; it is a property of what happens to a document that names a class. So each of
 * the three settings a wrong answer would make dangerous is paired with a companion test that runs
 * the same document through raw Jackson configured the other way — if the companion stops failing,
 * the payload has gone inert and the main assertion has quietly become vacuous.
 */
@DisplayName("JsonMapper")
class JsonMapperTest {

  /**
   * A payload that names a class and hands it a constructor argument — the shape of the CVE class
   * FR-20 exists to close, in miniature.
   */
  private static final String GADGET_SHAPED =
      "{\"payload\":[\"it.d4np.utils.json.JsonFixtures$Marker\",{\"armed\":true}]}";

  /**
   * A credential document that stops mid-value, so the parser fails while the password is the text
   * it is looking at. {@code hunter2} is the value that must reach no message.
   */
  private static final String TRUNCATED_CREDENTIALS = "{\"user\":\"ada\",\"password\":\"hunter2";

  /**
   * Well-formed, but the value does not fit the component — the mapping failure, not a parse one.
   */
  private static final String REJECTED_VALUE = "{\"sku\":\"A-1\",\"quantity\":\"hunter2\"}";

  private final JsonMapper json = JsonMapper.create();

  @Test
  void readsAndWritesTheShapesItIsGiven() {
    JsonFixtures.Order order = new JsonFixtures.Order("A-1", 2);

    String document = json.writeValueAsString(order);

    assertThat(document).isEqualTo("{\"sku\":\"A-1\",\"quantity\":2}");
    assertThat(json.readValue(document, JsonFixtures.Order.class)).isEqualTo(order);
  }

  /**
   * FR-20: a producer adding a field must not break a consumer that does not know about it. This is
   * the leniency FR-21's {@code readPartial} deliberately does <em>not</em> inherit (RFC-0003).
   */
  @Test
  void toleratesAPropertyTheTargetDoesNotDeclare() {
    String fromANewerProducer = "{\"sku\":\"A-1\",\"quantity\":2,\"warehouse\":\"EU-3\"}";

    JsonFixtures.Order order = json.readValue(fromANewerProducer, JsonFixtures.Order.class);

    assertThat(order).isEqualTo(new JsonFixtures.Order("A-1", 2));
  }

  /**
   * FR-20's headline, and the threat model's <em>polymorphic-deserialization gadget chain</em> row.
   * The class name in the document is read as data — a string in a list — not as an instruction.
   */
  @Test
  void neverLetsTheDocumentChooseTheClass() {
    JsonFixtures.Envelope envelope = json.readValue(GADGET_SHAPED, JsonFixtures.Envelope.class);

    assertThat(envelope.payload())
        .isInstanceOf(List.class)
        .isNotInstanceOf(JsonFixtures.Marker.class);
    assertThat((List<?>) envelope.payload())
        .first()
        .isEqualTo("it.d4np.utils.json.JsonFixtures$Marker");
  }

  /**
   * The companion that keeps the test above honest: the same document, through a mapper with
   * default typing activated, <strong>does</strong> instantiate the class it names. Without this,
   * {@code neverLetsTheDocumentChooseTheClass} would keep passing over a payload that had stopped
   * being a payload.
   */
  @Test
  void theSameDocumentIsLiveUnderDefaultTyping() throws Exception {
    ObjectMapper unhardened =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.WRAPPER_ARRAY)
            .build();

    JsonFixtures.Envelope envelope =
        unhardened.readValue(GADGET_SHAPED, JsonFixtures.Envelope.class);

    assertThat(envelope.payload()).isEqualTo(new JsonFixtures.Marker(true));
  }

  /**
   * The boundary of the guarantee, pinned so nobody has to infer it. Disabling default typing stops
   * the <em>document</em> from choosing a type; it does not override a base type the <em>host</em>
   * annotated, and it should not — that is the host's own reviewed decision.
   */
  @Test
  void annotationDrivenPolymorphismStaysTheHostsDecision() {
    String document =
        "{\"command\":{\"@class\":\"it.d4np.utils.json.JsonFixtures$Reboot\",\"host\":\"db-1\"}}";

    JsonFixtures.Dispatch dispatch = json.readValue(document, JsonFixtures.Dispatch.class);

    assertThat(dispatch.command()).isEqualTo(new JsonFixtures.Reboot("db-1"));
  }

  /** FR-20's {@code JavaTimeModule}, plus the ISO-8601 rendering ADR-0024 records. */
  @Test
  void readsAndWritesJavaTimeAsIso8601() {
    JsonFixtures.Timed timed =
        new JsonFixtures.Timed(Instant.parse("2026-08-07T10:15:30Z"), LocalDate.of(2026, 8, 7));

    String document = json.writeValueAsString(timed);

    assertThat(document).isEqualTo("{\"at\":\"2026-08-07T10:15:30Z\",\"on\":\"2026-08-07\"}");
    assertThat(json.readValue(document, JsonFixtures.Timed.class)).isEqualTo(timed);
  }

  /**
   * The companion for the module registration: raw Jackson refuses {@code java.time} outright, so
   * the round trip above is evidence the module is registered rather than evidence that {@code
   * Instant} happens to be a well-behaved bean.
   */
  @Test
  void javaTimeIsNotSupportedWithoutTheModule() {
    ObjectMapper withoutTheModule = new ObjectMapper();

    assertThatThrownBy(
            () -> withoutTheModule.writeValueAsString(Instant.parse("2026-08-07T10:15:30Z")))
        .hasMessageContaining("java.time.Instant")
        .hasMessageContaining("not supported by default");
  }

  /**
   * The companion for the ISO-8601 rendering: with {@code WRITE_DATES_AS_TIMESTAMPS} left at
   * Jackson's default, the same instant is an epoch number — two wire formats for one field across
   * a service that also uses its framework's mapper.
   */
  @Test
  void aTimestampIsWhatTheDateSettingKeepsOut() throws Exception {
    ObjectMapper defaults =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    assertThat(defaults.writeValueAsString(Instant.parse("2026-08-07T10:15:30Z")))
        .doesNotContain("2026-08-07")
        .matches("[0-9.]+");
  }

  /**
   * Compliance control <strong>C-01</strong>. The document holds a password and the parse fails
   * while the parser is looking at it, which is exactly when Jackson wants to quote its source.
   * Nothing this library produces may carry it — not the message, and not the cause chain a
   * careless boundary handler might render.
   */
  @Test
  void noMessageCarriesTheDocument() {
    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue(TRUNCATED_CREDENTIALS, JsonFixtures.Credentials.class))
        .satisfies(
            thrown -> {
              assertThat(thrown.getMessage())
                  .doesNotContain("hunter2")
                  .contains("JsonFixtures$Credentials");
              assertThat(everyMessageIn(thrown)).noneMatch(message -> message.contains("hunter2"));
            });
  }

  /**
   * The companion for {@code INCLUDE_SOURCE_IN_LOCATION}: enabled — which is Jackson's default on
   * the 2.15.3 a Spring Boot 3.2 host downgrades this library to — the very same document leaks the
   * password through the parser's own exception.
   */
  @Test
  void theSourceSnippetIsWhatTheLocationSettingKeepsOut() {
    ObjectMapper unhardened =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    assertThatThrownBy(
            () -> unhardened.readValue(TRUNCATED_CREDENTIALS, JsonFixtures.Credentials.class))
        .hasMessageContaining("hunter2");
  }

  /**
   * C-01 again, on the mapping path rather than the parse path: the path travels, the value does
   * not.
   */
  @Test
  void namesThePathAndNeverTheValue() {
    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue(REJECTED_VALUE, JsonFixtures.Order.class))
        .satisfies(
            thrown -> {
              assertThat(thrown.getMessage()).contains("at quantity").doesNotContain("hunter2");
              assertThat(thrown.toString()).doesNotContain("hunter2");
            });
  }

  /**
   * <strong>The residual, pinned rather than described.</strong> RFC-0003 said the two defences
   * were the message rule (ours, always) and the disabled source location (Jackson's, protecting
   * the cause). Running it narrows the second: {@code INCLUDE_SOURCE_IN_LOCATION} keeps the source
   * <em>snippet</em> out of the location, and this test proves it does — but {@code
   * InvalidFormatException} quotes the rejected value in the body of its own message, which no
   * Jackson setting governs.
   *
   * <p>So the cause chain is <em>not</em> safe to render, and that is a stated fact rather than a
   * caution: FR-19's fallback handler (item 7.1) must not put a cause's {@code getMessage()} into
   * an RFC 7807 body, and this is the test that says why. The exception this library throws stays
   * clean either way, which is the defence that was always load-bearing.
   *
   * <p>The absent snippet is asserted as an absence rather than by the marker Jackson renders in
   * its place: 2.22 writes {@code REDACTED (...INCLUDE_SOURCE_IN_LOCATION disabled)} and the 2.15.3
   * floor writes {@code UNKNOWN}. The floor build named in this module's POM is what caught that,
   * which is the kind of version coupling it exists to find.
   */
  @Test
  void jacksonsOwnMessageStillQuotesTheValue() {
    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue(REJECTED_VALUE, JsonFixtures.Order.class))
        .satisfies(
            thrown -> {
              assertThat(thrown.getCause()).isNotNull();
              assertThat(String.valueOf(thrown.getCause()))
                  .contains("hunter2")
                  .doesNotContain("\"sku\":\"A-1\"");
            });
  }

  /**
   * A map key is the one name in a path that comes from the document rather than from a declared
   * type, so it is bounded in length — {@code JsonDiagnostics.MAX_NAME_LENGTH} characters and a
   * marker.
   */
  @Test
  void boundsTheLengthOfADocumentSuppliedName() {
    String longKey = "k".repeat(200);
    String document = "{\"entries\":{\"" + longKey + "\":[1,\"nope\"]}}";

    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue(document, JsonFixtures.Bag.class))
        .satisfies(
            thrown ->
                assertThat(thrown.getMessage())
                    .contains("entries." + "k".repeat(JsonDiagnostics.MAX_NAME_LENGTH) + "...[1]")
                    .doesNotContain("k".repeat(JsonDiagnostics.MAX_NAME_LENGTH + 1)));
  }

  /**
   * Bounded in content as well as length: a key carrying a newline would otherwise fold one log
   * line into two, which is a log-injection primitive rather than a formatting problem.
   */
  @Test
  void stripsControlCharactersFromADocumentSuppliedName() {
    String document = "{\"entries\":{\"total\\r\\n[ERROR] injected\":[\"nope\"]}}";

    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue(document, JsonFixtures.Bag.class))
        .satisfies(
            thrown ->
                assertThat(thrown.getMessage())
                    .contains("entries.total[ERROR] injected[0]")
                    .doesNotContain("\n", "\r"));
  }

  /** A path is bounded too: a deeply nested document cannot turn one failure into a paragraph. */
  @Test
  void stopsRenderingAPathAfterTheSegmentCap() {
    String document = "{\"child\":".repeat(15) + "{\"value\":\"nope\"}" + "}".repeat(15);

    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue(document, JsonFixtures.Node.class))
        .satisfies(
            thrown -> {
              String capped =
                  String.join(".", Collections.nCopies(JsonDiagnostics.MAX_PATH_SEGMENTS, "child"));
              assertThat(thrown.getMessage()).endsWith(": at " + capped + "...");
              assertThat(thrown.getMessage()).doesNotContain("value");
            });
  }

  /**
   * Control <strong>C-02</strong>: {@code null} is a valid JSON document and Jackson answers it
   * with a {@code null} reference. Handing that back would move the failure to the caller's next
   * dereference, somewhere else entirely.
   */
  @Test
  void refusesTheLiteralNullDocument() {
    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.readValue("null", JsonFixtures.Order.class))
        .withMessageContaining("literal null")
        .satisfies(thrown -> assertThat(thrown.getCause()).isNull());
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsNullArguments() {
    assertThatNullPointerException().isThrownBy(() -> json.writeValueAsString(null));
    assertThatNullPointerException()
        .isThrownBy(() -> json.readValue(null, JsonFixtures.Order.class));
    assertThatNullPointerException().isThrownBy(() -> json.readValue("{}", null));
    assertThatNullPointerException().isThrownBy(() -> JsonMapper.withModules(null));
    List<com.fasterxml.jackson.databind.Module> withANull = new ArrayList<>();
    withANull.add(null);
    assertThatNullPointerException().isThrownBy(() -> JsonMapper.withModules(withANull));
  }

  /**
   * The write path reports the source type; the value it could not render stays out of the text.
   */
  @Test
  void reportsWhatItCouldNotWrite() {
    assertThatExceptionOfType(JsonConversionException.class)
        .isThrownBy(() -> json.writeValueAsString(new Object()))
        .withMessageContaining("cannot write java.lang.Object as JSON");
  }

  /**
   * RFC-0003's additive-only customisation. The host's module takes effect, and the three settings
   * that make the mapper safe are still in force afterwards — additive means added to, not traded
   * against.
   */
  @Test
  void registersHostModulesWithoutGivingUpTheHardening() {
    SimpleModule skuOnly = new SimpleModule();
    skuOnly.addSerializer(JsonFixtures.Order.class, new SkuOnlySerializer());
    JsonMapper customised = JsonMapper.withModules(List.of(skuOnly));

    assertThat(customised.writeValueAsString(new JsonFixtures.Order("A-1", 2)))
        .isEqualTo("\"A-1\"");
    assertThat(customised.readValue(GADGET_SHAPED, JsonFixtures.Envelope.class).payload())
        .isInstanceOf(List.class);
    assertThat(
            customised.readValue(
                "{\"sku\":\"A-1\",\"quantity\":2,\"warehouse\":\"EU-3\"}",
                JsonFixtures.Order.class))
        .isEqualTo(new JsonFixtures.Order("A-1", 2));
  }

  /** The list is copied at construction, so a caller cannot reconfigure a mapper after the fact. */
  @Test
  void copiesTheModuleListItIsGiven() {
    List<com.fasterxml.jackson.databind.Module> modules = new ArrayList<>();
    JsonMapper customised = JsonMapper.withModules(modules);

    SimpleModule added = new SimpleModule();
    added.addSerializer(JsonFixtures.Order.class, new SkuOnlySerializer());
    modules.add(added);

    assertThat(customised.writeValueAsString(new JsonFixtures.Order("A-1", 2)))
        .isEqualTo("{\"sku\":\"A-1\",\"quantity\":2}");
  }

  /**
   * The structural half of FR-20's guarantee: there is no handle to the configured mapper, so there
   * is nothing to call {@code activateDefaultTyping} on. Asserted over the reflected surface rather
   * than trusted to review, because a getter added later would compile and pass every other test
   * here.
   */
  @Test
  void publishesNoHandleToTheConfiguredMapper() {
    assertThat(JsonMapper.class.getConstructors()).isEmpty();
    for (Method method : JsonMapper.class.getMethods()) {
      assertThat(ObjectMapper.class.isAssignableFrom(method.getReturnType()))
          .as("public method %s returns a mapper", method.getName())
          .isFalse();
      for (Class<?> parameter : method.getParameterTypes()) {
        assertThat(ObjectMapper.class.isAssignableFrom(parameter))
            .as("public method %s accepts a mapper", method.getName())
            .isFalse();
      }
    }
  }

  /** Every message a boundary handler could reach, including {@code toString()} renderings. */
  private static List<String> everyMessageIn(Throwable thrown) {
    List<String> messages = new ArrayList<>();
    for (Throwable current = thrown; current != null; current = current.getCause()) {
      messages.add(String.valueOf(current.getMessage()));
      messages.add(current.toString());
    }
    return messages;
  }

  /** Named rather than anonymous so the module test reads as one thing being registered. */
  private static final class SkuOnlySerializer extends JsonSerializer<JsonFixtures.Order> {

    @Override
    public void serialize(
        JsonFixtures.Order value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeString(value.sku());
    }
  }
}
