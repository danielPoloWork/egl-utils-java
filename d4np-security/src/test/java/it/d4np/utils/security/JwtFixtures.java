package it.d4np.utils.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Key material and profiles the FR-11 suite shares.
 *
 * <p>One RSA key pair is generated per JVM rather than per test: 2048-bit generation is slow enough
 * to dominate the suite otherwise, and no test here depends on having a *fresh* pair.
 *
 * <p>Named without a {@code Test} prefix or suffix so surefire does not offer it to the JUnit
 * Platform as a test class.
 */
final class JwtFixtures {

  /** The issuer every profile below requires. */
  static final String ISSUER = "https://idp.example.com";

  /** The audience every profile below requires. */
  static final String AUDIENCE = "orders-api";

  /** 256 bits exactly, which is ADR-003's floor. */
  static final byte[] SECRET =
      "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  /** 248 bits, one byte under the floor. */
  static final byte[] SHORT_SECRET =
      "0123456789abcdef0123456789abcde".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private static final KeyPair RSA = generate();

  private JwtFixtures() {
    throw new AssertionError("no instances");
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("every JDK ships RSA", impossible);
    }
  }

  /**
   * The shared RSA private key.
   *
   * @return the private key
   */
  static RSAPrivateKey privateKey() {
    return (RSAPrivateKey) RSA.getPrivate();
  }

  /**
   * The shared RSA public key.
   *
   * @return the public key
   */
  static RSAPublicKey publicKey() {
    return (RSAPublicKey) RSA.getPublic();
  }

  /**
   * The standard hardened profile these tests verify against.
   *
   * @return the profile
   */
  static JwtProfile profile() {
    return JwtProfile.requiring(ISSUER, AUDIENCE).build();
  }

  /**
   * The RSA public key's modulus, as raw bytes.
   *
   * <p>This is what makes the algorithm-confusion test real rather than a mock: an attacker has the
   * public key, because it is public, and the attack is signing an HS256 token using those bytes as
   * the HMAC secret.
   *
   * @return the modulus bytes, which are at least 256 bytes for a 2048-bit key
   */
  static byte[] publicKeyBytes() {
    return publicKey().getEncoded();
  }

  /**
   * Base64url-decodes without padding, for the RFC 7515 vectors.
   *
   * @param value the encoded value
   * @return the bytes
   */
  static byte[] base64Url(String value) {
    return Base64.getUrlDecoder().decode(value);
  }
}
