package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/** Wires the return loop and the Tuesday report to the bot and runs them on a schedule; both stay silent while their flags are off. */
@Configuration
public class JourneyLoopConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JourneyLoopConfiguration.class);

    @Bean
    ReturnLoop returnLoop(GuestJourneyProperties properties, GuestVisitRepository visits, JourneyRecorder recorder, UsefulnessPolicy policy,
                          TableReservationNotificationService notifications, TableReservationRepository reservations,
                          TableRequestRouter router, LastLunchService lastLunch,
                          museon_online.astor_butler.domain.lunch.BusinessLunchCatalog lunches,
                          @Value("${astor.business-lunch.default-venue-code:AERIS}") String venueCode) {
        return new ReturnLoop(properties, visits, recorder, policy,
                (chatId, text) -> notifications.notifyGuestChat(chatId, text, "journey-return-loop"),
                reservationId -> reservations.findOrder(reservationId)
                        .map(order -> new ReturnLoop.Guest(order.guestName(), router.waiterName(order).orElse(null))),
                chatId -> lunches.find(venueCode).flatMap(menu -> lastLunch.find(chatId, menu)).isPresent(),
                Clock.systemUTC());
    }

    @Bean
    JourneyWeeklyReport journeyWeeklyReport(@Value("${astor.guest-journey.weekly-report.enabled:false}") boolean enabled,
                                            GuestVisitRepository visits, TableReservationNotificationService notifications,
                                            StringRedisTemplate redis, @Value("${telegram.admin.chat-id:}") String adminChatId,
                                            GuestJourneyProperties properties) {
        return new JourneyWeeklyReport(enabled && adminChatId != null && !adminChatId.isBlank(), visits,
                text -> notifications.sendStaffNotice(adminChatId, text),
                key -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("astor:" + key, "1", Duration.ofDays(8))),
                Clock.systemUTC(), properties.getZoneId());
    }

    @Bean
    JourneySchedules journeySchedules(ReturnLoop returnLoop, JourneyWeeklyReport report) {
        return new JourneySchedules(returnLoop, report);
    }

    /** The two ticks: the return loop every 15 minutes, the report check every 5. */
    static class JourneySchedules {
        private final ReturnLoop returnLoop;
        private final JourneyWeeklyReport report;

        JourneySchedules(ReturnLoop returnLoop, JourneyWeeklyReport report) {
            this.returnLoop = returnLoop;
            this.report = report;
        }

        @Scheduled(fixedDelayString = "${astor.guest-journey.return-loop.poll-ms:900000}", initialDelayString = "${astor.guest-journey.return-loop.initial-delay-ms:120000}")
        public void returnLoop() {
            try {
                returnLoop.tick();
            } catch (RuntimeException e) {
                log.warn("Return loop tick failed: {}", e.toString());
            }
        }

        @Scheduled(fixedDelayString = "${astor.guest-journey.weekly-report.poll-ms:300000}", initialDelayString = "${astor.guest-journey.weekly-report.initial-delay-ms:120000}")
        public void report() {
            try {
                report.tick();
            } catch (RuntimeException e) {
                log.warn("Journey weekly report tick failed: {}", e.toString());
            }
        }
    }
}
