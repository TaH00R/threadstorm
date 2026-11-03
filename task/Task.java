package task;

/** A unit of work submitted to ThreadStorm. */
@FunctionalInterface
public interface Task {
    void execute(TaskContext context) throws Exception;
}
