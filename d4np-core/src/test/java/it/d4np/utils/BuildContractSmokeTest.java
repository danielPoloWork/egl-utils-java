package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reactor's first test — ROADMAP item 1.3.
 *
 * <p>Deliberately asserts the <em>build contract</em> rather than {@code assertTrue(true)}. A smoke
 * test that only proves it can run tells you nothing the build did not already tell you; these
 * three assertions fail loudly if the toolchain is misconfigured in ways the build would otherwise
 * report as green:
 *
 * <ul>
 *   <li>if {@code maven-surefire-plugin} is not pinned to a JUnit-Platform-aware version, this
 *       class is never discovered and the build still passes — so the existence of a
 *       {@code Tests run: 3} line in the build log is itself part of the contract;
 *   <li>if {@code maven.compiler.release} stops taking effect, the bytecode check catches it before
 *       a 17-incompatible symbol reaches a published artifact (NFR-07);
 *   <li>if the module's package root ever drifts from {@code it.d4np.utils}, the first assertion
 *       fails rather than the mistake surviving to a consumer's {@code import}.
 * </ul>
 *
 * <p>Replace nothing here when real tests arrive: this class guards the build, not the library.
 */
@DisplayName("build contract")
class BuildContractSmokeTest {

  /** Class-file major version for Java 17 — the published baseline of spec §1.1 / NFR-07. */
  private static final int JAVA_17_CLASS_MAJOR = 61;

  private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

  @Test
  @DisplayName("the JUnit 5 + AssertJ stack is wired and the package root is it.d4np.utils")
  void toolchainIsWiredAndPackageRootHolds() {
    assertThat(getClass().getPackageName()).isEqualTo("it.d4np.utils");
    assertThatCode(() -> assertThat(true).isTrue()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("compiled bytecode targets the JDK 17 baseline, not the building JDK")
  void bytecodeTargetsThePublishedBaseline() throws IOException {
    String resource = getClass().getSimpleName() + ".class";
    try (InputStream in = getClass().getResourceAsStream(resource)) {
      assertThat(in).as("own class file %s must be readable from the classpath", resource).isNotNull();
      try (DataInputStream data = new DataInputStream(in)) {
        assertThat(data.readInt()).as("class-file magic").isEqualTo(CLASS_FILE_MAGIC);
        data.readUnsignedShort(); // minor version — not part of the contract
        assertThat(data.readUnsignedShort())
            .as(
                "class-file major version: %d is Java 17. A higher value means --release/--testRelease"
                    + " stopped pinning the baseline and the build is silently targeting the JDK it"
                    + " happens to run on",
                JAVA_17_CLASS_MAJOR)
            .isEqualTo(JAVA_17_CLASS_MAJOR);
      }
    }
  }

  @Test
  @DisplayName("the running JDK meets the published baseline (17 floor, 21 exercised in CI)")
  void runtimeMeetsThePublishedBaseline() {
    assertThat(Runtime.version().feature())
        .as("spec §1.1 declares a JDK 17 floor; CI additionally exercises 21")
        .isGreaterThanOrEqualTo(17);
  }
}
