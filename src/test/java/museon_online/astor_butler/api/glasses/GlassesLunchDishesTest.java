package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** #184: the numbered lunch dishes come from Butler under the relay token and are kept for ten minutes. */
class GlassesLunchDishesTest {
    private static final String TOKEN = "unit-relay-token-not-real";
    private static final String MENU = "[{\"number\":14,\"code\":\"BORSCHT\",\"title\":\"Борщ со сметаной\"},"
            + "{\"number\":2,\"code\":\"CAESAR_CHICKEN\",\"title\":\"Цезарь с цыплёнком\"}]";
    private final HttpClient client = mock(HttpClient.class);
    private Instant now = Instant.parse("2026-10-12T09:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

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

    /** The response is built before the stubbing starts: Mockito refuses a mock made inside another stubbing. */
    private void answers(int status, String body) throws Exception {
        HttpResponse<String> response = response(status, body);
        whenSent().thenReturn(response);
    }

    private GlassesLunchDishes dishes(String token) {
        return new GlassesLunchDishes(client, URI.create("http://butler.invalid/api/internal/glasses/lunch-dishes"), token, clock);
    }

    private static void refused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(GlassesFailure.class, failure -> {
            assertThat(failure.status).isEqualTo(503);
            assertThat(failure.code).isEqualTo("ORDER_UNAVAILABLE");
            assertThat(failure.getMessage()).isEqualTo("Не смог записать заказ, повторите");
        });
    }

    @Test void readsTheVenuesDishesWithTheSharedTokenAndFindsOneByNumber() throws Exception {
        answers(200, MENU);
        var dishes = dishes(TOKEN);

        assertThat(dishes.dishes("AERIS")).containsExactly(new GlassesLunchDishes.Dish(14, "BORSCHT", "Борщ со сметаной"),
                new GlassesLunchDishes.Dish(2, "CAESAR_CHICKEN", "Цезарь с цыплёнком"));
        assertThat(dishes.dish("aeris", 14)).contains(new GlassesLunchDishes.Dish(14, "BORSCHT", "Борщ со сметаной"));
        assertThat(dishes.dish("aeris", 99)).isEmpty();

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(1)).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().toString()).isEqualTo("http://butler.invalid/api/internal/glasses/lunch-dishes?venue=aeris");
        assertThat(request.headers().firstValue("X-Astor-Relay-Token")).hasValue(TOKEN);
    }

    @Test void theMenuIsKeptForTenMinutesThenReadAgain() throws Exception {
        answers(200, MENU);
        var dishes = dishes(TOKEN);
        dishes.dishes("aeris");
        now = now.plus(Duration.ofMinutes(9));
        dishes.dishes("aeris");
        verify(client, times(1)).send(any(), any());
        now = now.plus(Duration.ofMinutes(2));
        dishes.dishes("aeris");
        verify(client, times(2)).send(any(), any());
        // Another venue is another entry; forgetting clears everything.
        dishes.dishes("c3flex");
        verify(client, times(3)).send(any(), any());
        dishes.forget();
        dishes.dishes("aeris");
        verify(client, times(4)).send(any(), any());
    }

    @Test void aVenueWithoutNumbersIsAnEmptyListAndIsCachedToo() throws Exception {
        answers(200, "[]");
        var dishes = dishes(TOKEN);
        assertThat(dishes.dishes("aeris")).isEmpty();
        assertThat(dishes.dishes("aeris")).isEmpty();
        verify(client, times(1)).send(any(), any());
    }

    @Test void refusalsGarbageAndNetworkFailuresRefuseTheOrderAndCacheNothing() throws Exception {
        assertThat(GlassesLunchDishes.disabled().configured()).isFalse();
        refused(() -> dishes("short").dishes("aeris"));
        verifyNoInteractions(client);

        var dishes = dishes(TOKEN);
        assertThat(dishes.configured()).isTrue();
        answers(401, "{\"error\":{\"code\":\"UNAUTHORIZED\"}}");
        refused(() -> dishes.dishes("aeris"));
        answers(404, "{\"error\":{\"code\":\"VENUE_NOT_FOUND\"}}");
        refused(() -> dishes.dishes("aeris"));
        answers(200, "not json");
        refused(() -> dishes.dishes("aeris"));
        answers(200, "{\"number\":1}");
        refused(() -> dishes.dishes("aeris"));
        answers(200, "[{\"number\":0,\"title\":\"x\"}]");
        refused(() -> dishes.dishes("aeris"));
        answers(200, "[{\"number\":1,\"title\":\"\"}]");
        refused(() -> dishes.dishes("aeris"));
        whenSent().thenThrow(new java.io.IOException("butler is away"));
        refused(() -> dishes.dishes("aeris"));
        // Nothing of that was cached: the next good answer is read.
        reset(client);
        answers(200, MENU);
        assertThat(dishes.dishes("aeris")).hasSize(2);
        verify(client, times(1)).send(any(), any());
    }
}
