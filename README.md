# ThreadStorm 0.7.0

ThreadStorm is a Java task-execution engine built to explore worker pools, monitor-based queues, scheduling, lifecycle management, backpressure, fault tolerance and runtime observability.

## What's new in 0.7.0 — Terminal Dashboard

A dependency-free ANSI terminal dashboard now lets you inspect and exercise the running engine:

- `submit <message> [priority]` submits a task (uses REJECT when the bounded queue is full).
- `demo` submits eight sample tasks.
- `workers` displays worker state, current task and utilization.
- `queue` displays queue depth and capacity.
- `metrics` displays counters, throughput and latency percentiles.
- `failures` and `deadletters` inspect failure information.
- `timeline [count]` displays recent bounded event history.
- `quit` gracefully drains accepted work and exits.

The TUI is an adapter over the public `ThreadStorm` API. It does not access or mutate the internal queue, worker threads or metrics state directly. The core engine remains usable without the CLI.

## Project layout

```text
threadstorm-0.7.0/
├── cli/ThreadStormCli.java
├── core/
├── metrics/
├── task/
├── tests/
├── Main.java
└── README.md
```

## Build and run (Java 17+)

From the project root in PowerShell:

```powershell
javac -Xlint:all -d out Main.java core\*.java task\*.java metrics\*.java cli\*.java
java -cp out Main --workers 4 --capacity 32
```

Disable ANSI color when using a terminal that does not support it:

```powershell
java -cp out Main --workers 4 --capacity 32 --no-color
```

## TUI actions

```text
help
submit hello from ThreadStorm HIGH
demo
workers
queue
metrics
failures
deadletters
timeline 30
shutdown
```

Priorities: `CRITICAL`, `HIGH`, `NORMAL`, `LOW`. `submit` uses the queue's non-blocking `REJECT` policy so the console remains responsive instead of hanging while the queue is full. The command reports a rejection as an error; it does not silently drop work.

## Run tests

Compile the project first, then:

```powershell
javac -Xlint:all -cp out -d out tests\PrioritySchedulingTest.java tests\FaultToleranceTest.java tests\ThreadStormStressTest.java tests\ObservabilityTest.java
java -cp out tests.PrioritySchedulingTest
java -cp out tests.FaultToleranceTest
java -cp out ThreadStormStressTest
java -cp out tests.ObservabilityTest
```

## Existing capabilities

- Fixed worker pool and monitor-based task queue.
- Bounded queue with BLOCK, REJECT and TIMEOUT submission policies.
- Priority scheduling with FIFO ordering within equal priority and priority aging.
- Task lifecycle, cooperative cancellation and graceful shutdown.
- Configurable retries, exponential backoff, dead-letter queue and cooperative execution timeouts.
- Worker snapshots, execution and queue-wait percentiles, throughput and bounded event timeline.

## Metric semantics and design boundaries

- Execution timeouts are cooperative. Tasks must periodically call `context.checkpoint()`; Java cannot safely force-stop arbitrary task code.
- Retry backoff occupies the worker that owns the task. This avoids re-enqueue deadlocks with a bounded queue, at the cost of worker availability during backoff.
- The dead-letter queue and metrics are in-memory and reset when the process exits.
- Percentiles are nearest-rank calculations over a bounded retained sample window, not statistically lossless histograms.
- Worker utilization is an in-process busy/idle estimate, not operating-system CPU utilization.
- The TUI is intentionally a local interactive adapter, not a network service or remote-control protocol.
- ThreadStorm is an educational concurrency-engineering project, not a production replacement for `ExecutorService`.


