# ThreadStorm 0.6.0

**Concurrency. Control. Observability.**

ThreadStorm is a Java task-execution engine built to explore worker pools, monitor-based queues, scheduling, lifecycle management, backpressure, fault tolerance and runtime observability.

## What's new in 0.6.0 — Advanced observability

- Per-worker state: `STARTING`, `IDLE`, `BUSY`, `STOPPED`.
- Per-worker busy time, idle time, utilization percentage and completed-task count.
- Execution latency average and P50/P95/P99 percentiles.
- Queue-wait average and P95 percentile.
- Rolling 60-second and lifetime completion throughput.
- Bounded in-memory task event timeline, including worker lifecycle, task start/completion/failure and retry events.
- Immutable snapshots through `getWorkerSnapshots()` and `getTimeline()`.
- Existing queue, scheduling, cancellation, retry, cooperative timeout and dead-letter features remain available.

## Project layout

```text
threadstorm-0.6.0/
├── core/
│   ├── DeadLetterEntry.java
│   ├── DeadLetterQueue.java
│   ├── EngineState.java
│   ├── Priority.java
│   ├── RetryPolicy.java
│   ├── SubmissionPolicy.java
│   ├── TaskEnvelope.java
│   ├── TaskQueue.java
│   ├── TaskRejectedException.java
│   ├── ThreadStorm.java
│   ├── Worker.java
│   └── WorkerPool.java
├── metrics/
│   ├── ExceptionReport.java
│   ├── MetricsCollector.java
│   ├── TaskEvent.java
│   ├── WorkerSnapshot.java
│   └── WorkerState.java
├── task/
├── tests/
├── Main.java
└── README.md
```

## Build and run (Java 17+)

Run these commands from the project root in PowerShell:

```powershell
javac -Xlint:all -d out Main.java core\*.java task\*.java metrics\*.java
java -cp out Main
```

## Run tests

```powershell
javac -Xlint:all -cp out -d out tests\PrioritySchedulingTest.java tests\FaultToleranceTest.java tests\ThreadStormStressTest.java tests\ObservabilityTest.java
java -cp out tests.PrioritySchedulingTest
java -cp out tests.FaultToleranceTest
java -cp out ThreadStormStressTest
java -cp out tests.ObservabilityTest
```

## Reading metrics programmatically

```java
MetricsCollector metrics = engine.getMetrics();

System.out.println("Completed: " + metrics.getCompleted());
System.out.println("P95 execution (ms): " + metrics.getExecutionP95Millis());
System.out.println("P99 execution (ms): " + metrics.getExecutionP99Millis());
System.out.println("Throughput (tasks/sec): " + metrics.getThroughputPerSecond());

for (WorkerSnapshot worker : metrics.getWorkerSnapshots()) {
    System.out.println(worker.getName() + " " + worker.getState()
        + " utilization=" + worker.getUtilizationPercent() + "%");
}

for (TaskEvent event : metrics.getTimeline()) {
    System.out.println(event.getTimestamp() + " " + event.getEventType()
        + " task=" + event.getTaskId() + " worker=" + event.getWorkerName());
}
```

The timeline is intentionally bounded to the latest 2,000 events. Latency percentile samples are bounded to the latest 10,000 observations. This prevents observability itself from causing unbounded memory growth.

## Metric semantics and limitations

- Execution latency samples are recorded for terminal successes and failures; cancelled tasks are not included in latency percentiles.
- P50/P95/P99 use the nearest-rank method over the retained sample window, not a statistically lossless histogram.
- Rolling throughput is calculated from completions retained in the preceding 60 seconds and their observed time span. Lifetime throughput uses engine age.
- Worker utilization is measured from the worker's recorded busy and idle intervals. It is an in-process estimate, not an operating-system CPU utilization measurement.
- Metrics and timeline are in-memory and reset when the process restarts.
- Timeline events are snapshots, not a durable audit log.

## Existing design boundaries

- The dead-letter queue is in-memory; entries are lost when the process exits.
- Retries apply to thrown `Exception`s; fatal JVM `Error`s are not swallowed or retried.
- Execution timeouts are cooperative. Tasks must periodically call `context.checkpoint()`; Java cannot safely force-stop arbitrary task code.
- Retry backoff occupies the worker that owns the task. This avoids re-enqueue deadlocks with a bounded queue, at the cost of worker availability during backoff.
- ThreadStorm is an educational concurrency-engineering project, not a production replacement for `ExecutorService`.
