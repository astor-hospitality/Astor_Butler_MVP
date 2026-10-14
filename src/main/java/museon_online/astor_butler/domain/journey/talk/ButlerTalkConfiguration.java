package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.UsefulnessPolicy;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.tips.TipStaffMember;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import museon_online.astor_butler.integration.saby.SabyNutritionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;

/** Wires the butler's talk to the bot: reservations, the waiter of the table, the staff chat, Saby nutrition. */
@Configuration
public class ButlerTalkConfiguration {

    @Bean
    TableRequestRouter tableRequestRouter(TableReservationRepository reservations, TipsPersonalRepository staff,
                                          TableReservationNotificationService notifications, ButlerTalkProperties properties) {
        return new TableRequestRouter(reservations::findOrder,
                (tableCode, day) -> staff.waiterOfTable(properties.getStaffTenant(), tableCode, day).map(TipStaffMember::displayName),
                notifications::notifyStaffGuestRequest, Clock.systemUTC(), "Asia/Yekaterinburg");
    }

    @Bean
    ButlerTalk butlerTalk(ButlerTalkProperties properties, VenueFactsCatalog facts, SabyNutritionService nutrition,
                          BusinessLunchCatalog menu, TableRequestRouter router, TableReservationNotificationService notifications,
                          JourneyEvents events, GuestVisitRepository visits,
                          @Value("${astor.business-lunch.default-venue-code:AERIS}") String venueCode) {
        ButlerTalk talk = new ButlerTalk(properties, facts, nutrition::nutrition, menu, router, notifications::notifyStaffGuestQuestion,
                events, venueCode);
        talk.setVisitLookup(chatId -> visits.activeByChat(chatId, Instant.now()));
        return talk;
    }

    @Bean
    SeatedHint seatedHint(ButlerTalkProperties properties, UsefulnessPolicy policy, TableRequestRouter router,
                          TableReservationNotificationService notifications) {
        return new SeatedHint(properties, policy, router, (order, text) -> notifications.notifyGuestText(order, text, "seated-hint"));
    }
}
