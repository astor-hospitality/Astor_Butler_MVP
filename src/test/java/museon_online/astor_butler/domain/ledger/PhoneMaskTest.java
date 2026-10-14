package museon_online.astor_butler.domain.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneMaskTest {

    @Test
    void masksRussianPhonesInFreeText() {
        assertThat(PhoneMask.mask("звоните +79120000060 или 8 912 000-00-60"))
                .isEqualTo("звоните +7 912 ***-**-60 или +7 912 ***-**-60");
        assertThat(PhoneMask.mask(null)).isNull();
    }

    @Test
    void masksBracketsAndDashesButLeavesOtherNumbers() {
        assertThat(PhoneMask.mask("8 (912) 000-00-60")).isEqualTo("+7 912 ***-**-60");
        assertThat(PhoneMask.mask("+7-912-000-00-60.")).isEqualTo("+7 912 ***-**-60.");
        assertThat(PhoneMask.mask("стол на 4 гостей в 19:30, бронь 12345")).isEqualTo("стол на 4 гостей в 19:30, бронь 12345");
        assertThat(PhoneMask.mask("+7 912 ***-**-60")).isEqualTo("+7 912 ***-**-60");
    }
}
