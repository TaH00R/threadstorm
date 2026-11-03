import core.Priority;
import core.ThreadStorm;

void main() throws Exception {
    ThreadStorm engine = new ThreadStorm(1, 10);
    CountDownLatch workerBusy = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    engine.start();

    // Occupy the only worker so queued tasks can be inspected in scheduling order.
    engine.submit(context -> {
        IO.println("Worker occupied by gate task");
        workerBusy.countDown();
        releaseWorker.await();
    });
    workerBusy.await();

    engine.submit(context -> IO.println("LOW task executed"), Priority.LOW);
    engine.submit(context -> IO.println("NORMAL task executed"), Priority.NORMAL);
    engine.submit(context -> IO.println("HIGH task executed"), Priority.HIGH);
    engine.submit(context -> IO.println("CRITICAL task executed"), Priority.CRITICAL);
    engine.submit(context -> IO.println("Second HIGH task executed"), Priority.HIGH);

    IO.println("Queued tasks: " + engine.getQueueSize());
    IO.println("Releasing worker; tasks should run by priority, FIFO within equal priority.");
    releaseWorker.countDown();
    engine.shutdown();
    IO.println("Engine state: " + engine.getState());
}
