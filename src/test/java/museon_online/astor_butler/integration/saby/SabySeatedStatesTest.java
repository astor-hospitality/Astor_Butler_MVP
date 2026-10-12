package museon_online.astor_butler.integration.saby;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «The guest is at the table» has no documented Saby code (#151): the codes come from {@code ASTOR_SABY_SEATED_STATES}
 * and nothing is SEATED while that is empty.
 */
class SabySeatedStatesTest {

    @Test
    void emptyOrBlankMeansTheMappingIsOff() {
        assertThat(SabySeatedStates.parse(null).empty()).isTrue();
        assertThat(SabySeatedStates.parse("").empty()).isTrue();
        assertThat(SabySeatedStates.parse(" , ,").empty()).isTrue();
        assertThat(SabySeatedStates.parse("").matches(50, 1002)).isFalse();
        assertThat(SabyBookingState.of(50, 1002)).isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(SabyBookingState.of(50, 1002, null)).isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(SabyBookingState.of(50, 1002, SabySeatedStates.NONE)).isEqualTo(SabyBookingState.CONFIRMED);
    }

    @Test
    void aStateAloneMatchesWhateverTheProductStateSaysAndAPairMatchesOnlyThePair() {
        SabySeatedStates seated = SabySeatedStates.parse(" 60, 50/1002 ");

        assertThat(seated.codes()).extracting(SabySeatedStates.Code::toString).containsExactly("60", "50/1002");
        assertThat(seated.matches(60, null)).isTrue();
        assertThat(seated.matches(60, 1001)).isTrue();
        assertThat(seated.matches(50, 1002)).isTrue();
        assertThat(seated.matches(50, 1001)).isFalse();
        assertThat(seated.matches(50, null)).isFalse();
        assertThat(seated.matches(null, 1002)).isFalse();
    }

    @Test
    void aConfiguredCodeReadsAsSeatedAndCancellationStillWins() {
        SabySeatedStates seated = SabySeatedStates.parse("50/1002,180");

        assertThat(SabyBookingState.of(50, 1002, seated)).isEqualTo(SabyBookingState.SEATED);
        assertThat(SabyBookingState.of(50, 1001, seated)).isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(SabyBookingState.of(20, null, seated)).isEqualTo(SabyBookingState.CONFIRMED);
        // An explicit code wins over the documented guess (180 «отгружен» reads as COMPLETED without the setting).
        assertThat(SabyBookingState.of(180, null, seated)).isEqualTo(SabyBookingState.SEATED);
        assertThat(SabyBookingState.of(180, null)).isEqualTo(SabyBookingState.COMPLETED);
        assertThat(SabyBookingState.of(200, 1999, seated)).isEqualTo(SabyBookingState.COMPLETED);
        assertThat(SabyBookingState.of(220, 1002, seated)).as("cancelled on state").isEqualTo(SabyBookingState.CANCELLED);
        assertThat(SabyBookingState.of(50, 1998, seated)).as("cancelled on product state").isEqualTo(SabyBookingState.CANCELLED);
        assertThat(SabyBookingState.of(999, 5, seated)).isEqualTo(SabyBookingState.UNKNOWN);
    }

    @Test
    void aTypoInTheListDropsThatEntryOnly() {
        SabySeatedStates seated = SabySeatedStates.parse("fifty, 50/abc, 60");

        assertThat(seated.codes()).extracting(SabySeatedStates.Code::toString).containsExactly("60");
        assertThat(SabySeatedStates.parse("nope").empty()).isTrue();
    }

    @Test
    void thePropertiesParseTheVariableOnEveryRead() {
        SabyReservationProperties properties = new SabyReservationProperties();

        assertThat(properties.seatedCodes().empty()).isTrue();
        properties.setSeatedStates("50/1002");
        assertThat(properties.seatedCodes().matches(50, 1002)).isTrue();
    }
}
