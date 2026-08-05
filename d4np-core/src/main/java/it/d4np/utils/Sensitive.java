package it.d4np.utils;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Blocks a component's value from an audit trail while keeping the fact that it changed — layer 2
 * of FR-16's precedence (RFC-0002).
 *
 * <pre>{@code
 * public @Audited record Account(String owner, @Sensitive String password) { }
 *
 * // owner    -> alice      -> alice
 * // password -> [REDACTED] -> [REDACTED]   (changed)
 * }</pre>
 *
 * <p><strong>Redacted is not omitted, and the difference is the whole value of this
 * marker.</strong> A blocked component still appears in the event, carrying {@link
 * AuditEvent#REDACTED} on both sides and {@link AuditEvent.Change#changed()}. <em>"The password was
 * changed at 14:02 by alice"</em> is precisely the record an audit trail exists to hold, and it
 * carries no plaintext — one bit saying a secret rotated is not the secret.
 *
 * <p><strong>It is not redundant against the deny-by-default {@link Audited} allowlist,</strong>
 * which is the first objection to raise and it has an answer: the ergonomic way to audit a domain
 * type is one type-level {@code @Audited}, and per-component {@code @Sensitive} is what makes that
 * form safe. Without it, "audit this type" and "never publish this component" could not be said
 * about the same type at the same time.
 *
 * <p><strong>It does not request capture.</strong> A component carrying only {@code @Sensitive} is
 * omitted like any unmarked component — this is an opt-<em>out</em>, and an opt-out of something
 * nobody asked for changes nothing. Pair it with {@code @Audited} (on the component or on the type)
 * when the trail should show that the value moved.
 *
 * <p><strong>It cannot be written on a type,</strong> and the compiler enforces that rather than
 * this Javadoc: FR-16's layers are defined over components, so a type-level "never capture" has no
 * defined meaning, and a marker with no meaning is worse than a missing one because it reads as a
 * guarantee. The never-capture list ({@link AuditPolicy}) is where a rule that spans types lives.
 *
 * <p><strong>{@link AuditPolicy}'s never-capture list outranks it,</strong> and that ordering never
 * matters in practice because both outcomes are identical — the list exists for the component that
 * <em>nobody</em> marked, added to an already-{@code @Audited} type months later by someone who
 * never read this page.
 *
 * @see Audited
 * @see AuditPolicy
 * @see AuditLog
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Sensitive {}
