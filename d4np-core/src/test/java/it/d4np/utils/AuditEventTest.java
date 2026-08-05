package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import it.d4np.utils.AuditFixtures.Account;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an {@link AuditEvent} is allowed to hold and to say — the type FR-16's redaction guarantee
 * is attached to (RFC-0002).
 *
 * <p>Two assertions here are worth defending. {@code toStringCarriesNoRawValue}, because an event
 * ends up in interceptors, queues and debuggers, and its {@code toString()} is safe only because
 * nothing raw ever entered it; and {@code aDeserialisedEventIsStillImmutable}, because
 * deserialisation skips the constructor and would otherwise be a way to hand a mutable list to code
 * that promises an immutable one.
 */
@DisplayName("AuditEvent")
class AuditEventTest {

  private final AuditLog audit = AuditLog.using(event -> {});

  private AuditEvent rotation() {
    return audit.capture(
        "alice",
        "PASSWORD_ROTATED",
        new Account("alice", "hunter2", 41),
        new Account("alice", "correct-horse-battery", 42));
  }

  @Test
  void toStringCarriesNoRawValue() {
    String rendered = rotation().toString();

    assertThat(rendered)
        .contains("alice")
        .contains("PASSWORD_ROTATED")
        .contains(AuditEvent.REDACTED)
        .doesNotContain("hunter2")
        .doesNotContain("correct-horse-battery");
  }

  @Test
  void aBlockedComponentCarriesTheOneMarkerOnBothSides() {
    assertThat(AuditEvent.REDACTED).isEqualTo("[REDACTED]");
    assertThat(rotation().changes())
        .filteredOn(AuditEvent.Change::redacted)
        .isNotEmpty()
        .allSatisfy(
            change -> {
              assertThat(change.before()).isEqualTo("[REDACTED]");
              assertThat(change.after()).isEqualTo("[REDACTED]");
            });
  }

  @Test
  void survivesASerialisationRoundTrip() throws IOException, ClassNotFoundException {
    AuditEvent original = rotation();

    AuditEvent restored = SerializationSupport.roundTrip(original);

    assertThat(restored.actor()).isEqualTo(original.actor());
    assertThat(restored.action()).isEqualTo(original.action());
    assertThat(restored.subjectType()).isEqualTo(original.subjectType());
    assertThat(restored.occurredAt()).isEqualTo(original.occurredAt());
    assertThat(restored.changes()).isEqualTo(original.changes());
    assertThat(restored.toString()).doesNotContain("hunter2");
  }

  @Test
  void theWriteFailureCarriesTheEventThroughSerialisationToo()
      throws IOException, ClassNotFoundException {
    AuditLog failing =
        AuditLog.using(
            event -> {
              throw new IllegalStateException("store is down");
            });
    AuditEvent event = failing.capture("alice", "CREATED", null, new Account("alice", "p", 0));

    try {
      failing.record(event);
      throw new AssertionError("expected the sink failure to be reported");
    } catch (AuditWriteException thrown) {
      // ErrorDetail is serialisable so that BusinessException is (item 2.1); the same constraint
      // lands
      // here, because this exception carries an event — and a non-serialisable payload would make
      // every AuditWriteException fail to serialise, silently and only in the hosts that do it.
      AuditWriteException restored = SerializationSupport.roundTrip(thrown);

      assertThat(restored.event().actor()).isEqualTo("alice");
      assertThat(restored.event().changes()).isEqualTo(event.changes());
      assertThat(restored.getMessage()).contains("was not written");
    }
  }

  @Test
  void aDeserialisedEventIsStillImmutable() throws IOException, ClassNotFoundException {
    // readResolve re-runs the constructor, which deserialisation skips: without it the stream
    // decides
    // what `changes` is, including whether it can be modified.
    AuditEvent restored = SerializationSupport.roundTrip(rotation());

    assertThat(restored.changes()).isUnmodifiable();
  }

  @Test
  void changesAreOrderedAndUnmodifiable() {
    AuditEvent event = rotation();

    assertThat(event.changes()).isUnmodifiable();
    assertThat(event.changes().stream().map(AuditEvent.Change::path).toList())
        .containsExactly("loginCount", "owner", "password");
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void aChangeRefusesAPathThatNamesNothing() {
    assertThatNullPointerException()
        .isThrownBy(() -> new AuditEvent.Change(null, "a", "b", false, true));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AuditEvent.Change(" ", "a", "b", false, true))
        .withMessageContaining("must not be blank");
  }

  @Test
  void aChangeValidatesOnDeserialisationBecauseItIsARecord()
      throws IOException, ClassNotFoundException {
    // The difference between the two shapes in this file: a record's deserialisation runs its
    // canonical constructor, so Change's checks hold with no readResolve of its own.
    AuditEvent.Change original = new AuditEvent.Change("owner", "a", "b", false, true);

    assertThat(SerializationSupport.roundTrip(original)).isEqualTo(original);
  }
}
