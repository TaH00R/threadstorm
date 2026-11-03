# ThreadStorm 0.3.0

**Concurrency. Control. Observability.**

ThreadStorm is a small Java task execution engine built around a custom worker pool and a bounded FIFO task queue.

## What's new in 0.3.0

- Configurable bounded queue capacity.
- `BLOCK`: wait until a queue slot becomes free.
- `REJECT`: fail immediately when the queue is full.
- `TIMEOUT`: wait for a configured duration, then reject.
- Producers are notified when workers remove tasks.
- Shutdown wakes blocked producers and consumers.
- In-flight submissions are coordinated with shutdown so the queue is not closed while an already-admitted submit call is still trying to enqueue.
- Existing task lifecycle, cooperative cancellation, graceful draining and basic metrics retained.

## Requirements

- Java 17+
- No external dependencies.

## Project structure

The package directories are intentionally at the project root; there is no `src/` directory.

```text
threadstorm/
├── core/
├── metrics/
├── task/
├── Main.java
├── README.md
└── .gitignore
```

## Compile and run

### Windows PowerShell

```powershell
javac -d out Main.java core\*.java task\*.java metrics\*.java
java -cp out Main
```

### Linux / macOS

```bash
javac -d out Main.java core/*.java task/*.java metrics/*.java
java -cp out Main
```

## Stress test

A dependency-free stress test submits 1,000 tasks from four producer threads and invokes shutdown concurrently from two threads.

```powershell
javac -cp out -d out tests\\ThreadStormStressTest.java
java -cp out ThreadStormStressTest
```

Expected final line:

```text
STRESS PASS: 1000 tasks; concurrent shutdown completed.
```

## API examples

```java
ThreadStorm engine = new ThreadStorm(4, 20);
engine.start();

// Default policy is BLOCK.
TaskHandle first = engine.submit(context -> {
    context.throwIfCancellationRequested();
    System.out.println("Hello from " + context.getWorkerName());
});

// Reject immediately if the queue is full.
engine.submit(task, SubmissionPolicy.REJECT);

// Wait up to 500 milliseconds for queue capacity.
engine.submit(task, SubmissionPolicy.TIMEOUT, 500, TimeUnit.MILLISECONDS);

engine.shutdown();
```

`submit` methods throw `InterruptedException`. If a submitting thread is interrupted while waiting for capacity, the task is not accepted and the interruption propagates to the caller.

## Backpressure model

The queue is protected by its intrinsic monitor:

- Producers wait while `tasks.size() >= capacity`.
- Consumers wait while the queue is empty.
- `wait()` releases the queue monitor while a thread is waiting.
- A worker calls `notifyAll()` after removing an item, waking producers.
- A producer calls `notifyAll()` after adding an item, waking consumers.
- All waits are inside `while` loops because wakeups do not guarantee that the condition is still true.

## Important behaviour

- Queue capacity limits waiting tasks, not tasks currently executing.
- `BLOCK` is the default submission policy.
- `REJECT` and expired `TIMEOUT` submissions throw `TaskRejectedException`.
- `shutdown()` stops new submissions, waits for in-flight submissions to finish admission, closes the queue, then drains accepted tasks.
- Cancellation is cooperative for running tasks. A task must check `TaskContext` to respond promptly.
- A task that ignores interruption/cancellation can delay graceful shutdown.

## Current limitations

- FIFO only; priority scheduling is not implemented yet.
- No automatic retry or dead-letter queue yet.
- Metrics are aggregate counters and averages; percentile latency metrics are not implemented.
- This is an educational single-process engine, not a replacement for `ExecutorService`.

## Next milestone

0.4.0: priority scheduling and starvation prevention.
