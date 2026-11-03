package core;

import metrics.MetricsCollector;
import java.util.ArrayList;
import java.util.List;

final class WorkerPool {
    private final List<Worker> workers = new ArrayList<>();

    WorkerPool(int workerCount, TaskQueue queue, MetricsCollector metrics, DeadLetterQueue deadLetters) {
        for (int i = 1; i <= workerCount; i++) {
            workers.add(new Worker("Worker-" + i, queue, metrics, deadLetters));
        }
    }

    void start() { for (Worker worker : workers) worker.start(); }
    void awaitTermination() throws InterruptedException {
        for (Worker worker : workers) worker.join();
    }
    int size() { return workers.size(); }
}
