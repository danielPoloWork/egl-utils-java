package it.d4np.utils;

import java.io.Serializable;
import java.util.Objects;

/**
 * The shared failure vocabulary: a machine-readable {@code code}, human-readable {@code message},
 * and an optional underlying {@code cause}.
 *
 * <p>One type carries every failure this library reports, whichever channel it travels on — the
 * {@link Result.Err} arm for an outcome the caller branches on, {@link BusinessException} for a
 * rule violation that aborts the use case (ADR-002). That is deliberate: consumers get one error
 * taxonomy end to end rather than one per mechanism, and the Spring adapter's RFC 7807 mapping
 * (FR-19) has a single shape to translate.
 *
 * <p><strong>{@code code} is a {@code String}, not an enum</strong> — a shared library cannot
 * enumerate its consumers' business codes (RFC-0001). Treat it as the stable, machine-readable
 * discriminator a caller may switch on; treat {@link #message()} as prose that may be reworded
 * without notice.
 *
 * <p><strong>Security: {@code message} is caller-facing text and must not carry secrets,
 * credentials or PII.</strong> This is a contract, not advice: {@code message} crosses trust
 * boundary B2 into an RFC 7807 {@code application/problem+json} response body and reaches the HTTP
 * client (FR-19, threat model §2). Put diagnostics that must not leave the process in {@link
 * #cause()}, which the adapter logs and never serialises.
 *
 * <p><strong>Nullability.</strong> {@code code} and {@code message} are non-null and rejected with
 * {@link NullPointerException} at construction; {@code cause} is the one nullable component and is
 * marked {@link Nullable}.
 *
 * <p><strong>Thread safety.</strong> Immutable, and therefore safely publishable without
 * synchronisation — a reader that sees the reference sees fully constructed components, which is
 * asserted by a named jcstress harness rather than claimed (spec §6; {@code
 * ImmutablePublicationStress}). The one exception is inherited: {@link Throwable} is mutable (its
 * stack trace and suppressed list can be written after construction), so a shared {@code
 * ErrorDetail} is as thread-safe as the {@code cause} a caller put in it.
 *
 * <p><strong>Serialisation.</strong> This record is {@link Serializable} because {@link
 * BusinessException} carries one and {@link Throwable} is already {@code Serializable} — a
 * non-serialisable payload would make every {@code BusinessException} fail to serialise, silently
 * and only in the hosts that do it (session replication, JMS, RMI). As with any exception, the
 * round trip succeeds only if the {@code cause} a caller supplied is itself serialisable.
 *
 * @param code the stable, machine-readable failure discriminator; never {@code null}
 * @param message human-readable, caller-facing text; never {@code null}, never carrying secrets
 * @param cause the underlying failure, or {@code null} when there is none
 * @see Result
 * @see BusinessException
 */
public record ErrorDetail(String code, String message, @Nullable Throwable cause)
    implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * Canonical constructor: rejects a null {@code code} or {@code message}.
   *
   * <p>The {@code @param} tags below repeat the record header's, which JDK 18+ infers on its own.
   * JDK 17's doclint does not, and NFR-07 makes 17 the published baseline, so they are written out
   * rather than left to a toolchain-dependent inference.
   *
   * @param code the stable, machine-readable failure discriminator; must not be {@code null}
   * @param message human-readable, caller-facing text; must not be {@code null}
   * @param cause the underlying failure, or {@code null} when there is none
   */
  public ErrorDetail {
    Objects.requireNonNull(code, "ErrorDetail.code must not be null");
    Objects.requireNonNull(message, "ErrorDetail.message must not be null");
  }

  /**
   * A detail with no underlying cause — the common case, since most business failures are decisions
   * rather than exceptions.
   *
   * @param code the stable, machine-readable failure discriminator; never {@code null}
   * @param message human-readable, caller-facing text; never {@code null}, never carrying secrets
   */
  public ErrorDetail(String code, String message) {
    this(code, message, null);
  }
}
