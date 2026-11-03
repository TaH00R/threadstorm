package core;

/** Scheduling priority. Lower rank values are more urgent. */
public enum Priority {
    CRITICAL(0), HIGH(1), NORMAL(2), LOW(3);

    private final int rank;
    Priority(int rank) { this.rank = rank; }
    int rank() { return rank; }
}
