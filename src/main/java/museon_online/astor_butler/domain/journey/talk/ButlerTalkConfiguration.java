package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.UsefulnessPolicy;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.roster.TableWaiters;
import museon_online.astor_butler.domain.tips.TipStaffMember;
import museon_online.astor_butler.domain.tips.TipsPersonalProperties;
import museon_online.astor_butler.integration.saby.SabyNutritionService;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Wires the butler's talk to the bot: reservations, the waiter of the table, the staff chat's wishes topic, Saby nutrition. */
@Configuration
public class ButlerTalkConfiguration {

    /**
     * The waiter of the table: the day's assignment, or for a request the waiter on shift whose standing zone holds the
     * table (kept for the day). The tenant and the shift day are those of /официант and /зона (astor.tips.personal).
     */
    @Bean
    TableRequestRouter tableRequestRouter(TableReservationRepository reservations, TableWaiters tableWaiters,
                                          TableRequestCards cards, TipsPersonalProperties tips) {
        TableRequestRouter.Waiters waiters = new TableRequestRouter.Waiters() {
            @Override
            public Optional<String> nameOf(String tableCode, LocalDate shiftDate) {
                return tableWaiters.assigned(tableCode, shiftDate).map(TipStaffMember::displayName);
            }

            @Override
            public TableRequestRouter.Addressee forRequest(String tableCode, LocalDate shiftDate) {
                TableWaiters.TableWaiter waiter = tableWaiters.forRequest(tableCode, shiftDate);
                return new TableRequestRouter.Addressee(waiter.known() ? waiter.name() : null, waiter.zone());
            }
        };
        return new TableRequestRouter(reservations::findOrder, waiters, cards, Clock.systemUTC(), tips.zone().getId());
    }

    /** Table requests to the staff group's wishes topic, addressed to the waiter; the bookings topic without it. */
    @Bean
    TableRequestCards tableRequestCards(ObjectProvider<TelegramBot> telegramBot, TableReservationNotificationService notifications,
                                        @Value("${telegram.bot.enabled:false}") boolean telegramEnabled,
                                        @Value("${telegram.booking.notifications-enabled:true}") boolean notificationsEnabled,
                                        @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId,
                                        @Value("${telegram.booking.wishes-thread-id:}") String wishesThreadId) {
        return new TableRequestCards(telegramBot, notifications, telegramEnabled, notificationsEnabled, hostessChatId, wishesThreadId);
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
