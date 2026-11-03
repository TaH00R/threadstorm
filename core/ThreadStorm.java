package core;

import metrics.MetricsCollector;
import task.Task;
import task.TaskHandle;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Public facade for the ThreadStorm bounded execution engine. */
public final class ThreadStorm {
    private final Object lifecycleLock = new Object();
    private final TaskQueue queue;
    private final MetricsCollector metrics = new MetricsCollector();
    private final DeadLetterQueue deadLetters = new DeadLetterQueue();
    private final WorkerPool workerPool;
    private final AtomicLong taskSequence = new AtomicLong();
    private volatile EngineState state = EngineState.CREATED;
    private int activeSubmitters;

    public ThreadStorm(int workerCount, int queueCapacity) {
        if (workerCount <= 0) throw new IllegalArgumentException("Worker count must be positive");
        this.queue = new TaskQueue(queueCapacity);
        this.workerPool = new WorkerPool(workerCount, queue, metrics, deadLetters);
    }

    /** Convenience constructor: a queue capacity of 100 is used. */
    public ThreadStorm(int workerCount) { this(workerCount, 100); }

    public void start() {
        synchronized (lifecycleLock) {
            if (state != EngineState.CREATED) {
                throw new IllegalStateException("Engine can only be started once; current state: " + state);
            }
            state = EngineState.RUNNING;
            workerPool.start();
        }
        System.out.println("[ThreadStorm] Started with " + workerPool.size()
            + " workers; queue capacity=" + queue.getCapacity());
    }

    /** Default submission policy is BLOCK: wait until queue capacity is available. */
    public TaskHandle submit(Task task) throws InterruptedException {
        return submit(task, Priority.NORMAL, SubmissionPolicy.BLOCK, 0, TimeUnit.NANOSECONDS);
    }

    public TaskHandle submit(Task task, Priority priority) throws InterruptedException {
        return submit(task, priority, SubmissionPolicy.BLOCK, 0, TimeUnit.NANOSECONDS);
    }

    /** Submit with retry and cooperative execution-timeout configuration. */
    public TaskHandle submit(Task task, Priority priority, RetryPolicy retryPolicy, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout cannot be negative");
        return submitConfigured(task, priority, SubmissionPolicy.BLOCK, 0, TimeUnit.NANOSECONDS, retryPolicy, unit.toMillis(timeout));
    }


    public TaskHandle submit(Task task, SubmissionPolicy policy) throws InterruptedException {
        return submit(task, Priority.NORMAL, policy, 0, TimeUnit.NANOSECONDS);
    }

    public TaskHandle submit(Task task, SubmissionPolicy policy, long timeout, TimeUnit unit)
            throws InterruptedException {
        return submit(task, Priority.NORMAL, policy, timeout, unit);
    }

    public TaskHandle submit(Task task, Priority priority, SubmissionPolicy policy, long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(unit, "unit");

        return submitConfigured(task, priority, policy, timeout, unit, RetryPolicy.NONE, 0);
    }

    /**
     * Stop accepting new work, allow already-started submit calls to finish admission,
     * then close the queue and drain all accepted tasks.
     */
    public void shutdown() {
        boolean interrupted = false;
        synchronized (lifecycleLock) {
            if (state == EngineState.TERMINATED) return;

            if (state == EngineState.SHUTTING_DOWN) {
                // Another caller owns shutdown. Wait for it instead of joining workers
                // before the owner has closed the queue.
                while (state != EngineState.TERMINATED) {
                    try { lifecycleLock.wait(); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return;
            }

            if (state == EngineState.CREATED) {
                state = EngineState.TERMINATED;
                queue.shutdown();
                lifecycleLock.notifyAll();
                System.out.println("[ThreadStorm] Terminated before start");
                metrics.displayReport();
                return;
            }

            state = EngineState.SHUTTING_DOWN;
            while (activeSubmitters > 0) {
                try { lifecycleLock.wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            queue.shutdown();
        }

        for (;;) {
            try {
                workerPool.awaitTermination();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        synchronized (lifecycleLock) {
            state = EngineState.TERMINATED;
            lifecycleLock.notifyAll();
        }
        System.out.println("[ThreadStorm] All workers terminated");
        metrics.displayReport();
        if (interrupted) Thread.currentThread().interrupt();
    }


    private TaskHandle submitConfigured(Task task, Priority priority, SubmissionPolicy policy, long timeout, TimeUnit unit, RetryPolicy retryPolicy, long executionTimeoutMillis) throws InterruptedException {
        Objects.requireNonNull(task, "task"); Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(policy, "policy"); Objects.requireNonNull(unit, "unit");
        synchronized (lifecycleLock) {
            if (state != EngineState.RUNNING) throw new IllegalStateException("Engine is not accepting tasks; state=" + state);
            activeSubmitters++;
        }
        TaskHandle handle = new TaskHandle(String.format("TASK-%06d", taskSequence.incrementAndGet()));
        try {
            queue.submit(new TaskEnvelope(task, handle, priority, retryPolicy, executionTimeoutMillis), policy, timeout, unit);
            metrics.recordSubmitted();
            return handle;
        } finally {
            synchronized (lifecycleLock) { activeSubmitters--; lifecycleLock.notifyAll(); }
        }
    }

    public DeadLetterQueue getDeadLetterQueue() { return deadLetters; }
    public EngineState getState() { return state; }
    public int getQueueSize() { return queue.size(); }
    public int getQueueCapacity() { return queue.getCapacity(); }
    public long getQueueAgingIntervalNanos() { return queue.getAgingIntervalNanos(); }
    public MetricsCollector getMetrics() { return metrics; }
}
