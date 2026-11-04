package tests;

import core.TaskRejectedException;
import core.ThreadStorm;
import task.TaskHandle;
import task.TaskStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises submission/shutdown races while producers may be blocked by queue backpressure. */
public final class LifecycleHardeningTest {
    private LifecycleHardeningTest() { }

    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(2, 2);
        List<TaskHandle> accepted = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch begin = new CountDownLatch(1);
        engine.start();

        Thread[] producers = new Thread[4];
        for (int p = 0; p < producers.length; p++) {
            producers[p] = new Thread(() -> {
                ready.countDown();
                try {
                    begin.await();
                    for (int i = 0; i < 150; i++) {
                        try {
                            TaskHandle handle = engine.submit(context -> {
                                executions.incrementAndGet();
                                Thread.sleep(1);
                            });
                            accepted.add(handle);
                        } catch (IllegalStateException | TaskRejectedException stopped) {
                            rejected.incrementAndGet();
                            return;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "producer-" + p);
            producers[p].start();
        }

        if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Producers did not become ready");
        begin.countDown();
        Thread.sleep(15);
        Thread shutdown = new Thread(engine::shutdown, "shutdown-owner");
        shutdown.start();

        for (Thread producer : producers) producer.join(15_000);
        shutdown.join(15_000);
        if (shutdown.isAlive()) throw new AssertionError("Shutdown hung while producers were blocked");
        for (Thread producer : producers) if (producer.isAlive()) throw new AssertionError("Producer did not terminate");

        int acceptedCount;
        synchronized (accepted) { acceptedCount = accepted.size(); }
        if (engine.getMetrics().getSubmitted() != acceptedCount) {
            throw new AssertionError("Accepted/submitted mismatch: handles=" + acceptedCount
                + ", metrics=" + engine.getMetrics().getSubmitted());
        }
        for (TaskHandle handle : accepted) {
            if (!handle.isDone()) throw new AssertionError("Accepted task is not terminal: " + handle.getId());
        }
        if (executions.get() != engine.getMetrics().getCompleted()) {
            throw new AssertionError("Execution/completion mismatch");
        }
        if (engine.getState() != core.EngineState.TERMINATED) throw new AssertionError("Engine not terminated");
        System.out.println("LIFECYCLE PASS: " + acceptedCount + " accepted, " + rejected.get()
            + " rejected after shutdown began; all accepted tasks reached terminal states.");
    }
}
