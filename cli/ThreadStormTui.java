package cli;

import core.DeadLetterEntry;
import core.EngineState;
import core.Priority;
import core.SubmissionPolicy;
import core.TaskRejectedException;
import core.ThreadStorm;
import metrics.ExceptionReport;
import metrics.TaskEvent;
import metrics.WorkerSnapshot;
import task.TaskHandle;
import task.TaskStatus;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Full-screen, keyboard-driven terminal control center for ThreadStorm. */
public final class ThreadStormTui {
    private static final String RESET = "\u001B[0m";
    private static final String CLEAR = "\u001B[2J\u001B[H";
    private static final String HIDE = "\u001B[?25l";
    private static final String SHOW = "\u001B[?25h";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String[] PAGES = {"OVERVIEW", "WORKERS", "TASKS", "METRICS", "EVENTS", "FAILURES", "HELP"};

    private final ThreadStorm engine;
    private final TerminalInput terminal;
    private final LinkedBlockingQueue<TerminalInput.Input> inputQueue = new LinkedBlockingQueue<>();
    private final LinkedHashMap<String, TaskRow> tasks = new LinkedHashMap<>();
    private final Deque<Double> throughputHistory = new ArrayDeque<>();
    private final Deque<Double> utilizationHistory = new ArrayDeque<>();
    private final boolean color;
    private final boolean ascii;
    private final int workerCount;
    private final int capacity;
    private volatile boolean inputAlive = true;
    private boolean running = true;
    private int page;
    private int selected;
    private String filter = "";
    private String notice = "Engine ready · press ? for keyboard shortcuts";
    private Overlay overlay = Overlay.NONE;
    private final StringBuilder input = new StringBuilder();
    private long lastSampleNanos;
    private long lastTaskPruneNanos;
    private String selectedTaskId;

    private enum Overlay { NONE, SUBMIT, COMMAND, SEARCH, CONFIRM_QUIT }
    private record TaskRow(TaskHandle handle, String description, Priority priority, long expectedMillis) { }

    private ThreadStormTui(int workers, int capacity, boolean color, boolean ascii, TerminalInput terminal) {
        this.workerCount = workers;
        this.capacity = capacity;
        this.color = color;
        this.ascii = ascii;
        this.terminal = terminal;
        this.engine = new ThreadStorm(workers, capacity, true);
        engine.start();
    }

    public static void main(String[] args) {
        int workers = option(args, "--workers", Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
        int capacity = option(args, "--capacity", 128);
        boolean color = !contains(args, "--no-color");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        // ASCII is the safe default on Windows; --unicode opts into box characters.
        boolean ascii = contains(args, "--ascii") || (windows && !contains(args, "--unicode"));
        // Write explicit UTF-8 instead of relying on the Windows JVM default charset.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        if (workers <= 0 || capacity <= 0) {
            System.err.println("--workers and --capacity must be positive integers.");
            return;
        }
        TerminalInput terminal = null;
        try {
            terminal = new TerminalInput();
            new ThreadStormTui(workers, capacity, color, ascii, terminal).run();
        } catch (IOException ex) {
            System.err.println("ThreadStorm TUI requires an interactive terminal: " + ex.getMessage());
            System.err.println("Try the line-oriented interface: java -cp out cli.ThreadStormCli");
        } finally {
            if (terminal != null) terminal.close();
        }
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) if (value.equals(arg)) return true;
        return false;
    }

    private static int option(String[] args, String name, int fallback) {
        for (int i = 0; i < args.length; i++) {
            if (name.equals(args[i]) && i + 1 < args.length) {
                try { return Integer.parseInt(args[i + 1]); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid value for " + name); }
            }
        }
        return fallback;
    }

    private void run() {
        if (terminal.isLineMode()) notice = "Line input: type a shortcut then Enter (e.g. d + Enter); type esc + Enter to cancel";
        System.out.print(HIDE + "\u001B[?1049h" + CLEAR);
        System.out.flush();
        Thread reader = new Thread(() -> {
            while (inputAlive) {
                try { inputQueue.put(terminal.read()); }
                catch (Exception ex) {
                    inputAlive = false;
                    System.err.println("[ThreadStorm] Terminal input failed: " + ex.getMessage());
                    // A transient/unsupported input reader must not silently tear down
                    // the dashboard. Keep the last rendered screen available and report
                    // how the user can recover or exit explicitly.
                    inputQueue.offer(new TerminalInput.Input(TerminalInput.Key.OTHER,
                        "Terminal input unavailable; restart in a real terminal or press Ctrl+C to exit."));
                }
            }
        }, "threadstorm-tui-input");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean redraw = true;
            TerminalSize size = TerminalSize.detect();
            long nextSizeCheck = System.nanoTime();
            while (running) {
                if (redraw) { render(size); redraw = false; }
                TerminalInput.Input key = inputQueue.poll(180, TimeUnit.MILLISECONDS);
                if (key != null) { handle(key); redraw = true; }
                long now = System.nanoTime();
                if (now - lastSampleNanos >= 1_000_000_000L) { sampleHistory(); if (!terminal.isLineMode()) redraw = true; }
                if (now >= nextSizeCheck) {
                    TerminalSize detected = TerminalSize.detect();
                    if (!detected.equals(size)) { size = detected; if (!terminal.isLineMode()) redraw = true; }
                    nextSizeCheck = now + TimeUnit.SECONDS.toNanos(2);
                }
                if (now - lastTaskPruneNanos >= 5_000_000_000L) { pruneTasks(); lastTaskPruneNanos = now; }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            notice = "Interrupted · shutting down";
        } finally {
            inputAlive = false;
            try { engine.shutdown(); }
            catch (RuntimeException ex) { notice = "Shutdown error: " + ex.getMessage(); }
            System.out.print(CLEAR + SHOW + "\u001B[?1049l" + RESET);
            System.out.println("ThreadStorm stopped. Thanks for keeping the threads in line.");
            System.out.flush();
        }
    }

    private void handle(TerminalInput.Input event) {
        if (overlay != Overlay.NONE) { handleOverlay(event); return; }
        switch (event.key()) {
            case CTRL_C -> running = false;
            case ESCAPE -> {
                if (page == 7) {
                    page = 2;
                    List<TaskRow> visible = visibleTasks();
                    for (int i = 0; i < visible.size(); i++) if (visible.get(i).handle().getId().equals(selectedTaskId)) { selected = i; break; }
                } else filter = "";
            }
            case TAB, RIGHT -> page = (page + 1) % PAGES.length;
            case LEFT -> page = (page + PAGES.length - 1) % PAGES.length;
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(1);
            case ENTER -> openSelected();
            case OTHER -> handleCharacter(event.text());
            default -> { }
        }
    }

    private void handleCharacter(String value) {
        if (value == null || value.isEmpty()) return;
        if (value.startsWith("Terminal input unavailable;")) { notice = value; return; }
        char key = value.charAt(0);
        if (key >= '1' && key <= '7') { page = key - '1'; selected = 0; return; }
        switch (Character.toLowerCase(key)) {
            case 'q' -> overlay = Overlay.CONFIRM_QUIT;
            case 's' -> { input.setLength(0); overlay = Overlay.SUBMIT; }
            case 'd' -> submitDemo();
            case 'c' -> cancelSelected();
            case 'r' -> { sampleHistory(); notice = "Snapshot refreshed"; }
            case '/' -> { input.setLength(0); input.append(filter); overlay = Overlay.SEARCH; }
            case ':' -> { input.setLength(0); overlay = Overlay.COMMAND; }
            case '?' -> page = 6;
            case 'g' -> { page = 0; selected = 0; }
            case 'w' -> page = 1;
            case 't' -> page = 2;
            case 'm' -> page = 3;
            case 'e' -> page = 4;
            case 'f' -> page = 5;
            case 'x' -> { filter = ""; notice = "Task filter cleared"; }
            default -> { }
        }
    }

    private void handleOverlay(TerminalInput.Input event) {
        if (event.key() == TerminalInput.Key.ESCAPE) { overlay = Overlay.NONE; input.setLength(0); return; }
        if (event.key() == TerminalInput.Key.BACKSPACE) {
            if (input.length() > 0) input.deleteCharAt(input.length() - 1);
            return;
        }
        if (event.key() == TerminalInput.Key.ENTER) {
            String value = input.toString().trim();
            Overlay current = overlay;
            overlay = Overlay.NONE;
            input.setLength(0);
            switch (current) {
                case SUBMIT -> submitFromInput(value);
                case COMMAND -> executeCommand(value);
                case SEARCH -> { filter = value; selected = 0; notice = filter.isEmpty() ? "Task filter cleared" : "Filtering tasks: " + filter; page = 2; }
                case CONFIRM_QUIT -> { if (value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes")) running = false; else notice = "Shutdown cancelled"; }
                default -> { }
            }
            return;
        }
        if (event.key() == TerminalInput.Key.OTHER && event.text() != null) {
            String text = event.text();
            if (!text.isBlank() && text.charAt(0) >= 32 && input.length() < 120) input.append(text, 0, Math.min(text.length(), 120 - input.length()));
            if (terminal.isLineMode()) handleOverlay(new TerminalInput.Input(TerminalInput.Key.ENTER, ""));
        }
    }

    private void submitFromInput(String value) {
        if (value.isBlank()) { notice = "Submission cancelled · task description cannot be empty"; return; }
        String[] parts = value.split("\\s+");
        Priority priority = Priority.NORMAL;
        int end = parts.length;
        if (parts.length > 1) {
            try { priority = Priority.valueOf(parts[parts.length - 1].toUpperCase(Locale.ROOT)); end--; }
            catch (IllegalArgumentException ignored) { }
        }
        String description = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, end)).trim();
        if (description.isBlank()) { notice = warn("Task description cannot be empty"); return; }
        submitTask(description, priority, 350);
    }

    private void submitTask(String description, Priority priority, long durationMillis) {
        try {
            TaskHandle handle = engine.submit(context -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
                while (System.nanoTime() < deadline) {
                    context.checkpoint();
                    Thread.sleep(Math.min(50, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                }
                context.checkpoint();
            }, priority, SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
            tasks.put(handle.getId(), new TaskRow(handle, description, priority, durationMillis));
            selectedTaskId = handle.getId();
            notice = good("Accepted " + handle.getId() + " · " + priority);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); notice = bad("Submission interrupted");
        } catch (TaskRejectedException | IllegalStateException ex) {
            notice = bad("Submission rejected: " + ex.getMessage());
        }
    }

    private void submitDemo() {
        int accepted = 0;
        for (int i = 1; i <= 16; i++) {
            Priority priority = switch (i % 4) {
                case 0 -> Priority.CRITICAL; case 1 -> Priority.HIGH;
                case 2 -> Priority.NORMAL; default -> Priority.LOW;
            };
            try {
                final int number = i;
                TaskHandle handle = engine.submit(context -> {
                    long duration = 250L + (number % 6) * 170L;
                    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration);
                    while (System.nanoTime() < end) { context.checkpoint(); Thread.sleep(25); }
                    if (number % 11 == 0) throw new IllegalStateException("Demo failure " + number);
                }, priority, SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
                tasks.put(handle.getId(), new TaskRow(handle, "Demo workload #" + number, priority, 250));
                selectedTaskId = handle.getId(); accepted++;
            } catch (Exception ex) { break; }
        }
        notice = good("Demo workload submitted · " + accepted + "/16 tasks accepted");
    }

    private void cancelSelected() {
        TaskRow row;
        if (page == 7 && selectedTaskId != null) row = tasks.get(selectedTaskId);
        else {
            List<TaskRow> visible = visibleTasks();
            if (visible.isEmpty()) { notice = warn("No task selected"); return; }
            row = visible.get(Math.max(0, Math.min(selected, visible.size() - 1)));
        }
        if (row == null) { notice = warn("No task selected"); return; }
        boolean cancelled = row.handle().cancel();
        notice = cancelled ? warn("Cancellation requested for " + row.handle().getId())
            : warn("Task is already terminal: " + row.handle().getStatus());
    }

    private void executeCommand(String command) {
        if (command.isBlank()) return;
        String[] parts = command.split("\\s+", 2);
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "help" -> page = 6;
            case "demo" -> submitDemo();
            case "workers" -> page = 1;
            case "tasks" -> page = 2;
            case "metrics" -> page = 3;
            case "events", "timeline" -> page = 4;
            case "failures" -> page = 5;
            case "dashboard", "overview" -> page = 0;
            case "submit" -> submitFromInput(parts.length > 1 ? parts[1] : "");
            case "cancel" -> cancelSelected();
            case "filter" -> { filter = parts.length > 1 ? parts[1] : ""; page = 2; selected = 0; }
            case "clear" -> { filter = ""; notice = "Filter cleared"; }
            case "quit", "exit" -> overlay = Overlay.CONFIRM_QUIT;
            default -> notice = warn("Unknown command: " + parts[0] + " · press ? for help");
        }
    }

    private void moveSelection(int delta) {
        int count = switch (page) {
            case 1 -> engine.getMetrics().getWorkerSnapshots().size();
            case 2 -> visibleTasks().size();
            case 4 -> engine.getMetrics().getTimeline().size();
            case 5 -> engine.getMetrics().getFailures().size() + engine.getDeadLetterQueue().getEntries().size();
            default -> 0;
        };
        if (count > 0) selected = Math.max(0, Math.min(count - 1, selected + delta));
    }

    private void openSelected() {
        if (page == 2) {
            List<TaskRow> visible = visibleTasks();
            if (!visible.isEmpty()) { selectedTaskId = visible.get(Math.max(0, Math.min(selected, visible.size() - 1))).handle().getId(); page = 7; }
        }
    }

    private void sampleHistory() {
        var metrics = engine.getMetrics();
        throughputHistory.addLast(metrics.getThroughputPerSecond());
        List<WorkerSnapshot> workers = metrics.getWorkerSnapshots();
        double utilization = workers.stream().mapToDouble(WorkerSnapshot::getUtilizationPercent).average().orElse(0);
        utilizationHistory.addLast(utilization);
        while (throughputHistory.size() > 48) throughputHistory.removeFirst();
        while (utilizationHistory.size() > 48) utilizationHistory.removeFirst();
        lastSampleNanos = System.nanoTime();
    }

    private void pruneTasks() {
        if (tasks.size() <= 500) return;
        var iterator = tasks.entrySet().iterator();
        while (tasks.size() > 400 && iterator.hasNext()) {
            Map.Entry<String, TaskRow> entry = iterator.next();
            if (entry.getValue().handle().isDone() && !entry.getKey().equals(selectedTaskId)) iterator.remove();
        }
    }

    private List<TaskRow> visibleTasks() {
        List<TaskRow> result = new ArrayList<>(tasks.values());
        result.sort(Comparator.comparingLong((TaskRow row) -> row.handle().getSubmittedAt().toEpochMilli()).reversed());
        if (!filter.isBlank()) result.removeIf(row -> !(row.handle().getId() + " " + row.description() + " " + row.priority() + " " + row.handle().getStatus()).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)));
        return result;
    }

    private void render(TerminalSize size) {
        int width = Math.max(40, Math.min(size.columns(), 180));
        int height = Math.max(14, size.rows());
        List<String> lines = new ArrayList<>();
        lines.add(" THREADSTORM  /  CONCURRENCY CONTROL CENTER                                      v1.0.0");
        lines.add(" " + statusLabel(engine.getState()) + "   " + LocalTime.now().format(CLOCK) + "   Java " + Runtime.version().feature()
            + "   workers " + workerCount + "   queue capacity " + capacity);
        lines.add(rule(width));
        StringBuilder nav = new StringBuilder(" ");
        for (int i = 0; i < PAGES.length; i++) nav.append(i == page ? "[" + (i + 1) + " " + PAGES[i] + "]" : " " + (i + 1) + " " + PAGES[i] + " ").append("  ");
        lines.add(nav.toString());
        lines.add(rule(width));

        switch (page) {
            case 0 -> dashboard(lines, width, height);
            case 1 -> workersPage(lines, width, height);
            case 2 -> tasksPage(lines, width, height);
            case 3 -> metricsPage(lines, width, height);
            case 4 -> eventsPage(lines, width, height);
            case 5 -> failuresPage(lines, width, height);
            case 6 -> helpPage(lines, width);
            case 7 -> taskDetailPage(lines, width);
            default -> dashboard(lines, width, height);
        }
        drawOverlay(lines, width);
        while (lines.size() < height - 3) lines.add("");
        lines.add(rule(width));
        lines.add(" " + notice);
        lines.add(overlay == Overlay.NONE ? (terminal.isLineMode()
            ? " Type a key then Enter: 1-7 pages | s submit | d demo | q quit | up/down select"
            : " [1-7] pages  [Tab] next  [↑↓] select  [s] submit  [d] demo  [/] filter  [:] command  [q] quit")
            : overlayPrompt());
        StringBuilder screen = new StringBuilder(CLEAR);
        for (int i = 0; i < Math.min(lines.size(), height); i++) {
            String line = fit(ascii ? toAscii(lines.get(i)) : lines.get(i), width);
            screen.append(paintLine(line, i)).append("\u001B[K\n");
        }
        if (overlay != Overlay.NONE) {
            screen.append("\u001B[?25h");
        } else screen.append(HIDE);
        System.out.print(screen);
        System.out.flush();
    }

    private void dashboard(List<String> out, int width, int height) {
        var m = engine.getMetrics();
        long terminalCount = m.getCompleted() + m.getFailed() + m.getCancelled();
        out.add("  ENGINE SNAPSHOT");
        if (width < 96) {
            out.add("  WORKERS " + m.getWorkerSnapshots().size() + "   QUEUE " + engine.getQueueSize() + "/" + capacity
                + "   RUNNING " + m.getCurrentlyRunning() + "   PENDING " + m.getPending());
            out.add("  SUCCESS " + m.getCompleted() + "   FAILED " + m.getFailed() + "   CANCELLED " + m.getCancelled()
                + "   ACCOUNTED " + terminalCount + "/" + m.getSubmitted());
        } else {
        out.add("  ┌────────────────────┬────────────────────┬────────────────────┬────────────────────┐");
        out.add(String.format("  │ %-18s │ %-18s │ %-18s │ %-18s │", "WORKERS  " + m.getWorkerSnapshots().size(),
            "QUEUE    " + engine.getQueueSize() + "/" + capacity, "RUNNING  " + m.getCurrentlyRunning(), "PENDING  " + m.getPending()));
        out.add(String.format("  │ %-18s │ %-18s │ %-18s │ %-18s │", "SUCCESS  " + m.getCompleted(), "FAILED   " + m.getFailed(),
            "CANCELLED " + m.getCancelled(), "ACCOUNTED " + terminalCount + "/" + m.getSubmitted()));
        out.add("  └────────────────────┴────────────────────┴────────────────────┴────────────────────┘");
        }
        out.add("");
        out.add(width < 96 ? "  WORKER POOL / RECENT ACTIVITY" : "  WORKER POOL                                                   RECENT ACTIVITY");
        out.add("  " + "─".repeat(Math.max(12, width - 4)));
        List<WorkerSnapshot> workers = m.getWorkerSnapshots();
        List<TaskEvent> events = m.getTimeline();
        int eventStart = Math.max(0, events.size() - Math.max(4, workers.size()));
        int rows = Math.max(workers.size(), events.size() - eventStart);
        for (int i = 0; i < rows; i++) {
            String left = "";
            if (i < workers.size()) {
                WorkerSnapshot w = workers.get(i);
                left = String.format("%-11s %-8s %5.1f%% %s", w.getName(), w.getState(), w.getUtilizationPercent(), bar(w.getUtilizationPercent(), 12));
            }
            String right = "";
            if (eventStart + i < events.size()) {
                TaskEvent e = events.get(eventStart + i);
                right = String.format("%-8s %-15s %s", e.getTimestamp().toString().substring(11, 19), e.getEventType(), e.getTaskId() == null ? e.getWorkerName() : e.getTaskId());
            }
            if (width < 96) {
                if (!left.isBlank()) out.add("  " + left);
                if (!right.isBlank()) out.add("    " + right);
            } else {
                out.add("  " + fit(left, width / 2 - 3) + "  " + fit(right, width / 2 - 4));
            }
        }
        out.add("");
        out.add("  PERFORMANCE  ·  LAST 60 SECONDS");
        out.add(String.format("  Throughput %8.2f tasks/s     Lifetime %8.2f tasks/s     Avg execution %8.2f ms",
            m.getThroughputPerSecond(), m.getLifetimeThroughputPerSecond(), m.getAverageExecutionMillis()));
        out.add(String.format("  Latency     P50 %8.2f ms     P95 %8.2f ms           P99 %8.2f ms",
            m.getExecutionP50Millis(), m.getExecutionP95Millis(), m.getExecutionP99Millis()));
        out.add("  Throughput  " + sparkline(throughputHistory, 42) + "    Worker utilization  " + sparkline(utilizationHistory, 24));
        out.add("");
        out.add("  QUICK ACTIONS  ·  Press S to submit work, D to launch a mixed-priority demo, or 2–6 to inspect subsystems.");
    }

    private void workersPage(List<String> out, int width, int height) {
        var workers = engine.getMetrics().getWorkerSnapshots();
        out.add("  WORKER INSPECTOR  ·  " + workers.size() + " worker threads");
        out.add("  " + "─".repeat(width - 4));
        out.add(String.format("  %-14s %-10s %-18s %9s %10s %10s %8s", "WORKER", "STATE", "CURRENT TASK", "UTIL", "BUSY (s)", "IDLE (s)", "DONE"));
        out.add("  " + "─".repeat(width - 4));
        for (int i = 0; i < workers.size(); i++) {
            WorkerSnapshot w = workers.get(i);
            String line = String.format("  %-14s %-10s %-18s %8.1f%% %10.2f %10.2f %8d",
                w.getName(), w.getState(), w.getCurrentTaskId() == null ? "—" : w.getCurrentTaskId(),
                w.getUtilizationPercent(), w.getBusyNanos() / 1e9, w.getIdleNanos() / 1e9, w.getTasksCompleted());
            out.add(i == selected ? ">" + line.substring(1) : line);
            out.add("    " + bar(w.getUtilizationPercent(), Math.max(10, width - 22)));
        }
        out.add("");
        out.add("  State definitions: STARTING · IDLE · BUSY · STOPPED");
        out.add("  Utilization is cumulative busy time / observed worker lifetime.");
    }

    private void tasksPage(List<String> out, int width, int height) {
        List<TaskRow> visible = visibleTasks();
        out.add("  TASK EXPLORER  ·  " + visible.size() + " visible / " + tasks.size() + " retained   filter: " + (filter.isBlank() ? "(none)" : filter));
        out.add("  " + "─".repeat(width - 4));
        out.add(String.format("  %-3s %-14s %-12s %-10s %-10s %-7s %s", "", "TASK ID", "STATUS", "PRIORITY", "WORKER", "TRIES", "DESCRIPTION"));
        out.add("  " + "─".repeat(width - 4));
        int max = Math.max(1, height - 17);
        for (int i = 0; i < Math.min(visible.size(), max); i++) {
            TaskRow row = visible.get(i);
            TaskHandle h = row.handle();
            String worker = h.getWorkerName() == null ? "—" : h.getWorkerName();
            String line = String.format("  %-3s %-14s %-12s %-10s %-10s %-7d %s", i == selected ? ">" : "",
                h.getId(), h.getStatus(), row.priority(), worker, h.getAttempts(), row.description());
            out.add(fit(line, width - 2));
        }
        if (visible.isEmpty()) out.add("  No tasks yet. Press D for a demo workload or S to submit a task.");
        out.add("");
        out.add("  ↑/↓ select · Enter inspect · C request cancellation · / filter · X clear filter");
    }

    private void metricsPage(List<String> out, int width, int height) {
        var m = engine.getMetrics();
        out.add("  PERFORMANCE LAB  ·  Live measurements from the in-memory metrics collector");
        out.add("  " + "─".repeat(width - 4));
        out.add("  THROUGHPUT");
        out.add(String.format("    Rolling window     %12.3f tasks/sec", m.getThroughputPerSecond()));
        out.add(String.format("    Lifetime average   %12.3f tasks/sec", m.getLifetimeThroughputPerSecond()));
        out.add("    Recent history     " + sparkline(throughputHistory, Math.min(64, width - 26)));
        out.add("");
        out.add("  EXECUTION LATENCY (retained sample window)");
        out.add(String.format("    Average %10.3f ms     P50 %10.3f ms     P95 %10.3f ms     P99 %10.3f ms",
            m.getAverageExecutionMillis(), m.getExecutionP50Millis(), m.getExecutionP95Millis(), m.getExecutionP99Millis()));
        out.add(String.format("    Queue wait %7.3f ms average     %7.3f ms P95", m.getAverageQueueWaitMillis(), m.getQueueWaitP95Millis()));
        out.add("");
        out.add("  TASK ACCOUNTING");
        out.add(String.format("    Submitted %d   Started %d   Completed %d   Failed %d   Cancelled %d   Running %d   Pending %d",
            m.getSubmitted(), m.getStarted(), m.getCompleted(), m.getFailed(), m.getCancelled(), m.getCurrentlyRunning(), m.getPending()));
        out.add("    Terminal accounting " + (m.getCompleted() + m.getFailed() + m.getCancelled()) + "/" + m.getSubmitted());
        out.add("");
        out.add("  WORKER UTILIZATION HISTORY");
        out.add("    " + sparkline(utilizationHistory, Math.min(80, width - 10)));
        out.add("    Current average utilization " + String.format(Locale.ROOT, "%.2f%%", m.getWorkerSnapshots().stream().mapToDouble(WorkerSnapshot::getUtilizationPercent).average().orElse(0)));
        out.add("");
        out.add("  Note: latency percentiles use the collector's bounded recent sample set; this is not a JMH benchmark.");
    }

    private void eventsPage(List<String> out, int width, int height) {
        List<TaskEvent> events = engine.getMetrics().getTimeline();
        out.add("  EVENT STREAM  ·  latest " + events.size() + " retained events");
        out.add("  " + "─".repeat(width - 4));
        out.add(String.format("  %-10s %-20s %-15s %-15s %s", "TIME", "EVENT", "TASK", "WORKER", "DETAIL"));
        out.add("  " + "─".repeat(width - 4));
        int start = Math.max(0, events.size() - Math.max(5, height - 16));
        for (int i = events.size() - 1; i >= start; i--) {
            TaskEvent e = events.get(i);
            out.add(fit(String.format("  %-10s %-20s %-15s %-15s %s", e.getTimestamp().toString().substring(11, 19),
                e.getEventType(), e.getTaskId() == null ? "—" : e.getTaskId(), e.getWorkerName() == null ? "—" : e.getWorkerName(), e.getDetail()), width - 2));
        }
        if (events.isEmpty()) out.add("  Waiting for events...");
    }

    private void failuresPage(List<String> out, int width, int height) {
        List<ExceptionReport> failures = engine.getMetrics().getFailures();
        List<DeadLetterEntry> dead = engine.getDeadLetterQueue().getEntries();
        out.add("  FAILURE CENTER  ·  " + failures.size() + " failures   " + dead.size() + " dead-letter entries");
        out.add("  " + "─".repeat(width - 4));
        if (failures.isEmpty() && dead.isEmpty()) out.add("  No failures recorded. That's a good day.");
        for (int i = Math.max(0, failures.size() - Math.max(2, (height - 15) / 2)); i < failures.size(); i++)
            out.add(fit("  FAILURE  " + failures.get(i), width - 2));
        for (int i = Math.max(0, dead.size() - Math.max(2, (height - 15) / 2)); i < dead.size(); i++)
            out.add(fit("  DEAD LETTER  " + dead.get(i), width - 2));
        out.add("");
        out.add("  Tasks in the dead-letter queue have exhausted their configured retry budget.");
        out.add("  Inspect exception details in the event stream and the task handle API.");
    }

    private void helpPage(List<String> out, int width) {
        out.add("  KEYBOARD REFERENCE");
        out.add("  " + "─".repeat(width - 4));
        out.add("  NAVIGATION");
        out.add("    1–7             Switch between dashboard, workers, tasks, metrics, events, failures and help");
        out.add("    Tab / ← / →    Move between pages");
        out.add("    ↑ / ↓          Move selection in the current list");
        out.add("    Enter          Inspect selected task");
        out.add("    Esc            Close dialog / return from task details");
        out.add("");
        out.add("  TASK OPERATIONS");
        out.add("    S              Submit a task (append CRITICAL, HIGH, NORMAL or LOW to set priority)");
        out.add("    D              Submit a mixed-priority demonstration workload");
        out.add("    C              Request cancellation of the selected task");
        out.add("    /              Filter tasks by ID, description, priority or status");
        out.add("    X              Clear task filter");
        out.add("");
        out.add("  COMMAND PALETTE  ·  press :");
        out.add("    submit <text> [priority]   demo   dashboard   workers   tasks   metrics");
        out.add("    events   failures   filter <text>   clear   help   quit");
        out.add("");
        out.add("  ENGINE NOTES");
        out.add("    The TUI reads public engine snapshots; it never mutates internal queues or worker state.");
        out.add("    Cancellation is cooperative for running tasks. Accepted tasks drain during graceful shutdown.");
        out.add("    Press Q and confirm to quit. Ctrl+C also requests graceful shutdown.");
    }

    private void taskDetailPage(List<String> out, int width) {
        TaskRow row = tasks.get(selectedTaskId);
        if (row == null) { out.add("  Task is no longer retained."); return; }
        TaskHandle h = row.handle();
        out.add("  TASK DETAIL  ·  " + h.getId());
        out.add("  " + "─".repeat(width - 4));
        out.add("    Description       " + row.description());
        out.add("    Status            " + h.getStatus());
        out.add("    Priority          " + row.priority());
        out.add("    Worker            " + (h.getWorkerName() == null ? "—" : h.getWorkerName()));
        out.add("    Attempts          " + h.getAttempts());
        out.add("    Cancellation      " + (h.isCancellationRequested() ? "requested" : "not requested"));
        out.add("    Submitted         " + h.getSubmittedAt());
        out.add("    Started           " + (h.getStartedAt() == null ? "—" : h.getStartedAt()));
        out.add("    Completed         " + (h.getCompletedAt() == null ? "—" : h.getCompletedAt()));
        out.add("    Queue wait        " + String.format(Locale.ROOT, "%.3f ms", h.getQueueWaitNanos() / 1e6));
        out.add("    Execution         " + String.format(Locale.ROOT, "%.3f ms", h.getExecutionNanos() / 1e6));
        if (h.getFailure() != null) out.add("    Failure           " + h.getFailure());
        out.add("");
        out.add("    C request cancellation     Esc return to task list");
    }

    private void drawOverlay(List<String> lines, int width) {
        if (overlay == Overlay.NONE) return;
        String title = switch (overlay) {
            case SUBMIT -> " SUBMIT TASK "; case COMMAND -> " COMMAND PALETTE ";
            case SEARCH -> " FILTER TASKS "; case CONFIRM_QUIT -> " CONFIRM SHUTDOWN "; default -> " ";
        };
        lines.add("");
        lines.add("  ┌" + "─".repeat(Math.min(width - 6, 72)) + "┐");
        lines.add("  │" + fit(title, Math.min(width - 8, 72)) + "│");
        String prompt = overlay == Overlay.CONFIRM_QUIT ? "Drain accepted tasks and quit? Type yes or no: " : "> ";
        lines.add("  │" + fit(prompt + input + "█", Math.min(width - 8, 72)) + "│");
        lines.add("  └" + "─".repeat(Math.min(width - 6, 72)) + "┘");
    }

    private String overlayPrompt() {
        return switch (overlay) {
            case SUBMIT -> " Enter description; optionally end with CRITICAL/HIGH/NORMAL/LOW · Esc cancel";
            case COMMAND -> " Enter command · Esc cancel";
            case SEARCH -> " Enter filter · Esc cancel · X clears filter";
            case CONFIRM_QUIT -> " Type yes to confirm graceful shutdown, anything else to cancel";
            default -> "";
        };
    }

    private String paintLine(String line, int row) {
        if (!color) return line;
        if (row == 0) return "\u001B[1;38;2;91;220;190m" + line + RESET;
        if (row == 1) return "\u001B[38;2;130;160;180m" + line + RESET;
        if (line.contains("FAILED") || line.contains("FAILURE")) return "\u001B[38;2;255;120;120m" + line + RESET;
        if (line.contains("SUCCEEDED") || line.contains("SUCCESS")) return "\u001B[38;2;100;220;160m" + line + RESET;
        if (line.contains("BUSY") || line.contains("CRITICAL")) return "\u001B[38;2;245;195;95m" + line + RESET;
        if (row == 2 || row == 4) return "\u001B[38;2;70;145;160m" + line + RESET;
        if (line.startsWith("  ")) return "\u001B[38;2;205;215;225m" + line + RESET;
        return line;
    }

    private static String toAscii(String value) {
        return value.replace('┌', '+').replace('┐', '+').replace('└', '+')
            .replace('┘', '+').replace('┬', '+').replace('┴', '+')
            .replace('├', '+').replace('┤', '+').replace('┼', '+')
            .replace('│', '|').replace('─', '-').replace('═', '=')
            .replace('█', '#').replace('░', '.').replace('▁', '.')
            .replace('▂', ':').replace('▃', '-').replace('▄', '=')
            .replace('▅', '+').replace('▆', '*').replace('▇', '#')
            .replace('·', '-').replace('—', '-').replace('–', '-')
            .replace('●', '*').replace('○', 'o').replace('◌', 'o')
            .replace('↑', '^').replace('↓', 'v').replace('←', '<').replace('→', '>')
            .replace('…', '.');
    }

    private String statusLabel(EngineState state) {
        return switch (state) {
            case RUNNING -> "● RUNNING"; case SHUTTING_DOWN -> "◌ DRAINING";
            case TERMINATED -> "○ TERMINATED"; case CREATED -> "○ CREATED";
        };
    }
    private String rule(int width) { return "─".repeat(Math.max(1, width - 1)); }
    private String fit(String text, int width) {
        if (width <= 0) return "";
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        if (value.length() > width) return value.substring(0, Math.max(0, width - 1)) + "…";
        return value + " ".repeat(width - value.length());
    }
    private String bar(double percent, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) * width / 100.0);
        return "[" + "█".repeat(filled) + "░".repeat(Math.max(0, width - filled)) + "]";
    }
    private String sparkline(Deque<Double> values, int width) {
        if (values.isEmpty()) return "·".repeat(Math.max(1, width));
        String levels = "▁▂▃▄▅▆▇█";
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= 0) max = 1;
        StringBuilder result = new StringBuilder();
        List<Double> samples = new ArrayList<>(values);
        int start = Math.max(0, samples.size() - width);
        for (int i = start; i < samples.size(); i++) {
            int level = (int) Math.round(Math.max(0, Math.min(1, samples.get(i) / max)) * 7);
            result.append(levels.charAt(level));
        }
        return result.toString();
    }
    private String good(String value) { return value; }
    private String warn(String value) { return value; }
    private String bad(String value) { return value; }
}
