package museon_online.astor_butler.domain.summary;

import org.springframework.stereotype.Component;

import java.util.List;

/** What to remember about a guest next time: the notes of their last three summarized visits, newest first. */
@Component
public class GuestMemory {

    static final int VISITS = 3;

    private final VisitSummaryRepository summaries;

    public GuestMemory(VisitSummaryRepository summaries) {
        this.summaries = summaries;
    }

    /** By phone when it finds anything, otherwise by chat; empty when the guest has no summaries. */
    public List<String> of(String phone, Long chatId) {
        List<VisitSummary> latest = phone == null || phone.isBlank() ? List.of() : summaries.latestByPhone(phone.trim(), VISITS);
        if (latest.isEmpty() && chatId != null) {
            latest = summaries.latestByChat(chatId, VISITS);
        }
        return latest.stream().flatMap(summary -> summary.nextTimeGuest().stream()).toList();
    }
}
