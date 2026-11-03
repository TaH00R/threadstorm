package metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe, bounded in-memory metrics and event collector. */
public final class MetricsCollector {
    private static final int MAX_LATENCY_SAMPLES = 10_000;
    private static final int MAX_TIMELINE_EVENTS = 2_000;
    private static final long THROUGHPUT_WINDOW_NANOS = 60_000_000_000L;

    private long submitted, started, completed, failed, cancelled;
    private long totalExecutionNanos, totalQueueWaitNanos, currentlyRunning;
    private final long engineStartedNanos = System.nanoTime();
    private final List<ExceptionReport> failures = new ArrayList<>();
    private final Deque<Long> executionSamples = new ArrayDeque<>();
    private final Deque<Long> queueWaitSamples = new ArrayDeque<>();
    private final Deque<Long> completionTimes = new ArrayDeque<>();
    private final Deque<TaskEvent> timeline = new ArrayDeque<>();
    private final Map<String, WorkerData> workers = new LinkedHashMap<>();

    private static final class WorkerData {
        WorkerState state = WorkerState.STARTING;
        String taskId;
        long stateSince = System.nanoTime();
        long busyNanos;
        long idleNanos;
        long tasksCompleted;
        long stoppedAt;
    }

    public synchronized void recordSubmitted() { submitted++; }

    public synchronized void registerWorker(String name) {
        workers.putIfAbsent(name, new WorkerData());
        addEvent(null, name, "WORKER_STARTED", "Worker thread started");
    }

    public synchronized void workerIdle(String name) {
        transitionWorker(name, WorkerState.IDLE, null);
    }

    public synchronized void workerBusy(String name, String taskId) {
        transitionWorker(name, WorkerState.BUSY, taskId);
        addEvent(taskId, name, "TASK_STARTED", "Task execution started");
    }

    public synchronized void workerStopped(String name) {
        transitionWorker(name, WorkerState.STOPPED, null);
        WorkerData data = workers.get(name);
        if (data != null) data.stoppedAt = System.nanoTime();
        addEvent(null, name, "WORKER_STOPPED", "Worker thread stopped");
    }

    private void transitionWorker(String name, WorkerState next, String taskId) {
        WorkerData data = workers.computeIfAbsent(name, ignored -> new WorkerData());
        long now = System.nanoTime();
        long elapsed = Math.max(0, now - data.stateSince);
        if (data.state == WorkerState.BUSY) data.busyNanos += elapsed;
        else if (data.state == WorkerState.IDLE || data.state == WorkerState.STARTING) data.idleNanos += elapsed;
        data.state = next;
        data.taskId = taskId;
        data.stateSince = now;
    }

    public synchronized void recordStarted() { started++; currentlyRunning++; }
    public synchronized void recordFinished() { if (currentlyRunning > 0) currentlyRunning--; }

    public synchronized void workerTaskFinished(String name, String taskId) {
        WorkerData data = workers.get(name);
        if (data != null) data.tasksCompleted++;
        addEvent(taskId, name, "TASK_FINISHED", "Worker finished processing task");
    }

    public synchronized void recordRetry(String taskId, String workerName, int attempt, long delayMillis) {
        addEvent(taskId, workerName, "TASK_RETRY", "Retry attempt " + attempt + " scheduled after " + delayMillis + " ms");
    }

    public synchronized void recordSuccess(long executionNanos, long queueWaitNanos) {
        recordSuccess(null, null, executionNanos, queueWaitNanos);
    }

    public synchronized void recordSuccess(String taskId, String workerName, long executionNanos, long queueWaitNanos) {
        completed++;
        totalExecutionNanos += Math.max(0, executionNanos);
        totalQueueWaitNanos += Math.max(0, queueWaitNanos);
        addSamples(executionNanos, queueWaitNanos);
        completionTimes.addLast(System.nanoTime());
        trimThroughputWindow();
        addEvent(taskId, workerName, "TASK_SUCCEEDED", "Task completed successfully");
    }

    public synchronized void recordFailure(String taskId, String workerName, Throwable failure,
                                            long executionNanos, long queueWaitNanos) {
        failed++;
        failures.add(new ExceptionReport(taskId, workerName, failure, executionNanos));
        totalExecutionNanos += Math.max(0, executionNanos);
        totalQueueWaitNanos += Math.max(0, queueWaitNanos);
        addSamples(executionNanos, queueWaitNanos);
        completionTimes.addLast(System.nanoTime());
        trimThroughputWindow();
        addEvent(taskId, workerName, "TASK_FAILED", failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    public synchronized void recordCancelled() {
        cancelled++;
        addEvent(null, null, "TASK_CANCELLED", "Task was cancelled");
    }

    private void addSamples(long executionNanos, long queueWaitNanos) {
        executionSamples.addLast(Math.max(0, executionNanos));
        queueWaitSamples.addLast(Math.max(0, queueWaitNanos));
        while (executionSamples.size() > MAX_LATENCY_SAMPLES) executionSamples.removeFirst();
        while (queueWaitSamples.size() > MAX_LATENCY_SAMPLES) queueWaitSamples.removeFirst();
    }

    private void addEvent(String taskId, String worker, String type, String detail) {
        timeline.addLast(new TaskEvent(taskId, worker, type, detail));
        while (timeline.size() > MAX_TIMELINE_EVENTS) timeline.removeFirst();
    }

    private void trimThroughputWindow() {
        long cutoff = System.nanoTime() - THROUGHPUT_WINDOW_NANOS;
        while (!completionTimes.isEmpty() && completionTimes.peekFirst() < cutoff) completionTimes.removeFirst();
    }

    private static double percentileMillis(Deque<Long> samples, double percentile) {
        if (samples.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index) / 1_000_000.0;
    }

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
    public synchronized double getAverageExecutionMillis() {
        long count = completed + failed;
        return count == 0 ? 0 : totalExecutionNanos / (double) count / 1_000_000.0;
    }
    public synchronized double getAverageQueueWaitMillis() {
        long count = completed + failed;
        return count == 0 ? 0 : totalQueueWaitNanos / (double) count / 1_000_000.0;
    }
    public synchronized double getExecutionP50Millis() { return percentileMillis(executionSamples, 0.50); }
    public synchronized double getExecutionP95Millis() { return percentileMillis(executionSamples, 0.95); }
    public synchronized double getExecutionP99Millis() { return percentileMillis(executionSamples, 0.99); }
    public synchronized double getQueueWaitP95Millis() { return percentileMillis(queueWaitSamples, 0.95); }
    public synchronized double getThroughputPerSecond() {
        trimThroughputWindow();
        if (completionTimes.isEmpty()) return 0;
        long observedNanos = Math.max(1_000_000_000L, System.nanoTime() - completionTimes.peekFirst());
        return completionTimes.size() / (observedNanos / 1_000_000_000.0);
    }
    public synchronized double getLifetimeThroughputPerSecond() {
        double elapsed = (System.nanoTime() - engineStartedNanos) / 1_000_000_000.0;
        return elapsed <= 0 ? 0 : (completed + failed) / elapsed;
    }

    public synchronized List<WorkerSnapshot> getWorkerSnapshots() {
        long now = System.nanoTime();
        List<WorkerSnapshot> result = new ArrayList<>();
        for (Map.Entry<String, WorkerData> entry : workers.entrySet()) {
            WorkerData data = entry.getValue();
            long busy = data.busyNanos;
            long idle = data.idleNanos;
            long endpoint = data.state == WorkerState.STOPPED ? data.stoppedAt : now;
            long current = Math.max(0, endpoint - data.stateSince);
            if (data.state == WorkerState.BUSY) busy += current;
            else if (data.state == WorkerState.IDLE || data.state == WorkerState.STARTING) idle += current;
            long total = busy + idle;
            double utilization = total == 0 ? 0 : busy * 100.0 / total;
            result.add(new WorkerSnapshot(entry.getKey(), data.state, data.taskId,
                busy, idle, data.tasksCompleted, utilization));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized List<TaskEvent> getTimeline() {
        return Collections.unmodifiableList(new ArrayList<>(timeline));
    }

    public synchronized void displayReport() {
        long terminal = completed + failed + cancelled;
        System.out.println("\n========== THREADSTORM OBSERVABILITY REPORT ==========");
        System.out.println("Tasks: submitted=" + submitted + ", started=" + started + ", completed=" + completed
            + ", failed=" + failed + ", cancelled=" + cancelled + ", running=" + currentlyRunning
            + ", pending=" + getPending());
        System.out.printf("Execution latency: avg=%.3f ms | p50=%.3f ms | p95=%.3f ms | p99=%.3f ms%n",
            getAverageExecutionMillis(), getExecutionP50Millis(), getExecutionP95Millis(), getExecutionP99Millis());
        System.out.printf("Queue wait:        avg=%.3f ms | p95=%.3f ms%n",
            getAverageQueueWaitMillis(), getQueueWaitP95Millis());
        System.out.printf("Throughput:        %.3f tasks/sec (last 60s) | %.3f tasks/sec (lifetime)%n",
            getThroughputPerSecond(), getLifetimeThroughputPerSecond());
        System.out.println("Accounting:        " + terminal + "/" + submitted + " terminal");
        System.out.println("\nWorkers:");
        for (WorkerSnapshot worker : getWorkerSnapshots()) {
            System.out.printf("  %-12s %-8s utilization=%6.2f%% busy=%.2fs idle=%.2fs current=%s%n",
                worker.getName(), worker.getState(), worker.getUtilizationPercent(),
                worker.getBusyNanos() / 1e9, worker.getIdleNanos() / 1e9,
                worker.getCurrentTaskId() == null ? "-" : worker.getCurrentTaskId());
        }
        System.out.println("Timeline events retained: " + timeline.size() + " (max " + MAX_TIMELINE_EVENTS + ")");
        if (failures.isEmpty()) System.out.println("No recorded failures.");
        else for (ExceptionReport report : failures) report.display();
        System.out.println("======================================================\n");
    }
}
