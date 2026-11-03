package core;

import metrics.MetricsCollector;
import task.TaskContext;
import task.TaskHandle;
import task.TaskStatus;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

final class Worker extends Thread {
    private final TaskQueue queue;
    private final MetricsCollector metrics;
    private final DeadLetterQueue deadLetters;
    Worker(String name, TaskQueue queue, MetricsCollector metrics, DeadLetterQueue deadLetters) {
        super(name); this.queue = queue; this.metrics = metrics; this.deadLetters = deadLetters;
    }
    @Override public void run() {
        metrics.registerWorker(getName());
        System.out.println("[" + getName() + "] Started");
        while (!isInterrupted()) {
            metrics.workerIdle(getName());
            TaskEnvelope envelope = queue.take();
            if (envelope == null) break;
            TaskHandle handle = envelope.getHandle();
            if (!handle.tryStart(getName())) {
                if (handle.getStatus() == TaskStatus.CANCELLED) metrics.recordCancelled();
                continue;
            }
            metrics.recordStarted();
            metrics.workerBusy(getName(), handle.getId());
            try {
                Throwable finalFailure = null;
                int retry = 0;
                while (true) {
                    try {
                        TaskContext context = new TaskContext(handle.getId(), getName(), handle, envelope.getTimeoutMillis());
                        envelope.getTask().execute(context);
                        context.checkpoint();
                        handle.markSucceeded();
                        finalFailure = null;
                        break;
                    } catch (CancellationException cancelled) {
                        handle.markCancelled(); finalFailure = null; break;
                    } catch (Exception failure) {
                        finalFailure = failure;
                        if (retry >= envelope.getRetryPolicy().getMaxRetries() || handle.isCancellationRequested()) break;
                        retry++;
                        long delay = envelope.getRetryPolicy().backoffMillisForRetry(retry);
                        metrics.recordRetry(handle.getId(), getName(), retry, delay);
                        System.out.println("[" + getName() + "] Retry " + retry + "/" + envelope.getRetryPolicy().getMaxRetries() + " for " + handle.getId() + " after " + delay + " ms");
                        if (!handle.prepareRetry()) { finalFailure = null; break; }
                        try { Thread.sleep(delay); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); handle.markCancelled(); finalFailure = null; break; }
                        if (!handle.tryStart(getName())) { finalFailure = null; break; }
                    }
                }
                if (finalFailure != null) {
                    handle.markFailed(finalFailure);
                    if (handle.getStatus() == TaskStatus.FAILED) {
                        deadLetters.add(new DeadLetterEntry(handle, finalFailure));
                        metrics.recordFailure(handle.getId(), getName(), finalFailure, handle.getExecutionNanos(), handle.getQueueWaitNanos());
                    } else metrics.recordCancelled();
                } else if (handle.getStatus() == TaskStatus.SUCCEEDED) {
                    metrics.recordSuccess(handle.getId(), getName(), handle.getExecutionNanos(), handle.getQueueWaitNanos());
                } else if (handle.getStatus() == TaskStatus.CANCELLED) metrics.recordCancelled();
            } finally {
                metrics.recordFinished();
                metrics.workerTaskFinished(getName(), handle.getId());
            }
        }
        metrics.workerStopped(getName());
        System.out.println("[" + getName() + "] Stopped");
    }
}
