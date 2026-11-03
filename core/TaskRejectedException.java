package core;

public final class TaskRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public TaskRejectedException(String message) { super(message); }
}
