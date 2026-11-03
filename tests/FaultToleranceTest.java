package tests;

import core.RetryPolicy;
import core.ThreadStorm;
import task.TaskHandle;
import task.TaskStatus;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic smoke tests for retries, dead letters and cooperative timeouts. */
public final class FaultToleranceTest {
    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(2, 16);
        AtomicInteger flakyAttempts = new AtomicInteger();
        AtomicInteger permanentAttempts = new AtomicInteger();
        engine.start();

        TaskHandle flaky = engine.submit(context -> {
            if (flakyAttempts.incrementAndGet() < 3) throw new IllegalStateException("temporary failure");
        }, core.Priority.NORMAL, new RetryPolicy(3, 5, 2.0), 0, TimeUnit.MILLISECONDS);

        TaskHandle permanent = engine.submit(context -> {
            permanentAttempts.incrementAndGet();
            throw new IllegalArgumentException("permanent failure");
        }, core.Priority.NORMAL, new RetryPolicy(2, 2, 2.0), 0, TimeUnit.MILLISECONDS);

        TaskHandle timed = engine.submit(context -> {
            Thread.sleep(35);
            context.checkpoint();
        }, core.Priority.NORMAL, RetryPolicy.NONE, 5, TimeUnit.MILLISECONDS);

        engine.shutdown();
        require(flaky.getStatus() == TaskStatus.SUCCEEDED, "flaky task should eventually succeed");
        require(flaky.getAttempts() == 3, "flaky task should execute three attempts");
        require(permanent.getStatus() == TaskStatus.FAILED, "permanent task should fail");
        require(permanentAttempts.get() == 3, "initial attempt plus two retries expected");
        require(engine.getDeadLetterQueue().size() == 2, "both permanently failed tasks should be in dead-letter queue");
        require(timed.getStatus() == TaskStatus.FAILED, "cooperative timeout should fail task");
        System.out.println("PASS: retry, exponential backoff, dead-letter and cooperative timeout tests");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
