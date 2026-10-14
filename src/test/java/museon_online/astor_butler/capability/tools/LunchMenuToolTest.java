package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.integration.saby.SabyNutrition;
import museon_online.astor_butler.integration.saby.SabyNutritionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LunchMenuToolTest {

    // Wednesday 2026-10-14, 10:00 in Yekaterinburg.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneOffset.UTC);

    private SabyNutritionService nutrition;
    private LunchMenuTool tool;

    @BeforeEach
    void setUp() {
        BusinessLunchOffer offer = new BusinessLunchOffer("AERIS", "AERIS", true,
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(12, 0), LocalTime.of(16, 0), 90, "",
                List.of(new BusinessLunchOffer.Course("SOUP", "Суп", List.of(
                        new BusinessLunchOffer.Dish("PEA", "Гороховый суп", 290, null, null, null, null, null, "Гороховый суп ЛАНЧ"),
                        new BusinessLunchOffer.Dish("CREAM", "Крем-суп", 230, null)))),
                List.of());
        nutrition = mock(SabyNutritionService.class);
        when(nutrition.nutrition(anyString())).thenReturn(Optional.empty());
        when(nutrition.nutrition("Гороховый суп ЛАНЧ")).thenReturn(Optional.of(new SabyNutrition(300, 210, 12, 8, 24)));
        tool = new LunchMenuTool(new BusinessLunchCatalog(List.of(offer)), nutrition, new CapabilityProperties(),
                new GuestJourneyProperties(), CLOCK);
    }

    @Test
    void nutritionIsAddedWhenSabyKnowsIt() {
        CapabilityResult result = tool.invoke(null, Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("from", "12:00").containsEntry("to", "16:00").containsEntry("served", true);
        assertThat(result.facts().get("days")).isEqualTo(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courses = (List<Map<String, Object>>) result.facts().get("courses");
        assertThat(courses).singleElement().satisfies(course -> assertThat(course).containsEntry("title", "Суп"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dishes = (List<Map<String, Object>>) courses.getFirst().get("dishes");
        assertThat(dishes.get(0)).containsEntry("code", "PEA").containsEntry("title", "Гороховый суп")
                .containsEntry("priceRub", 290).containsEntry("grams", 300).containsEntry("kcal", 210)
                .containsEntry("protein", 12).containsEntry("fat", 8).containsEntry("carbs", 24);
        assertThat(dishes.get(1)).containsEntry("code", "CREAM").doesNotContainKeys("grams", "kcal", "protein", "fat", "carbs");
    }

    @Test
    void nutritionIsCachedForTenMinutes() {
        tool.invoke(null, Map.of());
        tool.invoke(null, Map.of());

        verify(nutrition, times(1)).nutrition("Гороховый суп ЛАНЧ");
        verify(nutrition, times(1)).nutrition("Крем-суп");
    }

    @Test
    void saturdayIsNotALunchDay() {
        CapabilityResult result = tool.invoke(null, Map.of("date", "2026-10-17"));

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("served", false);
    }

    @Test
    void unreadableDateIsABadArgument() {
        assertThat(tool.invoke(null, Map.of("date", "завтра")).error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
    }
}
