package it.d4np.utils.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-10's failure type — what it is allowed to say, and where the key is allowed to appear. */
class DistributedLockExceptionTest {

  /** The shape every distributed-lock tutorial uses, and therefore the one C-01 has to survive. */
  private static final String IDENTIFYING_KEY = "order:tenant-42:user-7";

  @Test
  @DisplayName("the message names the operation and the failure's type, and never the key")
  void theMessageNeverCarriesTheKey() {
    // C-01: a lock key is client-derived far more often than not, and getMessage() is what reaches
    // a log and -- if anyone renders a cause chain -- an FR-19 body.
    DistributedLockException thrown =
        DistributedLockException.acquireFailed(
            IDENTIFYING_KEY, new IOException("connection reset"));

    assertThat(thrown.getMessage())
        .contains("acquire")
        .contains("java.io.IOException")
        .doesNotContain(IDENTIFYING_KEY)
        .doesNotContain("tenant-42")
        .doesNotContain("user-7");
  }

  @Test
  @DisplayName("and never the backend's own message, which is where a payload would live")
  void theMessageNeverCarriesTheBackendsText() {
    // Item 4.3 measured H2 putting an entire statement plus a bound parameter in its message. A
    // lock
    // client is no more careful, so only the cause's TYPE is read.
    DistributedLockException thrown =
        DistributedLockException.acquireFailed(
            IDENTIFYING_KEY, new IllegalStateException("AUTH failed for user hunter2"));

    assertThat(thrown.getMessage()).doesNotContain("hunter2").doesNotContain("AUTH failed");
  }

  @Test
  @DisplayName("the cause is attached, so the detail survives for whoever is allowed to see it")
  void theCauseIsAttached() {
    IOException backend = new IOException("connection reset");

    assertThat(DistributedLockException.acquireFailed(IDENTIFYING_KEY, backend)).hasCause(backend);
  }

  @Test
  @DisplayName("key() carries it instead, so reading it is a decision rather than an accident")
  void keyCarriesItInstead() {
    // RFC-0001's ErrorDetail split, applied here: caller-facing text in the message, in-process
    // diagnostics beside it. A handler that wants to correlate log lines can ask.
    assertThat(DistributedLockException.acquireFailed(IDENTIFYING_KEY, new IOException("x")).key())
        .isEqualTo(IDENTIFYING_KEY);
  }

  @Test
  @DisplayName("a key holding control characters cannot fold one log line into two")
  void stripsControlCharactersFromTheKey() {
    String injected = "orders\r\nWARNING: fabricated log line";

    String bounded = DistributedLockException.acquireFailed(injected, new IOException("x")).key();

    assertThat(bounded).doesNotContain("\r").doesNotContain("\n").startsWith("orders");
  }

  @Test
  @DisplayName("an enormous key is truncated and says so")
  void truncatesAnEnormousKey() {
    String enormous = "k".repeat(10_000);

    String bounded = DistributedLockException.acquireFailed(enormous, new IOException("x")).key();

    assertThat(bounded).hasSize(64 + "...".length()).endsWith("...");
  }

  @Test
  @DisplayName("the bounding is in the type, so a thrower in another module cannot forget it")
  void theBoundingIsInTheTypeNotInTheThrower() {
    // ADR-0022's rule and ADR-0034's shape: every thrower of this type lives in a DIFFERENT module,
    // so a rule each of them must remember is advisory by construction. Both doors are checked.
    String injected = "orders\r\ninjected";

    assertThat(DistributedLockException.acquireFailed(injected, new IOException("x")).key())
        .isEqualTo(DistributedLockException.releaseFailed(injected, new IOException("x")).key())
        .doesNotContain("\n");
  }

  @Test
  @DisplayName("releaseFailed names the other operation")
  void releaseFailedNamesTheOtherOperation() {
    // It exists to be LOGGED rather than thrown, because close() must not throw -- but it still has
    // to keep C-01 on a path nobody is watching.
    assertThat(DistributedLockException.releaseFailed(IDENTIFYING_KEY, new IOException("x")))
        .hasMessageContaining("release")
        .hasMessageNotContaining(IDENTIFYING_KEY);
  }

  @Test
  @DisplayName("is unchecked and is NOT a BusinessException, because 500 is not 422")
  void isUncheckedAndNotABusinessException() {
    // FR-19 maps BusinessException to 422 and an unchecked infrastructure fault to 500 plus an
    // alert. A lock backend being unreachable is the second. JdbcAccessException's shape.
    assertThat(RuntimeException.class).isAssignableFrom(DistributedLockException.class);
    assertThat(DistributedLockException.class.getSuperclass()).isEqualTo(RuntimeException.class);
  }

  @Test
  @DisplayName("rejects null arguments")
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void rejectsNullArguments() {
    assertThatThrownBy(() -> DistributedLockException.acquireFailed(null, new IOException("x")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> DistributedLockException.acquireFailed(IDENTIFYING_KEY, null))
        .isInstanceOf(NullPointerException.class);
  }
}
