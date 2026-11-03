package metrics;

import java.time.Instant;

/** Immutable point-in-time event for the bounded execution timeline. */
public final class TaskEvent {
    private final String taskId;
    private final String workerName;
    private final String eventType;
    private final Instant timestamp;
    private final long timestampNanos;
    private final String detail;

    public TaskEvent(String taskId, String workerName, String eventType, String detail) {
        this.taskId = taskId;
        this.workerName = workerName;
        this.eventType = eventType;
        this.detail = detail;
        this.timestamp = Instant.now();
        this.timestampNanos = System.nanoTime();
    }

    public String getTaskId() { return taskId; }
    public String getWorkerName() { return workerName; }
    public String getEventType() { return eventType; }
    public Instant getTimestamp() { return timestamp; }
    public long getTimestampNanos() { return timestampNanos; }
    public String getDetail() { return detail; }
}
