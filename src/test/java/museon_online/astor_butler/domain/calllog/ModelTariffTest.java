package museon_online.astor_butler.domain.calllog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelTariffTest {

    private final ModelTariff tariff = new ModelTariff();

    @Test
    void pricesGigaChat3ProByInputAndOutput() {
        // 1200 * 73 / 1M = 0.0876, 300 * 176 / 1M = 0.0528
        assertThat(tariff.cost("GigaChat-3-Pro", 1200, 300)).isEqualByComparingTo(new BigDecimal("0.1404"));
    }

    @Test
    void theLongestPrefixWins() {
        assertThat(tariff.rateFor("GigaChat-2-Max-preview").inPerMillion()).isEqualByComparingTo("569");
        assertThat(tariff.rateFor("GigaChat-2-Pro").inPerMillion()).isEqualByComparingTo("500");
        assertThat(tariff.rateFor("GigaChat-2").inPerMillion()).isEqualByComparingTo("65");
        assertThat(tariff.rateFor("gigachat-3-pro").outPerMillion()).isEqualByComparingTo("176");
    }

    @Test
    void anUnknownModelOrNoTokensHasNoCost() {
        assertThat(tariff.cost("yandexgpt-lite", 1200, 300)).isNull();
        assertThat(tariff.cost(null, 1200, 300)).isNull();
        assertThat(tariff.cost("GigaChat-3-Pro", null, null)).isNull();
    }

    @Test
    void anOverrideReplacesOrAddsAPrefixAndABadOneIsIgnored() {
        ModelTariff custom = new ModelTariff(Map.of("GigaChat-3-Pro", "100,200", "yandexgpt", "40,40", "broken", "x"));

        assertThat(custom.cost("GigaChat-3-Pro", 1_000_000, 0)).isEqualByComparingTo("100");
        assertThat(custom.cost("yandexgpt-lite", 0, 1_000_000)).isEqualByComparingTo("40");
        assertThat(custom.rateFor("broken")).isNull();
    }
}
