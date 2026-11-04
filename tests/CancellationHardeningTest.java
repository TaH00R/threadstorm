package tests;

import core.ThreadStorm;
import task.TaskHandle;
import task.TaskStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verifies queued cancellation and cooperative cancellation of running work. */
public final class CancellationHardeningTest {
    private CancellationHardeningTest() { }

    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(1, 8);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch cooperativeStarted = new CountDownLatch(1);
        AtomicBoolean queuedTaskExecuted = new AtomicBoolean();
        engine.start();
        try {
            TaskHandle blocker = engine.submit(context -> {
                blockerStarted.countDown();
                releaseBlocker.await();
            });
            if (!blockerStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Blocker did not start");

            TaskHandle queued = engine.submit(context -> queuedTaskExecuted.set(true));
            if (!queued.cancel()) throw new AssertionError("Queued cancellation request was rejected");
            if (queued.await(2, TimeUnit.SECONDS) != TaskStatus.CANCELLED)
                throw new AssertionError("Queued task did not complete as CANCELLED");

            TaskHandle running = engine.submit(context -> {
                cooperativeStarted.countDown();
                while (true) {
                    context.checkpoint();
                    Thread.sleep(2);
                }
            });
            releaseBlocker.countDown();
            if (!cooperativeStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Cooperative task did not start");
            if (!running.cancel()) throw new AssertionError("Running cancellation request was rejected");
            if (running.await(5, TimeUnit.SECONDS) != TaskStatus.CANCELLED)
                throw new AssertionError("Running task did not cooperate with cancellation");
            if (queuedTaskExecuted.get()) throw new AssertionError("Cancelled queued task was executed");
            System.out.println("CANCELLATION PASS: queued cancellation and cooperative running cancellation verified.");
        } finally {
            releaseBlocker.countDown();
            engine.shutdown();
        }
    }
}
