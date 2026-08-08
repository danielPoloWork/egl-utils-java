package it.d4np.utils.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-21 (RFC-0003): the contract of {@link ObjectMapperExtensions}, {@link PartialUpdate} and
 * {@link JsonTypeToken}.
 *
 * <p><strong>The two claims that could go quietly wrong are each paired with a companion.</strong>
 * "The type token is what makes a generic target work" is not a property of the token existing; it
 * is a property of what the untyped call produces instead, so the token test sits beside one that
 * shows a raw {@code List.class} handing back {@code LinkedHashMap}s. "No message carries the
 * payload" is likewise asserted against raw Jackson leaking the same value on the same input —
 * without that, the assertion would keep passing over a conversion that had stopped failing.
 */
@DisplayName("ObjectMapperExtensions")
class ObjectMapperExtensionsTest {

  /** The value that must reach no message this library produces (control C-01). */
  private static final String SECRET = "hunter2";

  private final JsonMapper json = JsonMapper.create();

  @Nested
  @DisplayName("convert")
  class Convert {

    @Test
    void convertsBetweenTwoShapes() {
      JsonFixtures.Order order = new JsonFixtures.Order("A-1", 2);

      JsonFixtures.Sku view = ObjectMapperExtensions.convert(json, order, JsonFixtures.Sku.class);

      assertThat(view).isEqualTo(new JsonFixtures.Sku("A-1"));
    }

    /** Deep, not shallow: the nested {@code Order}s become nested {@code Sku}s too. */
    @Test
    void convertsPastTheTopLevel() {
      JsonFixtures.Basket basket =
          new JsonFixtures.Basket(
              "B-9", List.of(new JsonFixtures.Order("A-1", 2), new JsonFixtures.Order("A-2", 1)));

      JsonFixtures.BasketView view =
          ObjectMapperExtensions.convert(json, basket, JsonFixtures.BasketView.class);

      assertThat(view)
          .isEqualTo(
              new JsonFixtures.BasketView(
                  "B-9", List.of(new JsonFixtures.Sku("A-1"), new JsonFixtures.Sku("A-2"))));
    }

    /**
     * Every setting FR-20 configured is in force on this path too — it is the same mapper, and a
     * conversion that rendered dates differently from a write would be two wire formats again
     * (ADR-0025).
     */
    @Test
    void inheritsTheHardenedConfiguration() {
      JsonFixtures.Timed timed =
          new JsonFixtures.Timed(Instant.parse("2026-08-07T10:15:30Z"), LocalDate.of(2026, 8, 7));

      JsonFixtures.TimedView view =
          ObjectMapperExtensions.convert(json, timed, JsonFixtures.TimedView.class);

      assertThat(view).isEqualTo(new JsonFixtures.TimedView("2026-08-07T10:15:30Z", "2026-08-07"));
    }

    /** FR-21's generic target: {@code List<Sku>.class} cannot be written, so a token stands in. */
    @Test
    void convertsIntoAGenericTarget() {
      List<JsonFixtures.Order> orders =
          List.of(new JsonFixtures.Order("A-1", 2), new JsonFixtures.Order("A-2", 1));

      List<JsonFixtures.Sku> views =
          ObjectMapperExtensions.convert(
              json, orders, new JsonTypeToken<List<JsonFixtures.Sku>>() {});

      assertThat(views)
          .containsExactly(new JsonFixtures.Sku("A-1"), new JsonFixtures.Sku("A-2"))
          .allSatisfy(view -> assertThat(view).isInstanceOf(JsonFixtures.Sku.class));
    }

    /**
     * The companion that keeps the test above honest. Without the token the element type is gone,
     * and the call succeeds — handing back a {@code List} of {@code LinkedHashMap}s that fails at
     * the caller's first {@code Sku} method. This is what the token buys, stated as a measurement
     * rather than as a rationale.
     */
    @Test
    void theRawTargetLosesTheElementTypeAndSaysNothing() {
      List<JsonFixtures.Order> orders = List.of(new JsonFixtures.Order("A-1", 2));

      List<?> erased = ObjectMapperExtensions.convert(json, orders, List.class);

      assertThat(erased).first().isInstanceOf(Map.class).isNotInstanceOf(JsonFixtures.Sku.class);
    }

    /**
     * Control <strong>C-01</strong> on the conversion path. The source carries a password and the
     * target cannot hold it, which is exactly when Jackson wants to quote the value it rejected.
     */
    @Test
    void noConversionMessageCarriesTheValue() {
      JsonFixtures.Credentials credentials = new JsonFixtures.Credentials("ada", SECRET);

      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.convert(
                      json, credentials, JsonFixtures.MistypedCredentials.class))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage())
                    .doesNotContain(SECRET)
                    .contains("cannot convert")
                    .contains("JsonFixtures$Credentials")
                    .contains("JsonFixtures$MistypedCredentials")
                    .contains("at password");
                assertThat(thrown.toString()).doesNotContain(SECRET);
              });
    }

    /**
     * The companion, and the reason ADR-0026 exists. {@code ObjectMapper.convertValue} does not
     * raise the <em>checked</em> {@code JsonProcessingException} RFC-0003's wrapping rule was
     * written against — it raises an unchecked {@code IllegalArgumentException} carrying Jackson's
     * own message, which quotes the value. Nothing would have forced that to be caught.
     */
    @Test
    void theSameConversionLeaksThroughRawJackson() {
      ObjectMapper raw = new ObjectMapper();
      JsonFixtures.Credentials credentials = new JsonFixtures.Credentials("ada", SECRET);

      assertThatThrownBy(
              () -> raw.convertValue(credentials, JsonFixtures.MistypedCredentials.class))
          .isInstanceOf(IllegalArgumentException.class)
          .isNotInstanceOf(JsonConversionException.class)
          .hasMessageContaining(SECRET);
    }

    /**
     * The generic overload reports the token's rendering, which is a type name this library built.
     */
    @Test
    void namesTheGenericTargetItCouldNotReach() {
      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.convert(
                      json,
                      List.of(new JsonFixtures.Credentials("ada", SECRET)),
                      new JsonTypeToken<List<JsonFixtures.MistypedCredentials>>() {}))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage())
                    .contains("java.util.List<it.d4np.utils.json.JsonFixtures$MistypedCredentials>")
                    .doesNotContain(SECRET);
                assertThat(thrown.getCause()).isInstanceOf(IllegalArgumentException.class);
              });
    }

    /**
     * Control <strong>C-02</strong> on the conversion path: a host module is entitled to register a
     * serializer that writes {@code null}, and a conversion through it produces nothing at all.
     * Handing that back would move the failure to the caller's next dereference.
     */
    @Test
    void refusesAConversionThatProducesNull() {
      SimpleModule writesNull = new SimpleModule();
      writesNull.addSerializer(JsonFixtures.Order.class, new NullSerializer());
      JsonMapper customised = JsonMapper.withModules(List.of(writesNull));

      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.convert(
                      customised, new JsonFixtures.Order("A-1", 2), JsonFixtures.Sku.class))
          .withMessageContaining("produced null")
          .satisfies(thrown -> assertThat(thrown.getCause()).isNull());
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNullArguments() {
      JsonFixtures.Order order = new JsonFixtures.Order("A-1", 2);
      JsonTypeToken<List<JsonFixtures.Sku>> token = new JsonTypeToken<List<JsonFixtures.Sku>>() {};

      assertThatNullPointerException()
          .isThrownBy(() -> ObjectMapperExtensions.convert(null, order, JsonFixtures.Sku.class));
      assertThatNullPointerException()
          .isThrownBy(() -> ObjectMapperExtensions.convert(json, null, JsonFixtures.Sku.class));
      assertThatNullPointerException()
          .isThrownBy(() -> ObjectMapperExtensions.convert(json, order, (Class<Object>) null));
      assertThatNullPointerException()
          .isThrownBy(() -> ObjectMapperExtensions.convert(null, order, token));
      assertThatNullPointerException()
          .isThrownBy(() -> ObjectMapperExtensions.convert(json, null, token));
      assertThatNullPointerException()
          .isThrownBy(
              () -> ObjectMapperExtensions.convert(json, order, (JsonTypeToken<Object>) null));
    }
  }

  @Nested
  @DisplayName("readPartial")
  class ReadPartial {

    /**
     * FR-21's headline. The two documents produce an equal instance and a different update, which
     * is the entire reason this operation returns more than the instance.
     */
    @Test
    void tellsAnExplicitNullFromAnAbsence() {
      PartialUpdate<JsonFixtures.Order> cleared =
          ObjectMapperExtensions.readPartial(json, "{\"sku\":null}", JsonFixtures.Order.class);
      PartialUpdate<JsonFixtures.Order> untouched =
          ObjectMapperExtensions.readPartial(json, "{}", JsonFixtures.Order.class);

      assertThat(cleared.value()).isEqualTo(untouched.value());
      assertThat(cleared.isPresent("sku")).isTrue();
      assertThat(untouched.isPresent("sku")).isFalse();
      assertThat(cleared.presentProperties()).containsExactly("sku");
      assertThat(untouched.presentProperties()).isEmpty();
    }

    /** Sorted, so a message assertion is not flaky and a log line diffs (item 3.1's reasoning). */
    @Test
    void reportsPresentPropertiesSortedAndUnmodifiable() {
      PartialUpdate<JsonFixtures.Order> update =
          ObjectMapperExtensions.readPartial(
              json, "{\"quantity\":2,\"sku\":\"A-1\"}", JsonFixtures.Order.class);

      assertThat(update.presentProperties()).containsExactly("quantity", "sku");
      assertThatThrownBy(() -> update.presentProperties().add("warehouse"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The FR-20/FR-21 collision, resolved per operation. A client that sent {@code emailAddres}
     * believes it changed something, so the update is refused and the offender is named.
     */
    @Test
    void refusesAnUnknownProperty() {
      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(
                      json, "{\"sku\":\"A-1\",\"emailAddres\":1}", JsonFixtures.Order.class))
          .withMessageContaining("cannot apply a partial update to")
          .withMessageContaining("unknown property ['emailAddres']");
    }

    /**
     * <strong>The refusal is per operation, not per mapper</strong>, and this is the assertion that
     * says so: an ordinary read of a document with the same unknown property still succeeds
     * afterwards. Flipping {@code FAIL_ON_UNKNOWN_PROPERTIES} on the mapper would have broken every
     * consumer reading a document it does not own.
     */
    @Test
    void leavesEveryOtherReadLenient() {
      String fromANewerProducer = "{\"sku\":\"A-1\",\"quantity\":2,\"warehouse\":\"EU-3\"}";

      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(
                      json, fromANewerProducer, JsonFixtures.Order.class));

      assertThat(json.readValue(fromANewerProducer, JsonFixtures.Order.class))
          .isEqualTo(new JsonFixtures.Order("A-1", 2));
    }

    /**
     * Jackson stops at the first unknown property; a client fixing its payload one round trip at a
     * time is a worse endpoint than one that hears the whole answer.
     */
    @Test
    void reportsEveryUnknownPropertyNotJustTheFirst() {
      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(
                      json, "{\"zebra\":1,\"sku\":\"A-1\",\"alpha\":2}", JsonFixtures.Order.class))
          .withMessageContaining("2 unknown properties ['alpha', 'zebra']");
    }

    /** One wide document cannot turn a refusal into a paragraph, as one deep one cannot. */
    @Test
    void boundsHowManyNamesItReports() {
      StringBuilder document = new StringBuilder("{\"sku\":\"A-1\"");
      for (int i = 0; i < 20; i++) {
        document.append(",\"p").append(i).append("\":1");
      }
      document.append('}');

      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(
                      json, document.toString(), JsonFixtures.Order.class))
          .satisfies(
              thrown -> {
                String message = String.valueOf(thrown.getMessage());
                assertThat(message).contains("20 unknown properties").endsWith("...]");
                assertThat(message.split("'p", -1)).hasSize(JsonDiagnostics.MAX_NAMES + 1);
              });
    }

    /**
     * The bound on the reported <em>set</em> is not a bound on the <em>check</em>: strictness has
     * no shallow end. The offender is nested, so the top-level names are all known and the name
     * Jackson reported is the one that travels.
     */
    @Test
    void refusesAnUnknownPropertyAtEveryDepth() {
      String document = "{\"reference\":\"B-9\",\"lines\":[{\"sku\":\"A-1\",\"nope\":1}]}";

      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () -> ObjectMapperExtensions.readPartial(json, document, JsonFixtures.Basket.class))
          .withMessageContaining("unknown property ['nope']");
    }

    /** A partial update is an object; every other JSON shape is a different request. */
    @Test
    void refusesADocumentThatIsNotAnObject() {
      for (String notAnObject : List.of("[{\"sku\":\"A-1\"}]", "\"A-1\"", "7", "true")) {
        assertThatExceptionOfType(JsonConversionException.class)
            .isThrownBy(
                () ->
                    ObjectMapperExtensions.readPartial(json, notAnObject, JsonFixtures.Order.class))
            .withMessageContaining("the document is not a JSON object");
      }
    }

    /** Control <strong>C-02</strong>, kept identical to {@code JsonMapper.readValue}'s. */
    @Test
    void refusesTheLiteralNullDocument() {
      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () -> ObjectMapperExtensions.readPartial(json, "null", JsonFixtures.Order.class))
          .withMessageContaining("literal null")
          .satisfies(thrown -> assertThat(thrown.getCause()).isNull());
    }

    /** A malformed body reports the target type and Jackson's path, never the document. */
    @Test
    void noMessageCarriesTheDocument() {
      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(
                      json,
                      "{\"user\":\"ada\",\"password\":\"" + SECRET,
                      JsonFixtures.Credentials.class))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage()).doesNotContain(SECRET).contains("cannot read JSON");
                assertThat(thrown.toString()).doesNotContain(SECRET);
              });
    }

    /**
     * An unknown property name is client input, so the same bound a map key gets in a path applies
     * here — a name carrying a newline would fold one log line into two.
     */
    @Test
    void boundsAnUnknownNameInLengthAndContent() {
      String longName = "k".repeat(200);
      String injected = "total\\r\\n[ERROR] injected";

      assertThatExceptionOfType(JsonConversionException.class)
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(
                      json,
                      "{\"" + longName + "\":1,\"" + injected + "\":2}",
                      JsonFixtures.Order.class))
          .satisfies(
              thrown -> {
                assertThat(thrown.getMessage())
                    .contains("'" + "k".repeat(JsonDiagnostics.MAX_NAME_LENGTH) + "...'")
                    .doesNotContain("k".repeat(JsonDiagnostics.MAX_NAME_LENGTH + 1))
                    .contains("'total[ERROR] injected'")
                    .doesNotContain("\n", "\r");
              });
    }

    /**
     * A {@code Map} target is the case where the present-name set is client input rather than the
     * target type's vocabulary — every key is a known property, so nothing is refused.
     */
    @Test
    void treatsEveryKeyAsKnownForAMapTarget() {
      PartialUpdate<Map> update =
          ObjectMapperExtensions.readPartial(json, "{\"anything\":1}", Map.class);

      assertThat(update.isPresent("anything")).isTrue();
      assertThat(update.value()).isEqualTo(Map.of("anything", 1));
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNullArguments() {
      assertThatNullPointerException()
          .isThrownBy(
              () -> ObjectMapperExtensions.readPartial(null, "{}", JsonFixtures.Order.class));
      assertThatNullPointerException()
          .isThrownBy(
              () -> ObjectMapperExtensions.readPartial(json, null, JsonFixtures.Order.class));
      assertThatNullPointerException()
          .isThrownBy(() -> ObjectMapperExtensions.readPartial(json, "{}", null));
      assertThatNullPointerException()
          .isThrownBy(
              () ->
                  ObjectMapperExtensions.readPartial(json, "{}", JsonFixtures.Order.class)
                      .isPresent(null));
    }
  }

  @Nested
  @DisplayName("PartialUpdate")
  class PartialUpdateContract {

    @Test
    void isEqualWhenBothTheValueAndThePresentNamesAre() {
      PartialUpdate<JsonFixtures.Order> one =
          ObjectMapperExtensions.readPartial(json, "{\"sku\":\"A-1\"}", JsonFixtures.Order.class);
      PartialUpdate<JsonFixtures.Order> same =
          ObjectMapperExtensions.readPartial(json, "{\"sku\":\"A-1\"}", JsonFixtures.Order.class);
      PartialUpdate<JsonFixtures.Order> equalValueDifferentDocument =
          ObjectMapperExtensions.readPartial(
              json, "{\"sku\":\"A-1\",\"quantity\":0}", JsonFixtures.Order.class);

      assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
      assertThat(one.value()).isEqualTo(equalValueDifferentDocument.value());
      assertThat(one).isNotEqualTo(equalValueDifferentDocument);
    }

    /**
     * A {@code toString()} reaches a log far more casually than an exception does, and the value
     * was built from an untrusted document — so it names the type and lists the names instead.
     */
    @Test
    void rendersTheTypeAndTheNamesAndNeverTheValue() {
      PartialUpdate<JsonFixtures.Credentials> update =
          ObjectMapperExtensions.readPartial(
              json,
              "{\"user\":\"ada\",\"password\":\"" + SECRET + "\"}",
              JsonFixtures.Credentials.class);

      assertThat(update.toString())
          .contains("JsonFixtures$Credentials")
          .contains("['password', 'user']")
          .doesNotContain(SECRET);
    }

    /** The names it renders are bounded too, for the {@code Map} target that makes them input. */
    @Test
    void boundsTheNamesItRenders() {
      PartialUpdate<Map> update =
          ObjectMapperExtensions.readPartial(
              json, "{\"total\\r\\n[ERROR] injected\":1}", Map.class);

      assertThat(update.toString()).contains("'total[ERROR] injected'").doesNotContain("\n", "\r");
    }
  }

  @Nested
  @DisplayName("JsonTypeToken")
  class TypeToken {

    /** Two tokens over the same type are the same token, whatever anonymous class holds them. */
    @Test
    void isEqualToAnotherTokenOverTheSameType() {
      JsonTypeToken<List<JsonFixtures.Sku>> one = new JsonTypeToken<List<JsonFixtures.Sku>>() {};
      JsonTypeToken<List<JsonFixtures.Sku>> other = new JsonTypeToken<List<JsonFixtures.Sku>>() {};
      JsonTypeToken<List<JsonFixtures.Order>> different =
          new JsonTypeToken<List<JsonFixtures.Order>>() {};

      assertThat(one).isEqualTo(other).hasSameHashCodeAs(other).isNotEqualTo(different);
      assertThat(one).hasToString("java.util.List<it.d4np.utils.json.JsonFixtures$Sku>");
    }

    /**
     * A raw token has nothing to capture, and the failure is a programming error rather than a bad
     * document — so it is {@code IllegalArgumentException} and not {@code JsonConversionException},
     * which FR-19 maps to 400.
     */
    @Test
    void refusesARawToken() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new RawToken())
          .withMessageContaining("must be created with a type argument");
    }

    /**
     * The mistake worth refusing rather than tolerating: erasure discarded {@code T} before the
     * constructor ran, so Jackson would resolve it to the variable's bound and hand back a {@code
     * LinkedHashMap} that fails at a cast somewhere else entirely.
     */
    @Test
    void refusesATokenOverATypeVariable() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> tokenFor())
          .withMessageContaining("cannot capture the type variable");
    }

    /**
     * A named subclass that fills the argument in is legitimate, and is what the raw case lacks.
     */
    @Test
    void acceptsANamedSubclassThatFillsTheArgumentIn() {
      assertThat(new SkuListToken()).isEqualTo(new JsonTypeToken<List<JsonFixtures.Sku>>() {});
    }

    private <T> JsonTypeToken<T> tokenFor() {
      return new JsonTypeToken<T>() {};
    }
  }

  /**
   * FR-20's structural guarantee, extended to every type FR-21 published: none of them returns,
   * accepts or exposes an {@code ObjectMapper}. Asserted over the reflected surface rather than
   * trusted to review, because a helper added later would compile and pass every other test here.
   */
  @Test
  void publishesNoHandleToTheConfiguredMapper() {
    for (Class<?> published :
        List.of(ObjectMapperExtensions.class, PartialUpdate.class, JsonTypeToken.class)) {
      for (Method method : published.getMethods()) {
        assertThat(ObjectMapper.class.isAssignableFrom(method.getReturnType()))
            .as("public method %s.%s returns a mapper", published.getSimpleName(), method.getName())
            .isFalse();
        for (Class<?> parameter : method.getParameterTypes()) {
          assertThat(ObjectMapper.class.isAssignableFrom(parameter))
              .as(
                  "public method %s.%s accepts a mapper",
                  published.getSimpleName(), method.getName())
              .isFalse();
        }
      }
    }
    assertThat(ObjectMapperExtensions.class.getConstructors()).isEmpty();
  }

  /** A raw subclass, named rather than anonymous so the refusal is about the missing argument. */
  @SuppressWarnings("rawtypes")
  private static final class RawToken extends JsonTypeToken {}

  /** A named subclass that does fill the argument in — the legitimate half of the same shape. */
  private static final class SkuListToken extends JsonTypeToken<List<JsonFixtures.Sku>> {}

  /** Writes {@code null} for whatever it is given, so a conversion can produce nothing at all. */
  private static final class NullSerializer extends JsonSerializer<JsonFixtures.Order> {

    @Override
    public void serialize(
        JsonFixtures.Order value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeNull();
    }
  }
}
