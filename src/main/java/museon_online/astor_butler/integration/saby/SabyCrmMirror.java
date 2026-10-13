package museon_online.astor_butler.integration.saby;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitEvent;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyRecorder;
import museon_online.astor_butler.domain.journey.JourneyStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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

    /**
     * In order within each visit; a visit whose event fails waits for the next run, the others go on. An event that keeps
     * failing is dropped after {@link GuestVisitRepository#MIRROR_ATTEMPTS}. A deal whose creation Saby may have done
     * but did not confirm is parked as {@link #PENDING} for a manual check rather than created twice.
     */
    public void flush() {
        if (!properties.ready()) {
            return;
        }
        Set<Long> held = new HashSet<>();
        for (GuestVisitEvent event : visits.unmirrored(BATCH)) {
            if (held.contains(event.visitId())) {
                continue;
            }
            try {
                Optional<GuestVisit> visit = visits.findById(event.visitId());
                if (visit.isEmpty()) {
                    visits.markMirrored(event.id());
                    continue;
                }
                Optional<String> lead = lead(visit.get());
                if (lead.isEmpty()) {
                    held.add(event.visitId());
                    continue;
                }
                JourneyStage stage = event.kind().stage();
                boolean moves = stage != null && JourneyRecorder.moves(visits.sabyStage(visit.get().id()).orElse(null), stage);
                client.addEvent(lead.get(), moves ? properties.phaseDirection(stage) : null,
                        event.axis().name() + ": " + event.kind().name());
                if (moves) {
                    visits.setSabyStage(visit.get().id(), stage);
                }
                visits.markMirrored(event.id());
            } catch (RuntimeException e) {
                held.add(event.visitId());
                visits.markMirrorFailed(event.id());
                log.warn("Saby CRM mirror held visit {} at event {}: {}", event.visitId(), event.id(), e.toString());
            }
        }
    }

    /** The visit's deal, created on first need; empty while it is parked. */
    private Optional<String> lead(GuestVisit visit) {
        if (PENDING.equals(visit.sabyLeadId())) {
            log.warn("Saby CRM deal of visit {} is unconfirmed — check the funnel and set saby_lead_id by hand", visit.id());
            return Optional.empty();
        }
        if (visit.sabyLeadId() != null) {
            return Optional.of(visit.sabyLeadId());
        }
        visits.setSabyLead(visit.id(), PENDING);
        String lead;
        try {
            lead = client.insertLead(visit, names.of(visit.reservationId()).orElse(null));
        } catch (SabyApiException e) {
            if (refused(e)) {
                visits.setSabyLead(visit.id(), null);
            }
            throw e;
        }
        visits.setSabyLead(visit.id(), lead);
        return Optional.of(lead);
    }

    /**
     * Saby answered and said no (an error status or an RPC error, or we were not let in): nothing was created, so the
     * deal may be tried again. A timeout or an unreadable answer may hide a created deal — that one is parked.
     */
    private static boolean refused(SabyApiException e) {
        return e.kind() == SabyApiException.Kind.HTTP_ERROR || e.kind() == SabyApiException.Kind.AUTH_FAILED;
    }

    public static final String PENDING = "PENDING";
}
