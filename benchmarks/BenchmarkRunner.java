package benchmarks;

import core.Priority;
import core.ThreadStorm;
import task.Task;
import task.TaskHandle;
import task.TaskStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Dependency-free comparative benchmark harness. Use JMH for publishable microbenchmarks. */
public final class BenchmarkRunner {
    private static volatile long blackhole;

    private BenchmarkRunner() { }

    public static void main(String[] args) throws Exception {
        int tasks = option(args, "--tasks", 2_000);
        int work = option(args, "--work", 20_000);
        int workers = option(args, "--workers", Math.max(2, Runtime.getRuntime().availableProcessors()));
        int warmups = option(args, "--warmups", 1);
        int runs = option(args, "--runs", 3);
        String csv = stringOption(args, "--csv", "benchmark-results.csv");
        if (tasks <= 0 || work <= 0 || workers <= 0 || warmups < 0 || runs <= 0) {
            throw new IllegalArgumentException("tasks/workers/work/runs must be positive; warmups cannot be negative");
        }

        System.out.println("ThreadStorm Benchmark Suite 1.0.0");
        System.out.println("Started: " + Instant.now());
        System.out.printf(Locale.ROOT, "JVM: %s | processors: %d | tasks: %d | work/task: %d | workers: %d%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), tasks, work, workers);
        System.out.println("Workload: deterministic CPU-bound integer mixing; lower time is better.");
        System.out.println("Note: this is a comparative harness, not a substitute for JMH.\n");

        for (int i = 0; i < warmups; i++) {
            runSequential(Math.max(1, tasks / 4), Math.max(1, work / 4));
            runExecutor(Math.max(1, tasks / 4), Math.max(1, work / 4), workers);
            runThreadStorm(Math.max(1, tasks / 4), Math.max(1, work / 4), workers);
        }

        List<Result> results = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            results.add(measure("Sequential", run, tasks, work, () -> runSequential(tasks, work)));
            results.add(measure("ExecutorService", run, tasks, work, () -> runExecutor(tasks, work, workers)));
            results.add(measure("ThreadStorm", run, tasks, work, () -> runThreadStorm(tasks, work, workers)));
        }
        printSummary(results, runs);
        writeCsv(Path.of(csv), results);
        System.out.println("\nCSV report: " + Path.of(csv).toAbsolutePath());
        System.out.println("Checksum (prevents dead-code elimination): " + blackhole);
    }

    private static Result measure(String name, int run, int tasks, int work, TimedOperation operation) throws Exception {
        long start = System.nanoTime();
        operation.run();
        long elapsed = System.nanoTime() - start;
        double seconds = elapsed / 1_000_000_000.0;
        double throughput = tasks / seconds;
        Result result = new Result(name, run, tasks, work, elapsed, throughput);
        System.out.printf(Locale.ROOT, "%-16s run %d: %8.2f ms | %10.1f tasks/s%n", name, run, elapsed / 1_000_000.0, throughput);
        return result;
    }

    private static void runSequential(int tasks, int work) {
        long sum = 0;
        for (int i = 0; i < tasks; i++) sum += cpuWork(i, work);
        blackhole ^= sum;
    }

    private static void runExecutor(int tasks, int work, int workers) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Long>> futures = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                final int seed = i;
                futures.add(executor.submit(() -> cpuWork(seed, work)));
            }
            long sum = 0;
            for (Future<Long> future : futures) sum += future.get();
            blackhole ^= sum;
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) executor.shutdownNow();
        }
    }

    private static void runThreadStorm(int tasks, int work, int workers) throws Exception {
        ThreadStorm engine = new ThreadStorm(workers, Math.max(64, Math.min(tasks, 4_096)));
        List<TaskHandle> handles = new ArrayList<>(tasks);
        LongAdder sum = new LongAdder();
        engine.start();
        try {
            for (int i = 0; i < tasks; i++) {
                final int seed = i;
                Task task = context -> sum.add(cpuWork(seed, work));
                handles.add(engine.submit(task, Priority.NORMAL));
            }
            awaitAll(handles, 2, TimeUnit.MINUTES);
            for (TaskHandle handle : handles) {
                if (handle.getStatus() != TaskStatus.SUCCEEDED) {
                    throw new IllegalStateException("ThreadStorm task did not succeed: " + handle.getId() + " (" + handle.getStatus() + ")", handle.getFailure());
                }
            }
            blackhole ^= sum.sum();
        } finally {
            engine.shutdown();
        }
    }

    private static void awaitAll(List<TaskHandle> handles, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            boolean done = true;
            for (TaskHandle handle : handles) {
                if (!handle.isDone()) { done = false; break; }
            }
            if (done) return;
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Timed out waiting for benchmark tasks");
            Thread.sleep(1);
        }
    }

    private static long cpuWork(int seed, int iterations) {
        long value = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < iterations; i++) {
            value ^= value >>> 30;
            value *= 0xBF58476D1CE4E5B9L;
            value ^= value >>> 27;
            value *= 0x94D049BB133111EBL;
            value ^= value >>> 31;
        }
        return value;
    }

    private static void printSummary(List<Result> results, int runs) {
        System.out.println("\n=== SUMMARY (median across " + runs + " measured runs) ===");
        System.out.printf("%-16s %14s %16s%n", "Implementation", "Median ms", "Median tasks/s");
        for (String name : List.of("Sequential", "ExecutorService", "ThreadStorm")) {
            List<Long> times = results.stream().filter(r -> r.name.equals(name)).map(r -> r.elapsedNanos).sorted().toList();
            List<Double> rates = results.stream().filter(r -> r.name.equals(name)).map(r -> r.tasksPerSecond).sorted().toList();
            System.out.printf(Locale.ROOT, "%-16s %14.2f %16.1f%n", name, times.get(times.size() / 2) / 1_000_000.0, rates.get(rates.size() / 2));
        }
        System.out.println("Results are machine-, JVM-, and workload-dependent. Do not generalize from one run.");
    }

    private static void writeCsv(Path path, List<Result> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("implementation,run,tasks,work_per_task,elapsed_nanos,tasks_per_second");
        for (Result r : results) lines.add(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%.3f", r.name, r.run, r.tasks, r.work, r.elapsedNanos, r.tasksPerSecond));
        Files.write(path, lines);
    }

    private static int option(String[] args, String key, int fallback) {
        String value = stringOption(args, key, null);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid integer for " + key + ": " + value); }
    }

    private static String stringOption(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length; i++) if (key.equals(args[i])) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + key);
            return args[i + 1];
        }
        return fallback;
    }

    @FunctionalInterface private interface TimedOperation { void run() throws Exception; }
    private static final class Result {
        final String name; final int run, tasks, work; final long elapsedNanos; final double tasksPerSecond;
        Result(String name, int run, int tasks, int work, long elapsedNanos, double tasksPerSecond) {
            this.name = name; this.run = run; this.tasks = tasks; this.work = work; this.elapsedNanos = elapsedNanos; this.tasksPerSecond = tasksPerSecond;
        }
    }
}
