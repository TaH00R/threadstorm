package core;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/** Thread-safe FIFO queue. Workers drain accepted tasks after shutdown. */
public final class TaskQueue {
    private final Queue<TaskEnvelope> tasks = new ArrayDeque<>();
    private boolean shutdown;

    public synchronized void submit(TaskEnvelope task) {
        Objects.requireNonNull(task);
        if (shutdown) throw new IllegalStateException("Task queue is shut down");
        tasks.add(task);
        notifyAll();
    }

    public synchronized TaskEnvelope take() {
        while (tasks.isEmpty() && !shutdown) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return tasks.poll();
    }

    public synchronized void shutdown() {
        shutdown = true;
        notifyAll();
    }

    public synchronized int size() { return tasks.size(); }
    public synchronized boolean isShutdown() { return shutdown; }
}
