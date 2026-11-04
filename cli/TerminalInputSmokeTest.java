package cli;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Standalone cross-platform line-decoding regression test. */
public final class TerminalInputSmokeTest {
    public static void main(String[] args) throws Exception {
        String sequence = "d\r\ns\r\nmy task\r\nq\r\ny\r\n";
        try (TerminalInput input = new TerminalInput(
                new ByteArrayInputStream(sequence.getBytes(StandardCharsets.US_ASCII)), true)) {
            for (String expected : new String[]{"d", "s", "my task", "q", "y"}) {
                TerminalInput.Input event = input.read();
                if (event.key() != TerminalInput.Key.OTHER || !expected.equals(event.text())) {
                    throw new AssertionError("Expected " + expected + ", got " + event);
                }
            }
        }
        System.out.println("TERMINAL INPUT PASS: CRLF shortcuts and overlay text");
    }
}
