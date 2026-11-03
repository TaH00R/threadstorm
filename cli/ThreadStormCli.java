package cli;

import core.DeadLetterEntry;
import core.Priority;
import core.SubmissionPolicy;
import core.ThreadStorm;
import metrics.TaskEvent;
import metrics.WorkerSnapshot;
import task.TaskHandle;

import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/** Small dependency-free interactive console for inspecting and exercising ThreadStorm. */
public final class ThreadStormCli {
    private final ThreadStorm engine;
    private boolean running = true;

    private ThreadStormCli(int workers, int capacity) {
        engine = new ThreadStorm(workers, capacity);
        engine.start();
    }

    public static void main(String[] args) {
        int workers = option(args, "--workers", Math.max(2, Runtime.getRuntime().availableProcessors()));
        int capacity = option(args, "--capacity", 100);
        if (workers <= 0 || capacity <= 0) {
            System.err.println("--workers and --capacity must be positive integers.");
            return;
        }
        new ThreadStormCli(workers, capacity).run();
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
        System.out.println("\nThreadStorm CLI — type 'help' for commands.\n");
        try (Scanner scanner = new Scanner(System.in)) {
            while (running && scanner.hasNextLine()) {
                System.out.print("threadstorm> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                try { execute(line); }
                catch (Exception ex) { System.out.println("Error: " + ex.getMessage()); }
            }
        } finally {
            if (running) shutdown();
        }
    }

    private void execute(String line) throws InterruptedException {
        String[] parts = line.split("\\s+");
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help();
            case "submit" -> submit(parts);
            case "demo" -> demo();
            case "workers" -> workers();
            case "queue" -> System.out.printf("Queue: %d / %d%n", engine.getQueueSize(), engine.getQueueCapacity());
            case "metrics" -> metrics();
            case "failures" -> failures();
            case "deadletters" -> deadLetters();
            case "timeline" -> timeline(parts);
            case "shutdown", "exit", "quit" -> shutdown();
            default -> System.out.println("Unknown command. Type 'help'.");
        }
    }

    private void submit(String[] parts) throws InterruptedException {
        if (parts.length < 2) { System.out.println("Usage: submit <message> [CRITICAL|HIGH|NORMAL|LOW]"); return; }
        Priority priority = parts.length >= 3 ? Priority.valueOf(parts[parts.length - 1].toUpperCase(Locale.ROOT)) : Priority.NORMAL;
        int end = parts.length >= 3 ? parts.length - 1 : parts.length;
        String message = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, end));
        TaskHandle handle = engine.submit(context -> System.out.printf("[%s] %s%n", context.getWorkerName(), message), priority,
                SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
        System.out.println("Submitted " + handle.getId() + " with priority " + priority);
    }

    private void demo() throws InterruptedException {
        for (int i = 1; i <= 5; i++) {
            final int number = i;
            Priority priority = Priority.values()[i % Priority.values().length];
            TaskHandle handle = engine.submit(context -> {
                Thread.sleep(150L * number);
                context.checkpoint();
                System.out.printf("[%s] Demo task %d finished%n", context.getWorkerName(), number);
            }, priority, SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
            System.out.println("Queued " + handle.getId() + " (" + priority + ")");
        }
    }

    private void workers() {
        for (WorkerSnapshot w : engine.getMetrics().getWorkerSnapshots())
            System.out.printf("%-18s state=%-8s current=%-14s utilization=%.1f%% completed=%d%n",
                    w.getName(), w.getState(), w.getCurrentTaskId(), w.getUtilizationPercent(), w.getTasksCompleted());
    }

    private void metrics() {
        var m = engine.getMetrics();
        System.out.printf("submitted=%d started=%d completed=%d failed=%d cancelled=%d running=%d pending=%d%n",
                m.getSubmitted(), m.getStarted(), m.getCompleted(), m.getFailed(), m.getCancelled(), m.getCurrentlyRunning(), m.getPending());
        System.out.printf("throughput(60s)=%.2f tasks/s lifetime=%.2f tasks/s%n", m.getThroughputPerSecond(), m.getLifetimeThroughputPerSecond());
        System.out.printf("execution avg=%.3fms P50=%.3fms P95=%.3fms P99=%.3fms queue-wait avg=%.3fms P95=%.3fms%n",
                m.getAverageExecutionMillis(), m.getExecutionP50Millis(), m.getExecutionP95Millis(), m.getExecutionP99Millis(),
                m.getAverageQueueWaitMillis(), m.getQueueWaitP95Millis());
    }

    private void failures() {
        var list = engine.getMetrics().getFailures();
        if (list.isEmpty()) { System.out.println("No recorded failures."); return; }
        list.forEach(System.out::println);
    }

    private void deadLetters() {
        var list = engine.getDeadLetterQueue().getEntries();
        if (list.isEmpty()) { System.out.println("Dead-letter queue is empty."); return; }
        for (DeadLetterEntry entry : list) System.out.println(entry);
    }

    private void timeline(String[] parts) {
        int limit = parts.length > 1 ? Integer.parseInt(parts[1]) : 20;
        if (limit < 1) { System.out.println("Limit must be positive."); return; }
        var events = engine.getMetrics().getTimeline();
        int start = Math.max(0, events.size() - limit);
        for (int i = start; i < events.size(); i++) {
            TaskEvent event = events.get(i);
            System.out.println(event.getTimestamp() + " " + event.getEventType() + " task=" + event.getTaskId() + " worker=" + event.getWorkerName());
        }
    }

    private void shutdown() {
        if (!running) return;
        running = false;
        engine.shutdown();
        System.out.println("ThreadStorm CLI stopped.");
    }

    private static void help() {
        System.out.println("""
                help                 Show commands
                submit <message> [priority]  Submit a task (uses REJECT when queue is full)
                demo                 Submit five sample tasks
                workers              Show worker state and utilization
                queue                Show queue depth and capacity
                metrics              Show counters, throughput and latency percentiles
                failures             Show recorded task failures
                deadletters          Show tasks that exhausted retries
                timeline [count]     Show the latest events (default 20)
                shutdown | exit      Gracefully drain accepted tasks and quit
                Priorities: CRITICAL, HIGH, NORMAL, LOW
                """);
    }
}
