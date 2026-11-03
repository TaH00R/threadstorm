package metrics;

/** Immutable worker metrics snapshot. */
public final class WorkerSnapshot {
    private final String name;
    private final WorkerState state;
    private final String currentTaskId;
    private final long busyNanos;
    private final long idleNanos;
    private final long tasksCompleted;
    private final double utilizationPercent;

    public WorkerSnapshot(String name, WorkerState state, String currentTaskId,
                          long busyNanos, long idleNanos, long tasksCompleted,
                          double utilizationPercent) {
        this.name = name;
        this.state = state;
        this.currentTaskId = currentTaskId;
        this.busyNanos = busyNanos;
        this.idleNanos = idleNanos;
        this.tasksCompleted = tasksCompleted;
        this.utilizationPercent = utilizationPercent;
    }

    public String getName() { return name; }
    public WorkerState getState() { return state; }
    public String getCurrentTaskId() { return currentTaskId; }
    public long getBusyNanos() { return busyNanos; }
    public long getIdleNanos() { return idleNanos; }
    public long getTasksCompleted() { return tasksCompleted; }
    public double getUtilizationPercent() { return utilizationPercent; }
}
