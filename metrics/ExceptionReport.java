package metrics;

import java.time.Instant;

public final class ExceptionReport {
    private final String taskId;
    private final String workerName;
    private final String errorMessage;
    private final long executionNanos;
    private final Instant occurredAt;

    public ExceptionReport(String taskId, String workerName, Throwable failure, long executionNanos) {
        this.taskId = taskId;
        this.workerName = workerName;
        this.errorMessage = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        this.executionNanos = executionNanos;
        this.occurredAt = Instant.now();
    }

    public String getTaskId() { return taskId; }
    public String getWorkerName() { return workerName; }
    public String getErrorMessage() { return errorMessage; }
    public long getExecutionNanos() { return executionNanos; }
    public Instant getOccurredAt() { return occurredAt; }

    public void display() {
        System.out.printf("[FAILURE] %s | %s | %s | %.3f ms%n",
            taskId, workerName, errorMessage, executionNanos / 1_000_000.0);
    }
}
