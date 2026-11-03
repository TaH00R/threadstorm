# ThreadStorm 0.2.0

A custom Java concurrent task execution engine focused on concurrency, task lifecycle management, and observability.

## Requirements
- JDK 17 or newer

## Project layout

```text
threadstorm/
├── core/
├── metrics/
├── task/
└── Main.java
```

Packages intentionally live at the repository root; there is no `src/` directory.

## Compile and run (Windows PowerShell)

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac -d out Main.java core\*.java task\*.java metrics\*.java
java -cp out Main
```

## Compile and run (macOS/Linux)

```bash
mkdir -p out
javac -d out Main.java core/*.java task/*.java metrics/*.java
java -cp out Main
```

## 0.2.0 features
- Custom persistent worker pool
- Shared FIFO task queue using `wait()` / `notifyAll()`
- Unique task IDs and inspectable `TaskHandle`
- Task lifecycle states: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`
- Coordinated submission and shutdown transition
- Graceful shutdown that drains accepted tasks
- Cooperative cancellation support through `TaskContext`
- Thread-safe aggregate metrics and failure reports

## Important limitations
- Cancellation of a running task is cooperative: task code must check `TaskContext.isCancellationRequested()` or call `throwIfCancellationRequested()`.
- This version has no bounded queue, priority scheduler, automatic retry, persistence, or dashboard yet.
- Metrics are in-memory and reset when the process exits.
- This is an educational prototype, not a production-ready executor.
