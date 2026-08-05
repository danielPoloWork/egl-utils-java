package it.d4np.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What the JVM will tell us about an audited type — the reflective half of FR-16, kept away from
 * the policy half.
 *
 * <p>{@link AuditLog} owns the four layers, the precedence between them, the recursion and the
 * redaction; this class answers the three questions that are only about the type: <em>which
 * components does it have</em>, <em>which markers are on them</em>, and <em>can this value be
 * rendered at all</em>. The split is the same one {@link KeyDiagnostics} makes for the keyed
 * lookups: the subtle part is separated so it has one place to be right.
 *
 * <p><strong>No deep reflection, ever.</strong> {@code setAccessible} is never called, so a
 * consuming module needs no {@code opens} clause. Asking a host to open its domain packages in
 * order to run a <em>redaction</em> engine is the wrong privilege to request, and a type whose
 * accessors are not public is simply not auditable — which this class says out loud instead of
 * working around. Reading an <em>annotation</em> off a private field is not deep reflection and
 * needs no access at all; only reading its <em>value</em> would, and values are read exclusively
 * through public accessors.
 *
 * <p>Package-private and non-instantiable: an implementation detail of {@link AuditLog}, not a
 * reflection toolkit offered to consumers.
 */
final class AuditComponents {

  private AuditComponents() {}

  /**
   * Whether {@code type}'s accessors can be reached at all without deep reflection.
   *
   * <p>Separate from {@link #of(Class)} so a caller that already knows a better message can ask
   * first: a non-public class reached as a <em>nested component</em> is almost always a collection
   * or an array, and telling that caller "the type is not public" would answer a question they did
   * not ask.
   *
   * @param type the class a value's layers would be read from
   * @return {@code true} if the class is public
   */
  static boolean isVisible(Class<?> type) {
    return Modifier.isPublic(type.getModifiers());
  }

  /**
   * The audited-capable components of {@code type}, ordered by name.
   *
   * <p>A record's components are its record components; anything else contributes its JavaBean
   * properties — a public no-argument {@code getX()}, or {@code isX()} returning a boolean. Public
   * fields with no accessor are deliberately not components (FR-16 reads through accessors), and a
   * marker on a field with no accessor is refused rather than ignored, because a dropped {@link
   * Audited} is a record that silently never arrives.
   *
   * <p>Inherited public getters are included, so a subclass's audited state covers the properties
   * it inherited. A type-level {@link Audited} is <em>not</em> inherited, though — see the
   * annotation for why a permit-everything marker must not cross a class boundary.
   *
   * @param type the runtime class of the value being audited
   * @return the components, sorted by name; possibly empty
   * @throws AuditCaptureException if {@code type} is not public, if two accessors map to one
   *     component name, or if a marked field has no accessor
   */
  static List<Component> of(Class<?> type) {
    if (!isVisible(type)) {
      throw new AuditCaptureException(
          "audit capture cannot read "
              + type.getName()
              + ": the type is not public, and FR-16 reads values through public accessors only —"
              + " no deep reflection is requested, so a non-public type is not auditable");
    }
    boolean typeAudited = type.isAnnotationPresent(Audited.class);
    List<Component> components =
        type.isRecord() ? recordComponents(type, typeAudited) : beanComponents(type, typeAudited);
    // String.compareTo, not a collator: the order of an audit record must not depend on the host
    // that captured it (the C-03 hazard, one layer down).
    components.sort(Comparator.comparing(Component::name));
    requireEveryMarkedFieldHasAComponent(type, components);
    return List.copyOf(components);
  }

  /**
   * Whether {@code value} may be captured directly, as FR-16 enumerates it.
   *
   * <p>Everything else — a collection, an array, a domain object — is a composite, and a composite
   * is only captured by recursing into it under {@link Audited}. That is not a gap in this list:
   * {@code String.valueOf} on a composite prints a record's generated {@code toString()}, which
   * includes every component it has, so admitting one more "obviously printable" type here is how a
   * {@link Sensitive} marker gets bypassed while looking correct.
   *
   * @param value a component's value; never {@code null}
   * @return {@code true} if the value renders to text on its own
   */
  static boolean isSimple(Object value) {
    return value instanceof CharSequence
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>
        || value instanceof UUID
        || value instanceof TemporalAccessor
        || value instanceof TemporalAmount
        || value instanceof ZoneId;
  }

  /**
   * Renders a simple value to the text that lands in the record.
   *
   * <p>An enum renders by {@link Enum#name()} rather than {@code toString()}: {@code toString()} is
   * overridable and is usually a display label, while {@code name()} is the constant's stable
   * identity — and audit records are compared across hosts, across locales and across versions.
   *
   * @param value a value {@link #isSimple(Object)} accepted
   * @return the rendered text; never {@code null}
   */
  static String render(Object value) {
    return value instanceof Enum<?> constant ? constant.name() : String.valueOf(value);
  }

  private static List<Component> recordComponents(Class<?> type, boolean typeAudited) {
    List<Component> components = new ArrayList<>();
    for (RecordComponent component : type.getRecordComponents()) {
      Method accessor = component.getAccessor();
      Field field = declaredField(type, component.getName());
      components.add(
          new Component(
              component.getName(),
              accessor,
              typeAudited || marked(Audited.class, component, accessor, field),
              marked(Sensitive.class, component, accessor, field)));
    }
    return components;
  }

  private static List<Component> beanComponents(Class<?> type, boolean typeAudited) {
    List<Component> components = new ArrayList<>();
    for (Method method : type.getMethods()) {
      String name = propertyName(method);
      if (name == null || alreadyPresent(type, components, name, method)) {
        continue;
      }
      Field field = declaredField(type, name);
      components.add(
          new Component(
              name,
              method,
              typeAudited || marked(Audited.class, method, field),
              marked(Sensitive.class, method, field)));
    }
    return components;
  }

  /**
   * The property a getter exposes, or {@code null} if the method is not one.
   *
   * <p>{@code getClass()} is excluded by name — it is a public no-argument getter on every object
   * ever written, and capturing it would put the class name in every record twice.
   */
  @Nullable
  private static String propertyName(Method method) {
    if (method.getParameterCount() != 0
        || method.getReturnType() == void.class
        || Modifier.isStatic(method.getModifiers())
        || method.isSynthetic()
        || method.isBridge()
        || "getClass".equals(method.getName())) {
      return null;
    }
    String name = method.getName();
    if (name.startsWith("get") && name.length() > 3) {
      return decapitalize(name.substring(3));
    }
    boolean bool =
        method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class;
    if (bool && name.startsWith("is") && name.length() > 2) {
      return decapitalize(name.substring(2));
    }
    return null;
  }

  /**
   * The JavaBeans rule, hand-rolled because {@code java.beans} lives in {@code java.desktop} and
   * this module requires nothing but {@code java.base}.
   *
   * <p>{@link Character#toLowerCase(char)} rather than {@link String#toLowerCase()}: the character
   * mapping is locale-independent, and the string overload is the C-03 hazard that would rename
   * {@code getId()}'s property on a Turkish-locale JVM.
   */
  private static String decapitalize(String name) {
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(0))
        && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  /**
   * Whether this property is already accounted for, and refuses the case where two different
   * accessors claim it.
   *
   * <p>{@link Class#getMethods()} can hand back the same accessor twice — once from a class and
   * once from an interface it implements — so a repeat of the <em>same</em> method name is skipped
   * rather than treated as a conflict. {@code getActive()} beside {@code isActive()} is the genuine
   * ambiguity: both are legal Java, they map to one component, and which of the two the markers
   * were written on is unknowable. Refusing beats picking one, because picking silently decides
   * whether a value is captured.
   */
  private static boolean alreadyPresent(
      Class<?> type, List<Component> components, String name, Method method) {
    for (Component present : components) {
      if (!present.name().equals(name)) {
        continue;
      }
      if (present.accessor().getName().equals(method.getName())) {
        return true;
      }
      throw new AuditCaptureException(
          "audit capture cannot read "
              + type.getName()
              + ": accessors ["
              + present.accessor().getName()
              + "] and ["
              + method.getName()
              + "] both map to component ["
              + name
              + "], so which one the audit markers apply to is ambiguous");
    }
    return false;
  }

  /**
   * Refuses a marker that will never be read, which is the failure mode a silent design would hide.
   *
   * <p>{@code @Audited private String userName;} beside a {@code getUser()} accessor is the shape
   * this catches: the marker is present, correct-looking and attached to nothing, so the component
   * would be omitted from every record and the omission would only surface during an audit. Names
   * are compared through {@link StringCaseConverter#toSnake(String)} so that a {@code
   * getURL()}/{@code URL} pair still matches.
   */
  private static void requireEveryMarkedFieldHasAComponent(
      Class<?> type, List<Component> components) {
    for (Class<?> level = type;
        level != null && level != Object.class;
        level = level.getSuperclass()) {
      for (Field field : level.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        if (!field.isAnnotationPresent(Audited.class)
            && !field.isAnnotationPresent(Sensitive.class)) {
          continue;
        }
        if (!hasComponentNamed(components, field.getName())) {
          throw new AuditCaptureException(
              "audit capture cannot read "
                  + type.getName()
                  + ": field ["
                  + field.getName()
                  + "] carries an audit marker but no public accessor exposes it, so the marker"
                  + " would silently do nothing");
        }
      }
    }
  }

  private static boolean hasComponentNamed(List<Component> components, String fieldName) {
    String normalised = StringCaseConverter.toSnake(fieldName);
    for (Component component : components) {
      if (StringCaseConverter.toSnake(component.name()).equals(normalised)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code marker} is present on any declaration site a component has.
   *
   * <p>All sites are read rather than only the canonical one, and for a redaction marker that is a
   * safety property rather than convenience: a {@link Sensitive} written on the field of a JavaBean
   * while this class looked only at the getter would be present, correct, and completely bypassed —
   * the worst available outcome, because it looks right in review.
   */
  private static boolean marked(
      Class<? extends Annotation> marker, @Nullable AnnotatedElement... sites) {
    for (AnnotatedElement site : sites) {
      if (site != null && site.isAnnotationPresent(marker)) {
        return true;
      }
    }
    return false;
  }

  /** The declared field of that name anywhere in the hierarchy, for its annotations only. */
  @Nullable
  private static Field declaredField(Class<?> type, String name) {
    for (Class<?> level = type; level != null; level = level.getSuperclass()) {
      try {
        return level.getDeclaredField(name);
      } catch (NoSuchFieldException absent) {
        // Not every property has a field of the same name — a computed getter has none at all.
      }
    }
    return null;
  }

  /**
   * One component of an audited type: how to read it, and what the markers on it say.
   *
   * @param name the component's declared name
   * @param accessor the public accessor that yields its value
   * @param audited whether FR-16 layer 3 or 4 requests capture
   * @param sensitive whether FR-16 layer 2 blocks the value
   */
  record Component(String name, Method accessor, boolean audited, boolean sensitive) {

    /**
     * Reads this component from {@code target} through its public accessor.
     *
     * @param target the object being audited
     * @return the raw value, or {@code null} if the component holds none
     * @throws AuditCaptureException if the accessor cannot be invoked or itself threw
     */
    @Nullable
    Object read(Object target) {
      try {
        return accessor.invoke(target);
      } catch (IllegalAccessException blocked) {
        throw new AuditCaptureException(
            "audit capture cannot read ["
                + name
                + "] of "
                + accessor.getDeclaringClass().getName()
                + ": the accessor is not reachable from this module, and FR-16 does not request"
                + " deep reflective access — export the package or expose a public accessor",
            blocked);
      } catch (InvocationTargetException failed) {
        // The accessor's own exception, not the reflective wrapper, because that is what a host
        // recognises. The wrapper is kept if there is somehow nothing inside it, so nothing is
        // lost.
        Throwable thrown = failed.getCause();
        throw new AuditCaptureException(
            "audit capture cannot read ["
                + name
                + "] of "
                + accessor.getDeclaringClass().getName()
                + ": the accessor threw, so the object cannot be audited in this state",
            thrown == null ? failed : thrown);
      }
    }
  }
}
