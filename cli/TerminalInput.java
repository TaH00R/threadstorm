package cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Portable terminal input with no shell-specific subprocess on Windows.
 * On POSIX TTYs, stty enables single-key input. On Windows and redirected
 * consoles, keys are read as lines (type a shortcut then Enter).
 */
final class TerminalInput implements AutoCloseable {
    enum Key { UP, DOWN, LEFT, RIGHT, ENTER, BACKSPACE, ESCAPE, TAB, CTRL_C, OTHER }
    record Input(Key key, String text) { }

    private final InputStream stream;
    private final boolean lineMode;
    private final String savedStty;
    private boolean closed;
    private boolean ignoreNextLf;

    TerminalInput() throws IOException { this(System.in, false); }

    // Package-private injection makes the input decoder testable without a console.
    TerminalInput(InputStream stream, boolean forceLineMode) throws IOException {
        this.stream = stream;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean explicitLine = forceLineMode || Boolean.getBoolean("threadstorm.lineInput");
        String original = null;
        if (!windows && !explicitLine && System.console() != null) {
            try {
                original = runStty("-g").trim();
                runStty("-echo -icanon min 1 time 0");
            } catch (IOException ex) {
                // Unsupported TTY (IDE, terminal multiplexer without /dev/tty): line mode.
                original = null;
            }
        }
        this.savedStty = original;
        this.lineMode = windows || explicitLine || original == null;
    }

    boolean isLineMode() { return lineMode; }

    Input read() throws IOException {
        if (closed) throw new IOException("Terminal reader closed");
        return lineMode ? readLineInput() : readRawInput();
    }

    private Input readLineInput() throws IOException {
        // Read a complete line and convert it to the same events as raw input.
        // Line mode works in PowerShell, cmd.exe, pwsh, bash, zsh and IDE consoles.
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = stream.read();
            if (c < 0) throw new IOException("Terminal input stream ended (EOF)");
            if (ignoreNextLf) {
                ignoreNextLf = false;
                if (c == '\n') continue;
            }
            if (c == '\r') { ignoreNextLf = true; break; }
            if (c == '\n') break;
            if (c == 3) return new Input(Key.CTRL_C, "");
            if (c == 8 || c == 127) { if (line.length() > 0) line.deleteCharAt(line.length() - 1); continue; }
            if (line.length() < 4096) line.append((char)c);
        }
        // Empty line is Enter; a typed key plus Enter generates key then Enter.
        if (line.length() == 0) return new Input(Key.ENTER, "");
        String value = line.toString();
        if (value.equalsIgnoreCase("esc") || value.equalsIgnoreCase("escape")) return new Input(Key.ESCAPE, "");
        if (value.equalsIgnoreCase("up")) return new Input(Key.UP, "");
        if (value.equalsIgnoreCase("down")) return new Input(Key.DOWN, "");
        if (value.equalsIgnoreCase("left")) return new Input(Key.LEFT, "");
        if (value.equalsIgnoreCase("right")) return new Input(Key.RIGHT, "");
        if (value.equalsIgnoreCase("tab")) return new Input(Key.TAB, "");
        // Text goes into overlays without losing characters; outside overlays,
        // the TUI uses its first character as a shortcut.
        return new Input(Key.OTHER, value);
    }

    private Input readRawInput() throws IOException {
        int c = stream.read();
        if (c < 0) throw new IOException("Terminal input stream ended (EOF)");
        if (c == 3) return new Input(Key.CTRL_C, "");
        if (c == '\r' || c == '\n') return new Input(Key.ENTER, "");
        if (c == '\t') return new Input(Key.TAB, "");
        if (c == 8 || c == 127) return new Input(Key.BACKSPACE, "");
        if (c == 27) {
            // Escape sequence is usually delivered in one burst.
            if (stream.available() <= 0) return new Input(Key.ESCAPE, "");
            int second = stream.read();
            if (second == '[' && stream.available() > 0) {
                return switch (stream.read()) {
                    case 'A' -> new Input(Key.UP, "");
                    case 'B' -> new Input(Key.DOWN, "");
                    case 'C' -> new Input(Key.RIGHT, "");
                    case 'D' -> new Input(Key.LEFT, "");
                    default -> new Input(Key.ESCAPE, "");
                };
            }
            return new Input(Key.ESCAPE, "");
        }
        return new Input(Key.OTHER, new String(new byte[]{(byte)c}, StandardCharsets.UTF_8));
    }

    private static String runStty(String argument) throws IOException {
        Process process = new ProcessBuilder("sh", "-c", "stty " + argument + " < /dev/tty")
            .redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IOException("Unable to configure terminal: " + output.trim());
            return output;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while configuring terminal", ex);
        }
    }

    @Override public void close() {
        closed = true;
        if (savedStty != null && !savedStty.isBlank()) {
            try { runStty(savedStty); } catch (IOException ignored) { }
        }
    }
}
