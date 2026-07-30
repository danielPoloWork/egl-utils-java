package it.d4np.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Java-serialisation round trip, for the tests that assert the {@link Serializable} contract {@link
 * BusinessException} inherits from {@link Throwable}.
 *
 * <p>Named without a {@code Test} prefix or suffix on purpose: surefire's default include patterns
 * are {@code Test*}, {@code *Test}, {@code *Tests} and {@code *TestCase}, and a support class that
 * matches one of them is offered to the JUnit Platform as a test class.
 */
final class SerializationSupport {

  private SerializationSupport() {}

  /**
   * Serialises {@code original} and reads it back.
   *
   * @param <T> the serialisable type
   * @param original the instance to round-trip
   * @return a fresh instance restored from the byte stream
   * @throws IOException if the byte stream cannot be written or read
   * @throws ClassNotFoundException if the restored type is not on the classpath
   */
  static <T extends Serializable> T roundTrip(T original)
      throws IOException, ClassNotFoundException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(original);
    }
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      @SuppressWarnings("unchecked") // the stream was written from a T two statements ago
      T restored = (T) in.readObject();
      return restored;
    }
  }
}
