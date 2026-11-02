package task;

public class SimulationTask implements Task {
    private final String taskName;
    private final int duration;

    public SimulationTask(String taskName, int duration) {
        this.taskName = taskName;
        this.duration = duration;
    }

    @Override
    public void execute() {
        System.out.println("[" + Thread.currentThread().getName() + "] Started " + taskName);
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Task interrupted: " + taskName, e);
        }

        System.out.println("[" + Thread.currentThread().getName() + "] Completed " + taskName);
    }
}