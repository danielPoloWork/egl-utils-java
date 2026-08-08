package it.d4np.utils.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that counts what it hands out and remembers it — the instrument FR-05's
 * lifecycle claim is measured with.
 *
 * <p>"Acquires a connection per operation and closes it" is not a property of a {@code finally}
 * block being present in the source; it is a property of what happens to the connections. A real
 * pool would hide both halves — it hands back connections that were returned to it, and a {@code
 * close()} on a pooled connection does not close anything. So the tests use this instead: every
 * {@link #getConnection()} opens a genuinely new connection through {@code DriverManager} and keeps
 * the reference, and {@link #allHandedOutAreClosed()} asks the driver itself whether each one is
 * closed.
 *
 * <p>Written by hand rather than taken from H2 for the reason {@link JdbcFixtures} states: nothing
 * in this module, including its tests, names a driver type.
 *
 * <p>Not thread-safe, and it does not need to be: the tests that use it are single-threaded, and a
 * synchronized counter would be pretending the executor's own thread-safety is what is under test
 * here. It is not — {@code SimpleJdbcExecutor} holds no mutable state at all.
 */
final class CountingDataSource implements DataSource {

  private final String url;

  /** Every connection this has ever handed out, in order. */
  private final List<Connection> handedOut = new ArrayList<>();

  CountingDataSource(String url) {
    this.url = url;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection connection = JdbcFixtures.connect(url);
    handedOut.add(connection);
    return connection;
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return getConnection();
  }

  /**
   * How many connections have been requested.
   *
   * @return the count, which FR-05 says is one per operation
   */
  int handedOut() {
    return handedOut.size();
  }

  /**
   * Whether every connection handed out has since been closed.
   *
   * @return {@code true} when the driver reports all of them closed
   * @throws SQLException if the driver cannot answer
   */
  boolean allHandedOutAreClosed() throws SQLException {
    for (Connection connection : handedOut) {
      if (!connection.isClosed()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    throw new SQLFeatureNotSupportedException("not needed by these tests");
  }

  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    throw new SQLFeatureNotSupportedException("not needed by these tests");
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    throw new SQLFeatureNotSupportedException("not needed by these tests");
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return 0;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("not needed by these tests");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLFeatureNotSupportedException("not needed by these tests");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return false;
  }
}
