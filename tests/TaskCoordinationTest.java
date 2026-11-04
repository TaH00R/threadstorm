package tests;

import core.Priority;
import core.ThreadStorm;
import task.TaskGroup;
import task.TaskHandle;
import task.TaskStatus;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Smoke tests for task groups, completion handles, and dependency dispatch. */
public final class TaskCoordinationTest {
    private TaskCoordinationTest() { }

    public static void main(String[] args) throws Exception {
        ThreadStorm engine = new ThreadStorm(3, 32);
        engine.start();
        try {
            TaskGroup group = engine.createTaskGroup("coordination-suite");
            AtomicInteger value = new AtomicInteger();
            TaskHandle first = group.submit(context -> value.incrementAndGet());
            TaskHandle second = group.submit(context -> value.addAndGet(2));
            if (!group.awaitAll(5, TimeUnit.SECONDS)) throw new AssertionError("Group did not finish");
            if (first.await() != TaskStatus.SUCCEEDED || second.await() != TaskStatus.SUCCEEDED)
                throw new AssertionError("Group task did not succeed");
            if (group.size() != 2 || group.count(TaskStatus.SUCCEEDED) != 2)
                throw new AssertionError("Group accounting incorrect");

            TaskHandle dependent = engine.submitAfter(List.of(first, second), context -> value.addAndGet(4), Priority.HIGH)
                .get(5, TimeUnit.SECONDS);
            if (dependent.await() != TaskStatus.SUCCEEDED || value.get() != 7)
                throw new AssertionError("Dependency execution incorrect");

            TaskHandle failedDependency = engine.submit(context -> { throw new IllegalStateException("expected"); });
            failedDependency.await();
            boolean rejectedDependency = false;
            try {
                engine.submitAfter(List.of(failedDependency), context -> value.incrementAndGet(), Priority.NORMAL)
                    .get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                rejectedDependency = true;
            }
            if (!rejectedDependency) throw new AssertionError("Failed dependency should prevent dispatch");
            System.out.println("PASS: task groups, completion stages, and dependency dispatch");
        } finally {
            engine.shutdown();
        }
    }
}
