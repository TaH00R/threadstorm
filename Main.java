import core.Priority;
import core.ThreadStorm;
import task.TaskHandle;

import java.util.concurrent.CountDownLatch;

public class Main {
    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(1, 10);
        CountDownLatch workerBusy = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        engine.start();

        // Occupy the only worker so queued tasks can be inspected in scheduling order.
        engine.submit(context -> {
            System.out.println("Worker occupied by gate task");
            workerBusy.countDown();
            releaseWorker.await();
        });
        workerBusy.await();

        engine.submit(context -> System.out.println("LOW task executed"), Priority.LOW);
        engine.submit(context -> System.out.println("NORMAL task executed"), Priority.NORMAL);
        engine.submit(context -> System.out.println("HIGH task executed"), Priority.HIGH);
        engine.submit(context -> System.out.println("CRITICAL task executed"), Priority.CRITICAL);
        engine.submit(context -> System.out.println("Second HIGH task executed"), Priority.HIGH);

        System.out.println("Queued tasks: " + engine.getQueueSize());
        System.out.println("Releasing worker; tasks should run by priority, FIFO within equal priority.");
        releaseWorker.countDown();
        engine.shutdown();
        System.out.println("Engine state: " + engine.getState());
    }
}
