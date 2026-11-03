package core;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded FIFO queue using the object's intrinsic monitor.
 * Producers wait on "not full"; consumers wait on "not empty".
 */
public final class TaskQueue {
    private final Queue<TaskEnvelope> tasks = new ArrayDeque<>();
    private final int capacity;
    private boolean shutdown;

    public TaskQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
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
                if (remaining <= 0) {
                    throw new TaskRejectedException("Timed out waiting for queue capacity");
                }
                waitNanos(remaining);
            } else {
                wait();
            }
        }

        if (shutdown) throw new TaskRejectedException("Queue is shut down");
        tasks.add(task);
        notifyAll(); // Wake a consumer waiting for work.
    }

    public synchronized TaskEnvelope take() {
        while (tasks.isEmpty() && !shutdown) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (tasks.isEmpty()) return null;
        TaskEnvelope next = tasks.remove();
        notifyAll(); // Wake a producer waiting for a free slot.
        return next;
    }

    public synchronized void shutdown() {
        shutdown = true;
        notifyAll(); // Wake blocked producers and consumers.
    }

    private void waitNanos(long nanos) throws InterruptedException {
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        wait(millis, extraNanos);
    }

    public synchronized int size() { return tasks.size(); }
    public int getCapacity() { return capacity; }
    public synchronized boolean isShutdown() { return shutdown; }
}
