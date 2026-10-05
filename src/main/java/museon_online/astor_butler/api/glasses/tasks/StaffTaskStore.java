package museon_online.astor_butler.api.glasses.tasks;

import java.util.List;
import java.util.Optional;

/**
 * Storage port for tasks and processed events. The service holds the rules; an adapter only keeps data.
 * A database adapter must make {@link #replace} a real compare-and-set on the version column.
 */
public interface StaffTaskStore {

    void insert(StaffTask task);

    Optional<StaffTask> find(String taskId);

    /** Stores the next value only if the stored version still equals expectedVersion. */
    boolean replace(StaffTask next, long expectedVersion);

    /** Tasks of one venue, most recently changed first. */
    List<StaffTask> byTenant(String tenant);

    /**
     * Tasks assigned to one staff member that changed after the cursor, oldest change first.
     * The cursor is the position returned with the previous page; 0 reads from the beginning.
     */
    Page changedFor(String tenant, String staffId, long cursor, int limit);

    Optional<ProcessedEvent> event(String tenant, String eventId);

    void remember(ProcessedEvent event);

    record Page(List<StaffTask> items, long nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** What a client event did. A retry with the same id and fingerprint gets the same result back. */
    record ProcessedEvent(String tenant, String eventId, String fingerprint, StaffTask result) { }
}
