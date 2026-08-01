package it.d4np.utils;

/**
 * A classpath resource could not be found — thrown by {@link ResourceLoaderUtils}.
 *
 * <p><strong>The message names the module, and that is the point.</strong> Under JPMS a resource
 * inside a named module is encapsulated unless its package is {@code open}, so "not found" has two
 * very different causes that look identical from a stack trace: the file is genuinely missing, or
 * it exists and the lookup was not entitled to see it. Reporting the anchor class and its module
 * turns the second case from a mystery into a one-line diagnosis.
 *
 * <p><strong>A wiring defect, not a business outcome</strong>, so — like {@link
 * StrategyNotFoundException} and {@link FactoryKeyNotFoundException} — it extends {@link
 * RuntimeException} directly rather than {@link BusinessException}. A missing packaged resource
 * means the build did not include it or the module does not open it; no end user can act on that,
 * and FR-19's fallback maps it to 500.
 *
 * <p>Use {@link ResourceLoaderUtils#find(Class, String)} where absence is an ordinary answer.
 *
 * <p><strong>Thread safety.</strong> Immutable apart from the mutable state every {@link Throwable}
 * carries.
 *
 * @see ResourceLoaderUtils
 */
public final class ResourceNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The requested resource name, normalized. */
  private final String resource;

  /** The anchor class's name, as text — see {@link StrategyNotFoundException} on why not a type. */
  private final String anchor;

  /** The anchor's module name, or {@code "unnamed"} on the classpath. */
  private final String module;

  /**
   * Package-private: {@link ResourceLoaderUtils} is the only thing that can decide a resource is
   * absent.
   *
   * @param resource the normalized resource name
   * @param anchor the class the lookup was anchored on
   */
  ResourceNotFoundException(String resource, Class<?> anchor) {
    super(describe(resource, anchor));
    this.resource = resource;
    this.anchor = anchor.getName();
    this.module = moduleNameOf(anchor);
  }

  /**
   * The resource that was not found.
   *
   * @return the normalized, absolute resource name; never {@code null}
   */
  public String resource() {
    return resource;
  }

  /**
   * The class the lookup was anchored on.
   *
   * @return the anchor's fully-qualified name; never {@code null}
   */
  public String anchor() {
    return anchor;
  }

  /**
   * The module the anchor belongs to.
   *
   * @return the module name, or {@code "unnamed"} when the anchor is on the classpath
   */
  public String module() {
    return module;
  }

  private static String describe(String resource, Class<?> anchor) {
    return "resource ["
        + resource
        + "] not found from anchor "
        + anchor.getName()
        + " (module "
        + moduleNameOf(anchor)
        + "); if the file exists, check that its package is opened by the module descriptor";
  }

  private static String moduleNameOf(Class<?> anchor) {
    Module module = anchor.getModule();
    return module.isNamed() ? module.getName() : "unnamed";
  }
}
