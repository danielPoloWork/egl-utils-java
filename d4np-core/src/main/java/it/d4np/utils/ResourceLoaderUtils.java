package it.d4np.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Classpath resources, loaded the one way that works in all three deployment shapes.
 *
 * <pre>{@code
 * String sql = ResourceLoaderUtils.readString(MyDao.class, "queries/find-user.sql");
 * }</pre>
 *
 * <p><strong>Every lookup is anchored on a caller-supplied {@code Class<?>}</strong>, and that is
 * the load-bearing decision rather than an API convenience. Under JPMS a resource inside a named
 * module is encapsulated unless its package is {@code open}, so {@code
 * ClassLoader.getSystemResource} and the thread-context class loader both return {@code null} for
 * exactly the shape this project mandates — every module ships a {@code module-info}. Anchoring on
 * a class in the owning module is the only rule that holds across all three: an exploded directory,
 * a JAR on the classpath, and a named module. Pass a class that lives beside the resource.
 *
 * <p><strong>Names are absolute, and a leading {@code /} is optional.</strong> This is the single
 * most common surprise in {@link Class#getResourceAsStream(String)}: a name <em>without</em> a
 * leading slash is resolved <em>relative to the anchor's package</em>, so the same string means two
 * different files depending on where the anchor happens to live. Both forms are normalized to the
 * absolute one here, so {@code "config/app.yml"} and {@code "/config/app.yml"} are the same
 * resource.
 *
 * <p><strong>A name containing {@code ..} is rejected</strong> with {@link
 * IllegalArgumentException}. Where a resource name is built from caller-supplied input, traversal
 * would otherwise let it escape the anchor and read an unrelated packaged file; refusing the
 * segment outright is cheaper to reason about than normalizing it away.
 *
 * <p><strong>The charset default is UTF-8, written out.</strong> Not {@link
 * Charset#defaultCharset()}, which is still platform-dependent at this project's JDK 17 baseline
 * (JEP 400 makes it UTF-8 only from 18), so a packaged file would decode differently on a
 * developer's machine and on a container.
 *
 * <p><strong>Non-goal: there is no directory or wildcard listing</strong>, and there will not be.
 * Enumerating resources cannot be made to behave uniformly across JAR, exploded and modular
 * layouts, and an API that works in tests and returns empty in production is worse than no API.
 * Stated here so it is not requested later.
 *
 * <p><strong>{@link #open(Class, String)} hands the caller an open stream to close;</strong> the
 * two {@code readString} methods read and close it themselves.
 *
 * <p><strong>Thread safety.</strong> Stateless and static; safe from any thread.
 *
 * @see ResourceNotFoundException
 */
public final class ResourceLoaderUtils {

  private ResourceLoaderUtils() {}

  /**
   * Locates a resource, where absence is an ordinary answer.
   *
   * @param anchor a class in the module that owns the resource; must not be {@code null}
   * @param name the absolute resource name, with or without a leading {@code /}
   * @return the resource's {@link URL}, or {@link Optional#empty()} if it is not visible
   * @throws NullPointerException if {@code anchor} or {@code name} is {@code null}
   * @throws IllegalArgumentException if {@code name} contains a {@code ..} segment
   */
  public static Optional<URL> find(Class<?> anchor, String name) {
    Objects.requireNonNull(anchor, "resource anchor must not be null");
    return Optional.ofNullable(anchor.getResource(normalize(name)));
  }

  /**
   * Opens a resource, where absence is a defect.
   *
   * <p><strong>The caller closes the returned stream.</strong>
   *
   * @param anchor a class in the module that owns the resource; must not be {@code null}
   * @param name the absolute resource name, with or without a leading {@code /}
   * @return an open stream; never {@code null}
   * @throws NullPointerException if {@code anchor} or {@code name} is {@code null}
   * @throws IllegalArgumentException if {@code name} contains a {@code ..} segment
   * @throws ResourceNotFoundException if the resource is not visible from {@code anchor}
   */
  public static InputStream open(Class<?> anchor, String name) {
    Objects.requireNonNull(anchor, "resource anchor must not be null");
    String normalized = normalize(name);
    InputStream stream = anchor.getResourceAsStream(normalized);
    if (stream == null) {
      throw new ResourceNotFoundException(normalized, anchor);
    }
    return stream;
  }

  /**
   * Reads a resource as UTF-8 text.
   *
   * @param anchor a class in the module that owns the resource; must not be {@code null}
   * @param name the absolute resource name, with or without a leading {@code /}
   * @return the decoded contents; never {@code null}
   * @throws NullPointerException if {@code anchor} or {@code name} is {@code null}
   * @throws IllegalArgumentException if {@code name} contains a {@code ..} segment
   * @throws ResourceNotFoundException if the resource is not visible from {@code anchor}
   * @throws UncheckedIOException if reading fails after the resource was opened
   */
  public static String readString(Class<?> anchor, String name) {
    return readString(anchor, name, StandardCharsets.UTF_8);
  }

  /**
   * Reads a resource as text in an explicit charset.
   *
   * @param anchor a class in the module that owns the resource; must not be {@code null}
   * @param name the absolute resource name, with or without a leading {@code /}
   * @param charset the charset to decode with; must not be {@code null}
   * @return the decoded contents; never {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @throws IllegalArgumentException if {@code name} contains a {@code ..} segment
   * @throws ResourceNotFoundException if the resource is not visible from {@code anchor}
   * @throws UncheckedIOException if reading fails after the resource was opened
   */
  public static String readString(Class<?> anchor, String name, Charset charset) {
    Objects.requireNonNull(charset, "charset must not be null");
    try (InputStream stream = open(anchor, name)) {
      return new String(stream.readAllBytes(), charset);
    } catch (IOException failure) {
      // Wrapped rather than declared: no core method throws a checked exception (RFC-0001), and a
      // read that fails after the resource was successfully opened is an environment fault, not a
      // condition the caller can branch on.
      throw new UncheckedIOException("failed to read resource [" + normalize(name) + "]", failure);
    }
  }

  /**
   * Rejects traversal and makes the name absolute.
   *
   * <p>The leading slash is added rather than stripped: {@code Class.getResourceAsStream} treats a
   * bare name as package-relative, so normalizing the other way would silently change which file an
   * absolute-looking name resolves to.
   */
  private static String normalize(String name) {
    Objects.requireNonNull(name, "resource name must not be null");
    if (name.contains("..")) {
      throw new IllegalArgumentException(
          "resource name ["
              + name
              + "] must not contain '..'; names are absolute and cannot escape");
    }
    return name.startsWith("/") ? name : "/" + name;
  }
}
