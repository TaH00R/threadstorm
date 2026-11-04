package cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

record TerminalSize(int columns, int rows) {
    static TerminalSize detect() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        try {
            Process process = windows
                ? new ProcessBuilder("cmd", "/c", "mode con").redirectErrorStream(true).start()
                : new ProcessBuilder("sh", "-c", "stty size < /dev/tty").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            if (windows) {
                int cols = parseAfter(output, "Columns:");
                int lines = parseAfter(output, "Lines:");
                if (cols > 0 && lines > 0) return new TerminalSize(cols, lines);
            } else {
                String[] parts = output.trim().split("\\s+");
                if (parts.length == 2) return new TerminalSize(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
            }
        } catch (Exception ignored) { }
        // A conservative fallback avoids writing past smaller terminal windows.
        return new TerminalSize(100, 30);
    }
    private static int parseAfter(String text, String key) {
        int index = text.toLowerCase(Locale.ROOT).indexOf(key.toLowerCase(Locale.ROOT));
        if (index < 0) return -1;
        String digits = text.substring(index + key.length()).replaceAll("[^0-9].*$", "").trim();
        try { return Integer.parseInt(digits); } catch (NumberFormatException ex) { return -1; }
    }
}
