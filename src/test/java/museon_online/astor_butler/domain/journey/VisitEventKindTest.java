package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class VisitEventKindTest {

    @Test
    void everyKindHasAxis() {
        assertThat(Arrays.stream(VisitEventKind.values())).allMatch(kind -> kind.axis() != null);
    }

    @Test
    void everyAxisOfTheDiplomaIsReachable() {
        assertThat(Arrays.stream(VisitEventKind.values()).map(VisitEventKind::axis))
                .contains(JourneyAxis.IDENTITY, JourneyAxis.PERSONALIZATION, JourneyAxis.GRATITUDE, JourneyAxis.INFO,
                        JourneyAxis.SOCIAL, JourneyAxis.PLAY, JourneyAxis.TIME, JourneyAxis.SAFETY);
    }
}
