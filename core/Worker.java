package core;

import metrics.MetricsCollector;
import task.TaskContext;
import task.TaskHandle;
import task.TaskStatus;

final class Worker extends Thread {
    private final TaskQueue queue;
    private final MetricsCollector metrics;

    Worker(String name, TaskQueue queue, MetricsCollector metrics) {
        super(name);
        this.queue = queue;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        System.out.println("[" + getName() + "] Started");
        while (!isInterrupted()) {
            TaskEnvelope envelope = queue.take();
            if (envelope == null) break;

            TaskHandle handle = envelope.getHandle();
            if (!handle.tryStart(getName())) {
                if (handle.getStatus() == TaskStatus.CANCELLED) metrics.recordCancelled();
                continue;
            }

            metrics.recordStarted();
            try {
                envelope.getTask().execute(new TaskContext(handle.getId(), getName(), handle));
                handle.markSucceeded();
                if (handle.getStatus() == TaskStatus.SUCCEEDED) {
                    metrics.recordSuccess(handle.getExecutionNanos(), handle.getQueueWaitNanos());
                } else {
                    metrics.recordCancelled();
                }
            } catch (Exception failure) {
                handle.markFailed(failure);
                if (handle.getStatus() == TaskStatus.FAILED) {
                    metrics.recordFailure(handle.getId(), getName(), failure,
                        handle.getExecutionNanos(), handle.getQueueWaitNanos());
                } else {
                    metrics.recordCancelled();
                }
            } finally {
                metrics.recordFinished();
            }
        }
        System.out.println("[" + getName() + "] Stopped");
    }
}
