package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.billing.BillPayState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SabyPayStateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsTheCodesSabyDocuments() {
        assertThat(SabyPayState.fromCode(0)).isEqualTo(BillPayState.UNPAID);
        assertThat(SabyPayState.fromCode(10)).isEqualTo(BillPayState.PREPAID_FULL);
        assertThat(SabyPayState.fromCode(30)).isEqualTo(BillPayState.PREPAID_PARTIAL);
        assertThat(SabyPayState.fromCode(50)).isEqualTo(BillPayState.CREDIT);
        assertThat(SabyPayState.fromCode(200)).isEqualTo(BillPayState.PAID);
    }

    @Test
    void onlyFullPrepaymentAndFullSettlementLeaveNothingToPay() {
        assertThat(SabyPayState.fromCode(10).settled()).isTrue();
        assertThat(SabyPayState.fromCode(200).settled()).isTrue();
        assertThat(SabyPayState.fromCode(0).settled()).isFalse();
        assertThat(SabyPayState.fromCode(30).settled()).isFalse();
        assertThat(SabyPayState.fromCode(50).settled()).isFalse();
    }

    @Test
    void aCodeItDoesNotKnowIsNeverPaid() {
        assertThat(SabyPayState.fromCode(null)).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.fromCode(20)).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.fromCode(-1)).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.fromCode(201).settled()).isFalse();
    }

    @Test
    void readsThePayStateOfAStateAnswer() throws Exception {
        assertThat(SabyPayState.read(json("{\"state\":20,\"payState\":200,\"payments\":[]}"))).isEqualTo(BillPayState.PAID);
        assertThat(SabyPayState.read(json("{\"payState\":\"30\"}"))).isEqualTo(BillPayState.PREPAID_PARTIAL);
        assertThat(SabyPayState.read(json("{\"payState\":0}"))).isEqualTo(BillPayState.UNPAID);
    }

    @Test
    void anAnswerItCannotReadIsUnknown() throws Exception {
        assertThat(SabyPayState.read(null)).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.read(json("{\"state\":20}"))).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.read(json("{\"payState\":null}"))).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.read(json("{\"payState\":\"paid\"}"))).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.read(json("{\"payState\":200.5}"))).isEqualTo(BillPayState.UNKNOWN);
        assertThat(SabyPayState.read(json("{\"payState\":{\"code\":200}}"))).isEqualTo(BillPayState.UNKNOWN);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
