/**
 * Pooling and async execution over {@code java.util.concurrent} alone (spec §3, FR-08..FR-10).
 *
 * <p><strong>This module has no third-party dependency at any scope.</strong> Its {@code
 * maven-enforcer} allowlist carries exactly two entries — internal modules and test scope — with no
 * {@code provided}-scope exemption of the kind {@code d4np-core} holds for Bean Validation, so a
 * logging or Redis client reaching this package fails {@code mvn validate} rather than review
 * (ADR-001, ADR-0006, NFR-08). That rule is what keeps {@code d4np-lock-redisson} worth existing:
 * the moment Redisson could be added here, the separation would be a convention instead of a
 * structure.
 *
 * <h2>Conventions that hold for every type in this package</h2>
 *
 * <ul>
 *   <li><strong>Non-null by default.</strong> Every parameter, return and field is non-null unless
 *       it carries {@link it.d4np.utils.Nullable}, checked by NullAway at {@code ERROR} severity on
 *       the JDK 21+ build cells (ADR-0009).
 *   <li><strong>No {@code null} to signal absence.</strong> {@link java.util.Optional} for absence,
 *       an unchecked exception for a defect (RFC-0001).
 *   <li><strong>No checked exceptions.</strong> Nothing here declares one.
 *   <li><strong>A configured guarantee is unreachable, not merely documented.</strong> A pool this
 *       module builds is published as {@link it.d4np.utils.concurrent.ManagedThreadPool}, which
 *       exposes no setter for anything the specification fixed — the rule ADR-0022 states and FR-20
 *       and FR-07 each applied before it.
 *   <li><strong>Thread safety is documented per type</strong>, and a thread-safety claim is backed
 *       by a named jcstress harness rather than asserted in prose (spec §6).
 * </ul>
 *
 * <h2>What a consumer still owns</h2>
 *
 * <p>The threads' lifetime is the caller's: {@link
 * it.d4np.utils.concurrent.CustomThreadPoolFactory} hands back a pool the caller must close, and
 * {@link it.d4np.utils.concurrent.AsyncExecutor} never creates a thread at all — it runs work on an
 * {@link java.util.concurrent.Executor} it was given.
 *
 * <p><strong>So is the meaning of "context".</strong> FR-09 names MDC, which is SLF4J's and
 * unreachable from a module with no third-party dependency at any scope, so this package publishes
 * the {@link it.d4np.utils.concurrent.ContextPropagator} SPI and ships no implementation that reads
 * a logging framework. Binding it to MDC is four lines in the host, given verbatim in that type's
 * Javadoc. The default carries nothing and says so, rather than reaching for {@code org.slf4j.MDC}
 * reflectively and propagating only when it happens to be present (ADR-0036).
 *
 * <h2>The lifecycle rule that is not visible in the source</h2>
 *
 * <p>{@code ExecutorService} became {@link java.lang.AutoCloseable} in <strong>Java 19</strong>,
 * with a default {@code close()} that drains for a day at a time. This module compiles at {@code
 * --release 17}, where that method does not exist, and runs on 17 and 21 alike — so {@code
 * ManagedThreadPool} declares <em>both</em> {@code AutoCloseable} and {@code close()} explicitly.
 * Neither declaration is redundant, and the <strong>interface</strong> declaration is the one
 * holding the guarantee up: it is what makes the deletion of {@code close()} a compile error rather
 * than a silent reversion to the JDK's one-day drain. That guard lapses if the {@code --release}
 * baseline ever moves past 18. See {@code ManagedThreadPool}'s Javadoc and ADR-0035.
 *
 * @see <a
 *     href="https://github.com/danielPoloWork/egl-utils-java/blob/main/docs/rfc/0004-concurrency-contracts.md">RFC-0004
 *     — concurrency contracts</a>
 */
package it.d4np.utils.concurrent;
