package museon_online.astor_butler.api.glasses.tasks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps everything in one JVM and loses it on restart. Good for tests and for a single-instance pilot;
 * real assignments need a database adapter behind the same port.
 */
public final class InMemoryStaffTaskStore implements StaffTaskStore {
    private final Map<String, StaffTask> tasks = new HashMap<>();
    private final Map<String, Long> changedAt = new HashMap<>();
    private final Map<String, ProcessedEvent> events = new HashMap<>();
    private long sequence;

    @Override public synchronized void insert(StaffTask task) {
        if (tasks.containsKey(task.taskId())) throw new IllegalStateException("Task already exists");
        put(task);
    }

    @Override public synchronized Optional<StaffTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override public synchronized boolean replace(StaffTask next, long expectedVersion) {
        StaffTask stored = tasks.get(next.taskId());
        if (stored == null || stored.version() != expectedVersion) return false;
        put(next);
        return true;
    }

    @Override public synchronized List<StaffTask> byTenant(String tenant) {
        return tasks.values().stream()
                .filter(task -> task.tenant().equals(tenant))
                .sorted(Comparator.comparingLong((StaffTask task) -> changedAt.get(task.taskId())).reversed())
                .toList();
    }

    @Override public synchronized Page changedFor(String tenant, String staffId, long cursor, int limit) {
        List<StaffTask> items = new ArrayList<>(tasks.values().stream()
                .filter(task -> task.tenant().equals(tenant) && task.assigneeStaffId().equals(staffId))
                .filter(task -> changedAt.get(task.taskId()) > cursor)
                .sorted(Comparator.comparingLong(task -> changedAt.get(task.taskId())))
                .limit(limit)
                .toList());
        long next = items.isEmpty() ? cursor : changedAt.get(items.get(items.size() - 1).taskId());
        return new Page(items, next);
    }

    @Override public synchronized Optional<ProcessedEvent> event(String tenant, String eventId) {
        return Optional.ofNullable(events.get(tenant + "\n" + eventId));
    }

    @Override public synchronized void remember(ProcessedEvent event) {
        events.put(event.tenant() + "\n" + event.eventId(), event);
    }

    private void put(StaffTask task) {
        tasks.put(task.taskId(), task);
        changedAt.put(task.taskId(), ++sequence);
    }
}
