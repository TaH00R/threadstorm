package core;

import task.TaskHandle;
import java.time.Instant;

/** Immutable record for a task that exhausted its retry budget. */
public final class DeadLetterEntry {
    private final TaskHandle handle;
    private final Throwable cause;
    private final Instant recordedAt = Instant.now();
    public DeadLetterEntry(TaskHandle handle, Throwable cause) { this.handle = handle; this.cause = cause; }
    public TaskHandle getHandle() { return handle; }
    public Throwable getCause() { return cause; }
    public Instant getRecordedAt() { return recordedAt; }
    @Override public String toString() { return handle.getId() + " failed permanently: " + cause; }
}
