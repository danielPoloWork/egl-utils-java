package it.d4np.utils;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requests capture into an audit trail — layers 3 and 4 of FR-16's precedence (RFC-0002).
 *
 * <pre>{@code
 * // layer 3 — one component at a time, which is the deny-by-default shape
 * public record Customer(@Audited String email, String internalNote) { }
 *
 * // layer 4 — the whole type, which is the ergonomic shape and needs @Sensitive to stay safe
 * public @Audited record Account(String owner, @Sensitive String password) { }
 * }</pre>
 *
 * <p><strong>Nothing is captured without this marker.</strong> {@link AuditLog#capture} omits an
 * unmarked component <em>entirely</em> — the name does not appear in the event, which is a
 * different outcome from {@code [REDACTED]} and deliberately so: a redaction says "this changed and
 * you may not see it", an omission says "nobody asked for this". Deny-by-default fails toward an
 * incomplete record, which is recoverable; permit-by-default fails toward a published secret, which
 * is not.
 *
 * <p><strong>On a type this captures every component, and {@link Sensitive} is what makes that
 * safe.</strong> The pairing is the point: one marker on the type is the form a domain object
 * actually gets annotated in, and per-component opt-out is what stops the next component added to
 * that type from being published by default.
 *
 * <p><strong>It is deliberately not {@link java.lang.annotation.Inherited @Inherited}, and that is
 * a security decision rather than an oversight.</strong> An inherited type-level marker means a
 * subclass that adds a component <em>leaks it by default</em> — the exact failure mode RFC-0002
 * rejected the denylist design for, moved across a class boundary where it is even less visible.
 * Member-level markers are still seen on an inherited accessor, because they travel with the member
 * they were written on rather than with the type. A subclass that should be audited says so itself.
 *
 * <p><strong>Where it may be written.</strong> On a type, on a record component, on a field, or on
 * a getter. All four are read, so it does not matter which one a codebase's convention prefers — a
 * marker that was present but looked at the wrong declaration site would be the worst of the
 * possible outcomes, since it would look correct in review. A marker on anything that is not a
 * record component or a JavaBean property is not a component and is ignored; that direction is safe
 * because it omits rather than publishes.
 *
 * @see Sensitive
 * @see AuditLog
 * @see AuditPolicy
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Audited {}
