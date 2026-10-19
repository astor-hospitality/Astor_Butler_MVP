package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The agent and the capabilities read visits of the guest journey: without it they warn at startup. */
class GuestJourneyPrerequisiteTest {

    @Test
    void anEnabledFeatureWithoutTheJourneyIsWarnedAbout() {
        GuestJourneyProperties journey = new GuestJourneyProperties();

        assertThat(journey.prerequisiteWarning("ASTOR_AGENT_ENABLED", true))
                .hasValueSatisfying(text -> assertThat(text).contains("ASTOR_AGENT_ENABLED").contains("ASTOR_GUEST_JOURNEY_ENABLED"));
    }

    @Test
    void noWarningWhenTheFeatureIsOffOrTheJourneyIsOn() {
        GuestJourneyProperties journey = new GuestJourneyProperties();
        assertThat(journey.prerequisiteWarning("ASTOR_CAPABILITIES_ENABLED", false)).isEmpty();

        journey.setEnabled(true);
        assertThat(journey.prerequisiteWarning("ASTOR_CAPABILITIES_ENABLED", true)).isEmpty();
    }
}
