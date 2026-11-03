package core;

import java.util.concurrent.TimeUnit;

/** Retry configuration. maxRetries excludes the initial attempt. */
public final class RetryPolicy {
    public static final RetryPolicy NONE = new RetryPolicy(0, 0, 1.0);
    private final int maxRetries;
    private final long initialBackoffMillis;
    private final double multiplier;

    public RetryPolicy(int maxRetries, long initialBackoff, double multiplier) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries cannot be negative");
        if (initialBackoff < 0) throw new IllegalArgumentException("backoff cannot be negative");
        if (multiplier < 1.0 || !Double.isFinite(multiplier)) throw new IllegalArgumentException("multiplier must be finite and >= 1");
        this.maxRetries = maxRetries;
        this.initialBackoffMillis = initialBackoff;
        this.multiplier = multiplier;
    }
    public static RetryPolicy of(int maxRetries, long initialBackoff, TimeUnit unit, double multiplier) {
        return new RetryPolicy(maxRetries, unit.toMillis(initialBackoff), multiplier);
    }
    public int getMaxRetries() { return maxRetries; }
    public long backoffMillisForRetry(int retryNumber) {
        if (retryNumber <= 0 || initialBackoffMillis == 0) return 0;
        double value = initialBackoffMillis * Math.pow(multiplier, retryNumber - 1);
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) value;
    }
}
