package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VenueInfoToolTest {

    @Test
    void nullFactsAreReportedAsNoData() {
        VenueInfoTool tool = new VenueInfoTool(new VenueFactsCatalog(VenueFacts.none()), new CapabilityProperties());

        CapabilityResult result = tool.invoke(null, Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("name", "AERIS").containsKey("address");
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.facts().get("facts");
        assertThat(facts).containsOnlyKeys("parking", "kids", "wifi", "winePairing");
        assertThat(facts.values()).containsOnlyNulls();
    }

    /** Run 16.10: «Уточню у команды про парковку», while the agent has no way to ask the team. */
    @Test
    void aMissingFactIsToBeSaidHonestlyNotPromised() {
        String description = new VenueInfoTool(new VenueFactsCatalog(VenueFacts.none()), new CapabilityProperties()).description();

        assertThat(description).contains("не обещай уточнить у команды", "честно скажи, что таких сведений пока нет")
                .doesNotContain("предложи уточнить");
    }

    @Test
    void knownFactsAreGiven() {
        VenueInfoTool tool = new VenueInfoTool(
                new VenueFactsCatalog(new VenueFacts("Парковка во дворе", null, null, null, null)), new CapabilityProperties());

        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) tool.invoke(null, Map.of()).facts().get("facts");

        assertThat(facts).containsEntry("parking", "Парковка во дворе").containsEntry("kids", null);
    }
}
