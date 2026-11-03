import core.SubmissionPolicy;
import core.TaskRejectedException;
import core.ThreadStorm;
import task.TaskHandle;

void main() throws Exception {
    ThreadStorm engine = new ThreadStorm(2, 3);
    CountDownLatch workersStarted = new CountDownLatch(2);
    CountDownLatch releaseWorkers = new CountDownLatch(1);
    engine.start();

    // Occupy both workers so we can fill the waiting queue deterministically.
    for (int i = 1; i <= 2; i++) {
        final int job = i;
        engine.submit(context -> {
            IO.println(context.getWorkerName() + " running gated job " + job);
            workersStarted.countDown();
            releaseWorkers.await();
        });
    }
    workersStarted.await();

    // The workers are occupied, so these three submissions fill the queue.
    for (int i = 1; i <= 3; i++) {
        engine.submit(context -> IO.println("Queued task executed: " + context.getTaskId()));
    }
    IO.println("Queue is full: " + engine.getQueueSize() + "/" + engine.getQueueCapacity());

    IO.println("\n--- REJECT policy ---");
    try {
        engine.submit(context -> {
        }, SubmissionPolicy.REJECT);
        IO.println("Unexpected: task was accepted.");
    } catch (TaskRejectedException rejected) {
        IO.println("Correctly rejected: " + rejected.getMessage());
    }

    IO.println("\n--- TIMEOUT policy ---");
    try {
        engine.submit(context -> {
        }, SubmissionPolicy.TIMEOUT, 200, TimeUnit.MILLISECONDS);
        IO.println("Unexpected: task was accepted.");
    } catch (TaskRejectedException rejected) {
        IO.println("Correctly timed out: " + rejected.getMessage());
    }

    // Free the workers; the queue drains and graceful shutdown completes.
    releaseWorkers.countDown();
    TaskHandle finalTask = engine.submit(context ->
            IO.println("Default BLOCK submission accepted: " + context.getTaskId()));
    IO.println("BLOCK submission handle: " + finalTask.getId());

    engine.submit(context -> {
        throw new IllegalStateException("Demonstration failure");
    });
    engine.shutdown();
    IO.println("Final engine state: " + engine.getState());
}
