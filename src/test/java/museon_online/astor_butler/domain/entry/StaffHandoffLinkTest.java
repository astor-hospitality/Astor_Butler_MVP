package museon_online.astor_butler.domain.entry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The two spellings of the staff parameter (#128): what is read, what is not, and what the builders produce. */
class StaffHandoffLinkTest {

    @Test
    void readsTheStaffMemberAndTheTableFromTheLunchLink() {
        assertThat(StaffHandoffLink.parse("/start lunch_aeris_s2_p2_d20261013_t1300_hanna_n5_r7f3a"))
                .contains(new StaffHandoffLink("anna", "5", true));
    }

    @Test
    void theTableIsOptionalAndTheHostStandIsAPseudoId() {
        assertThat(StaffHandoffLink.parse("/start lunch_aeris_hanna")).contains(new StaffHandoffLink("anna", null, true));
        assertThat(StaffHandoffLink.parse("/start@astor_butler_bot lunch_aeris_hhost")).hasValueSatisfying(link -> {
            assertThat(link.hostStand()).isTrue();
            assertThat(link.lunch()).isTrue();
            assertThat(link.tableCode()).isNull();
        });
        assertThat(StaffHandoffLink.parse("/START Lunch_AERIS_hIvan-2_n12a")).contains(new StaffHandoffLink("Ivan-2", "12a", true));
    }

    @Test
    void theTimeFieldOfTheLunchLinkIsNotATable() {
        // _t is the time in the lunch grammar (BusinessLunchHandoff); the table there is _n.
        assertThat(StaffHandoffLink.parse("/start lunch_aeris_hanna_t1300")).contains(new StaffHandoffLink("anna", null, true));
    }

    @Test
    void aPlainEntryWithoutTheLunchReadsAsAStart() {
        assertThat(StaffHandoffLink.parse("/start h_anna_t5")).contains(new StaffHandoffLink("anna", "5", false));
        assertThat(StaffHandoffLink.parse("/start h_anna")).contains(new StaffHandoffLink("anna", null, false));
        assertThat(StaffHandoffLink.isPlainStart("/start h_anna_t5")).isTrue();
        assertThat(StaffHandoffLink.isPlainStart("/start lunch_aeris_hanna")).isFalse();
        assertThat(StaffHandoffLink.isPlainStart("/start")).isFalse();
    }

    @Test
    void aLinkWithoutTheStaffFieldIsNotAHandoff() {
        for (String text : new String[]{
                "/start", "/restart", "/start lunch_aeris_s2_p2_t1300_r7f3a", "/start lunch", "/start lunch_nova_n5",
                "/start promo_42", "/start lunchbox_hanna", "/start h_", "/start h_anna_x", "h_anna", "ссылка", "", null}) {
            assertThat(StaffHandoffLink.parse(text)).as(String.valueOf(text)).isEmpty();
        }
    }

    @Test
    void buildsLinksTelegramAcceptsAndReadsThemBack() {
        String lunch = StaffHandoffLink.lunchStartParameter("AERIS", "anna", "5");
        assertThat(lunch).isEqualTo("lunch_aeris_hanna_n5");
        assertThat(StaffHandoffLink.url("@astor_butler_bot", lunch)).isEqualTo("https://t.me/astor_butler_bot?start=lunch_aeris_hanna_n5");
        assertThat(StaffHandoffLink.parse("/start " + lunch)).contains(new StaffHandoffLink("anna", "5", true));
        assertThat(StaffHandoffLink.lunchStartParameter(null, "host", null)).isEqualTo("lunch_aeris_hhost");

        String plain = StaffHandoffLink.plainStartParameter("anna", "12");
        assertThat(plain).isEqualTo("h_anna_t12");
        assertThat(StaffHandoffLink.parse("/start " + plain)).contains(new StaffHandoffLink("anna", "12", false));
        assertThat(StaffHandoffLink.plainStartParameter("host", null)).isEqualTo("h_host");
    }

    @Test
    void anIdThatCouldBeASeparatorIsRefused() {
        assertThat(StaffHandoffLink.validId("anna")).isTrue();
        assertThat(StaffHandoffLink.validId("ivan-2")).isTrue();
        assertThat(StaffHandoffLink.validId("12a")).isTrue();
        for (String bad : new String[]{"an na", "anna_2", "анна", "a.b", "", null, "x".repeat(65)}) {
            assertThat(StaffHandoffLink.validId(bad)).as(String.valueOf(bad)).isFalse();
        }
    }
}
