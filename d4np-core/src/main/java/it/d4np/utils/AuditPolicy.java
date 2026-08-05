package it.d4np.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The never-capture list — layer 1 of FR-16's precedence, the one layer nothing overrides
 * (RFC-0002).
 *
 * <pre>{@code
 * AuditLog log = AuditLog.using(sink, AuditPolicy.defaults()
 *     .withAdditionalNeverCapture("iban", "policy_number"));
 * }</pre>
 *
 * <p><strong>Additions are easy and removals are impossible</strong>, and the asymmetry is the
 * design. Every industry has identifiers this list has never heard of, so adding must be a
 * one-liner; a removal, by contrast, is invisible in review and permanent in the store — the
 * records are already written and already replicated by the time anyone notices. There is therefore
 * no removal method, and {@link #withAdditionalNeverCapture} returns a new policy rather than
 * mutating this one.
 *
 * <p><strong>Matching is on whole tokens, never substrings.</strong> A name is normalised through
 * {@link StringCaseConverter#toSnake(String)} first, and an entry matches when its token sequence
 * appears as a <em>contiguous run</em> in the component's tokens. Substring matching is not a
 * simplification here, it is wrong in both directions: {@code pin} is a substring of {@code
 * shipping} and {@code auth} of {@code author}, so a substring list redacts the wrong fields and
 * teaches a team that the audit trail is unreliable — which is how a control gets switched off.
 * Conversely a token that is too generic is wrong the other way, {@code key} swallowing {@code
 * primaryKey} and {@code sortKey}, which is why the base list carries the <em>pairs</em> {@code
 * api_key}, {@code access_key}, {@code private_key}, {@code secret_key} and {@code signing_key}
 * rather than {@code key}.
 *
 * <p><strong>Over-redaction is the accepted failure and is chosen, not tolerated.</strong> {@code
 * tokenCount} normalises to {@code [token, count]}, contains the run {@code [token]}, and is
 * redacted — a harmless counter loses its value in the trail. That is the right side to fail on,
 * and a field that must stay legible can be renamed.
 *
 * <p><strong>This is where FR-22's {@link java.util.Locale#ROOT} rule stops being a correctness
 * rule and becomes a security one</strong> (compliance control C-03). Normalisation runs {@link
 * StringCaseConverter}, so on a Turkish-locale JVM a default-locale {@code toLowerCase} would map
 * {@code API_KEY} to {@code apı_key} with a dotless {@code ı}, <strong>fail to match {@code
 * api_key}, and write the key to the audit store in clear</strong> — silently, and only on some
 * hosts. {@code AuditPolicyTest.matchesUnderATurkishDefaultLocale} reproduces the host rather than
 * reasoning about it.
 *
 * <p><strong>Thread safety.</strong> Immutable and safe to share; every field is final and the two
 * collections are unmodifiable snapshots taken at construction.
 *
 * @see Audited
 * @see Sensitive
 * @see AuditLog
 */
public final class AuditPolicy {

  /**
   * RFC-0002's base list, transcribed rather than paraphrased.
   *
   * <p>Written already normalised so the constructor's normalisation is a no-op over it, and
   * asserted entry-for-entry by {@code AuditPolicyTest.holdsTheRfc0002BaseList} — the test exists
   * so that a future edit to this array has to be a deliberate edit to the contract too.
   *
   * <p>{@code credential} and {@code credentials} are both present and neither is redundant: the
   * tokenizer does not split a plural {@code s} off a word (ADR-0018), so they are two different
   * tokens.
   */
  private static final List<String> BASE_NEVER_CAPTURE =
      List.of(
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

  /**
   * Shared because the type is immutable; {@link #defaults()} hands the same instance to everyone.
   */
  private static final AuditPolicy DEFAULTS = new AuditPolicy(BASE_NEVER_CAPTURE);

  /** The normalised entries, sorted so two policies print and diff comparably. */
  private final Set<String> entries;

  /**
   * The same entries as token sequences, precomputed.
   *
   * <p>Split once here rather than per component per capture: matching is the only thing this type
   * does, and it runs once for every audited component of every captured object.
   */
  private final List<List<String>> runs;

  private AuditPolicy(Collection<String> names) {
    Set<String> normalised = new TreeSet<>();
    for (String name : names) {
      normalised.add(normalise(name));
    }
    this.entries = Collections.unmodifiableSet(normalised);
    List<List<String>> tokenized = new ArrayList<>(normalised.size());
    for (String entry : normalised) {
      // Safe to split on the separator: normalise() has already collapsed every other separator
      // into '_' and trimmed the ends, so no token here can be empty.
      tokenized.add(List.of(entry.split("_")));
    }
    this.runs = List.copyOf(tokenized);
  }

  /**
   * The policy every {@link AuditLog} starts from — RFC-0002's base list and nothing else.
   *
   * @return the shared default policy; never {@code null}
   */
  public static AuditPolicy defaults() {
    return DEFAULTS;
  }

  /**
   * This policy plus {@code names}, for the identifiers an industry has and this library does not.
   *
   * <p>Names may be written in any case style — {@code policyNumber}, {@code POLICY_NUMBER} and
   * {@code policy-number} normalise to the same entry, so a host does not have to know which
   * convention this list is stored in.
   *
   * @param names the additional never-capture entries; each must hold at least one token
   * @return a new policy holding this policy's entries and {@code names}; never {@code null}
   * @throws NullPointerException if {@code names} or any element is {@code null}
   * @throws IllegalArgumentException if an element holds no token, such as {@code ""} or {@code
   *     "__"}
   */
  public AuditPolicy withAdditionalNeverCapture(String... names) {
    Objects.requireNonNull(names, "never-capture entries must not be null");
    List<String> combined = new ArrayList<>(entries);
    Collections.addAll(combined, names);
    return new AuditPolicy(combined);
  }

  /**
   * Every entry this policy will redact, normalised to {@code snake_case}.
   *
   * <p>The normalised form is deliberate: it is what actually matches, so a host reading this back
   * sees the rule rather than the spelling it happened to type.
   *
   * @return an unmodifiable, sorted set; never empty, because the base list is never removed
   */
  public Set<String> neverCapture() {
    return entries;
  }

  /**
   * Whether {@code componentName} is blocked by this list.
   *
   * <p>Package-private on purpose. The list's effect is observable through {@link AuditLog#capture}
   * and readable through {@link #neverCapture()}; a public predicate would be a third way to ask
   * the same question, and every one of those has to keep agreeing with the other two forever.
   *
   * @param componentName the component's declared name, in any case style
   * @return {@code true} if any entry's tokens appear as a contiguous run in the name's tokens
   */
  boolean isNeverCaptured(String componentName) {
    List<String> tokens = tokens(componentName);
    for (List<String> run : runs) {
      if (containsRun(tokens, run)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code needle} appears as a contiguous run in {@code haystack}.
   *
   * <p>Contiguity is what makes {@code api_key} match {@code apiKeyRotation} and not {@code
   * apiRequestKey}: the second name's tokens contain both of the entry's tokens but not next to
   * each other, and a name that merely mentions two words is not the field the entry names.
   */
  private static boolean containsRun(List<String> haystack, List<String> needle) {
    int span = needle.size();
    for (int start = 0; start + span <= haystack.size(); start++) {
      if (haystack.subList(start, start + span).equals(needle)) {
        return true;
      }
    }
    return false;
  }

  /** Splits a name the way {@link #normalise} spells an entry, so the two are comparable. */
  private static List<String> tokens(String name) {
    String normalised = StringCaseConverter.toSnake(name);
    return normalised.isEmpty() ? List.of() : List.of(normalised.split("_"));
  }

  /**
   * Normalises one entry, rejecting the ones that could never match.
   *
   * <p>An entry that holds no token — {@code ""}, {@code " "}, {@code "__"} — is refused rather
   * than dropped, because a silently ignored never-capture entry is a host believing it added a
   * rule it did not add.
   */
  private static String normalise(String name) {
    Objects.requireNonNull(name, "never-capture entry must not be null");
    String normalised = StringCaseConverter.toSnake(name);
    if (normalised.isEmpty()) {
      throw new IllegalArgumentException(
          "never-capture entry [" + name + "] holds no token, so it could never match a component");
    }
    return normalised;
  }
}
