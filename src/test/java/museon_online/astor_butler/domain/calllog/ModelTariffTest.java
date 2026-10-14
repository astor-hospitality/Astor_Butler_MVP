package museon_online.astor_butler.domain.calllog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ModelTariffTest {

    private final ModelTariff tariff = new ModelTariff();

    @Test
    void pricesGigaChat3ProByInputAndOutput() {
        // 1200 * 73 / 1M = 0.0876, 300 * 176 / 1M = 0.0528
        assertThat(tariff.cost("GigaChat-3-Pro", 1200, 300)).isEqualByComparingTo(new BigDecimal("0.1404"));
    }

    @Test
    void knowsTheAliasesAndVersionTagsButNotUnknownVariants() {
        assertThat(tariff.rateFor("GigaChat-2-Max").inPerMillion()).isEqualByComparingTo("569");
        assertThat(tariff.rateFor("GigaChat-Max").inPerMillion()).isEqualByComparingTo("569");
        assertThat(tariff.rateFor("GigaChat-Pro").inPerMillion()).isEqualByComparingTo("500");
        assertThat(tariff.rateFor("GigaChat-2-Pro").inPerMillion()).isEqualByComparingTo("500");
        assertThat(tariff.rateFor("GigaChat").inPerMillion()).isEqualByComparingTo("65");
        assertThat(tariff.rateFor("GigaChat:1.0.26.20").inPerMillion()).isEqualByComparingTo("65");
        assertThat(tariff.rateFor("GigaChat-2-Max-preview").inPerMillion()).isEqualByComparingTo("569");
        assertThat(tariff.rateFor("gigachat-3-pro").outPerMillion()).isEqualByComparingTo("176");
        // the bare GigaChat price must not swallow a variant it does not know
        assertThat(tariff.rateFor("GigaChat-3-Ultra")).isNull();
        assertThat(tariff.rateFor("GigaChat-2")).isNull();
    }

    @Test
    void matchesOnTheLastPathSegmentOfAVendorPrefixedName() {
        assertThat(tariff.cost("GigaChat/GigaChat-3-Pro", 1200, 300)).isEqualByComparingTo("0.1404");
        assertThat(tariff.cost("sber/GigaChat-2-Max", 1_000_000, 0)).isEqualByComparingTo("569");
    }

    @Test
    void anUnknownModelOrNoTokensHasNoCost() {
        assertThat(tariff.cost("yandexgpt-lite", 1200, 300)).isNull();
        assertThat(tariff.cost("yandexgpt-lite", 1, 1)).isNull();
        assertThat(tariff.cost(null, 1200, 300)).isNull();
        assertThat(tariff.cost("GigaChat-3-Pro", null, null)).isNull();
    }

    @Test
    void parsesTheEnvTariffStringAndIgnoresBadEntries() {
        ModelTariff custom = new ModelTariff("GigaChat-3-Pro=100,200; GigaChat/GigaChat-2-Max=600,700;yandexgpt-5-lite=40,40;broken=x;=1,1;nonsense");

        assertThat(custom.cost("GigaChat-3-Pro", 1_000_000, 0)).isEqualByComparingTo("100");
        assertThat(custom.cost("GigaChat-2-Max", 0, 1_000_000)).isEqualByComparingTo("700");
        assertThat(custom.cost("yandexgpt-5-lite", 0, 1_000_000)).isEqualByComparingTo("40");
        assertThat(custom.cost("GigaChat", 1_000_000, 0)).isEqualByComparingTo("65");
        assertThat(custom.rateFor("broken")).isNull();
    }
}
