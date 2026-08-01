package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code StringCaseConverter} against RFC-0001's FR-22 table — ROADMAP item 2.5.
 *
 * <p>The RFC states FR-22 <em>as a table</em>, so the table is transcribed here row for row rather
 * than paraphrased: it is the contract, and a test that restated it in the author's own words would
 * be testing the paraphrase. The tokenizer is asserted separately from the renderings, because a
 * tokenizer bug and a renderer bug can cancel out and leave every end-to-end conversion green.
 */
@DisplayName("StringCaseConverter")
class StringCaseConverterTest {

  private final Locale defaultLocale = Locale.getDefault();

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  // --- RFC-0001 FR-22, transcribed ---

  @ParameterizedTest(name = "[{index}] {0} -> {1} / {2} / {3}")
  @CsvSource({
    "HTTPServer,       httpServer,       http_server,        http-server",
    "parseHTTPRequest, parseHttpRequest, parse_http_request, parse-http-request",
    "URLs,             urls,             urls,               urls",
    "s3Client,         s3Client,         s3_client,          s3-client",
    "user2Name,        user2Name,        user2_name,         user2-name",
    "already_snake,    alreadySnake,     already_snake,      already-snake",
    "__leading__,      leading,          leading,            leading",
  })
  @DisplayName("renders every row of the pinned table")
  void rendersThePinnedTable(String input, String camel, String snake, String kebab) {
    assertThat(StringCaseConverter.toCamel(input)).isEqualTo(camel);
    assertThat(StringCaseConverter.toSnake(input)).isEqualTo(snake);
    assertThat(StringCaseConverter.toKebab(input)).isEqualTo(kebab);
  }

  @Test
  @DisplayName("the empty string is the table's last row, and every rendering is empty")
  void rendersTheEmptyString() {
    assertThat(StringCaseConverter.toCamel("")).isEmpty();
    assertThat(StringCaseConverter.toSnake("")).isEmpty();
    assertThat(StringCaseConverter.toKebab("")).isEmpty();
  }

  // --- the tokenizer, asserted directly ---

  @Test
  @DisplayName("tokenizes the table's inputs into the tokens it pins")
  void tokenizesThePinnedTable() {
    assertThat(StringCaseConverter.tokenize("HTTPServer")).containsExactly("HTTP", "Server");
    assertThat(StringCaseConverter.tokenize("parseHTTPRequest"))
        .containsExactly("parse", "HTTP", "Request");
    assertThat(StringCaseConverter.tokenize("URLs")).containsExactly("URLs");
    assertThat(StringCaseConverter.tokenize("s3Client")).containsExactly("s3", "Client");
    assertThat(StringCaseConverter.tokenize("user2Name")).containsExactly("user2", "Name");
    assertThat(StringCaseConverter.tokenize("already_snake")).containsExactly("already", "snake");
    assertThat(StringCaseConverter.tokenize("__leading__")).containsExactly("leading");
    assertThat(StringCaseConverter.tokenize("")).isEmpty();
  }

  @Test
  @DisplayName("URLs stays whole because only one lowercase follows — the ADR-0018 threshold")
  void keepsATrailingPluralWithItsAcronym() {
    // RFC-0001's prose rule ("followed by a lowercase letter") would split UR|Ls and render urLs,
    // contradicting its own table. Two lowercase characters are required to start a word.
    assertThat(StringCaseConverter.tokenize("URLs")).containsExactly("URLs");
    assertThat(StringCaseConverter.tokenize("ABs")).containsExactly("ABs");
    assertThat(StringCaseConverter.tokenize("IOStream")).containsExactly("IO", "Stream");
    assertThat(StringCaseConverter.tokenize("XMLHttpRequest"))
        .containsExactly("XML", "Http", "Request");
  }

  @Test
  @DisplayName("separator runs collapse, whatever the separator")
  void collapsesSeparatorRuns() {
    assertThat(StringCaseConverter.tokenize("a__b--c  d")).containsExactly("a", "b", "c", "d");
    assertThat(StringCaseConverter.toSnake("  spaced   words  ")).isEqualTo("spaced_words");
  }

  @Test
  @DisplayName("a digit joins the token before it and never starts one")
  void digitsJoinThePrecedingToken() {
    assertThat(StringCaseConverter.tokenize("s3")).containsExactly("s3");
    assertThat(StringCaseConverter.tokenize("v2Api")).containsExactly("v2", "Api");
    assertThat(StringCaseConverter.tokenize("HTTP2Server")).containsExactly("HTTP2", "Server");
  }

  // --- the two guaranteed properties ---

  @ParameterizedTest
  @ValueSource(
      strings = {
        "HTTPServer",
        "parseHTTPRequest",
        "URLs",
        "s3Client",
        "already_snake",
        "__leading__",
        "",
        "a",
        "ALLCAPS",
        "mixed_Case-input 42"
      })
  @DisplayName("every conversion is idempotent")
  void conversionsAreIdempotent(String input) {
    String camel = StringCaseConverter.toCamel(input);
    String snake = StringCaseConverter.toSnake(input);
    String kebab = StringCaseConverter.toKebab(input);

    assertThat(StringCaseConverter.toCamel(camel)).isEqualTo(camel);
    assertThat(StringCaseConverter.toSnake(snake)).isEqualTo(snake);
    assertThat(StringCaseConverter.toKebab(kebab)).isEqualTo(kebab);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "_", "---", "42", "...", "é", "😀", "a\tb", "ÅÄÖ", "İ"})
  @DisplayName("every conversion is total — no input throws")
  void conversionsAreTotal(String input) {
    // "Total" is a stated guarantee, so it is asserted over inputs a tokenizer could plausibly trip
    // on: punctuation, a surrogate pair, and Turkish dotted capital I.
    assertThat(StringCaseConverter.toCamel(input)).isNotNull();
    assertThat(StringCaseConverter.toSnake(input)).isNotNull();
    assertThat(StringCaseConverter.toKebab(input)).isNotNull();
  }

  // --- the security-relevant rule ---

  @Test
  @DisplayName("case mapping uses Locale.ROOT, so a Turkish-locale JVM cannot corrupt identifiers")
  void mapsCaseWithLocaleRoot() {
    // The failure this prevents: under Locale("tr"), "I".toLowerCase() is dotless 'ı', so a
    // converted identifier stops matching the key it was derived from -- silently, and only on
    // hosts with that default locale. RFC-0001 Cross-cutting calls this security-load-bearing.
    Locale.setDefault(new Locale("tr", "TR"));

    assertThat(StringCaseConverter.toSnake("IDToken")).isEqualTo("id_token");
    assertThat(StringCaseConverter.toCamel("ID_TOKEN")).isEqualTo("idToken");
    assertThat(StringCaseConverter.toKebab("HTTPIdentity")).isEqualTo("http-identity");
  }

  @Test
  @DisplayName("a two-letter word after an acronym stays attached — the cost of the URLs threshold")
  void aSingleLowercaseAfterAnAcronymDoesNotSplit() {
    // The honest consequence of ADR-0018: the threshold that keeps URLs whole also keeps HTTPId
    // whole, because "d" is one lowercase character just as "s" is. Pinned as a test so the
    // trade-off is visible rather than discovered by someone debugging a converted identifier.
    assertThat(StringCaseConverter.toSnake("HTTPId")).isEqualTo("httpid");
    assertThat(StringCaseConverter.toSnake("HTTPIdentity")).isEqualTo("http_identity");
  }

  @Test
  @DisplayName("the round trip across an acronym is explicitly NOT guaranteed")
  void doesNotPromiseAnAcronymRoundTrip() {
    // Asserted as the documented non-guarantee, so nobody "fixes" it into a promise by accident.
    String snake = StringCaseConverter.toSnake("HTTPServer");
    assertThat(snake).isEqualTo("http_server");
    assertThat(StringCaseConverter.toCamel(snake))
        .isEqualTo("httpServer")
        .isNotEqualTo("HTTPServer");
  }

  // --- the null boundary ---

  @Test
  @DisplayName("null throws rather than mapping to null")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsNull() {
    assertThatNullPointerException().isThrownBy(() -> StringCaseConverter.toCamel(null));
    assertThatNullPointerException().isThrownBy(() -> StringCaseConverter.toSnake(null));
    assertThatNullPointerException().isThrownBy(() -> StringCaseConverter.toKebab(null));
  }

  @Test
  @DisplayName("tokens never include an empty string")
  void neverProducesEmptyTokens() {
    List<String> tokens = StringCaseConverter.tokenize("__a__b__");

    assertThat(tokens).containsExactly("a", "b").allSatisfy(t -> assertThat(t).isNotEmpty());
  }
}
