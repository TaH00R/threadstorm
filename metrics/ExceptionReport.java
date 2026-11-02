package metrics;

public class ExceptionReport {
    private final String workerName;
    private final String errorMessage;
    private final long executionTime;

    public ExceptionReport(
            String workerName,
            String errorMessage,
            long executionTime) {

        this.workerName = workerName;
        this.errorMessage = errorMessage;
        this.executionTime = executionTime;
    }

    public void display() {
        System.out.println("[FAILURE] " + workerName + " | " + errorMessage + " | " + executionTime + "ms");
    }
}