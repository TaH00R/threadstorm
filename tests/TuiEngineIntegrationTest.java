package tests;

import core.ThreadStorm;
import core.SubmissionPolicy;
import core.Priority;
import task.TaskHandle;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/** Verifies the engine can be embedded by a terminal UI without corrupting its screen. */
public final class TuiEngineIntegrationTest {
    private TuiEngineIntegrationTest() { }

    public static void main(String[] args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream sink = new PrintStream(captured)) {
            System.setOut(sink);
            ThreadStorm engine = new ThreadStorm(2, 8, true);
            engine.start();
            TaskHandle handle = engine.submit(context -> {
                Thread.sleep(10);
                context.checkpoint();
            }, Priority.NORMAL, SubmissionPolicy.REJECT, 0, TimeUnit.MILLISECONDS);
            if (handle.await(2, TimeUnit.SECONDS) == null) throw new AssertionError("Task did not finish");
            engine.shutdown();
        } finally {
            System.setOut(original);
        }
        if (captured.size() != 0) throw new AssertionError("Quiet engine wrote to stdout: " + captured);
        System.out.println("TUI INTEGRATION PASS: quiet engine does not write over the terminal screen.");
    }
}
