package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchFixtures;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepeatLunchGreetingTest {

    private static final long CHAT = 421441838L;

    private final LastLunchService lastLunch = mock(LastLunchService.class);
    private final RepeatLunchProperties properties = new RepeatLunchProperties();
    private final RepeatLunchGreeting greeting = new RepeatLunchGreeting(properties, lastLunch,
            new BusinessLunchCatalog(List.of(BusinessLunchFixtures.aLaCarte())), "CARTE");

    private static IncomingMessage start(String firstName) {
        return IncomingMessage.telegram(CHAT, CHAT, 1, 100, "/start", null, firstName, null, "guest", "ru", false, "c");
    }

    @Test
    void knownGuestGreetingNamesLastDishesWhenFlagOn() {
        properties.setEnabled(true);
        when(lastLunch.find(eq(CHAT), any())).thenReturn(Optional.of(new LastLunch(List.of("BORSCHT", "CAPPUCCINO"),
                List.of("Борщ со сметаной", "Капучино"))));

        assertThat(greeting.text(start("Мария"))).contains(
                "С возвращением, Мария! В прошлый раз у вас был Борщ со сметаной, Капучино. Повторить или собрать заново?");
    }

    @Test
    void withoutANameTheGreetingHasNoAddress() {
        properties.setEnabled(true);
        when(lastLunch.find(eq(CHAT), any())).thenReturn(Optional.of(new LastLunch(List.of("BORSCHT"), List.of("Борщ со сметаной"))));

        assertThat(greeting.text(start(null))).contains("С возвращением! В прошлый раз у вас был Борщ со сметаной. Повторить или собрать заново?");
    }

    @Test
    void noGreetingWithoutALastLunch() {
        properties.setEnabled(true);
        when(lastLunch.find(eq(CHAT), any())).thenReturn(Optional.empty());

        assertThat(greeting.text(start("Мария"))).isEmpty();
    }

    @Test
    void noGreetingWhenFlagOff() {
        assertThat(greeting.text(start("Мария"))).isEmpty();
    }
}
