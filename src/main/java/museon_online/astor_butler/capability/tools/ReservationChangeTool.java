package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.booking.TableReservationChangeCommand;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code reservation_change{reservationId, date?, time?, partySize?}}: the guest moves their own reservation. What is
 * not given stays as it was; a new time keeps the visit length. The change goes back to the hostess.
 *
 * <p>Only after the guest's yes to this very change, offered in the venue's previous answer
 * ({@link GuestAgreement#allowsChange}: the same reservation, day, time and party size), whatever the model decides.
 * Otherwise nothing changes and the answer is {@code NOT_ALLOWED} with {@code reason = needs_guest_yes}, the
 * reservation, {@code action = change} and the new day, time or party size, so the model can name them and ask; that
 * refusal is the offer the guest's next yes answers. A REST call carries no agreement and is always refused.
 */
@Component
public class ReservationChangeTool implements CapabilityTool {

    private final TableReservationService reservations;
    private final ReservationAccess access;
    private final GuestJourneyProperties journeyProperties;

    public ReservationChangeTool(TableReservationService reservations, ReservationAccess access,
                                 GuestJourneyProperties journeyProperties) {
        this.reservations = reservations;
        this.access = access;
        this.journeyProperties = journeyProperties;
    }

    @Override
    public String name() {
        return "reservation_change";
    }

    @Override
    public String description() {
        return "Изменить бронь гостя: день, время или число гостей. Чего нет в аргументах — остаётся как было. "
                + "Только после «да» гостя на это же изменение, которое ты назвал ему в прошлом ответе. Без этого — "
                + "NOT_ALLOWED, reason=needs_guest_yes, с новыми днём, временем или числом гостей — назови их гостю и спроси. "
                + "Изменённая бронь снова ждёт подтверждения хостес. Возвращает reservationId и status.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("reservationId", Map.of("type", "integer", "description", "Номер брони гостя"));
        props.put("date", Map.of("type", "string", "description", "Новый день в формате YYYY-MM-DD"));
        props.put("time", Map.of("type", "string", "description", "Новое время в формате HH:mm, по времени ресторана"));
        props.put("partySize", Map.of("type", "integer", "description", "Новое число гостей"));
        return Map.of("type", "object", "properties", props, "required", List.of("reservationId"));
    }

    @Override
    public boolean commitment() {
        return true;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        ReservationAccess.Checked checked = access.checkOrOnly(ctx, args);
        if (!checked.allowed()) {
            return checked.error();
        }
        TableReservationOrder current = checked.order();
        LocalDate date = CapabilityArguments.date(args, "date");
        LocalTime time = CapabilityArguments.time(args, "time");
        Integer partySize = CapabilityArguments.integer(args, "partySize");
        if (partySize != null && partySize < 1) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        if (date == null && time == null && partySize == null) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        Instant start = null;
        Instant end = null;
        if (date != null || time != null) {
            if (current.requestedStartAt() == null && (date == null || time == null)) {
                return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
            }
            ZonedDateTime was = current.requestedStartAt() == null ? null : current.requestedStartAt().atZone(zone());
            start = (date != null ? date : was.toLocalDate()).atTime(time != null ? time : was.toLocalTime())
                    .atZone(zone()).toInstant();
            end = start.plus(ReservationCreateTool.VISIT_LENGTH);
        }
        GuestAgreement.Change change = new GuestAgreement.Change(current.id(), date, time, partySize);
        if (ctx.agreement() == null || !ctx.agreement().allowsChange(change)) {
            return GuestAgreement.refusal(current.id(), GuestAgreement.CHANGE, change.facts());
        }
        TableReservationOrder changed;
        try {
            changed = reservations.changeByGuest(current.id(), new TableReservationChangeCommand(
                    null, null, null, null, start, end, partySize, null));
        } catch (ApiException e) {
            return ReservationAccess.refusal(e, "table_busy");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", changed.id());
        facts.put("status", ReservationAccess.status(changed.status()));
        return CapabilityResult.ok(facts);
    }

    private ZoneId zone() {
        return ZoneId.of(journeyProperties.getZoneId());
    }
}
