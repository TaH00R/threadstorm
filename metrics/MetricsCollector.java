package metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MetricsCollector {
    private long submitted, started, completed, failed, cancelled;
    private long totalExecutionNanos, totalQueueWaitNanos, currentlyRunning;
    private final List<ExceptionReport> failures = new ArrayList<>();

    public synchronized void recordSubmitted() { submitted++; }
    public synchronized void recordStarted() { started++; currentlyRunning++; }
    public synchronized void recordFinished() { if (currentlyRunning > 0) currentlyRunning--; }

    public synchronized void recordSuccess(long executionNanos, long queueWaitNanos) {
        completed++; totalExecutionNanos += executionNanos; totalQueueWaitNanos += queueWaitNanos;
    }

    public synchronized void recordFailure(String taskId, String workerName, Throwable failure,
                                            long executionNanos, long queueWaitNanos) {
        failed++;
        failures.add(new ExceptionReport(taskId, workerName, failure, executionNanos));
        totalExecutionNanos += executionNanos;
        totalQueueWaitNanos += queueWaitNanos;
    }

    public synchronized void recordCancelled() { cancelled++; }
    public synchronized long getSubmitted() { return submitted; }
    public synchronized long getStarted() { return started; }
    public synchronized long getCompleted() { return completed; }
    public synchronized long getFailed() { return failed; }
    public synchronized long getCancelled() { return cancelled; }
    public synchronized long getCurrentlyRunning() { return currentlyRunning; }
    public synchronized long getPending() {
        return Math.max(0, submitted - completed - failed - cancelled - currentlyRunning);
    }
    public synchronized List<ExceptionReport> getFailures() {
        return Collections.unmodifiableList(new ArrayList<>(failures));
    }

    public synchronized void displayReport() {
        long terminal = completed + failed + cancelled;
        double avgExecutionMs = (completed + failed) == 0 ? 0
            : totalExecutionNanos / (double) (completed + failed) / 1_000_000.0;
        double avgQueueWaitMs = (completed + failed) == 0 ? 0
            : totalQueueWaitNanos / (double) (completed + failed) / 1_000_000.0;

        System.out.println("\n========== THREADSTORM REPORT ==========");
        System.out.println("Submitted:        " + submitted);
        System.out.println("Started:          " + started);
        System.out.println("Completed:        " + completed);
        System.out.println("Failed:           " + failed);
        System.out.println("Cancelled:        " + cancelled);
        System.out.println("Currently running:" + currentlyRunning);
        System.out.println("Pending:          " + getPending());
        System.out.printf("Avg execution:    %.3f ms%n", avgExecutionMs);
        System.out.printf("Avg queue wait:   %.3f ms%n", avgQueueWaitMs);
        System.out.println("Accounting:       " + terminal + "/" + submitted + " terminal");
        if (failures.isEmpty()) System.out.println("No recorded failures.");
        else for (ExceptionReport report : failures) report.display();
        System.out.println("========================================\n");
    }
}
