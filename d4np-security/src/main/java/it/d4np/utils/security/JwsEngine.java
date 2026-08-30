package it.d4np.utils.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import it.d4np.utils.Nullable;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Nimbus work behind {@link JwtVerifier} and {@link JwtTokenProvider}.
 *
 * <p>Package-private, which is what keeps ADR-003's <em>"raw Nimbus types do not leak from the
 * API"</em> a property of the module rather than a discipline: every JOSE type this project touches
 * is named in this file and nowhere else, so a library swap is one file plus that ADR's
 * supersession.
 *
 * <p><strong>One engine, one algorithm.</strong> The algorithm is fixed at construction and the
 * token's {@code alg} header is compared against it — never used to select a verifier. That is the
 * algorithm-confusion defence stated as code: the attack is an HS256 token verified against an
 * RS256 public key, and it needs a verifier willing to take the algorithm from the token. This one
 * never asks.
 */
final class JwsEngine {

  /** Resolves the verifier for a token's header, which is where a {@code kid} lookup happens. */
  @FunctionalInterface
  interface Verifiers {
    JWSVerifier forHeader(JWSHeader header);
  }

  private final JWSAlgorithm algorithm;
  private final JwtProfile profile;
  private final Verifiers verifiers;
  @Nullable private final JWSSigner signer;
  @Nullable private final String signingKeyId;

  /**
   * The flag a modular consumer must add, named here once so every message quotes it identically.
   *
   * <p>See {@link #requireJsonSupport()} for what it is for.
   */
  static final String ADD_READS_FLAG = "--add-reads com.nimbusds.jose.jwt=java.sql";

  private JwsEngine(
      JWSAlgorithm algorithm,
      JwtProfile profile,
      Verifiers verifiers,
      @Nullable JWSSigner signer,
      @Nullable String signingKeyId) {
    requireJsonSupport();
    this.algorithm = algorithm;
    this.profile = profile;
    this.verifiers = verifiers;
    this.signer = signer;
    this.signingKeyId = signingKeyId;
  }

  /**
   * An HS256 engine that can both sign and verify with one shared secret.
   *
   * @param secret the shared secret; **at least 256 bits**, per ADR-003
   * @param profile the hardened profile
   * @return the engine
   */
  static JwsEngine hs256(byte[] secret, JwtProfile profile) {
    byte[] copy = requireStrongSecret(secret);
    try {
      return new JwsEngine(
          JWSAlgorithm.HS256, profile, header -> macVerifier(copy), new MACSigner(copy), null);
    } catch (JOSEException rejected) {
      // Unreachable given the length check above, and mapped rather than ignored: a JOSEException
      // here would mean Nimbus refused a secret we accepted, which is a defect in this class.
      throw new IllegalArgumentException("the HS256 secret was refused by the JOSE provider");
    }
  }

  /** An HS256 engine that can only verify. */
  static JwsEngine hs256Verifying(byte[] secret, JwtProfile profile) {
    byte[] copy = requireStrongSecret(secret);
    return new JwsEngine(JWSAlgorithm.HS256, profile, header -> macVerifier(copy), null, null);
  }

  /** An RS256 engine verifying against one static public key. */
  static JwsEngine rs256Verifying(RSAPublicKey publicKey, JwtProfile profile) {
    Objects.requireNonNull(publicKey, "publicKey");
    return new JwsEngine(
        JWSAlgorithm.RS256, profile, header -> new RSASSAVerifier(publicKey), null, null);
  }

  /** An RS256 engine resolving keys from a JWKS endpoint by {@code kid}. */
  static JwsEngine rs256Verifying(JwksSource keys, JwtProfile profile) {
    Objects.requireNonNull(keys, "keys");
    return new JwsEngine(
        JWSAlgorithm.RS256, profile, header -> rsaVerifier(keys, header), null, null);
  }

  /** An RS256 engine that signs with a private key and verifies against a public one. */
  static JwsEngine rs256(
      RSAPrivateKey signingKey, String keyId, RSAPublicKey verificationKey, JwtProfile profile) {
    Objects.requireNonNull(signingKey, "signingKey");
    Objects.requireNonNull(verificationKey, "verificationKey");
    Objects.requireNonNull(keyId, "keyId");
    return new JwsEngine(
        JWSAlgorithm.RS256,
        profile,
        header -> new RSASSAVerifier(verificationKey),
        new RSASSASigner(signingKey),
        keyId);
  }

  /**
   * Fails at construction if Nimbus's JSON machinery cannot initialise on this module path.
   *
   * <p><strong>This exists because of a real defect with a silent failure mode, measured rather
   * than anticipated.</strong> Nimbus shades Gson, and Gson's {@code SqlTypesSupport} decides
   * whether to register {@code java.sql.Date} adapters by calling {@code
   * Class.forName("java.sql.Date")} and catching {@link ClassNotFoundException}. That guard is
   * correct on the class path and defeated on the <em>module</em> path: when {@code java.sql} is
   * resolved but {@code com.nimbusds.jose.jwt} does not read it — and its descriptor requires
   * neither {@code java.sql} nor {@code static java.sql} — the JVM raises {@link
   * IllegalAccessError}, which is an {@link Error} and not the exception Gson guards for. The
   * static initialiser dies, and every subsequent JSON operation fails with {@code
   * NoClassDefFoundError: Could not initialize class com.nimbusds.jose.util.JSONObjectUtils}.
   *
   * <p><strong>Without this check that surfaces at the first token parsed</strong>, in a request,
   * as an error naming a shaded class the reader has never heard of. With it, it surfaces when the
   * provider is built, naming the exact flag — the same reasoning that rejects a short HS256 secret
   * at construction rather than weakening tokens silently.
   *
   * <p>Cheap: the JVM initialises the class once, so this is a no-op after the first provider.
   */
  private static void requireJsonSupport() {
    try {
      // The exact call the failing stack bottoms out in, chosen so the probe exercises the real
      // initialiser rather than something adjacent to it.
      new JWTClaimsSet.Builder().build().toJSONObject();
    } catch (LinkageError unreadable) {
      throw new IllegalStateException(
          "the JOSE provider's JSON support could not initialise on this module path. "
              + "Nimbus's shaded Gson reads java.sql.Date, its module does not declare that edge, "
              + "and its ClassNotFoundException guard does not catch the IllegalAccessError JPMS "
              + "raises instead. Add "
              + ADD_READS_FLAG
              + " to the JVM, or run this library on the class path.",
          unreadable);
    }
  }

  private static byte[] requireStrongSecret(byte[] secret) {
    Objects.requireNonNull(secret, "secret");
    if (secret.length * 8 < 256) {
      // ADR-003: rejected at construction rather than weakening tokens silently. A short HS256
      // secret is offline-forgeable, and the moment to find that out is startup.
      throw new IllegalArgumentException(
          "an HS256 secret must be at least 256 bits; this one is " + (secret.length * 8));
    }
    return secret.clone();
  }

  private static JWSVerifier macVerifier(byte[] secret) {
    try {
      return new MACVerifier(secret);
    } catch (JOSEException rejected) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the HMAC key could not be used",
          rejected);
    }
  }

  private static JWSVerifier rsaVerifier(JwksSource keys, JWSHeader header) {
    String keyId = header.getKeyID();
    if (keyId == null) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.KEY_UNAVAILABLE,
          "the token carries no kid, so no JWKS entry can be selected");
    }
    RSAPublicKey key =
        keys.rsaKey(keyId)
            .orElseThrow(
                () ->
                    JwtVerificationException.of(
                        JwtVerificationException.Reason.KEY_UNAVAILABLE,
                        "no RSA key in the JWKS matches the token's kid"));
    return new RSASSAVerifier(key);
  }

  /** Whether this engine was built with signing key material. */
  boolean canSign() {
    return signer != null;
  }

  /**
   * Signs a token carrying the given claims for the given lifetime.
   *
   * @param claims the caller's subject and custom claims
   * @param lifetime how long the token is valid; must be positive
   * @return the compact serialization
   */
  String sign(JwtClaims claims, Duration lifetime) {
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isNegative() || lifetime.isZero()) {
      throw new IllegalArgumentException("lifetime must be positive; was " + lifetime);
    }
    JWSSigner active = signer;
    if (active == null) {
      throw new IllegalStateException("this provider holds no signing key");
    }

    Instant now = Instant.now();
    JWTClaimsSet.Builder set =
        new JWTClaimsSet.Builder()
            .subject(claims.subject())
            // iss, aud, iat and exp come from the profile and the lifetime, never from the caller
            // --
            // which is what makes a token with no expiry unrepresentable rather than merely
            // checked.
            .issuer(profile.issuer())
            .audience(profile.audience())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(lifetime)));
    for (String name : claims.claimNames()) {
      set.claim(name, claims.stringClaim(name).orElseThrow());
    }

    JWSHeader.Builder header =
        new JWSHeader.Builder(algorithm).type(new JOSEObjectType(profile.tokenType()));
    if (signingKeyId != null) {
      header.keyID(signingKeyId);
    }

    SignedJWT jwt = new SignedJWT(header.build(), set.build());
    try {
      jwt.sign(active);
    } catch (JOSEException failed) {
      // Wrapped rather than propagated: JOSEException is checked, and RFC-0001 forbids a published
      // method declaring one. Its message never reaches ours (C-01).
      throw new IllegalStateException("the token could not be signed");
    }
    return jwt.serialize();
  }

  /**
   * Verifies a token and returns its claims.
   *
   * <p>The order below is the contract. Signature verification happens <strong>before any claim is
   * read</strong> (spec §4), and the algorithm check happens before the key is resolved — so an
   * {@code alg=none} or algorithm-confusion token never reaches key material at all.
   *
   * @param token the compact serialization
   * @return the verified claims
   */
  JwtClaims verify(String token) {
    Objects.requireNonNull(token, "token");

    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException malformed) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.MALFORMED, "not a well-formed JWS", malformed);
    }

    JWSHeader header = jwt.getHeader();
    if (!algorithm.equals(header.getAlgorithm())) {
      // THE algorithm-confusion defence, and where alg=none lands. The algorithm is this engine's,
      // fixed at construction; the token's header is compared against it and never consulted to
      // choose anything.
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.ALGORITHM_NOT_ALLOWED,
          "this verifier accepts one algorithm and the token does not use it");
    }
    JOSEObjectType type = header.getType();
    if (type == null || !profile.tokenType().equals(type.getType())) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.TYPE_NOT_ALLOWED,
          "the token's typ header is not the one this profile requires");
    }

    boolean signatureValid;
    try {
      signatureValid = jwt.verify(verifiers.forHeader(header));
    } catch (JOSEException failed) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.SIGNATURE_INVALID,
          "the signature could not be checked",
          failed);
    }
    if (!signatureValid) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.SIGNATURE_INVALID, "the signature did not verify");
    }

    JWTClaimsSet set;
    try {
      set = jwt.getJWTClaimsSet();
    } catch (ParseException malformed) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.CLAIMS_INVALID,
          "the payload is not a claims set",
          malformed);
    }
    return validated(set);
  }

  private JwtClaims validated(JWTClaimsSet set) {
    Date expiry = set.getExpirationTime();
    if (expiry == null) {
      // ADR-003's "mandatory exp": a token without one never expires, and accepting it is the
      // single most common JWT misconfiguration.
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.EXPIRED, "the token carries no exp claim");
    }
    if (expiry.toInstant().plus(profile.clockSkew()).isBefore(Instant.now())) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.EXPIRED, "the token expired beyond the permitted skew");
    }
    if (!profile.issuer().equals(set.getIssuer())) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.ISSUER_MISMATCH,
          "the token's iss is not the one this profile requires");
    }
    List<String> audience = set.getAudience();
    if (audience == null || !audience.contains(profile.audience())) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.AUDIENCE_MISMATCH,
          "the token's aud does not contain the one this profile requires");
    }
    String subject = set.getSubject();
    if (subject == null || subject.isBlank()) {
      throw JwtVerificationException.of(
          JwtVerificationException.Reason.CLAIMS_INVALID, "the token carries no sub claim");
    }

    Map<String, String> custom = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : set.getClaims().entrySet()) {
      if (!REGISTERED.contains(entry.getKey()) && entry.getValue() != null) {
        custom.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    Date issued = set.getIssueTime();
    return JwtClaims.verified(
        subject,
        profile.issuer(),
        new LinkedHashSet<>(audience),
        issued == null ? Instant.EPOCH : issued.toInstant(),
        expiry.toInstant(),
        custom);
  }

  /** The claims this library validates itself and therefore does not repeat as custom claims. */
  private static final Set<String> REGISTERED = Set.of("iss", "sub", "aud", "exp", "iat", "nbf");

  /** For diagnostics only; never renders key material. */
  Optional<String> signingKeyId() {
    return Optional.ofNullable(signingKeyId);
  }

  /** The algorithm this engine accepts, for rendering. */
  String algorithmName() {
    return algorithm.getName();
  }
}
