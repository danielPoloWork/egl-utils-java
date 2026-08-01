package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code ResourceLoaderUtils} against RFC-0001's FR-24 table — ROADMAP item 2.5.
 *
 * <p>Fixtures live at {@code src/test/resources/it/d4np/utils/}, beside this class on purpose: the
 * contract is that resolution is anchored on a class in the <em>owning</em> module, and a fixture
 * elsewhere would test a different arrangement than the one the Javadoc tells callers to use.
 */
@DisplayName("ResourceLoaderUtils")
class ResourceLoaderUtilsTest {

  private static final String SAMPLE = "/it/d4np/utils/sample.txt";

  // --- find: absence is an ordinary answer ---

  @Test
  @DisplayName("find locates a resource that exists")
  void findLocatesAResource() {
    assertThat(ResourceLoaderUtils.find(ResourceLoaderUtilsTest.class, SAMPLE)).isPresent();
  }

  @Test
  @DisplayName("find is empty for a resource that does not exist")
  void findIsEmptyWhenAbsent() {
    assertThat(ResourceLoaderUtils.find(ResourceLoaderUtilsTest.class, "/nope/missing.txt"))
        .isEmpty();
  }

  // --- open: absence is a defect ---

  @Test
  @DisplayName("open returns a readable stream the caller closes")
  void openReturnsAStream() throws Exception {
    try (InputStream stream = ResourceLoaderUtils.open(ResourceLoaderUtilsTest.class, SAMPLE)) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .startsWith("hello, resource");
    }
  }

  @Test
  @DisplayName("open names the resource, the anchor and its module — the JPMS diagnosis")
  void openNamesTheAnchorAndModule() {
    ResourceNotFoundException thrown =
        catchThrowableOfType(
            ResourceNotFoundException.class,
            () -> ResourceLoaderUtils.open(ResourceLoaderUtilsTest.class, "/nope/missing.txt"));

    assertThat(thrown.resource()).isEqualTo("/nope/missing.txt");
    assertThat(thrown.anchor()).isEqualTo(ResourceLoaderUtilsTest.class.getName());
    assertThat(thrown.module()).isNotBlank();
    // "Not found" has two causes under JPMS -- genuinely missing, or encapsulated -- so the message
    // has to point at the second one or every such failure becomes a bisect.
    assertThat(thrown).hasMessageContaining("opened by the module descriptor");
  }

  @Test
  @DisplayName("is NOT a BusinessException — a missing packaged file is a wiring defect")
  void isNotABusinessException() {
    ResourceNotFoundException thrown =
        catchThrowableOfType(
            ResourceNotFoundException.class,
            () -> ResourceLoaderUtils.open(ResourceLoaderUtilsTest.class, "/nope/missing.txt"));

    assertThat(thrown)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(BusinessException.class);
  }

  // --- readString and its charset ---

  @Test
  @DisplayName("readString decodes as UTF-8 by default")
  void readStringDefaultsToUtf8() {
    assertThat(ResourceLoaderUtils.readString(ResourceLoaderUtilsTest.class, SAMPLE))
        .isEqualTo("hello, resource\n");
  }

  @Test
  @DisplayName("readString honours an explicit charset")
  void readStringHonoursAnExplicitCharset() {
    String latin1 =
        ResourceLoaderUtils.readString(
            ResourceLoaderUtilsTest.class,
            "/it/d4np/utils/latin1.txt",
            StandardCharsets.ISO_8859_1);

    assertThat(latin1).isEqualTo("café latin1\n");
  }

  @Test
  @DisplayName("the UTF-8 default is a real decision, not the platform default")
  void theDefaultIsUtf8NotThePlatformCharset() {
    // The fixture holds byte 0xE9 -- valid ISO-8859-1, invalid UTF-8 -- so the two decodings must
    // differ. If readString ever fell back to Charset.defaultCharset(), this file would decode
    // differently on a developer machine and in a container, which is what JEP 400 fixed only in 18
    // (this project's baseline is 17).
    String asUtf8 =
        ResourceLoaderUtils.readString(ResourceLoaderUtilsTest.class, "/it/d4np/utils/latin1.txt");
    String asLatin1 =
        ResourceLoaderUtils.readString(
            ResourceLoaderUtilsTest.class,
            "/it/d4np/utils/latin1.txt",
            StandardCharsets.ISO_8859_1);

    assertThat(asUtf8).isNotEqualTo(asLatin1).contains("�");
  }

  // --- name normalization ---

  @Test
  @DisplayName("a leading slash is optional and means the same resource")
  void theLeadingSlashIsOptional() {
    // Without normalization these two would be different files: Class.getResourceAsStream treats a
    // bare name as PACKAGE-RELATIVE, which is the most common surprise in this API.
    String withSlash = ResourceLoaderUtils.readString(ResourceLoaderUtilsTest.class, SAMPLE);
    String withoutSlash =
        ResourceLoaderUtils.readString(ResourceLoaderUtilsTest.class, "it/d4np/utils/sample.txt");

    assertThat(withoutSlash).isEqualTo(withSlash);
    assertThat(ResourceLoaderUtils.find(ResourceLoaderUtilsTest.class, "it/d4np/utils/sample.txt"))
        .isPresent();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"../secret.txt", "/it/../../etc/passwd", "it/d4np/../d4np/utils/sample.txt", ".."})
  @DisplayName("a name containing .. is rejected outright, on every entry point")
  void rejectsTraversal(String name) {
    // Rejected rather than normalized away: where a name is built from caller-supplied input,
    // refusing the segment is cheaper to reason about than proving a normalizer is airtight.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ResourceLoaderUtils.find(ResourceLoaderUtilsTest.class, name))
        .withMessageContaining("..");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ResourceLoaderUtils.open(ResourceLoaderUtilsTest.class, name));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ResourceLoaderUtils.readString(ResourceLoaderUtilsTest.class, name));
  }

  // --- the null boundary ---

  @Test
  @DisplayName("null anchor, name or charset throws")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsNulls() {
    assertThatNullPointerException()
        .isThrownBy(() -> ResourceLoaderUtils.find(null, SAMPLE))
        .withMessageContaining("anchor");
    assertThatNullPointerException()
        .isThrownBy(() -> ResourceLoaderUtils.find(ResourceLoaderUtilsTest.class, null))
        .withMessageContaining("name");
    assertThatNullPointerException()
        .isThrownBy(
            () -> ResourceLoaderUtils.readString(ResourceLoaderUtilsTest.class, SAMPLE, null))
        .withMessageContaining("charset");
  }
}
