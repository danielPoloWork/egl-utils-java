package it.d4np.utils.concurrent;

import it.d4np.utils.FluentBuilder;
import it.d4np.utils.Nullable;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * The immutable description of a pool, and the only way to configure one (FR-08, RFC-0004 §FR-08).
 *
 * <p><strong>Four parameters are mandatory and one of them is the point.</strong> FR-08 requires an
 * <em>explicit</em> {@link RejectedExecutionHandler} — and in the pool shape most readers have in
 * mind that handler can never run. {@code Executors.newFixedThreadPool} hands {@code
 * ThreadPoolExecutor} an <strong>unbounded</strong> queue, which never refuses a task, so
 * configuring a rejection policy over it is decoration and the threat model's <em>rejection
 * storm</em> row is mitigated by nothing. The queue capacity is therefore a required parameter with
 * no defaulting overload: this is FR-05's structural move — {@code SimpleJdbcExecutor} makes SQL
 * injection unavailable by not offering a concatenating overload — applied to a second requirement.
 *
 * <p>A caller who genuinely wants unbounded buffering says so with {@link Integer#MAX_VALUE}. That
 * is then a decision in their source rather than a default they inherited, which is the whole
 * difference.
 *
 * <p><strong>Validation is accumulated, not fail-fast.</strong> The builder extends {@link
 * FluentBuilder}, so a spec missing both a queue capacity and a rejection policy reports both in
 * one {@link it.d4np.utils.BuilderValidationException} rather than one per build attempt (FR-02,
 * ADR-0017). With nine parameters that is the difference between one round-trip and four.
 *
 * <p><strong>Thread safety.</strong> This type is immutable and safe to share. Its {@link Builder}
 * is <em>not</em>: a builder is a single-threaded construction idiom, and two threads configuring
 * one cannot agree on what they are building.
 *
 * @see CustomThreadPoolFactory
 * @see ManagedThreadPool
 */
public final class ThreadPoolSpec {

  private final String name;
  private final int coreThreads;
  private final int maxThreads;
  private final int queueCapacity;
  private final Duration keepAlive;
  private final Duration drainTimeout;
  private final RejectedExecutionHandler rejectionPolicy;
  private final boolean daemon;
  private final OptionalInt priority;
  private final Optional<Thread.UncaughtExceptionHandler> uncaughtExceptionHandler;

  private ThreadPoolSpec(Builder builder) {
    this.name = builder.name;
    this.coreThreads = builder.coreThreads;
    this.maxThreads = builder.maxThreads;
    this.queueCapacity = builder.queueCapacity;
    this.keepAlive = builder.keepAlive;
    // The two mandatory REFERENCE fields are @Nullable on the builder and non-null here, and
    // FluentBuilder.build() guarantees validate() ran first -- a guarantee that lives in another
    // class and that NullAway therefore cannot see. These are assertions of that contract, not
    // defensive padding: they fire only if construct() is ever reached without validate(), which
    // FluentBuilder's final build() exists to prevent. Every FluentBuilder subclass with a
    // mandatory reference field pays exactly this line.
    this.drainTimeout =
        Objects.requireNonNull(builder.drainTimeout, "drainTimeout was not validated");
    this.rejectionPolicy =
        Objects.requireNonNull(builder.rejectionPolicy, "rejectionPolicy was not validated");
    this.daemon = builder.daemon;
    this.priority = builder.priority;
    this.uncaughtExceptionHandler = Optional.ofNullable(builder.uncaughtExceptionHandler);
  }

  /**
   * Starts a spec for a pool with the given name.
   *
   * <p>The name is mandatory because the whole operational value of FR-08's "named pools" is a
   * readable thread dump: {@code pool-3-thread-7} is the state this requirement exists to replace.
   *
   * @param name the pool's name; threads are named {@code <name>-1}, {@code <name>-2}, and so on
   * @return a new builder
   */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /**
   * The pool's name, as it appears in thread names and in a thread dump.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  /**
   * Threads kept alive even when idle.
   *
   * @return the core pool size
   */
  public int coreThreads() {
    return coreThreads;
  }

  /**
   * The ceiling on simultaneously live threads.
   *
   * @return the maximum pool size
   */
  public int maxThreads() {
    return maxThreads;
  }

  /**
   * How many tasks may wait before the rejection policy runs.
   *
   * <p>Bounded by construction — see the class Javadoc for why this is mandatory.
   *
   * @return the queue capacity
   */
  public int queueCapacity() {
    return queueCapacity;
  }

  /**
   * How long a thread above {@link #coreThreads()} survives while idle.
   *
   * @return the keep-alive duration
   */
  public Duration keepAlive() {
    return keepAlive;
  }

  /**
   * How long {@link ManagedThreadPool#close()} waits for work to drain before interrupting it.
   *
   * <p>This is the budget NFR-05 means by "graceful shutdown drains within the configured timeout".
   * It lives on the spec rather than on the caller so that two call sites cannot drain the same
   * pool with two different budgets.
   *
   * @return the drain timeout
   */
  public Duration drainTimeout() {
    return drainTimeout;
  }

  /**
   * What happens to a task submitted when the queue is full.
   *
   * <p>Mandatory, per FR-08's "explicit {@code RejectedExecutionHandler}".
   *
   * @return the rejection policy
   */
  public RejectedExecutionHandler rejectionPolicy() {
    return rejectionPolicy;
  }

  /**
   * Whether the pool's threads are daemon threads.
   *
   * <p>Defaults to {@code false} — see {@link Builder#daemon(boolean)}.
   *
   * @return {@code true} if the threads are daemons
   */
  public boolean daemon() {
    return daemon;
  }

  /**
   * The requested thread priority, if one was set.
   *
   * <p><strong>Advisory.</strong> {@link Thread#setPriority(int)} is a hint the operating system
   * may ignore, and on common Linux configurations it has no effect at all. FR-08 names the feature
   * so it is offered; no test in this module asserts scheduling behaviour, because such a test is
   * flaky by construction — and spec §6's rule cuts both ways, so the claim is simply not made.
   *
   * @return the priority, or empty if the platform default was left in place
   */
  public OptionalInt priority() {
    return priority;
  }

  /**
   * The handler for an exception that escapes a pool thread, if one was set.
   *
   * @return the handler, or empty to use {@link ManagedThreadPool}'s logging default
   */
  public Optional<Thread.UncaughtExceptionHandler> uncaughtExceptionHandler() {
    return uncaughtExceptionHandler;
  }

  /**
   * Renders the configuration, and never the handlers.
   *
   * <p>A {@link RejectedExecutionHandler} or {@link Thread.UncaughtExceptionHandler} is a
   * caller-supplied object whose {@code toString()} this library does not control, so only its
   * <em>type</em> is named (compliance control C-01). The pool name is caller-supplied at
   * construction and is bounded by {@link Builder#name}'s own validation rather than here.
   *
   * @return a diagnostic rendering
   */
  @Override
  public String toString() {
    return "ThreadPoolSpec[name="
        + name
        + ", coreThreads="
        + coreThreads
        + ", maxThreads="
        + maxThreads
        + ", queueCapacity="
        + queueCapacity
        + ", keepAlive="
        + keepAlive
        + ", drainTimeout="
        + drainTimeout
        + ", rejectionPolicy="
        + rejectionPolicy.getClass().getName()
        + ", daemon="
        + daemon
        + ", priority="
        + (priority.isPresent() ? String.valueOf(priority.getAsInt()) : "default")
        + ", uncaughtExceptionHandler="
        + uncaughtExceptionHandler.map(h -> h.getClass().getName()).orElse("default")
        + "]";
  }

  /**
   * Accumulating builder for a {@link ThreadPoolSpec}.
   *
   * <p>Not thread-safe, by the same reasoning {@link FluentBuilder} documents.
   */
  public static final class Builder extends FluentBuilder<ThreadPoolSpec> {

    /** The longest pool name accepted; long enough to be descriptive, short enough for a dump. */
    private static final int MAX_NAME_LENGTH = 48;

    private final String name;
    private int coreThreads = -1;
    private int maxThreads = -1;
    private int queueCapacity = -1;
    private Duration keepAlive = Duration.ofSeconds(60);
    @Nullable private Duration drainTimeout;
    @Nullable private RejectedExecutionHandler rejectionPolicy;
    private boolean daemon;
    private OptionalInt priority = OptionalInt.empty();
    @Nullable private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    private Builder(String name) {
      this.name = name;
    }

    /**
     * Sets the number of threads kept alive when idle.
     *
     * @param coreThreads the core pool size; must be zero or more
     * @return this builder
     */
    public Builder coreThreads(int coreThreads) {
      this.coreThreads = coreThreads;
      return this;
    }

    /**
     * Sets the ceiling on simultaneously live threads.
     *
     * @param maxThreads the maximum pool size; must be at least one and at least {@code
     *     coreThreads}
     * @return this builder
     */
    public Builder maxThreads(int maxThreads) {
      this.maxThreads = maxThreads;
      return this;
    }

    /**
     * Sets how many tasks may wait before the rejection policy runs. <strong>Mandatory.</strong>
     *
     * <p>There is deliberately no overload that omits this: an unbounded queue makes the rejection
     * policy unreachable, which is the defect this parameter exists to prevent. Pass {@link
     * Integer#MAX_VALUE} to opt into unbounded buffering explicitly.
     *
     * @param queueCapacity the bound; must be at least one
     * @return this builder
     */
    public Builder queueCapacity(int queueCapacity) {
      this.queueCapacity = queueCapacity;
      return this;
    }

    /**
     * Sets how long a thread above the core size survives while idle.
     *
     * @param keepAlive the idle timeout; must not be negative
     * @return this builder
     */
    public Builder keepAlive(Duration keepAlive) {
      this.keepAlive = keepAlive;
      return this;
    }

    /**
     * Sets the budget {@link ManagedThreadPool#close()} spends draining.
     * <strong>Mandatory.</strong>
     *
     * @param drainTimeout the drain budget; must be positive
     * @return this builder
     */
    public Builder drainTimeout(Duration drainTimeout) {
      this.drainTimeout = drainTimeout;
      return this;
    }

    /**
     * Sets what happens to a task submitted when the queue is full. <strong>Mandatory.</strong>
     *
     * <p>The JDK ships four standard policies, and <strong>two of them lose work silently</strong>:
     * {@link java.util.concurrent.ThreadPoolExecutor.DiscardPolicy} drops the new task and {@link
     * java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy} drops the oldest queued one.
     * Both are accepted here — refusing them would be unenforceable, since a discarding handler is
     * four lines of a caller's own code — but they are named so the choice is made knowingly.
     * {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} throws and {@link
     * java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} applies backpressure; either one
     * tells the caller something happened.
     *
     * @param rejectionPolicy the policy
     * @return this builder
     */
    public Builder rejectionPolicy(RejectedExecutionHandler rejectionPolicy) {
      this.rejectionPolicy = rejectionPolicy;
      return this;
    }

    /**
     * Sets whether the pool's threads are daemon threads. Defaults to {@code false}.
     *
     * <p><strong>Non-daemon is the default deliberately.</strong> A daemon pool lets the JVM exit
     * with work still in flight — silent loss, and the direct contradiction of NFR-05's drain. A
     * non-daemon pool turns a forgotten {@link ManagedThreadPool#close()} into a hang, which a
     * thread dump diagnoses in seconds. It is also {@code Executors}' own default, so a consumer's
     * mental model is unchanged.
     *
     * @param daemon {@code true} to make the threads daemons
     * @return this builder
     */
    public Builder daemon(boolean daemon) {
      this.daemon = daemon;
      return this;
    }

    /**
     * Requests a thread priority. Advisory — see {@link ThreadPoolSpec#priority()}.
     *
     * @param priority a value between {@link Thread#MIN_PRIORITY} and {@link Thread#MAX_PRIORITY}
     * @return this builder
     */
    public Builder priority(int priority) {
      this.priority = OptionalInt.of(priority);
      return this;
    }

    /**
     * Sets the handler for an exception that escapes a pool thread.
     *
     * <p>Optional only in the sense that omitting it selects {@link ManagedThreadPool}'s default,
     * which logs. There is no configuration in which an escaping exception is discarded silently: a
     * pool thread dying with nothing in the log is the classic defect this parameter addresses.
     *
     * @param handler the handler
     * @return this builder
     */
    public Builder uncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
      this.uncaughtExceptionHandler = handler;
      return this;
    }

    /**
     * Records every violation rather than the first, per {@link FluentBuilder}'s contract.
     *
     * <p>The ordering matters in one place: {@code maxThreads >= coreThreads} is only checked once
     * both have been given a value, so a spec that set neither reports two missing fields instead
     * of a confusing comparison against the {@code -1} sentinel.
     */
    @Override
    protected void validate() {
      validateName();
      validateSizes();
      validateDurations();
      require(rejectionPolicy, "rejectionPolicy");
      priority.ifPresent(
          value -> {
            if (value < Thread.MIN_PRIORITY || value > Thread.MAX_PRIORITY) {
              reject(
                  "priority must be between "
                      + Thread.MIN_PRIORITY
                      + " and "
                      + Thread.MAX_PRIORITY
                      + "; was "
                      + value);
            }
          });
    }

    private void validateName() {
      if (name.isBlank()) {
        reject("name must not be blank");
      } else if (name.length() > MAX_NAME_LENGTH) {
        reject("name must be at most " + MAX_NAME_LENGTH + " characters; was " + name.length());
      } else if (name.codePoints().anyMatch(Character::isISOControl)) {
        // A thread name reaches a thread dump and a log line. A name holding \r\n folds one line
        // into two, which is the same reasoning KeyDiagnostics, JsonDiagnostics and PageDiagnostics
        // each applied to a rendered value -- see the RFC's note on why no helper is extracted.
        reject("name must not contain control characters");
      }
    }

    private void validateSizes() {
      if (coreThreads < 0) {
        reject("coreThreads must be zero or more; was " + coreThreads);
      }
      if (maxThreads < 1) {
        reject("maxThreads must be at least 1; was " + maxThreads);
      }
      if (coreThreads >= 0 && maxThreads >= 1 && maxThreads < coreThreads) {
        reject(
            "maxThreads (" + maxThreads + ") must be at least coreThreads (" + coreThreads + ")");
      }
      if (queueCapacity < 1) {
        reject("queueCapacity must be at least 1; was " + queueCapacity);
      }
    }

    private void validateDurations() {
      if (keepAlive.isNegative()) {
        reject("keepAlive must not be negative; was " + keepAlive);
      }
      if (drainTimeout == null) {
        reject("drainTimeout must be set");
      } else if (drainTimeout.isNegative() || drainTimeout.isZero()) {
        reject("drainTimeout must be positive; was " + drainTimeout);
      }
    }

    /**
     * Builds the spec. Called only after {@link #validate()} has passed.
     *
     * @return the immutable spec
     */
    @Override
    protected ThreadPoolSpec construct() {
      return new ThreadPoolSpec(this);
    }
  }
}
