package it.d4np.utils.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Turns the current row of a {@link ResultSet} into one object (FR-05, RFC-0003).
 *
 * <pre>{@code
 * RowMapper<Order> toOrder = row -> new Order(row.getString("sku"), row.getInt("quantity"));
 *
 * List<Order> orders =
 *     executor.query("select sku, quantity from orders where status = ?", toOrder, "OPEN");
 * }</pre>
 *
 * <p><strong>FR-05 asks for "POJO row mapping", and this is POJO row mapping with the mapping
 * written by whoever owns the POJO.</strong> The alternative — reflecting field names onto columns
 * — loses on three counts, any one of which decides it. It needs {@code opens} or public setters,
 * which is the privilege RFC-0002 refused to demand for FR-16. It cannot be checked at compile
 * time, so a renamed column becomes a runtime surprise where a lambda becomes a compile error. And
 * it cannot meet <strong>NFR-03</strong>: the budget is ≤ 10% over a hand-written {@code ResultSet}
 * loop, and per-row reflection would spend the whole allowance before the framing was measured. A
 * lambda over the same {@code ResultSet} <em>is</em> that loop plus one virtual call, which is what
 * makes the budget a statement about statement preparation, binding and iteration rather than about
 * the mapping.
 *
 * <h2>The contract, which the library cannot enforce</h2>
 *
 * <ul>
 *   <li><strong>Read the current row; do not move the cursor.</strong> The executor positions the
 *       {@code ResultSet} and calls {@link #map(ResultSet)} once per row. A mapper that calls
 *       {@code next()} silently skips rows, and no signal reaches the caller. This is a stated
 *       obligation in the same class as RFC-0001's defensive-copy rule for {@code FluentBuilder}.
 *   <li><strong>Do not retain the {@code ResultSet}.</strong> It is closed when the operation
 *       returns, and JDBC gives a library no way to hand out a row that outlives its statement.
 *   <li><strong>Never return {@code null}.</strong> A {@code null} in a returned {@code List} is a
 *       failure that surfaces at the caller's next dereference, so the executor refuses it with
 *       {@link IllegalStateException} — exactly as {@code Lazy} refuses an initializer that returns
 *       {@code null}, and for the same reason (control C-02).
 * </ul>
 *
 * <h2>Why this one method may throw a checked exception</h2>
 *
 * <p>RFC-0001's rule is that no <em>published method of this library</em> throws a checked
 * exception, and it holds: nothing on {@link SimpleJdbcExecutor} declares one. {@link
 * #map(ResultSet)} is the other direction — a method the <strong>caller</strong> implements, whose
 * body is JDBC calls that declare {@link SQLException}. Forbidding it here would only force every
 * mapper to open with a {@code try}/{@code catch} that wraps into some exception of its own, which
 * is the translation this module already does in one place.
 *
 * <p>A thrown {@code SQLException} is translated to {@link JdbcAccessException} like any other, so
 * a mapper reading a column that is not in the {@code select} list produces the same shape of
 * failure as the query that could not run.
 *
 * <h2>Two types share this simple name</h2>
 *
 * <p>Spring ships {@code org.springframework.jdbc.core.RowMapper}, and the name is kept anyway
 * under the test item 4.0 applied to three names: rename where a wrong choice <em>compiles and
 * diverges</em>, keep where it cannot compile. Spring's method is {@code mapRow(ResultSet, int)},
 * so a lambda written for this interface does not fit it and an editor's wrong auto-import fails to
 * compile rather than changing behaviour. FR-05 names the type, and a rename would cost a
 * specification change to gain nothing.
 *
 * @param <T> the type one row becomes
 * @see SimpleJdbcExecutor
 */
@FunctionalInterface
public interface RowMapper<T> {

  /**
   * Maps the row the {@link ResultSet} is currently positioned on.
   *
   * @param row the result set, positioned on a row and open for the duration of this call; never
   *     {@code null}
   * @return the mapped object; must not be {@code null}
   * @throws SQLException if reading a column fails — translated to {@link JdbcAccessException} by
   *     the executor that called this
   */
  T map(ResultSet row) throws SQLException;
}
