package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.d4np.utils.AuditFixtures.Account;
import it.d4np.utils.AuditFixtures.AmbiguousAccessors;
import it.d4np.utils.AuditFixtures.Assorted;
import it.d4np.utils.AuditFixtures.Bean;
import it.d4np.utils.AuditFixtures.BrokenAccessor;
import it.d4np.utils.AuditFixtures.Credentials;
import it.d4np.utils.AuditFixtures.Customer;
import it.d4np.utils.AuditFixtures.HasList;
import it.d4np.utils.AuditFixtures.HasObject;
import it.d4np.utils.AuditFixtures.HasUnmarked;
import it.d4np.utils.AuditFixtures.Hidden;
import it.d4np.utils.AuditFixtures.Integration;
import it.d4np.utils.AuditFixtures.Level1;
import it.d4np.utils.AuditFixtures.Level2;
import it.d4np.utils.AuditFixtures.Level3;
import it.d4np.utils.AuditFixtures.Level4;
import it.d4np.utils.AuditFixtures.Login;
import it.d4np.utils.AuditFixtures.MarkedFields;
import it.d4np.utils.AuditFixtures.MarkerWithoutAccessor;
import it.d4np.utils.AuditFixtures.NamedCredentials;
import it.d4np.utils.AuditFixtures.Node;
import it.d4np.utils.AuditFixtures.OnlySensitive;
import it.d4np.utils.AuditFixtures.Status;
import it.d4np.utils.AuditFixtures.Unmarked;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-16 (RFC-0002): the four layers, the loud refusals, and the one bit a redacted component keeps.
 *
 * <p>Two assertions here carry more weight than the rest. {@code
 * theRedactedComponentStillSaysThatItChanged} is the sentence the whole feature exists to be able
 * to write — <em>"the password was changed at 14:02 by alice"</em> with no plaintext anywhere near
 * it — and {@code neverRendersACompositeAsText} is the trap the design was built around: a record's
 * generated {@code toString()} includes every component, so rendering a composite would emit a
 * {@link Sensitive} value with the marker present, correct and bypassed.
 */
@DisplayName("AuditLog")
class AuditLogTest {

  private final List<AuditEvent> written = new CopyOnWriteArrayList<>();

  private final AuditLog audit = AuditLog.using(written::add);

  private static String valueAt(AuditEvent event, String path) {
    return event.changes().stream()
        .filter(change -> change.path().equals(path))
        .findFirst()
        .map(change -> change.before() + " -> " + change.after())
        .orElseThrow(() -> new AssertionError("no change at [" + path + "] in " + event));
  }

  private static AuditEvent.Change changeAt(AuditEvent event, String path) {
    return event.changes().stream()
        .filter(change -> change.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no change at [" + path + "] in " + event));
  }

  private static List<String> paths(AuditEvent event) {
    return event.changes().stream().map(AuditEvent.Change::path).toList();
  }

  @Nested
  @DisplayName("the four layers")
  class Layers {

    @Test
    void capturesOnlyTheComponentThatAsked() {
      AuditEvent event =
          audit.capture(
              "alice",
              "EMAIL_CHANGED",
              new Customer("old@example.com", "keep this internal"),
              new Customer("new@example.com", "keep this internal"));

      assertThat(paths(event)).containsExactly("email");
      assertThat(valueAt(event, "email")).isEqualTo("old@example.com -> new@example.com");
    }

    @Test
    void anUnmarkedComponentIsOmittedEntirelyRatherThanRedacted() {
      AuditEvent event =
          audit.capture(
              "alice",
              "NOTED",
              new Customer("a@b.c", "internal-was"),
              new Customer("a@b.c", "internal-now"));

      assertThat(paths(event)).doesNotContain("internalNote");
      assertThat(event.toString())
          .doesNotContain("internalNote")
          .doesNotContain("internal-was")
          .doesNotContain("internal-now")
          // Not [REDACTED] either: a redaction says "this changed and you may not see it", an
          // omission says "nobody asked for this", and only one of those is true here.
          .doesNotContain(AuditEvent.REDACTED);
    }

    @Test
    void aTypeLevelMarkerCapturesEveryComponent() {
      AuditEvent event =
          audit.capture("alice", "CREATED", null, new Account("alice", "hunter2", 0));

      assertThat(paths(event)).containsExactly("loginCount", "owner", "password");
    }

    @Test
    void sensitiveBlocksTheValueAndKeepsTheComponent() {
      AuditEvent event =
          audit.capture(
              "alice",
              "PASSWORD_ROTATED",
              new Account("alice", "hunter2", 41),
              new Account("alice", "correct-horse", 41));

      assertThat(valueAt(event, "password")).isEqualTo("[REDACTED] -> [REDACTED]");
      assertThat(changeAt(event, "password").redacted()).isTrue();
      assertThat(event.toString()).doesNotContain("hunter2").doesNotContain("correct-horse");
    }

    @Test
    void theRedactedComponentStillSaysThatItChanged() {
      Account before = new Account("alice", "hunter2", 41);

      assertThat(
              changeAt(
                      audit.capture("alice", "ROTATED", before, new Account("alice", "next", 41)),
                      "password")
                  .changed())
          .isTrue();
      assertThat(
              changeAt(
                      audit.capture(
                          "alice", "LOGGED_IN", before, new Account("alice", "hunter2", 42)),
                      "password")
                  .changed())
          .isFalse();
    }

    @Test
    void theNeverCaptureListRedactsWhatNobodyMarked() {
      AuditEvent event =
          audit.capture(
              "alice",
              "KEY_ROTATED",
              new Integration("stripe", "sk_live_before", 3),
              new Integration("stripe", "sk_live_after", 4));

      assertThat(valueAt(event, "apiKey")).isEqualTo("[REDACTED] -> [REDACTED]");
      assertThat(changeAt(event, "apiKey").changed()).isTrue();
      assertThat(event.toString()).doesNotContain("sk_live");
    }

    @Test
    void overRedactionIsAcceptedDeliberatelyForAHarmlessCounter() {
      AuditEvent event =
          audit.capture(
              "alice", "USED", new Integration("s", "k", 3), new Integration("s", "k", 4));

      // tokenCount normalises to [token, count] and contains the run [token]. RFC-0002 chooses this
      // side to fail on; the cost is pinned here so a future change to the list has to face it.
      assertThat(valueAt(event, "tokenCount")).isEqualTo("[REDACTED] -> [REDACTED]");
      assertThat(valueAt(event, "name")).isEqualTo("s -> s");
    }

    @Test
    void sensitiveAloneDoesNotRequestCapture() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new OnlySensitive("hunter2")))
          .withMessageContaining("nothing requests capture")
          .withMessageContaining("@Sensitive alone is an opt-out");
    }

    @Test
    void aMarkerOnAFieldIsHonouredWhenTheAccessorCarriesNone() {
      AuditEvent event =
          audit.capture(
              "alice",
              "TOKEN_ROTATED",
              new MarkedFields("alice", "tok-before"),
              new MarkedFields("alice", "tok-after"));

      assertThat(valueAt(event, "owner")).isEqualTo("alice -> alice");
      assertThat(valueAt(event, "token")).isEqualTo("[REDACTED] -> [REDACTED]");
      assertThat(event.toString()).doesNotContain("tok-");
    }
  }

  @Nested
  @DisplayName("composites")
  class Composites {

    @Test
    void recursesIntoAnAuditedComponentAndKeepsItsLayers() {
      AuditEvent event =
          audit.capture(
              "alice",
              "LOGIN_UPDATED",
              new Login("host-a", new Credentials("alice", "hunter2")),
              new Login("host-b", new Credentials("alice", "correct-horse")));

      assertThat(paths(event)).containsExactly("host", "principal.password", "principal.user");
      assertThat(valueAt(event, "principal.user")).isEqualTo("alice -> alice");
      assertThat(valueAt(event, "principal.password")).isEqualTo("[REDACTED] -> [REDACTED]");
    }

    @Test
    void layerOneBlocksAWholeCompositeRatherThanWalkingIntoIt() {
      // RFC-0002 illustrates the recursion rule with `@Audited Credentials credentials` — and its
      // own
      // never-capture list holds `credentials`, which outranks everything. So the subtree is
      // redacted
      // whole and the non-sensitive `user` inside it disappears too: over-redaction, in the
      // direction
      // the RFC says to fail in, and a single row rather than a walk.
      AuditEvent event =
          audit.capture(
              "alice",
              "ROTATED",
              new NamedCredentials(new Credentials("nested-user", "hunter2")),
              new NamedCredentials(new Credentials("nested-user", "correct-horse")));

      assertThat(paths(event)).containsExactly("credentials");
      assertThat(valueAt(event, "credentials")).isEqualTo("[REDACTED] -> [REDACTED]");
      assertThat(changeAt(event, "credentials").changed()).isTrue();
      assertThat(event.toString()).doesNotContain("hunter2").doesNotContain("nested-user");
    }

    @Test
    void neverRendersACompositeAsText() {
      Login before = new Login("host", new Credentials("alice", "hunter2"));

      AuditEvent event = audit.capture("alice", "LOGIN", before, before);

      // The trap: Credentials.toString() contains the password, so a single String.valueOf on the
      // composite would publish it with the @Sensitive marker present and completely bypassed.
      assertThat(before.principal().toString()).contains("hunter2");
      assertThat(event.toString()).doesNotContain("hunter2");
      assertThat(event.changes())
          .noneMatch(change -> String.valueOf(change.after()).contains("hunter2"));
    }

    @Test
    void refusesACompositeThatIsNotAudited() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(
              () ->
                  audit.capture(
                      "alice", "X", null, new HasUnmarked(new Unmarked("alice", "hunter2"))))
          .withMessageContaining("[nested]")
          .withMessageContaining("must carry @Audited on its own type");
    }

    @Test
    void refusesACollectionComponent() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new HasList(List.of("admin"))))
          .withMessageContaining("[roles]")
          .withMessageContaining("only simple values");
    }

    @Test
    void readsTheLayersFromTheRuntimeTypeNotTheDeclaredOne() {
      AuditEvent event =
          audit.capture(
              "alice",
              "X",
              new HasObject(new Credentials("alice", "before")),
              new HasObject(new Credentials("alice", "after")));

      // Declared as Object; captured as Credentials, so @Sensitive still applies.
      assertThat(paths(event)).containsExactly("value.password", "value.user");
      assertThat(valueAt(event, "value.password")).isEqualTo("[REDACTED] -> [REDACTED]");
    }

    @Test
    void refusesAnObjectComponentHoldingTwoDifferentTypes() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(
              () ->
                  audit.capture(
                      "alice",
                      "X",
                      new HasObject("a string"),
                      new HasObject(new Credentials("alice", "hunter2"))))
          .withMessageContaining("two states of one type")
          .withMessageContaining("[value]");
    }

    @Test
    void capturesThreeLevelsBelowTheRoot() {
      AuditEvent event =
          audit.capture("alice", "X", null, new Level2(new Level3(new Level4("deep"))));

      assertThat(paths(event)).containsExactly("next.next.leaf");
      assertThat(valueAt(event, "next.next.leaf")).isEqualTo("null -> deep");
    }

    @Test
    void refusesAFourthLevel() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(
              () ->
                  audit.capture(
                      "alice", "X", null, new Level1(new Level2(new Level3(new Level4("deep"))))))
          .withMessageContaining("deeper than the maximum of 3")
          .withMessageContaining("[next.next.next]");
    }

    @Test
    void refusesASelfReference() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new Node("a")))
          .withMessageContaining("cycle")
          .withMessageContaining("[link]");
    }

    @Test
    void refusesALongerCycle() {
      Node first = new Node("a");
      Node second = new Node("b");
      first.linkTo(second);
      second.linkTo(first);

      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, first))
          .withMessageContaining("[link.link]");
    }
  }

  @Nested
  @DisplayName("reading values")
  class Reading {

    @Test
    void readsThroughThePublicAccessorAndNotTheField() {
      AuditEvent event = audit.capture("alice", "X", null, new Bean("stored-value"));

      assertThat(valueAt(event, "greeting")).isEqualTo("null -> via-accessor:stored-value");
    }

    @Test
    void refusesANonPublicType() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new Hidden("v")))
          .withMessageContaining("the type is not public")
          .withMessageContaining("no deep reflection");
    }

    @Test
    void refusesAMarkerNoAccessorExposes() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new MarkerWithoutAccessor("v")))
          .withMessageContaining("[orphan]")
          .withMessageContaining("would silently do nothing");
    }

    @Test
    void refusesTwoAccessorsForOneComponent() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new AmbiguousAccessors()))
          .withMessageContaining("both map to component [active]")
          .withMessageContaining("ambiguous");
    }

    @Test
    void surfacesAnAccessorThatThrows() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(() -> audit.capture("alice", "X", null, new BrokenAccessor()))
          .withMessageContaining("[detail]")
          .withMessageContaining("the accessor threw")
          .withCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void rendersEveryKindOfSimpleValue() {
      UUID id = UUID.fromString("6d1e0f4e-0000-4000-8000-000000000001");
      Instant at = Instant.parse("2026-08-04T14:02:00Z");

      AuditEvent event =
          audit.capture(
              "alice", "X", null, new Assorted(Status.ACTIVE, id, at, Duration.ofMinutes(5), 'B'));

      // name(), not toString(): the enum's toString() is a display label ("status 0").
      assertThat(valueAt(event, "status")).isEqualTo("null -> ACTIVE");
      assertThat(valueAt(event, "id")).endsWith("-> " + id);
      assertThat(valueAt(event, "at")).endsWith("-> 2026-08-04T14:02:00Z");
      assertThat(valueAt(event, "every")).endsWith("-> PT5M");
      assertThat(valueAt(event, "grade")).endsWith("-> B");
    }

    @Test
    void capturesUnchangedComponentsToo() {
      AuditEvent event =
          audit.capture(
              "alice", "TOUCHED", new Account("alice", "same", 7), new Account("alice", "same", 7));

      assertThat(paths(event)).containsExactly("loginCount", "owner", "password");
      assertThat(event.changes()).allMatch(change -> !change.changed());
    }

    @Test
    void ordersChangesByPath() {
      AuditEvent event =
          audit.capture("alice", "X", null, new Login("h", new Credentials("u", "p")));

      List<String> sorted = new ArrayList<>(paths(event));
      sorted.sort(null);
      assertThat(paths(event)).isEqualTo(sorted);
    }
  }

  @Nested
  @DisplayName("the state pair")
  class StatePair {

    @Test
    void aCreationHasNoBeforeSide() {
      AuditEvent event = audit.capture("alice", "CREATED", null, new Customer("a@b.c", "note"));

      assertThat(changeAt(event, "email").before()).isNull();
      assertThat(changeAt(event, "email").after()).isEqualTo("a@b.c");
      assertThat(changeAt(event, "email").changed()).isTrue();
    }

    @Test
    void aDeletionHasNoAfterSide() {
      AuditEvent event = audit.capture("alice", "DELETED", new Customer("a@b.c", "note"), null);

      assertThat(changeAt(event, "email").before()).isEqualTo("a@b.c");
      assertThat(changeAt(event, "email").after()).isNull();
    }

    @Test
    void aBlockedComponentIsRedactedEvenWhenTheValueIsAbsent() {
      AuditEvent event =
          audit.capture("alice", "CREATED", null, new Account("alice", "hunter2", 0));

      // Not `null -> [REDACTED]`: whether a secret is currently set is itself a fact worth
      // withholding.
      assertThat(valueAt(event, "password")).isEqualTo("[REDACTED] -> [REDACTED]");
    }

    @Test
    void refusesTwoStatesOfDifferentTypes() {
      assertThatExceptionOfType(AuditCaptureException.class)
          .isThrownBy(
              () ->
                  audit.capture(
                      "alice", "X", new Customer("a@b.c", "n"), new Account("alice", "p", 0)))
          .withMessageContaining("two states of one type");
    }

    @Test
    void refusesTwoAbsentStates() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> audit.capture("alice", "X", null, null))
          .withMessageContaining("both were null");
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsAnUnattributableRecord() {
      Customer state = new Customer("a@b.c", "n");

      assertThatNullPointerException().isThrownBy(() -> audit.capture(null, "X", null, state));
      assertThatNullPointerException().isThrownBy(() -> audit.capture("alice", null, null, state));
      assertThatIllegalArgumentException()
          .isThrownBy(() -> audit.capture(" ", "X", null, state))
          .withMessageContaining("audit actor must not be blank");
      assertThatIllegalArgumentException()
          .isThrownBy(() -> audit.capture("alice", "", null, state))
          .withMessageContaining("audit action must not be blank");
    }

    @Test
    void namesTheActorTheActionAndTheRuntimeType() {
      AuditEvent event = audit.capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      assertThat(event.actor()).isEqualTo("alice");
      assertThat(event.action()).isEqualTo("CREATED");
      assertThat(event.subjectType()).isEqualTo(Customer.class.getName());
      assertThat(event.occurredAt()).isBetween(Instant.now().minusSeconds(60), Instant.now());
    }
  }

  @Nested
  @DisplayName("writing")
  class Writing {

    @Test
    void handsTheEventToTheSink() {
      AuditEvent event = audit.capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      audit.record(event);

      assertThat(written).containsExactly(event);
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsANullEvent() {
      assertThatNullPointerException().isThrownBy(() -> audit.record(null));
    }

    @Test
    void wrapsASinkFailureAndCarriesTheEvent() {
      IllegalStateException down = new IllegalStateException("audit store is down");
      AuditLog failing =
          AuditLog.using(
              event -> {
                throw down;
              });
      AuditEvent event = failing.capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      assertThatExceptionOfType(AuditWriteException.class)
          .isThrownBy(() -> failing.record(event))
          .withCause(down)
          .withMessageContaining("was not written")
          .satisfies(thrown -> assertThat(thrown.event()).isSameAs(event));
    }

    @Test
    void wrapsALinkageErrorFromTheSink() {
      AuditLog failing =
          AuditLog.using(
              event -> {
                throw new NoClassDefFoundError("com/example/AuditRow");
              });
      AuditEvent event = failing.capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      assertThatExceptionOfType(AuditWriteException.class).isThrownBy(() -> failing.record(event));
    }

    @Test
    void doesNotWrapAnErrorThatMeansTheVmIsDying() {
      AuditLog failing =
          AuditLog.using(
              event -> {
                throw new OutOfMemoryError("heap");
              });
      AuditEvent event = failing.capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      assertThatThrownBy(() -> failing.record(event)).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void aFailedWriteIsAnOperationsFailureNotABusinessOne() {
      AuditLog failing =
          AuditLog.using(
              event -> {
                throw new IllegalStateException("down");
              });
      AuditEvent event = failing.capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      // FR-19 maps BusinessException to 422 and an operations failure to 500 + alert. Asserted as a
      // negative because this is a relationship a later refactor can reverse by accident.
      assertThatThrownBy(() -> failing.record(event)).isNotInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> audit.capture("alice", "X", null, new Unmarked("a", "b")))
          .isNotInstanceOf(BusinessException.class);
    }

    @Test
    @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
    void rejectsNullsAtConstruction() {
      assertThatNullPointerException().isThrownBy(() -> AuditLog.using(null));
      assertThatNullPointerException()
          .isThrownBy(() -> AuditLog.using(written::add, null))
          .withMessageContaining("audit policy");
    }

    @Test
    void theDefaultLogIsUsableWithoutASink() {
      AuditEvent event =
          AuditLog.create().capture("alice", "CREATED", null, new Customer("a@b.c", "n"));

      assertThat(event.changes()).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("the policy in use")
  class PolicyInUse {

    @Test
    void aHostAdditionRedactsWhatTheBaseListDoesNot() {
      AuditLog strict =
          AuditLog.using(written::add, AuditPolicy.defaults().withAdditionalNeverCapture("email"));

      AuditEvent event =
          strict.capture(
              "alice",
              "EMAIL_CHANGED",
              new Customer("old@example.com", "n"),
              new Customer("new@example.com", "n"));

      assertThat(valueAt(event, "email")).isEqualTo("[REDACTED] -> [REDACTED]");
      assertThat(changeAt(event, "email").changed()).isTrue();
      assertThat(event.toString()).doesNotContain("@example.com");
    }
  }
}
