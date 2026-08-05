package it.d4np.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-16 layer 1 (RFC-0002): the never-capture list, and the two ways a match rule gets it wrong.
 *
 * <p>{@code matchesUnderATurkishDefaultLocale} is the one to read first. It is not a formatting
 * test: with the default-locale {@code toLowerCase} overload, {@code API_KEY} normalises to {@code
 * apı_key} with a dotless {@code ı}, misses the entry, and the key is written to the audit store in
 * clear — which is the moment compliance control C-03 stops being about identifiers and starts
 * being about secrets.
 */
@DisplayName("AuditPolicy")
class AuditPolicyTest {

  @Test
  void holdsTheRfc0002BaseList() {
    // Transcribed from RFC-0002 rather than read back from the code, so that editing the list means
    // editing the contract too. `credential` and `credentials` are both here and neither is
    // redundant: the tokenizer does not split a plural s off a word (ADR-0018).
    assertThat(AuditPolicy.defaults().neverCapture())
        .containsExactlyInAnyOrder(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "credential",
            "credentials",
            "api_key",
            "access_key",
            "private_key",
            "secret_key",
            "signing_key",
            "authorization",
            "cookie",
            "session_id",
            "otp",
            "ssn",
            "social_security_number",
            "card_number",
            "cvv",
            "cvc",
            "pin_code");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "password",
        "userPassword",
        "PASSWORD",
        "password_hash",
        "apiKey",
        "API_KEY",
        "api-key",
        "ApiKey",
        "apiKeyRotatedAt",
        "sessionId",
        "socialSecurityNumber",
        "cardNumber",
        "pinCode",
        "tokenCount",
        "credentialsOfficerName"
      })
  void redacts(String componentName) {
    assertThat(AuditPolicy.defaults().isNeverCaptured(componentName)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "shipping",
        "shippingAddress",
        "author",
        "authorName",
        "primaryKey",
        "sortKey",
        "keyword",
        "apiRequestKey",
        "email",
        "",
        "   "
      })
  void doesNotRedact(String componentName) {
    assertThat(AuditPolicy.defaults().isNeverCaptured(componentName)).isFalse();
  }

  @Test
  void matchesWholeTokensAndNotSubstrings() {
    // The two counterexamples RFC-0002 rejects substring matching with: pin is inside shipping and
    // auth is inside author, so a substring list would redact an address and an author's name.
    assertThat(AuditPolicy.defaults().isNeverCaptured("shipping")).isFalse();
    assertThat(AuditPolicy.defaults().isNeverCaptured("author")).isFalse();
    // And the reason the list carries pairs rather than a bare `key`.
    assertThat(AuditPolicy.defaults().isNeverCaptured("primaryKey")).isFalse();
    assertThat(AuditPolicy.defaults().isNeverCaptured("apiKey")).isTrue();
  }

  @Test
  void requiresTheRunToBeContiguous() {
    // apiRequestKey holds both of the entry's tokens and not next to each other, which is a
    // different
    // field from the one `api_key` names.
    assertThat(AuditPolicy.defaults().isNeverCaptured("apiRequestKey")).isFalse();
    assertThat(AuditPolicy.defaults().isNeverCaptured("apiKeyRotation")).isTrue();
  }

  @Test
  void matchesUnderATurkishDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));

      // With String.toLowerCase() (no locale) this is apı_key with a dotless i, it misses api_key,
      // and the key reaches the audit store in clear. Control C-03, with a secret behind it.
      assertThat(AuditPolicy.defaults().isNeverCaptured("API_KEY")).isTrue();
      assertThat(AuditPolicy.defaults().isNeverCaptured("sessionID")).isTrue();
      assertThat(AuditPolicy.defaults().neverCapture()).contains("api_key");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void additionsAreAdditiveAndLeaveTheOriginalAlone() {
    AuditPolicy base = AuditPolicy.defaults();

    AuditPolicy extended = base.withAdditionalNeverCapture("iban", "policyNumber");

    assertThat(extended.neverCapture()).containsAll(base.neverCapture());
    assertThat(extended.neverCapture()).contains("iban", "policy_number");
    assertThat(extended.isNeverCaptured("customerIbanCheck")).isTrue();
    assertThat(base.isNeverCaptured("iban")).isFalse();
  }

  @Test
  void normalisesAnAdditionWhateverCaseStyleItArrivesIn() {
    assertThat(AuditPolicy.defaults().withAdditionalNeverCapture("POLICY-NUMBER").neverCapture())
        .contains("policy_number");
  }

  @Test
  void hasNoWayToRemoveAnEntry() {
    // "A host may add entries. A host may not remove one" — a removal is invisible in review and
    // permanent in the store. Asserted reflectively so that adding a removal method fails here
    // rather than in a code review that happens not to notice.
    List<String> mutators =
        Arrays.stream(AuditPolicy.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .filter(
                name ->
                    name.contains("remove") || name.contains("without") || name.contains("clear"))
            .toList();

    assertThat(mutators).isEmpty();
    assertThat(AuditPolicy.defaults().neverCapture()).isUnmodifiable();
  }

  @Test
  void refusesAnEntryThatCouldNeverMatch() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AuditPolicy.defaults().withAdditionalNeverCapture("__"))
        .withMessageContaining("holds no token");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AuditPolicy.defaults().withAdditionalNeverCapture(""));
  }

  @Test
  @SuppressWarnings("NullAway") // asserting the null-rejection contract requires passing null
  void refusesNulls() {
    assertThatNullPointerException()
        .isThrownBy(() -> AuditPolicy.defaults().withAdditionalNeverCapture((String[]) null));
    assertThatNullPointerException()
        .isThrownBy(() -> AuditPolicy.defaults().withAdditionalNeverCapture("ok", null));
  }

  @Test
  void theDefaultPolicyIsShared() {
    assertThat(AuditPolicy.defaults()).isSameAs(AuditPolicy.defaults());
  }
}
