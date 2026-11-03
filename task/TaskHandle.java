package task;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A handle through which callers can inspect and cancel an accepted task. */
public final class TaskHandle {
    private final String id;
    private final Instant submittedAt = Instant.now();
    private final long submittedNanos = System.nanoTime();
    private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.QUEUED);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile long startedNanos;
    private volatile long completedNanos;
    private volatile Throwable failure;
    private volatile String workerName;
    private volatile int attempts;

    public TaskHandle(String id) { this.id = Objects.requireNonNull(id); }

    public String getId() { return id; }
    public TaskStatus getStatus() { return status.get(); }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Throwable getFailure() { return failure; }
    public String getWorkerName() { return workerName; }
    public int getAttempts() { return attempts; }
    public boolean isCancellationRequested() { return cancellationRequested.get(); }
    public boolean isDone() { return getStatus().isTerminal(); }

    public long getQueueWaitNanos() {
        long end = startedNanos == 0 ? System.nanoTime() : startedNanos;
        return Math.max(0, end - submittedNanos);
    }

    public long getExecutionNanos() {
        if (startedNanos == 0) return 0;
        long end = completedNanos == 0 ? System.nanoTime() : completedNanos;
        return Math.max(0, end - startedNanos);
    }

    /** Queued tasks are marked cancelled immediately; running tasks receive a cooperative request. */
    public boolean cancel() {
        while (true) {
            TaskStatus current = status.get();
            if (current.isTerminal()) return false;
            cancellationRequested.set(true);
            if (current == TaskStatus.QUEUED) {
                if (status.compareAndSet(TaskStatus.QUEUED, TaskStatus.CANCELLED)) {
                    finishTimestamps();
                    return true;
                }
            } else {
                return true; // Running tasks must cooperate by checking TaskContext.
            }
        }
    }

    public boolean tryStart(String workerName) {
        if (cancellationRequested.get()) {
            markCancelled();
            return false;
        }
        if (!status.compareAndSet(TaskStatus.QUEUED, TaskStatus.RUNNING)) return false;
        this.workerName = workerName;
        attempts++;
        this.startedAt = Instant.now();
        this.startedNanos = System.nanoTime();
        // Close the race where cancellation arrives between the initial check and RUNNING.
        if (cancellationRequested.get()) {
            markCancelled();
            return false;
        }
        return true;
    }

    /** Re-queue the same logical task after a failed attempt. */
    public boolean prepareRetry() {
        if (cancellationRequested.get()) { markCancelled(); return false; }
        boolean changed = status.compareAndSet(TaskStatus.RUNNING, TaskStatus.QUEUED);
        if (changed) { startedAt = null; startedNanos = 0; completedAt = null; completedNanos = 0; failure = null; }
        return changed;
    }

    public void markSucceeded() {
        if (cancellationRequested.get()) { markCancelled(); return; }
        if (status.compareAndSet(TaskStatus.RUNNING, TaskStatus.SUCCEEDED)) finishTimestamps();
    }

    public void markFailed(Throwable throwable) {
        Objects.requireNonNull(throwable);
        if (cancellationRequested.get()) { markCancelled(); return; }
        if (status.compareAndSet(TaskStatus.RUNNING, TaskStatus.FAILED)) {
            failure = throwable;
            finishTimestamps();
        }
    }

    public void markCancelled() {
        cancellationRequested.set(true);
        TaskStatus current;
        do {
            current = status.get();
            if (current.isTerminal()) return;
        } while (!status.compareAndSet(current, TaskStatus.CANCELLED));
        finishTimestamps();
    }

    private void finishTimestamps() {
        completedAt = Instant.now();
        completedNanos = System.nanoTime();
    }
}
