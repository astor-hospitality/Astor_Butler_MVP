package museon_online.astor_butler.domain.shift;

import museon_online.astor_butler.telegram.adapter.TelegramAdminNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Sends the briefing twice a day: in the first minutes of the shift, and once it is over.
 *
 * Off until `astor.shift.briefing.enabled` says otherwise, so turning the feature on is a separate,
 * deliberate step from deploying it. A failure to build or send is logged and never retried blindly:
 * a briefing is worth nothing an hour late, and a chat full of repeats is worse than a missing line.
 */
@Component
public class ShiftBriefingScheduler {
    private static final Logger log = LoggerFactory.getLogger(ShiftBriefingScheduler.class);
    private final ShiftBriefingService service;
    private final ShiftBriefingFormatter formatter;
    private final TelegramAdminNotifier notifier;
    private final ShiftBriefingProperties properties;

    public ShiftBriefingScheduler(ShiftBriefingService service, ShiftBriefingFormatter formatter,
                                  TelegramAdminNotifier notifier, ShiftBriefingProperties properties) {
        this.service = service;
        this.formatter = formatter;
        this.notifier = notifier;
        this.properties = properties;
    }

    @Scheduled(cron = "${astor.shift.briefing.morning-cron:0 5 10 * * *}", zone = "${astor.shift.briefing.timezone:Asia/Yekaterinburg}")
    public void morning() {
        send(ShiftBriefing.Kind.MORNING, LocalDate.now(properties.zone()));
    }

    @Scheduled(cron = "${astor.shift.briefing.evening-cron:0 30 23 * * *}", zone = "${astor.shift.briefing.timezone:Asia/Yekaterinburg}")
    public void evening() {
        send(ShiftBriefing.Kind.EVENING, LocalDate.now(properties.zone()));
    }

    void send(ShiftBriefing.Kind kind, LocalDate date) {
        if (!properties.isEnabled()) return;
        try {
            ShiftBriefing briefing = service.build(kind, date);
            // An empty morning is still worth saying: "no bookings today" is information for the shift.
            if (briefing.empty() && kind == ShiftBriefing.Kind.EVENING) {
                log.debug("Shift briefing skipped: nothing happened on {}", date);
                return;
            }
            if (!notifier.sendAnalytics(formatter.format(briefing))) {
                log.warn("Shift briefing was not delivered: kind={}, date={}", kind, date);
            }
        } catch (RuntimeException e) {
            log.warn("Shift briefing failed: kind={}, date={}, error={}", kind, date, e.getClass().getSimpleName());
        }
    }
}
