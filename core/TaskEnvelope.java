package core;

import task.Task;
import task.TaskHandle;

final class TaskEnvelope {
    private final Task task;
    private final TaskHandle handle;
    private final Priority priority;
    private long enqueueSequence;
    private long enqueuedNanos;

    TaskEnvelope(Task task, TaskHandle handle, Priority priority) {
        this.task = task;
        this.handle = handle;
        this.priority = priority;
    }

    Task getTask() { return task; }
    TaskHandle getHandle() { return handle; }
    Priority getPriority() { return priority; }
    long getEnqueueSequence() { return enqueueSequence; }
    long getEnqueuedNanos() { return enqueuedNanos; }

    void markEnqueued(long sequence) {
        this.enqueueSequence = sequence;
        this.enqueuedNanos = System.nanoTime();
    }
}
