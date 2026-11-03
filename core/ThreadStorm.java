package core;

import metrics.MetricsCollector;
import task.Task;
import task.TaskHandle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Public facade for the ThreadStorm execution engine. */
public final class ThreadStorm {
    private final Object lifecycleLock = new Object();
    private final TaskQueue queue;
    private final MetricsCollector metrics;
    private final WorkerPool workerPool;
    private final AtomicLong taskSequence = new AtomicLong();
    private volatile EngineState state = EngineState.CREATED;

    public ThreadStorm(int workerCount) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be greater than zero");
        }
        queue = new TaskQueue();
        metrics = new MetricsCollector();
        workerPool = new WorkerPool(workerCount, queue, metrics);
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (state != EngineState.CREATED) {
                throw new IllegalStateException("Engine can only be started once; current state: " + state);
            }
            state = EngineState.RUNNING;
            workerPool.start();
        }
        System.out.println("[ThreadStorm] Engine started with " + workerPool.size() + " workers");
    }

    /**
     * Submissions and the transition to SHUTTING_DOWN share the same lock.
     * Therefore a submission is either accepted before shutdown or rejected after it.
     */
    public TaskHandle submit(Task task) {
        Objects.requireNonNull(task, "task");
        synchronized (lifecycleLock) {
            if (state != EngineState.RUNNING) {
                throw new IllegalStateException("Engine is not accepting tasks; current state: " + state);
            }
            String id = String.format("TASK-%06d", taskSequence.incrementAndGet());
            TaskHandle handle = new TaskHandle(id);
            queue.submit(new TaskEnvelope(task, handle));
            metrics.recordSubmitted();
            return handle;
        }
    }

    /** Stops accepting submissions, drains accepted tasks, and waits for all workers. */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (state == EngineState.TERMINATED) return;
            if (state == EngineState.CREATED) {
                state = EngineState.TERMINATED;
                queue.shutdown();
                System.out.println("[ThreadStorm] Engine terminated before start");
                metrics.displayReport();
                return;
            }
            if (state == EngineState.RUNNING) {
                state = EngineState.SHUTTING_DOWN;
                queue.shutdown();
            }
        }

        boolean interrupted = false;
        for (;;) {
            try {
                workerPool.awaitTermination();
                break;
            } catch (InterruptedException e) {
                // Complete graceful shutdown even if the caller is interrupted.
                interrupted = true;
            }
        }

        synchronized (lifecycleLock) {
            state = EngineState.TERMINATED;
        }
        System.out.println("[ThreadStorm] All workers terminated");
        metrics.displayReport();
        if (interrupted) Thread.currentThread().interrupt();
    }

    public EngineState getState() { return state; }
    public int getQueueSize() { return queue.size(); }
    public MetricsCollector getMetrics() { return metrics; }
}
