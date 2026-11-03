package task;

public enum TaskStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
