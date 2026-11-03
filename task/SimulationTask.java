package task;

public final class SimulationTask implements Task {
    private final String taskName;
    private final long durationMillis;

    public SimulationTask(String taskName, long durationMillis) {
        if (durationMillis < 0) throw new IllegalArgumentException("Duration cannot be negative");
        this.taskName = taskName;
        this.durationMillis = durationMillis;
    }

    @Override
    public void execute(TaskContext context) throws InterruptedException {
        System.out.printf("[%s] %s started: %s%n",
            context.getWorkerName(), context.getTaskId(), taskName);

        long remainingNanos = durationMillis * 1_000_000L;
        long deadline = System.nanoTime() + remainingNanos;
        while (remainingNanos > 0) {
            context.throwIfCancellationRequested();
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            Thread.sleep(millis, nanos);
            remainingNanos = deadline - System.nanoTime();
        }

        context.throwIfCancellationRequested();
        System.out.printf("[%s] %s completed: %s%n",
            context.getWorkerName(), context.getTaskId(), taskName);
    }
}
