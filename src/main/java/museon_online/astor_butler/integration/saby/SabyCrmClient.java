package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import museon_online.astor_butler.domain.journey.GuestVisit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two Saby CRM calls of the journey mirror: a deal per visit, and an event (with a stage transition) per moment. */
class SabyCrmClient {

    private static final List<String[]> EVENT_FIELDS = List.of(
            new String[]{"EventType", "Число целое"}, new String[]{"LeadID", "Число целое"}, new String[]{"RespID", "Число целое"},
            new String[]{"KindOfContact", "Число целое"}, new String[]{"ContactType", "Число целое"}, new String[]{"Comment", "Строка"},
            new String[]{"DateTime", "Дата и время"}, new String[]{"PhaseDirectionId", "Число целое"});

    private final SabyApiClient client;
    private final SabyCrmProperties properties;

    SabyCrmClient(SabyApiClient client, SabyCrmProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** {@code CRMLead.insertRecord}: the deal of the visit; returns its id. */
    String insertLead(GuestVisit visit, String guestName) {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("ФИО", guestName == null || guestName.isBlank() ? "Гость Telegram" : guestName.trim());
        SabyOrderPayload.phone(visit.guestPhone()).ifPresent(phone -> contact.put("Телефон", phone));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("Регламент", properties.getRegulationId());
        if (properties.getResponsibleId() != null && !properties.getResponsibleId().isBlank()) {
            params.put("Ответственный", properties.getResponsibleId().trim());
        }
        params.put("КонтактноеЛицо", contact);
        params.put("Примечание", "Astor Butler · бронь #" + visit.reservationId());
        JsonNode result = client.rpc("CRMLead.insertRecord", params);
        JsonNode id = result.has("@Документ") ? result.path("@Документ") : result;
        if (!(id.isIntegralNumber() || id.isTextual() && id.asText().matches("\\d+"))) {
            throw new SabyApiException(SabyApiException.Kind.INVALID_RESPONSE, 200, "Saby CRMLead.insertRecord returned no deal id");
        }
        return id.asText();
    }

    /** {@code CRMEvent.AddEvent}: an event on the deal, moving it when a transition is given. */
    void addEvent(String leadId, Long phaseDirectionId, String comment) {
        List<Object> values = new ArrayList<>();
        values.add(3);
        values.add(Long.parseLong(leadId));
        values.add(null);
        values.add(0);
        values.add(2);
        values.add(comment);
        values.add(null);
        values.add(phaseDirectionId);
        List<Map<String, String>> schema = new ArrayList<>();
        for (String[] field : EVENT_FIELDS) {
            schema.add(Map.of("n", field[0], "t", field[1]));
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("d", values);
        record.put("s", schema);
        record.put("_type", "record");
        record.put("f", 0);
        client.rpc("CRMEvent.AddEvent", Map.of("EventData", record));
    }
}
