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
        for (int i = 2; i <= number; i++) {
            if ((i & 1023) == 0) context.throwIfCancellationRequested();
            result = result.multiply(BigInteger.valueOf(i));
        }
        System.out.printf("[%s] %s factorial(%d) calculated (%d digits)%n",
            context.getWorkerName(), context.getTaskId(), number, result.toString().length());
    }
}
