package metrics;

import java.util.ArrayList;
import java.util.List;

public class MetricsCollector {
    private int completedTasks;
    private int failedTasks;
    private long totalExecutionTime;
    private final List<ExceptionReport> failures;

    public MetricsCollector() {
        completedTasks = 0;
        failedTasks = 0;
        totalExecutionTime = 0;

        failures = new ArrayList<>();
    }

    public synchronized void recordSuccess(long time) {
        completedTasks++;
        totalExecutionTime += time;
    }

    public synchronized void recordFailure(ExceptionReport report) {
        failedTasks++;
        failures.add(report);
    }

    public synchronized void displayReport() {
        int total = completedTasks + failedTasks;

        System.out.println("\n========== THREADSTORM REPORT ==========");

        System.out.println("Total Tasks: " + total);
        System.out.println("Completed: " + completedTasks);
        System.out.println("Failed: " + failedTasks);

        double average = completedTasks == 0 ? 0 : (double) totalExecutionTime / completedTasks;

        System.out.println("Average Successful Execution Time: "  + average + " ms");
        System.out.println("\nFailure Details:");

        if (failures.isEmpty()) {
            System.out.println("No failures!");
        } else {
            for (ExceptionReport report : failures) {
                report.display();
            }
        }

        System.out.println("========================================");
    }
}