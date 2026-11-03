import core.ThreadStorm;
import metrics.MetricsCollector;
import task.ComputationTask;
import task.SimulationTask;
import task.TaskHandle;

public class Main {
    public static void main(String[] args) {
        ThreadStorm engine = new ThreadStorm(4);
        engine.start();

        TaskHandle factorial = engine.submit(new ComputationTask(100));
        engine.submit(new ComputationTask(250));
        engine.submit(new SimulationTask("Email Processing", 1200));
        engine.submit(new SimulationTask("File Upload", 1800));
        engine.submit(new SimulationTask("Database Backup", 900));

        TaskHandle failing = engine.submit(context -> {
            throw new IllegalStateException("Simulated task failure");
        });

        engine.submit(new SimulationTask("Report Generation", 1500));

        System.out.println("Submitted handles: " + factorial.getId() + ", " + failing.getId());
        engine.shutdown();

        System.out.println("Final engine state: " + engine.getState());
        MetricsCollector metrics = engine.getMetrics();
        System.out.printf("Accounting check: submitted=%d, terminal=%d%n",
            metrics.getSubmitted(),
            metrics.getCompleted() + metrics.getFailed() + metrics.getCancelled());
    }
}
