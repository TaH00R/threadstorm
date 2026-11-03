# ThreadStorm 0.4.0

**Concurrency. Control. Observability.**

ThreadStorm is an educational Java task execution engine built around a custom worker pool, bounded queue, and priority scheduler.

## What's new in 0.4.0

- Four priorities: `CRITICAL`, `HIGH`, `NORMAL`, `LOW`.
- Highest effective priority is selected when a worker takes its next task.
- FIFO ordering is preserved among tasks with the same effective priority.
- Starvation prevention through priority aging: every configured aging interval, a waiting task is promoted by one priority level, up to `CRITICAL`.
- Existing bounded-queue backpressure policies (`BLOCK`, `REJECT`, `TIMEOUT`) are retained.
- Existing lifecycle, cancellation, graceful shutdown and metrics are retained.

## Requirements

- Java 17+
- No external dependencies.

## Project structure

Package directories are intentionally at the project root; there is no `src/` directory.

```text
threadstorm/
├── core/
├── metrics/
├── task/
├── tests/
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

## Priority scheduling test

Compile and run after compiling the application classes:

```powershell
javac -cp out -d out tests\PrioritySchedulingTest.java
java -cp out tests.PrioritySchedulingTest
```

Expected final line:

```text
PRIORITY PASS: priority order and FIFO tie-breaking verified.
```

## API examples

```java
ThreadStorm engine = new ThreadStorm(4, 100);
engine.start();

engine.submit(task); // NORMAL priority, BLOCK policy
engine.submit(task, Priority.CRITICAL);
engine.submit(task, Priority.HIGH, SubmissionPolicy.REJECT, 0, TimeUnit.NANOSECONDS);
engine.shutdown();
```

The full overload accepts `(task, priority, submissionPolicy, timeout, timeUnit)`. Existing overloads remain source-compatible and default to `NORMAL`.

## Scheduling and starvation prevention

The queue is bounded and protected by its intrinsic monitor. Each accepted task receives a monotonically increasing enqueue sequence while holding the queue lock. On each `take()`, the queue scans the waiting tasks and chooses:

1. The task with the best effective priority.
2. For equal effective priorities, the task with the smallest enqueue sequence (FIFO).

Priority aging improves a task's effective priority by one level for every configured interval it remains queued. The default aging interval is five seconds. This means a continuously waiting `LOW` task can eventually reach `CRITICAL` effective priority, preventing indefinite starvation under sustained higher-priority arrivals.

The default constructor uses five seconds. For experiments, the queue also supports a configurable aging interval through `TaskQueue(int capacity, long agingInterval, TimeUnit unit)`.

The queue scans its bounded list when dequeuing, giving O(n) selection. This is a deliberate, simple trade-off for a bounded educational queue; a future optimization can use multiple priority queues plus an aging strategy.

## Backpressure model

- `BLOCK`: wait until a queue slot becomes available.
- `REJECT`: reject immediately when full.
- `TIMEOUT`: wait for the specified duration, then reject.
- Queue capacity limits waiting tasks, not tasks currently executing.
- All monitor waits are in `while` loops to handle spurious wakeups and competing producers/consumers.

## Important behaviour and limitations

- Priority only affects tasks that are still queued; it cannot preempt a task already running.
- FIFO is guaranteed within the same effective priority at the moment a worker selects a task. Aging can change effective priority over time.
- Aging is evaluated when a worker dequeues; no background scheduler is required.
- Cancellation remains cooperative for running tasks.
- No retries/dead-letter queue or percentile metrics yet.
- This is an educational single-process engine, not a replacement for Java's `ExecutorService`.

## Next milestone

0.5.0: fault tolerance — retries, exponential backoff, task timeouts, and a dead-letter queue.
