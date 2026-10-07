package museon_online.astor_butler.domain.billing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEstimateTest {

    @Test
    void addsUpEveryPricedLineInKopecks() {
        OrderEstimate estimate = OrderEstimate.of(List.of(
                OrderEstimate.Line.priced("BORSCHT", "Борщ со сметаной", 2, 270),
                OrderEstimate.Line.priced("MEDOVIK", "Медовик", 1, 220)
        ));

        assertThat(estimate.complete()).isTrue();
        assertThat(estimate.totalMinor()).isEqualTo(76_000L);
    }

    @Test
    void aDishOfASetAddsNothingToTheSetPrice() {
        OrderEstimate estimate = OrderEstimate.of(List.of(
                OrderEstimate.Line.priced("STARTER_MAIN", "Суп и горячее", 2, 650),
                OrderEstimate.Line.included("BROTH", "Куриный бульон", 2),
                OrderEstimate.Line.included("PASTA", "Паста", 2)
        ));

        assertThat(estimate.totalMinor()).isEqualTo(130_000L);
        assertThat(estimate.lines()).hasSize(3);
    }

    @Test
    void givesNoTotalRatherThanAPartialOne() {
        assertThat(OrderEstimate.of(List.of(
                OrderEstimate.Line.priced("BORSCHT", "Борщ со сметаной", 1, 270),
                OrderEstimate.Line.priced("SPECIAL", "Блюдо дня", 1, null)
        )).totalMinor()).isNull();
        assertThat(OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ со сметаной", 0, 270))).totalMinor()).isNull();
        assertThat(OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ со сметаной", 1, 0))).totalMinor()).isNull();
        assertThat(OrderEstimate.of(List.of(OrderEstimate.Line.included("BROTH", "Куриный бульон", 2))).totalMinor()).isNull();
        assertThat(OrderEstimate.of(List.of()).complete()).isFalse();
        assertThat(OrderEstimate.of(null).lines()).isEmpty();
    }
}
