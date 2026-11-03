package task;

import java.math.BigInteger;

public final class ComputationTask implements Task {
    private final int number;

    public ComputationTask(int number) {
        if (number < 0) throw new IllegalArgumentException("Number cannot be negative");
        this.number = number;
    }

    @Override
    public void execute(TaskContext context) {
        BigInteger result = BigInteger.ONE;
        for (int i = 1; i <= number; i++) {
            context.throwIfCancellationRequested();
            result = result.multiply(BigInteger.valueOf(i));
        }
        System.out.printf("[%s] %s factorial(%d) = %s%n",
            context.getWorkerName(), context.getTaskId(), number, result);
    }
}
