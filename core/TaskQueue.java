package core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Bounded monitor-based queue with priority scheduling and aging.
 * A bounded list is intentionally used so aging can be evaluated at dequeue time.
 */
public final class TaskQueue {
    private final List<TaskEnvelope> tasks = new ArrayList<>();
    private final int capacity;
    private final long agingIntervalNanos;
    private long nextSequence;
    private boolean shutdown;

    public TaskQueue(int capacity) { this(capacity, 5, TimeUnit.SECONDS); }

    public TaskQueue(int capacity, long agingInterval, TimeUnit unit) {
        if (capacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive");
        if (agingInterval <= 0) throw new IllegalArgumentException("Aging interval must be positive");
        this.capacity = capacity;
        this.agingIntervalNanos = Objects.requireNonNull(unit, "unit").toNanos(agingInterval);
        if (agingIntervalNanos <= 0) throw new IllegalArgumentException("Aging interval is too small");
    }

    public synchronized void submit(TaskEnvelope task, SubmissionPolicy policy,
                                    long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("Timeout cannot be negative");
        if (shutdown) throw new TaskRejectedException("Queue is shut down");
        if (policy == SubmissionPolicy.REJECT && tasks.size() >= capacity) {
            throw new TaskRejectedException("Queue is full (capacity=" + capacity + ")");
        }

        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        while (tasks.size() >= capacity && !shutdown) {
            if (policy == SubmissionPolicy.REJECT) {
                throw new TaskRejectedException("Queue is full (capacity=" + capacity + ")");
            }
            if (policy == SubmissionPolicy.TIMEOUT) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TaskRejectedException("Timed out waiting for queue capacity");
                waitNanos(remaining);
            } else {
                wait();
            }
        }
        if (shutdown) throw new TaskRejectedException("Queue is shut down");
        task.markEnqueued(nextSequence++);
        tasks.add(task);
        notifyAll();
    }

    public synchronized TaskEnvelope take() {
        while (tasks.isEmpty() && !shutdown) {
            try { wait(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        if (tasks.isEmpty()) return null;
        TaskEnvelope next = removeNext();
        notifyAll();
        return next;
    }

    /** Select highest effective priority; age promotes a waiting task one level per interval. */
    private TaskEnvelope removeNext() {
        long now = System.nanoTime();
        int bestIndex = 0;
        int bestRank = effectiveRank(tasks.get(0), now);
        long bestSequence = tasks.get(0).getEnqueueSequence();
        for (int i = 1; i < tasks.size(); i++) {
            TaskEnvelope candidate = tasks.get(i);
            int rank = effectiveRank(candidate, now);
            if (rank < bestRank || (rank == bestRank && candidate.getEnqueueSequence() < bestSequence)) {
                bestIndex = i;
                bestRank = rank;
                bestSequence = candidate.getEnqueueSequence();
            }
        }
        return tasks.remove(bestIndex);
    }

    private int effectiveRank(TaskEnvelope task, long now) {
        long waited = Math.max(0, now - task.getEnqueuedNanos());
        long promotions = waited / agingIntervalNanos;
        return (int) Math.max(0, task.getPriority().rank() - Math.min(3, promotions));
    }

    public synchronized void shutdown() { shutdown = true; notifyAll(); }

    private void waitNanos(long nanos) throws InterruptedException {
        wait(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
    }

    public synchronized int size() { return tasks.size(); }
    public int getCapacity() { return capacity; }
    public long getAgingIntervalNanos() { return agingIntervalNanos; }
    public synchronized boolean isShutdown() { return shutdown; }
}
