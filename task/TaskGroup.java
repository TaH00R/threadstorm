package task;

import core.Priority;
import core.ThreadStorm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** A named collection of tasks submitted to one ThreadStorm engine. */
public final class TaskGroup {
    private final String name;
    private final ThreadStorm engine;
    private final List<TaskHandle> handles = new ArrayList<>();

    public TaskGroup(String name, ThreadStorm engine) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Group name cannot be blank");
        this.name = name;
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public String getName() { return name; }

    public TaskHandle submit(Task task) throws InterruptedException { return submit(task, Priority.NORMAL); }

    public TaskHandle submit(Task task, Priority priority) throws InterruptedException {
        TaskHandle handle = engine.submit(task, priority);
        synchronized (handles) { handles.add(handle); }
        return handle;
    }

    public List<TaskHandle> getTasks() {
        synchronized (handles) { return Collections.unmodifiableList(new ArrayList<>(handles)); }
    }

    public int size() { synchronized (handles) { return handles.size(); } }

    public int count(TaskStatus status) {
        Objects.requireNonNull(status, "status");
        synchronized (handles) { return (int) handles.stream().filter(h -> h.getStatus() == status).count(); }
    }

    public void cancelAll() { for (TaskHandle handle : getTasks()) handle.cancel(); }

    /** Waits for every task currently in the group to reach a terminal state. */
    public boolean awaitAll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        for (TaskHandle handle : getTasks()) {
            if (remaining <= 0 && !handle.isDone()) return false;
            TaskStatus result = handle.await(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            if (result == null) return false;
            remaining = deadline - System.nanoTime();
        }
        return true;
    }

    public void awaitAll() throws InterruptedException {
        for (TaskHandle handle : getTasks()) handle.await();
    }

    public boolean isComplete() {
        for (TaskHandle handle : getTasks()) if (!handle.isDone()) return false;
        return true;
    }
}
