package museon_online.astor_butler.integration.saby;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitEvent;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Mirrors the guest journey ledger into the Saby CRM funnel «Визит гостя AERIS» (spec 2026-10-13 §3.4): events go in their
 * order; the first one of a visit creates its deal. A failure stops the run and the rest waits for the next one, so the
 * order holds and the deal is never created twice (its id is kept on the visit).
 */
@Component
@Slf4j
public class SabyCrmMirror {

    static final int BATCH = 50;

    /** The guest's name for the deal, by reservation. */
    interface Names {
        Optional<String> of(long reservationId);
    }

    private final SabyCrmProperties properties;
    private final SabyCrmClient client;
    private final GuestVisitRepository visits;
    private final Names names;

    @Autowired
    public SabyCrmMirror(SabyCrmProperties properties, SabyReservationProperties saby, RestTemplateBuilder restTemplateBuilder,
                         GuestVisitRepository visits, TableReservationRepository reservations) {
        this(properties, new SabyCrmClient(new SabyApiClient(saby, restTemplateBuilder), properties), visits,
                reservationId -> reservations.findOrder(reservationId).map(TableReservationOrder::guestName));
    }

    SabyCrmMirror(SabyCrmProperties properties, SabyCrmClient client, GuestVisitRepository visits, Names names) {
        this.properties = properties;
        this.client = client;
        this.visits = visits;
        this.names = names;
    }

    @Scheduled(fixedDelayString = "${astor.integrations.saby.crm.poll-ms:60000}", initialDelayString = "${astor.integrations.saby.crm.initial-delay-ms:90000}")
    public void scheduled() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Saby CRM mirror run failed: {}", e.toString());
        }
    }

    public void flush() {
        if (!properties.ready()) {
            return;
        }
        for (GuestVisitEvent event : visits.unmirrored(BATCH)) {
            try {
                Optional<GuestVisit> visit = visits.findById(event.visitId());
                if (visit.isEmpty()) {
                    visits.markMirrored(event.id());
                    continue;
                }
                String lead = visit.get().sabyLeadId();
                if (lead == null) {
                    lead = client.insertLead(visit.get(), names.of(visit.get().reservationId()).orElse(null));
                    visits.setSabyLead(visit.get().id(), lead);
                }
                client.addEvent(lead, properties.phaseDirection(event.kind().stage()), event.axis().name() + ": " + event.kind().name());
                visits.markMirrored(event.id());
            } catch (RuntimeException e) {
                log.warn("Saby CRM mirror stopped at event {}: {}", event.id(), e.toString());
                return;
            }
        }
    }
}
