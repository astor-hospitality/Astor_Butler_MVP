package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlassesStaffTaskRelayTest {
    private static final String TOKEN = "unit-relay-token-not-real";
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private final HttpClient client = mock(HttpClient.class);
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("AERIS", "manager-1");
    private final ObjectMapper mapper = new ObjectMapper();
    private final GlassesVoiceTasks.Draft draft = new GlassesVoiceTasks.Draft("Анна", "5", "Принести воду",
            "Принести воду на пятый стол", "NORMAL");

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings("unchecked")
    private org.mockito.stubbing.OngoingStubbing<HttpResponse<String>> whenSent() throws Exception {
        return when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
    }

    private String body(HttpRequest request) {
        AtomicReference<String> text = new AtomicReference<>("");
        request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { text.set(text.get() + StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        }));
        return text.get();
    }

    private static void refused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(GlassesFailure.class, failure -> {
            assertThat(failure.status).isEqualTo(503);
            assertThat(failure.code).isEqualTo("TASK_UNAVAILABLE");
            assertThat(failure.getMessage()).isEqualTo("Не смог записать поручение, повторите");
        });
    }

    @Test void sendsTheDraftWithTheSharedTokenAndReadsButlersReceipt() throws Exception {
        var ok = response(200, "{\"taskId\":\"task-1\",\"title\":\"Принести воду\",\"assignee\":\"Анна\","
                + "\"assigneeStaffId\":\"anna\",\"self\":false,\"instruction\":\"Принести воду на пятый стол\","
                + "\"tableCode\":\"5\",\"priority\":\"NORMAL\",\"status\":\"ASSIGNED\"}");
        whenSent().thenReturn(ok);
        var relay = new GlassesStaffTaskRelay(client, true, TOKEN);

        var receipt = relay.create(scope, ID, draft);

        assertThat(receipt).isEqualTo(new GlassesVoiceTasks.Receipt("task-1", "Принести воду", "Анна", "anna", false,
                "Принести воду на пятый стол", "5", "NORMAL", "ASSIGNED"));
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("X-Astor-Relay-Token")).hasValue(TOKEN);
        var json = mapper.readTree(body(request));
        assertThat(json.path("requestId").asText()).isEqualTo(ID);
        assertThat(json.path("tenant").asText()).isEqualTo("AERIS");
        assertThat(json.path("staff").asText()).isEqualTo("manager-1");
        assertThat(json.path("draft").path("assignee").asText()).isEqualTo("Анна");
        assertThat(json.path("draft").path("tableCode").asText()).isEqualTo("5");
        assertThat(json.path("draft").path("title").asText()).isEqualTo("Принести воду");
        assertThat(json.path("draft").path("instruction").asText()).isEqualTo("Принести воду на пятый стол");
        assertThat(json.path("draft").path("priority").asText()).isEqualTo("NORMAL");
    }

    @Test void aSelfAssignedReceiptHasNoAssigneeName() throws Exception {
        var ok = response(200, "{\"taskId\":\"task-2\",\"title\":\"Принести воду\",\"assignee\":null,"
                + "\"assigneeStaffId\":\"manager-1\",\"self\":true,\"instruction\":\"Принести воду\",\"tableCode\":null,"
                + "\"priority\":\"NORMAL\",\"status\":\"ASSIGNED\"}");
        whenSent().thenReturn(ok);
        var receipt = new GlassesStaffTaskRelay(client, true, TOKEN).create(scope, ID, draft);
        assertThat(receipt.self()).isTrue();
        assertThat(receipt.assignee()).isNull();
        assertThat(receipt.tableCode()).isNull();
    }

    @Test void offShortTokenRefusalsAndNetworkFailuresAllTellTheWearerToRepeat() throws Exception {
        assertThat(GlassesStaffTaskRelay.disabled().configured()).isFalse();
        assertThat(new GlassesStaffTaskRelay(client, true, "short").configured()).isFalse();
        refused(() -> new GlassesStaffTaskRelay(client, false, TOKEN).create(scope, ID, draft));
        refused(() -> new GlassesStaffTaskRelay(client, true, "short").create(scope, ID, draft));
        verifyNoInteractions(client);

        var relay = new GlassesStaffTaskRelay(client, true, TOKEN);
        assertThat(relay.configured()).isTrue();
        var unauthorized = response(401, "{\"error\":{\"code\":\"UNAUTHORIZED\"}}");
        var disabled = response(503, "{\"error\":{\"code\":\"TASKS_DISABLED\"}}");
        var garbage = response(200, "not json at all");
        var noTask = response(200, "{\"title\":\"no task id\"}");
        whenSent().thenReturn(unauthorized);
        refused(() -> relay.create(scope, ID, draft));
        whenSent().thenReturn(disabled);
        refused(() -> relay.create(scope, ID, draft));
        whenSent().thenReturn(garbage);
        refused(() -> relay.create(scope, ID, draft));
        whenSent().thenReturn(noTask);
        refused(() -> relay.create(scope, ID, draft));
        whenSent().thenThrow(new java.io.IOException("butler is away"));
        refused(() -> relay.create(scope, ID, draft));
    }
}
