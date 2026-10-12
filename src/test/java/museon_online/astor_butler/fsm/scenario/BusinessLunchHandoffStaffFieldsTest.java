package museon_online.astor_butler.fsm.scenario;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The staff fields of the link (#128) are invisible to the lunch parser: a link with them reads exactly as one without. */
class BusinessLunchHandoffStaffFieldsTest {

    @Test
    void theStaffFieldsChangeNothingForTheLunchParser() {
        assertThat(BusinessLunchHandoff.fromStartLink("/start lunch_aeris_s2_p2_d20261013_t1300_hanna_n5_r7f3a"))
                .isEqualTo(BusinessLunchHandoff.fromStartLink("/start lunch_aeris_s2_p2_d20261013_t1300_r7f3a"));
        assertThat(BusinessLunchHandoff.fromStartLink("/start lunch_aeris_hhost"))
                .contains(new BusinessLunchHandoff("aeris", null, null, null, null, null));
    }

    @Test
    void thePlainEntryIsNotALunch() {
        assertThat(BusinessLunchHandoff.fromStartLink("/start h_anna_t5")).isEmpty();
    }
}
