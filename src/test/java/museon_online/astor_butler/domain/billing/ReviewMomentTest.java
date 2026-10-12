package museon_online.astor_butler.domain.billing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The moment is read as the env names it; anything unknown falls back to the way it was. */
class ReviewMomentTest {

    @Test
    void readsTheEnvSpellingAndFallsBackToAfterClose() {
        assertThat(ReviewMoment.parse("on-bill")).isEqualTo(ReviewMoment.ON_BILL);
        assertThat(ReviewMoment.parse(" ON_BILL ")).isEqualTo(ReviewMoment.ON_BILL);
        assertThat(ReviewMoment.parse("after-close")).isEqualTo(ReviewMoment.AFTER_CLOSE);
        assertThat(ReviewMoment.parse(null)).isEqualTo(ReviewMoment.AFTER_CLOSE);
        assertThat(ReviewMoment.parse("")).isEqualTo(ReviewMoment.AFTER_CLOSE);
        assertThat(ReviewMoment.parse("tomorrow")).isEqualTo(ReviewMoment.AFTER_CLOSE);
    }

    @Test
    void springBindsTheEnvSpellingToTheMoment() {
        Binder onBill = new Binder(new MapConfigurationPropertySource(Map.of("astor.review.moment", "on-bill")));
        Binder afterClose = new Binder(new MapConfigurationPropertySource(Map.of("astor.review.moment", "after-close")));

        assertThat(onBill.bind("astor.review", ReviewProperties.class).get().getMoment()).isEqualTo(ReviewMoment.ON_BILL);
        assertThat(afterClose.bind("astor.review", ReviewProperties.class).get().getMoment()).isEqualTo(ReviewMoment.AFTER_CLOSE);
    }

    @Test
    void propertiesDefaultToTheOldBehaviour() {
        ReviewProperties properties = new ReviewProperties();
        assertThat(properties.getMoment()).isEqualTo(ReviewMoment.AFTER_CLOSE);
        assertThat(properties.onBill()).isFalse();
        properties.setMoment(ReviewMoment.ON_BILL);
        assertThat(properties.onBill()).isTrue();
        properties.setMoment(null);
        assertThat(properties.onBill()).isFalse();
    }
}
