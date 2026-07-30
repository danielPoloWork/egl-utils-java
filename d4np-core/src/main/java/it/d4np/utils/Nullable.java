package it.d4np.utils;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method return, parameter, field or record component that may legitimately be {@code
 * null}.
 *
 * <p><strong>Everything in {@code it.d4np} is non-null unless it carries this annotation.</strong>
 * That is not a convention a reader has to trust: NullAway is configured with {@code
 * -XepOpt:NullAway:AnnotatedPackages=it.d4np} at {@code ERROR} severity (ADR-0009), so on the JDK
 * 21+ build cells a {@code null} reaching an unannotated position fails compilation. This
 * annotation is how the exceptions are declared.
 *
 * <p><strong>Why the library declares its own instead of depending on one.</strong> ADR-001 fixes
 * {@code d4np-core} at zero third-party dependencies (NFR-08), and an annotation is the one kind of
 * dependency a library cannot hide from its consumers: it appears in the published signatures. So
 * core mints this one rather than putting JSpecify, JSR-305 or the Checker Framework into every
 * consumer's dependency graph for three lines of metadata. NullAway, IntelliJ and Eclipse all
 * recognise a nullability annotation whose fully-qualified name ends in {@code .Nullable}, so this
 * one is understood by the tools without any per-project configuration. See ADR-0011.
 *
 * <p><strong>Retention is {@link RetentionPolicy#RUNTIME}</strong> so the annotation survives into
 * the published class files and is visible to reflective consumers — the frameworks this library
 * targets (Spring, Jakarta Bean Validation) inspect signatures at run time, and a {@code
 * CLASS}-retained marker would be invisible to them while costing exactly the same bytes.
 *
 * <p><strong>There is deliberately no {@code @NonNull} counterpart.</strong> Non-null is the
 * default, and an annotation that restates the default would eventually be applied inconsistently —
 * at which point its absence stops meaning anything.
 *
 * <p>{@link ElementType#TYPE_USE} is not in the target set: no core signature yet needs {@code
 * List<@Nullable String>}, and widening the targets later is a source- and binary-compatible change
 * while narrowing them is not.
 *
 * @see java.util.Objects#requireNonNull(Object, String)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.METHOD,
  ElementType.PARAMETER,
  ElementType.FIELD,
  ElementType.RECORD_COMPONENT
})
public @interface Nullable {}
