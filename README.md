# ThreadStorm 0.9.0 — Task Groups & Dependency Coordination

ThreadStorm is a Java concurrent task execution engine with a terminal-native monitoring interface. Version 0.9.0 adds task groups, terminal completion signals, and success-gated dependency submission while preserving the terminal-native interface, benchmark harness, and engine features.

## Requirements

- Java 17 or later
- Windows PowerShell, macOS Terminal, or Linux terminal
- No third-party dependencies

## Compile the project

From the project root:

```powershell
javac -Xlint:all -d out Main.java core\*.java task\*.java metrics\*.java cli\*.java benchmarks\*.java
```

## Launch the terminal dashboard

```powershell
java -cp out Main --workers 4 --capacity 32
```

Disable ANSI colors if your terminal does not support them:

```powershell
java -cp out Main --workers 4 --capacity 32 --no-color
```

## Run the benchmark suite

The benchmark compares three implementations using the same deterministic CPU-bound task workload:

1. Sequential execution
2. A fixed-size `ExecutorService`
3. ThreadStorm

```powershell
java -cp out benchmarks.BenchmarkRunner --tasks 2000 --work 20000 --workers 4 --warmups 1 --runs 3 --csv benchmark-results.csv
```

Options:

| Option | Default | Meaning |
|---|---:|---|
| `--tasks` | 2000 | Number of tasks per measured run |
| `--work` | 20000 | CPU mixing iterations per task |
| `--workers` | Available processors (minimum 2) | Worker count for pooled implementations |
| `--warmups` | 1 | Warm-up rounds, excluded from reported results |
| `--runs` | 3 | Measured repetitions |
| `--csv` | benchmark-results.csv | Output CSV path |

The harness reports elapsed time and throughput (tasks/second), then writes each measured run to CSV. The benchmark uses the same workload function for all three implementations and consumes results through a checksum to reduce dead-code elimination risk.

## Important benchmarking caveats

This is a **comparative application-level harness**, not a statistically rigorous microbenchmark suite. It is useful for repeatable local experiments and identifying performance characteristics, but results depend on CPU, JVM version, garbage collection, OS scheduling, thermal state, and workload size. For publication-quality JVM microbenchmarks, add JMH with forks, warm-up iterations, measurement iterations, and profilers. Do not claim ThreadStorm is faster than `ExecutorService` unless repeated measurements on documented hardware support that claim.

The benchmark intentionally includes each framework's submission and coordination overhead because it measures end-to-end task execution through each API. ThreadStorm's startup/shutdown and task-handle checks are also part of its measured path; document this when comparing results.

## Existing test programs

Compile all project sources and tests:

```powershell
javac -Xlint:all -d out Main.java core\*.java task\*.java metrics\*.java cli\*.java benchmarks\*.java tests\*.java
```

Run the existing tests individually:

```powershell
java -cp out tests.PrioritySchedulingTest
java -cp out tests.FaultToleranceTest
java -cp out tests.ObservabilityTest
java -cp out ThreadStormStressTest
java -cp out tests.BenchmarkSmokeTest
java -cp out tests.TaskCoordinationTest
```

## Architecture

- `core/` — execution engine, queue, workers, scheduling and lifecycle
- `task/` — task contracts, handles, context and lifecycle states
- `metrics/` — bounded metrics, worker snapshots and event data
- `cli/` — terminal dashboard and command-line interface
- `benchmarks/` — comparative benchmark harness and CSV reporting
- `tests/` — standalone test programs

The TUI remains a terminal application. There is no web dashboard and the UI does not directly mutate internal worker or queue state.

## 0.9.0 — Task coordination

This release adds task groups and dependency-aware submission:

- `TaskGroup` groups related handles, supports group-wide cancellation, status counts, and bounded/unbounded waiting.
- `TaskHandle.await()` and `completion()` expose terminal completion without polling.
- `ThreadStorm.submitAfter(...)` dispatches a task only after all dependencies succeed. Dependency waiting is callback-based and does not occupy a worker thread.
- If a dependency fails or is cancelled, the dependent submission completes exceptionally and is not admitted to the engine.

Example:

```java
TaskGroup group = engine.createTaskGroup("import-pipeline");
TaskHandle download = group.submit(ctx -> downloadData());
TaskHandle validate = group.submit(ctx -> validateData());

TaskHandle publish = engine.submitAfter(
    List.of(download, validate),
    ctx -> publishResults(),
    Priority.HIGH
).get();

publish.await();
```

The current dependency API is success-gated, not a general DAG scheduler. It does not yet provide work stealing or dynamic worker scaling; those remain future scheduling experiments.

### Coordination test

```powershell
javac -cp out -d out tests\\TaskCoordinationTest.java
java -cp out tests.TaskCoordinationTest
```
