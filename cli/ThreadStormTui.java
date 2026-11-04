package cli;

import core.DeadLetterEntry;
import core.Priority;
import core.SubmissionPolicy;
import core.ThreadStorm;
import metrics.TaskEvent;
import metrics.WorkerSnapshot;
import task.TaskHandle;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

    /** Dependency-free, ANSI terminal dashboard for ThreadStorm. */
public final class ThreadStormTui {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "1";
    private static final String DIM = "2";
    private static final String CYAN = "36";
    private static final String GREEN = "32";
    private static final String YELLOW = "33";
    private static final String RED = "31";
    private static final String BLUE = "34";
    private static final String MAGENTA = "35";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ThreadStorm engine;
    private final Scanner scanner;
    private final boolean color;
    private boolean running = true;
    private String notice = "Engine initialized. Type help to see available actions.";

    private ThreadStormTui(int workers, int capacity, boolean color) {
        this.engine = new ThreadStorm(workers, capacity);
        this.scanner = new Scanner(System.in);
        this.color = color;
        engine.start();
    }

    public static void main(String[] args) {
        int workers = option(args, "--workers", Math.max(2, Runtime.getRuntime().availableProcessors()));
        int capacity = option(args, "--capacity", 100);
        boolean color = !contains(args, "--no-color");
        if (workers <= 0 || capacity <= 0) {
            System.err.println("--workers and --capacity must be positive integers.");
            return;
        }
        new ThreadStormTui(workers, capacity, color).run();
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
        try {
            while (running) {
                render();
                System.out.print("\n" + paint(CYAN, "  threadstorm") + paint(DIM, " › "));
                System.out.flush();
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                try { execute(line); }
                catch (Exception ex) { notice = paint(RED, "Error: " + safe(ex.getMessage())); }
            }
        } finally {
            if (engine.getState() != core.EngineState.TERMINATED) {
                notice = "Shutting down: draining accepted tasks...";
                render();
                engine.shutdown();
            }
            System.out.print(RESET + "\u001B[?25h\n");
        }
    }

    private void execute(String input) throws Exception {
        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "help", "h", "?" -> notice = "Commands: help | submit <message> [priority] | demo | workers | metrics | failures | deadletters | timeline [n] | clear | quit";
            case "submit", "s" -> submit(parts);
            case "demo", "d" -> demo();
            case "workers", "w" -> notice = "Worker panel refreshed.";
            case "metrics", "m" -> notice = "Metrics panel refreshed.";
            case "failures", "f" -> notice = "Failure panel refreshed.";
            case "deadletters" -> notice = "Dead-letter panel refreshed.";
            case "timeline", "t" -> notice = "Timeline panel refreshed.";
            case "clear" -> notice = "Screen refreshed.";
            case "quit", "exit", "q" -> { running = false; notice = "Graceful shutdown requested."; }
            default -> notice = paint(YELLOW, "Unknown action. Type 'help'.");
        }
    }

    private void submit(String[] parts) throws Exception {
        if (parts.length < 2) {
            notice = "Usage: submit <message> [CRITICAL|HIGH|NORMAL|LOW]";
            return;
        }
        Priority priority = Priority.NORMAL;
        int end = parts.length;
        if (parts.length >= 3) {
            try {
                priority = Priority.valueOf(parts[parts.length - 1].toUpperCase(Locale.ROOT));
                end--;
            } catch (IllegalArgumentException ignored) { /* Last token belongs to the message. */ }
        }
        String message = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, end));
        TaskHandle handle = engine.submit(context -> {
            context.checkpoint();
            Thread.sleep(250);
            context.checkpoint();
        }, priority, SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
        notice = paint(GREEN, "Accepted ") + handle.getId() + " · priority " + priority + " · " + message;
    }

    private void demo() throws Exception {
        int accepted = 0;
        for (int i = 1; i <= 8; i++) {
            final int taskNumber = i;
            Priority priority = switch (i % 4) {
                case 0 -> Priority.CRITICAL;
                case 1 -> Priority.HIGH;
                case 2 -> Priority.NORMAL;
                default -> Priority.LOW;
            };
            try {
                engine.submit(context -> {
                    context.checkpoint();
                    Thread.sleep(150L + taskNumber * 90L);
                    context.checkpoint();
                }, priority, SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
                accepted++;
            } catch (core.TaskRejectedException rejected) {
                break;
            }
        }
        notice = paint(GREEN, "Demo submitted ") + accepted + " sample tasks.";
    }

    private void render() {
        StringBuilder out = new StringBuilder();
        out.append("\u001B[?25l\u001B[H\u001B[2J");
        String title = " THREADSTORM ";
        out.append(paint(BOLD + CYAN, "╔" + "═".repeat(88) + "╗")).append('\n');
        out.append(paint(BOLD + CYAN, "║" + center(title + "  CONCURRENCY CONTROL CENTER", 88) + "║")).append('\n');
        String state = " ENGINE " + engine.getState() + " ";
        out.append(paint(BOLD + CYAN, "╚" + "═".repeat(88) + "╝")).append('\n');
        out.append("  ").append(paint(DIM, "JAVA CONCURRENCY ENGINE")).append("   ")
            .append(paint(GREEN, state)).append("   ").append(paint(DIM, LocalTime.now().format(CLOCK))).append("\n\n");

        var metrics = engine.getMetrics();
        long completed = metrics.getCompleted();
        long failed = metrics.getFailed();
        long cancelled = metrics.getCancelled();
        long submitted = metrics.getSubmitted();
        long terminal = completed + failed + cancelled;
        out.append(panelTitle("ENGINE OVERVIEW")).append('\n');
        out.append(String.format("  %-19s %-19s %-19s %-19s%n",
            metric("WORKERS", metrics.getWorkerSnapshots().size()),
            metric("QUEUE", engine.getQueueSize() + "/" + engine.getQueueCapacity()),
            metric("RUNNING", metrics.getCurrentlyRunning()),
            metric("PENDING", metrics.getPending())));
        out.append(String.format("  %-19s %-19s %-19s %-19s%n\n",
            metric("COMPLETED", completed), metric("FAILED", failed),
            metric("CANCELLED", cancelled), metric("ACCOUNTED", terminal + "/" + submitted)));

        out.append(panelTitle("WORKER POOL")).append('\n');
        List<WorkerSnapshot> workers = metrics.getWorkerSnapshots();
        if (workers.isEmpty()) out.append("  No worker snapshots available.\n");
        for (WorkerSnapshot worker : workers) {
            String status = worker.getState().name();
            String statusColor = status.equals("BUSY") ? GREEN : status.equals("STOPPED") ? RED : YELLOW;
            out.append(String.format("  %-12s %s %-8s %-14s %5.1f%%  %s%n",
                worker.getName(), paint(statusColor, "●"), status,
                truncate(worker.getCurrentTaskId() == null ? "—" : worker.getCurrentTaskId(), 14),
                worker.getUtilizationPercent(), bar(worker.getUtilizationPercent(), 16)));
        }
        out.append('\n');

        out.append(panelTitle("PERFORMANCE")).append('\n');
        out.append(String.format("  Throughput  %8.2f tasks/s (60s)   %8.2f tasks/s (lifetime)%n",
            metrics.getThroughputPerSecond(), metrics.getLifetimeThroughputPerSecond()));
        out.append(String.format("  Execution   avg %7.2f ms   P50 %7.2f ms   P95 %7.2f ms   P99 %7.2f ms%n",
            metrics.getAverageExecutionMillis(), metrics.getExecutionP50Millis(),
            metrics.getExecutionP95Millis(), metrics.getExecutionP99Millis()));
        out.append(String.format("  Queue wait  avg %7.2f ms   P95 %7.2f ms%n\n",
            metrics.getAverageQueueWaitMillis(), metrics.getQueueWaitP95Millis()));

        out.append(panelTitle("LIVE EVENT TIMELINE · LAST 7 EVENTS")).append('\n');
        List<TaskEvent> events = metrics.getTimeline();
        int start = Math.max(0, events.size() - 7);
        if (events.isEmpty()) out.append("  Waiting for task activity...\n");
        for (int i = start; i < events.size(); i++) {
            TaskEvent event = events.get(i);
            String type = event.getEventType();
            String eventColor = type.contains("FAIL") ? RED : type.contains("SUCC") ? GREEN
                : type.contains("RETRY") ? YELLOW : CYAN;
            out.append("  ").append(paint(DIM, event.getTimestamp().toString().substring(11, 19)))
                .append("  ").append(paint(eventColor, pad(type, 16)))
                .append(" ").append(pad(truncate(event.getTaskId() == null ? "—" : event.getTaskId(), 14), 14))
                .append(" ").append(truncate(event.getDetail(), 45)).append('\n');
        }
        out.append('\n').append(panelTitle("FAILURES & DEAD LETTERS")).append('\n');
        var failures = metrics.getFailures();
        var deadLetters = engine.getDeadLetterQueue().getEntries();
        out.append("  Failures: ").append(failures.size()).append("   Dead letters: ").append(deadLetters.size()).append('\n');
        int failureStart = Math.max(0, failures.size() - 2);
        for (int i = failureStart; i < failures.size(); i++)
            out.append("  ").append(paint(RED, truncate(failures.get(i).toString(), 82))).append('\n');
        int deadStart = Math.max(0, deadLetters.size() - 2);
        for (int i = deadStart; i < deadLetters.size(); i++)
            out.append("  ").append(paint(YELLOW, truncate(deadLetters.get(i).toString(), 82))).append('\n');
        if (failures.isEmpty() && deadLetters.isEmpty()) out.append("  No failures recorded.\n");
        out.append('\n').append(panelTitle("ACTIONS")).append('\n');
        out.append("  [s] submit task   [d] demo workload   [w] workers   [m] metrics   [f] failures\n");
        out.append("  [deadletters] inspect DLQ   [t] timeline   [help] commands   [q] graceful quit\n\n");
        out.append("  ").append(notice).append('\n');
        System.out.print(out);
    }

    private String panelTitle(String value) {
        return paint(BOLD + BLUE, "  ┌─ " + value + " " + "─".repeat(Math.max(1, 84 - value.length())) + "┐");
    }

    private String metric(String label, Object value) {
        return paint(DIM, pad(label, 10)) + " " + paint(BOLD + CYAN, pad(String.valueOf(value), 8));
    }

    private String bar(double percent, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) * width / 100.0);
        return paint(GREEN, "█".repeat(filled)) + paint(DIM, "░".repeat(width - filled));
    }

    private String paint(String code, String value) { return color ? "\u001B[" + code + "m" + value + RESET : value; }
    private static String pad(String value, int width) { return value + " ".repeat(Math.max(0, width - value.length())); }
    private static String truncate(String value, int width) { return value.length() <= width ? value : value.substring(0, Math.max(0, width - 1)) + "…"; }
    private static String center(String value, int width) {
        int left = Math.max(0, (width - value.length()) / 2);
        return " ".repeat(left) + value + " ".repeat(Math.max(0, width - left - value.length()));
    }
    private static String safe(String value) { return value == null ? "unknown error" : value; }
}
