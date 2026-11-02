package core;

import task.Task;
import java.util.ArrayDeque;
import java.util.Queue;

public class TaskQueue {
    private final Queue<Task> tasks;
    private boolean shutdown;

    public TaskQueue() {
        tasks = new ArrayDeque<>();
        shutdown = false;
    }

    public synchronized void submit(Task task) {
        if (shutdown) {
            throw new IllegalStateException("Cannot submit tasks after shutdown");
        }

        tasks.add(task);
        System.out.println("[Queue] Task submitted");
        notifyAll();
    }

    public synchronized Task take() {
        while (tasks.isEmpty() && !shutdown) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        if (tasks.isEmpty()) {
            return null;
        }

        return tasks.remove();
    }

    public synchronized void shutdown() {
        shutdown = true;
        notifyAll();
    }

    public synchronized int size() {
        return tasks.size();
    }
}