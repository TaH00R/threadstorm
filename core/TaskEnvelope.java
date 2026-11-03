package core;

import task.Task;
import task.TaskHandle;

final class TaskEnvelope {
    private final Task task;
    private final TaskHandle handle;

    TaskEnvelope(Task task, TaskHandle handle) {
        this.task = task;
        this.handle = handle;
    }

    Task getTask() { return task; }
    TaskHandle getHandle() { return handle; }
}
