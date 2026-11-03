package tests;

import core.ThreadStorm;
import metrics.TaskEvent;
import metrics.WorkerSnapshot;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ObservabilityTest {
    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(3, 64);
        CountDownLatch done = new CountDownLatch(30);
        engine.start();
        for (int i = 0; i < 30; i++) {
            engine.submit(context -> {
                context.checkpoint();
                Thread.sleep(5);
                context.checkpoint();
                done.countDown();
            });
        }
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Tasks did not finish");
        engine.shutdown();

        if (engine.getMetrics().getCompleted() != 30) throw new AssertionError("Expected 30 completed tasks");
        if (engine.getMetrics().getExecutionP95Millis() <= 0) throw new AssertionError("P95 should be positive");
        if (engine.getMetrics().getThroughputPerSecond() <= 0) throw new AssertionError("Throughput should be positive");

        List<WorkerSnapshot> workers = engine.getMetrics().getWorkerSnapshots();
        if (workers.size() != 3) throw new AssertionError("Expected 3 worker snapshots");
        long workerTaskTotal = 0;
        for (WorkerSnapshot worker : workers) {
            if (worker.getState().name().equals("STOPPED") == false) throw new AssertionError("Worker should be stopped");
            workerTaskTotal += worker.getTasksCompleted();
        }
        if (workerTaskTotal != 30) throw new AssertionError("Worker task totals should equal 30");
        List<TaskEvent> timeline = engine.getMetrics().getTimeline();
        if (timeline.isEmpty()) throw new AssertionError("Timeline should contain events");
        System.out.println("PASS: metrics percentiles, throughput, worker snapshots and timeline");
    }
}
