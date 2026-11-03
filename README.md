# ThreadStorm 0.5.0

**Concurrency. Control. Observability.**

ThreadStorm is a small Java task-execution engine built to explore worker pools, monitor-based queues, scheduling, task lifecycle management and fault tolerance.

## What's new in 0.5.0

- Configurable retry policies with exponential backoff.
- An in-memory dead-letter queue for tasks that exhaust their retry budget.
- Cooperative execution timeouts through `TaskContext.checkpoint()` / `throwIfTimedOut()`.
- Attempt counts exposed through `TaskHandle.getAttempts()`.
- Existing bounded queue, BLOCK/REJECT/TIMEOUT admission policies, priority scheduling with aging, cancellation and graceful shutdown remain available.

## Important timeout semantics

Java cannot safely force-stop an arbitrary task thread. ThreadStorm therefore uses **cooperative timeouts**: tasks must periodically call `context.checkpoint()` (or `throwIfTimedOut()`) during long-running work. A final checkpoint is also performed when `execute` returns. A task blocked forever in code that never checks its context cannot be forcibly timed out by this version.

Retries run on the same worker thread after the configured backoff. This deliberately avoids re-enqueueing retries into a bounded queue, which could deadlock if all workers blocked trying to reinsert retries into a full queue. The trade-off is that a worker is occupied during backoff and a retried task retains its original scheduling slot.

## Project layout

```text
threadstorm-0.5.0/
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

Run the fault-tolerance test:

```powershell
javac -cp out -d out tests\FaultToleranceTest.java
java -cp out tests.FaultToleranceTest
```

Run the existing scheduling and stress tests:

```powershell
javac -cp out -d out tests\PrioritySchedulingTest.java tests\ThreadStormStressTest.java
java -cp out tests.PrioritySchedulingTest
java -cp out tests.ThreadStormStressTest
```

## Using retries and timeouts

```java
RetryPolicy retry = new RetryPolicy(3, 100, 2.0); // 3 retries, 100ms initial backoff, x2
TaskHandle handle = engine.submit(context -> {
    context.checkpoint();
    // perform a unit of work
    context.checkpoint();
}, Priority.HIGH, retry, 2, TimeUnit.SECONDS);
```

`maxRetries` excludes the initial attempt. A value of `3` permits up to four total attempts. A timeout value of `0` disables the timeout. Dead-letter entries are available through `engine.getDeadLetterQueue().getEntries()`.

## Design boundaries

- The dead-letter queue is in-memory; entries are lost when the process exits.
- Retries apply to thrown `Exception`s; fatal JVM `Error`s are not swallowed or retried.
- Timeout enforcement is cooperative, not thread preemption.
- Retry backoff occupies the worker that owns the task.
- This is an educational/concurrency-engineering project, not a production replacement for `ExecutorService`.
