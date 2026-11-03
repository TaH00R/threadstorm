package tests;

import core.Priority;
import core.ThreadStorm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class PrioritySchedulingTest {
    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(1, 20);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        engine.start();
        engine.submit(context -> { started.countDown(); release.await(); });
        started.await();
        engine.submit(context -> order.add("LOW-1"), Priority.LOW);
        engine.submit(context -> order.add("HIGH-1"), Priority.HIGH);
        engine.submit(context -> order.add("HIGH-2"), Priority.HIGH);
        engine.submit(context -> order.add("CRITICAL-1"), Priority.CRITICAL);
        release.countDown();
        engine.shutdown();
        List<String> expected = List.of("CRITICAL-1", "HIGH-1", "HIGH-2", "LOW-1");
        if (!order.equals(expected)) {
            throw new AssertionError("Expected " + expected + " but got " + order);
        }
        System.out.println("PRIORITY PASS: priority order and FIFO tie-breaking verified.");
    }
}
