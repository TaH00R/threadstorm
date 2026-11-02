package task;

public class ComputationTask implements Task {

    private final int number;

    public ComputationTask(int number) {
        this.number = number;
    }

    @Override
    public void execute() {
        long result = 1;

        for (int i = 1; i <= number; i++) {
            result *= i;
        }

        System.out.println("[" + Thread.currentThread().getName() + "] Factorial of " + number + " = " + result);
    }
}