package tests;

import core.ThreadStorm;
import task.TaskStatus;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadStormStressTest {
    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(4, 8);
        AtomicInteger count = new AtomicInteger();
        engine.start();
        Thread[] producers = new Thread[4];
        for (int p = 0; p < producers.length; p++) {
            producers[p] = new Thread(() -> {
                for (int i = 0; i < 250; i++) {
                    try { engine.submit(ctx -> count.incrementAndGet()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            });
            producers[p].start();
        }
        for (Thread producer : producers) producer.join();
        Thread s1 = new Thread(engine::shutdown);
        Thread s2 = new Thread(engine::shutdown);
        s1.start(); s2.start();
        s1.join(10000); s2.join(10000);
        if (s1.isAlive() || s2.isAlive()) throw new AssertionError("Concurrent shutdown hung");
        if (count.get() != 1000) throw new AssertionError("Lost tasks: " + count.get());
        if (engine.getMetrics().getSubmitted() != 1000) throw new AssertionError("Submission count mismatch");
        if (engine.getMetrics().getCompleted() != 1000) throw new AssertionError("Completion count mismatch");
        System.out.println("STRESS PASS: " + count.get() + " tasks; concurrent shutdown completed.");
    }
}
