package task;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Per-attempt context with cooperative cancellation and timeout checks. */
public final class TaskContext {
    private final String taskId;
    private final String workerName;
    private final TaskHandle handle;
    private final long deadlineNanos;
    public TaskContext(String taskId, String workerName, TaskHandle handle) { this(taskId, workerName, handle, 0); }
    public TaskContext(String taskId, String workerName, TaskHandle handle, long timeoutMillis) {
        this.taskId = Objects.requireNonNull(taskId); this.workerName = Objects.requireNonNull(workerName);
        this.handle = Objects.requireNonNull(handle);
        this.deadlineNanos = timeoutMillis <= 0 ? 0 : System.nanoTime() + timeoutMillis * 1_000_000L;
    }
    public String getTaskId() { return taskId; }
    public String getWorkerName() { return workerName; }
    public boolean isCancellationRequested() { return handle.isCancellationRequested(); }
    public boolean isTimedOut() { return deadlineNanos > 0 && System.nanoTime() >= deadlineNanos; }
    public void throwIfCancellationRequested() { if (isCancellationRequested()) throw new CancellationException("Cancellation requested for task " + taskId); }
    public void throwIfTimedOut() throws TimeoutException { if (isTimedOut()) throw new TimeoutException("Task " + taskId + " exceeded its execution timeout"); }
    public void checkpoint() throws TimeoutException { throwIfCancellationRequested(); throwIfTimedOut(); }
}
