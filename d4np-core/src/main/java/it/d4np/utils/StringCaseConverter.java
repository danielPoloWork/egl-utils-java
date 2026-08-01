package it.d4np.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One tokenizer, three renderings: {@code camelCase}, {@code snake_case} and {@code kebab-case}.
 *
 * <pre>{@code
 * StringCaseConverter.toSnake("parseHTTPRequest");   // parse_http_request
 * StringCaseConverter.toCamel("already_snake");      // alreadySnake
 * StringCaseConverter.toKebab("HTTPServer");         // http-server
 * }</pre>
 *
 * <p><strong>Every conversion goes through the same tokenizer</strong>, so the three can never
 * disagree about where a word boundary is. The splitting rules, pinned by RFC-0001:
 *
 * <ul>
 *   <li><strong>Separator runs</strong> ({@code _}, {@code -}, whitespace) collapse and are
 *       trimmed, so {@code __leading__} yields one token;
 *   <li><strong>A digit joins the token before it</strong> and never starts one — {@code s3Client}
 *       is {@code s3}+{@code Client}, not {@code s}+{@code 3Client};
 *   <li><strong>An uppercase run followed by a lowercase word splits before the final
 *       uppercase</strong> — {@code HTTPServer} is {@code HTTP}+{@code Server};
 *   <li>a lowercase or digit followed by an uppercase splits between them — {@code userName}.
 * </ul>
 *
 * <p><strong>"Followed by a lowercase word" means two or more lowercase characters, and that
 * threshold is the one judgement call in here.</strong> RFC-0001's prose says an uppercase run
 * followed by <em>a lowercase letter</em> splits, but its own pinned table requires {@code URLs} to
 * stay a single token and render as {@code urls} — and the prose rule would split it {@code
 * UR}+{@code Ls}, giving {@code urLs}. The table is the testable artifact, so it wins, and the
 * two-character threshold is what reproduces all eight of its rows. See ADR-0018; a plural {@code
 * s} after an acronym is the case this protects.
 *
 * <p><strong>Case mapping always uses {@link Locale#ROOT}</strong>, never the default-locale
 * overloads of {@code toLowerCase} / {@code toUpperCase}. This is a correctness rule with security
 * consequences rather than a style preference: on a Turkish-locale JVM {@code "I".toLowerCase()} is
 * dotless {@code "ı"}, so an identifier converted here would stop matching the key it was derived
 * from — silently, and only on some hosts.
 *
 * <p><strong>Guaranteed:</strong> every conversion is <em>idempotent</em> ({@code
 * toSnake(toSnake(x))} equals {@code toSnake(x)}) and <em>total</em> — no input string makes any of
 * these throw.
 *
 * <p><strong>Not guaranteed:</strong> round-tripping across an acronym. {@code HTTPServer} becomes
 * {@code http_server} becomes {@code httpServer}, and the original capitalisation is gone.
 * Recovering it would need an acronym dictionary, which a zero-dependency module (ADR-001) has
 * nowhere to put and no way to keep current. Stated as a non-guarantee so nobody depends on it.
 *
 * <p><strong>Thread safety.</strong> Stateless and static; safe from any thread. No jcstress
 * harness accompanies it, which is correct rather than an omission — there is no mutable state for
 * an interleaving to corrupt.
 *
 * @see java.util.Locale#ROOT
 */
public final class StringCaseConverter {

  /** How many lowercase characters must follow an uppercase run before it splits; see ADR-0018. */
  private static final int LOWERCASE_RUN_THAT_MAKES_A_WORD = 2;

  private StringCaseConverter() {}

  /**
   * Converts to {@code camelCase}.
   *
   * @param input any string; must not be {@code null}
   * @return the converted string, empty if {@code input} holds no token
   * @throws NullPointerException if {@code input} is {@code null}
   */
  public static String toCamel(String input) {
    List<String> tokens = tokenize(input);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      out.append(i == 0 ? lower(tokens.get(i)) : capitalize(tokens.get(i)));
    }
    return out.toString();
  }

  /**
   * Converts to {@code snake_case}.
   *
   * @param input any string; must not be {@code null}
   * @return the converted string, empty if {@code input} holds no token
   * @throws NullPointerException if {@code input} is {@code null}
   */
  public static String toSnake(String input) {
    return join(tokenize(input), '_');
  }

  /**
   * Converts to {@code kebab-case}.
   *
   * @param input any string; must not be {@code null}
   * @return the converted string, empty if {@code input} holds no token
   * @throws NullPointerException if {@code input} is {@code null}
   */
  public static String toKebab(String input) {
    return join(tokenize(input), '-');
  }

  /**
   * Splits {@code input} into words — the single source of truth all three renderings share.
   *
   * <p>Package-private so the tokenizer can be tested against RFC-0001's table directly rather than
   * only through the renderings, which would let a tokenizer bug and a renderer bug cancel out.
   *
   * @param input any string; must not be {@code null}
   * @return the tokens, in order, never containing an empty string
   */
  static List<String> tokenize(String input) {
    Objects.requireNonNull(input, "StringCaseConverter input must not be null");
    int[] chars = input.codePoints().toArray();
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    for (int i = 0; i < chars.length; i++) {
      int c = chars[i];
      if (isSeparator(c)) {
        flush(token, tokens);
      } else if (Character.isDigit(c)) {
        // A digit never starts a token, so it simply extends whatever is being built.
        token.appendCodePoint(c);
      } else if (Character.isUpperCase(c)) {
        if (endsWithLowerOrDigit(token) || startsAWord(token, chars, i)) {
          flush(token, tokens);
        }
        token.appendCodePoint(c);
      } else {
        token.appendCodePoint(c);
      }
    }
    flush(token, tokens);
    return tokens;
  }

  /**
   * Whether the uppercase character at {@code index} begins a new word rather than continuing an
   * acronym — the {@code HTTP|Server} split.
   *
   * <p>True when everything buffered so far is uppercase (so the run including this character is at
   * least two long) <em>and</em> at least {@value #LOWERCASE_RUN_THAT_MAKES_A_WORD} lowercase
   * characters follow. The second half is the ADR-0018 threshold that keeps {@code URLs} whole.
   */
  private static boolean startsAWord(StringBuilder token, int[] chars, int index) {
    if (token.length() == 0 || !isAllUpperCase(token)) {
      return false;
    }
    int lowercaseFollowing = 0;
    for (int i = index + 1; i < chars.length && Character.isLowerCase(chars[i]); i++) {
      lowercaseFollowing++;
      if (lowercaseFollowing >= LOWERCASE_RUN_THAT_MAKES_A_WORD) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAllUpperCase(StringBuilder token) {
    return token.codePoints().allMatch(Character::isUpperCase);
  }

  private static boolean endsWithLowerOrDigit(StringBuilder token) {
    if (token.length() == 0) {
      return false;
    }
    int last = token.codePointBefore(token.length());
    return Character.isLowerCase(last) || Character.isDigit(last);
  }

  /**
   * {@code _}, {@code -} and whitespace. RFC-0001 names "space"; whitespace is the reading taken,
   * because a tab between words is not meaningfully different from a space and treating it as part
   * of an identifier would be the surprising choice.
   */
  private static boolean isSeparator(int c) {
    return c == '_' || c == '-' || Character.isWhitespace(c);
  }

  /** Appends the buffered token if it is non-empty, and resets the buffer either way. */
  private static void flush(StringBuilder token, List<String> tokens) {
    if (token.length() > 0) {
      tokens.add(token.toString());
      token.setLength(0);
    }
  }

  private static String join(List<String> tokens, char separator) {
    StringBuilder out = new StringBuilder();
    for (String token : tokens) {
      if (out.length() > 0) {
        out.append(separator);
      }
      out.append(lower(token));
    }
    return out.toString();
  }

  private static String lower(String token) {
    return token.toLowerCase(Locale.ROOT);
  }

  private static String capitalize(String token) {
    int first = token.codePointAt(0);
    return new StringBuilder()
        .appendCodePoint(Character.toUpperCase(first))
        .append(lower(token.substring(Character.charCount(first))))
        .toString();
  }
}
