package museon_online.astor_butler.domain.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.journey.talk.ButlerTalk;
import museon_online.astor_butler.domain.journey.talk.ButlerTalkConfiguration;
import museon_online.astor_butler.domain.journey.talk.ButlerTalkProperties;
import museon_online.astor_butler.domain.journey.talk.SeatedHint;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import museon_online.astor_butler.integration.saby.SabyNutritionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The guest journey beans compose with the rest of the bot, so a deploy with every flag off still starts. */
class GuestJourneyWiringTest {

    @Test
    void journeyBeansComposeWithTheBot() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(TableReservationRepository.class, () -> mock(TableReservationRepository.class));
            context.registerBean(TipsPersonalRepository.class, () -> mock(TipsPersonalRepository.class));
            context.registerBean(TableReservationNotificationService.class, () -> mock(TableReservationNotificationService.class));
            context.registerBean(GuestBillRepository.class, () -> mock(GuestBillRepository.class));
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.registerBean(SabyNutritionService.class, () -> mock(SabyNutritionService.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(BusinessLunchCatalog.class, () -> new BusinessLunchCatalog(List.of()));
            context.register(GuestJourneyProperties.class, RepeatLunchProperties.class, UsefulnessPolicyProperties.class,
                    ButlerTalkProperties.class, GuestVisitRepository.class, JourneyRecorder.class, JourneyEvents.class,
                    LastLunchService.class, RepeatLunchGreeting.class, UsefulnessPolicy.class, OptOutCommand.class, ReturnMatcher.class,
                    VenueFactsCatalog.class, ButlerTalkConfiguration.class, JourneyLoopConfiguration.class);
            context.refresh();

            assertThat(context.getBean(ButlerTalk.class).enabled()).isFalse();
            assertThat(context.getBean(TableRequestRouter.class)).isNotNull();
            assertThat(context.getBean(SeatedHint.class)).isNotNull();
            assertThat(context.getBean(ReturnLoop.class)).isNotNull();
            assertThat(context.getBean(JourneyWeeklyReport.class)).isNotNull();
            assertThat(context.getBean(JourneyRecorder.class).enabled()).isFalse();
        }
    }
}
