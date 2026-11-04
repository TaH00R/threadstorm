# ThreadStorm

ThreadStorm is a Java 17+ learning project that runs tasks on a custom pool of worker threads and shows what happens while they execute. It includes a bounded priority queue, cancellation, retries, graceful shutdown, live metrics, and a terminal dashboard.

It's a custom execution engine for learning about Java concurrency, not a replacement for Java's `ExecutorService` or a production task system.

## Quick start

Install a **JDK 17+** (`java -version` and `javac -version` should both work).

**Windows — PowerShell 5.1/7 or Windows Terminal:**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

**Windows — Command Prompt (cmd.exe):**

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File run.ps1
```

**Linux/macOS — Bash, Zsh, or another POSIX shell:**

```sh
./run.sh
```

The launcher builds and opens the dashboard. On Windows, `run.ps1` selects the same JDK for compilation and execution, avoiding old Java runtime conflicts.

### Using the dashboard

On **Linux/macOS terminals**, keys work immediately when raw input is available. On **Windows and terminals without raw input**, **type the key and press Enter**. This is intentional: it avoids external PowerShell keyboard readers and works across shells. Line mode refreshes when you enter a command to avoid corrupting text as you type.

| Action | Key / command |
|---|---|
| Switch pages | `1` to `7` |
| Run demo tasks | `d` |
| Submit task | `s`, then type its description |
| Navigate | `up`, `down` (arrow keys in raw mode) |
| Help | `?` |
| Quit | `q`, then `y` |
| Close an overlay | `esc` (Escape in raw mode) |

**Windows example:** type `d` and press **Enter** to submit demonstration tasks. To submit your own task, type `s` + **Enter**, then your task description + **Enter**.

### Options

```powershell
.\run.ps1 -Dashboard --workers 4 --capacity 128
.\run.ps1 -Cli              # simpler text interface
.\run.ps1 -Test             # run the engine and terminal-input tests
.\run.ps1 -NoBuild          # reuse compiled classes
```

On Linux/macOS you can use `./run.sh --workers 4 --capacity 128`. If drawing characters look wrong, add `--ascii` or `--no-color`.

PowerShell script policy blocking local scripts? The first Windows command above uses a one-time execution-policy override. It does not permanently change your machine's setting.

## What's inside?

- `core/` — workers, queue, priorities, lifecycle, and retry handling
- `task/` — tasks, handles, cancellation, and task groups
- `metrics/` — execution statistics, worker states, and events
- `cli/` — dashboard, keyboard input, and text CLI
- `benchmarks/` — local comparison with sequential and `ExecutorService` execution
- `tests/` — engine and integration tests

## Testing and limitations

Run `./test.sh` on Linux/macOS or `./run.ps1 -Test` on Windows. Benchmarks can be run with `java -cp out benchmarks.BenchmarkRunner` after building.

The dashboard needs an interactive **ANSI/VT-capable terminal**. Not every IDE output panel supports full-screen terminal control; use the text CLI with `-Cli` in those environments. Windows uses Enter-based controls rather than instant single-key capture; native single-key handling would require a dedicated cross-platform terminal library. This project has no external Java dependencies.
