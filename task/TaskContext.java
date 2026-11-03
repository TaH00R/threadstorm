package task;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Information and cooperative cancellation support for a running task. */
public final class TaskContext {
    private final String taskId;
    private final String workerName;
    private final TaskHandle handle;

    public TaskContext(String taskId, String workerName, TaskHandle handle) {
        this.taskId = Objects.requireNonNull(taskId);
        this.workerName = Objects.requireNonNull(workerName);
        this.handle = Objects.requireNonNull(handle);
    }

    public String getTaskId() { return taskId; }
    public String getWorkerName() { return workerName; }
    public boolean isCancellationRequested() { return handle.isCancellationRequested(); }

    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("Cancellation requested for task " + taskId);
        }
    }
}
