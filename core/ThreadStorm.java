package core;

import task.Task;
import metrics.MetricsCollector;

import java.util.ArrayList;
import java.util.List;

public class ThreadStorm {
    private final TaskQueue queue;
    private final MetricsCollector metrics;
    private final List<Worker> workers;
    private boolean started;
    private boolean shutdown;

    public ThreadStorm(int workerCount) {
        if (workerCount <= 0) throw new IllegalArgumentException("Worker count must be positive");

        queue = new TaskQueue();
        metrics = new MetricsCollector();
        workers = new ArrayList<>();
        started = false;
        shutdown = false;

        for (int i = 1; i <= workerCount; i++) {
            Worker worker = new Worker("Worker-" + i, queue, metrics);
            workers.add(worker);
        }
    }

    public synchronized void start() {
        if (started) throw new IllegalStateException("Engine already started");
        if (shutdown) throw new IllegalStateException("Engine already shut down");

        started = true;
        System.out.println("[ThreadStorm] Starting engine...");
        for (Worker worker : workers) worker.start();
    }

    public void submit(Task task) {
        synchronized (this) {
            if (!started || shutdown) throw new IllegalStateException("Engine is not accepting tasks");
        }
        queue.submit(task);
    }

    public void shutdown() {
        synchronized (this) {
            if (shutdown) return;
            if (!started) throw new IllegalStateException("Start the engine before shutting it down");
            shutdown = true;
        }

        System.out.println("[ThreadStorm] Shutting down...");
        queue.shutdown();
        for (Worker worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.out.println("[ThreadStorm] All workers stopped");
        metrics.displayReport();
    }
}