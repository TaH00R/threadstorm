import core.ThreadStorm;
import task.ComputationTask;
import task.SimulationTask;

public class Main {

    public static void main(String[] args) {
        ThreadStorm engine = new ThreadStorm(4);

        engine.start();

        engine.submit(new ComputationTask(5));
        engine.submit(new ComputationTask(10));
        engine.submit(new ComputationTask(15));

        engine.submit(new SimulationTask("Email Processing", 2000));
        engine.submit(new SimulationTask("File Upload", 3000));
        engine.submit(new SimulationTask("Database Backup", 1500));


        engine.submit(() -> {throw new RuntimeException("Simulated task failure");});

        engine.submit(new SimulationTask("Report Generation", 2500));
        engine.submit(new ComputationTask(20));

        engine.shutdown();
    }
}