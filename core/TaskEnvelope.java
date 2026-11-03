package core;

import task.Task;
import task.TaskHandle;

final class TaskEnvelope {
    private final Task task;
    private final TaskHandle handle;
    private final Priority priority;
    private long enqueueSequence;
    private long enqueuedNanos;
    private final RetryPolicy retryPolicy;
    private final long timeoutMillis;

    TaskEnvelope(Task task, TaskHandle handle, Priority priority) { this(task, handle, priority, RetryPolicy.NONE, 0); }
    TaskEnvelope(Task task, TaskHandle handle, Priority priority, RetryPolicy retryPolicy, long timeoutMillis) {
        this.task = task; this.handle = handle; this.priority = priority;
        this.retryPolicy = retryPolicy; this.timeoutMillis = timeoutMillis;
    }

    Task getTask() { return task; }
    TaskHandle getHandle() { return handle; }
    Priority getPriority() { return priority; }
    long getEnqueueSequence() { return enqueueSequence; }
    long getEnqueuedNanos() { return enqueuedNanos; }
    RetryPolicy getRetryPolicy() { return retryPolicy; }
    long getTimeoutMillis() { return timeoutMillis; }

    void markEnqueued(long sequence) {
        this.enqueueSequence = sequence;
        this.enqueuedNanos = System.nanoTime();
    }
}
