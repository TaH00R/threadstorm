package core;

import task.Task;
import metrics.MetricsCollector;
import metrics.ExceptionReport;

public class Worker extends Thread {
    private final TaskQueue queue;
    private final MetricsCollector metrics;

    public Worker(String name, TaskQueue queue, MetricsCollector metrics) {
        super(name);
        this.queue = queue;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        System.out.println("[" + getName() + "] Started");

        while (!isInterrupted()) {
            Task task = queue.take();
            if (task == null) break;
            long start = System.nanoTime();

            try {
                task.execute();
                long duration = (System.nanoTime() - start) / 1_000_000;
                metrics.recordSuccess(duration);

            } catch (RuntimeException e) {
                long duration = (System.nanoTime() - start) / 1_000_000;
                ExceptionReport report = new ExceptionReport(getName(), e.getMessage(), duration);
                metrics.recordFailure(report);
            }
        }

        System.out.println("[" + getName() + "] Stopped");
    }
}