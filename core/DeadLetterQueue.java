package core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thread-safe in-memory store for tasks that exhausted retries. */
public final class DeadLetterQueue {
    private final List<DeadLetterEntry> entries = new ArrayList<>();
    public synchronized void add(DeadLetterEntry entry) { entries.add(entry); }
    public synchronized List<DeadLetterEntry> getEntries() { return Collections.unmodifiableList(new ArrayList<>(entries)); }
    public synchronized int size() { return entries.size(); }
}
