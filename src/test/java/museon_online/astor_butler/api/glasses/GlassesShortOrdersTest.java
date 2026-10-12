package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** #184: «забить 14 на 12» becomes one staff task for the table through the relay, or a spoken refusal. */
class GlassesShortOrdersTest {
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private final GlassesAccess.Scope scope = new GlassesAccess.Scope("AERIS", "kirill");
    private final GlassesStaffTaskRelay relay = mock(GlassesStaffTaskRelay.class);
    private final GlassesLunchDishes dishes = mock(GlassesLunchDishes.class);
    private final List<GlassesLunchDishes.Dish> menu = List.of(
            new GlassesLunchDishes.Dish(2, "CAESAR_CHICKEN", "Цезарь с цыплёнком"),
            new GlassesLunchDishes.Dish(14, "BORSCHT", "Борщ со сметаной"),
            new GlassesLunchDishes.Dish(21, "STROGANOFF", "Бефстроганов с картофельным пюре"));

    private GlassesShortOrders orders(boolean enabled) {
        when(relay.configured()).thenReturn(true);
        when(dishes.configured()).thenReturn(true);
        when(dishes.dishes("aeris")).thenReturn(menu);
        return new GlassesShortOrders(relay, dishes, enabled, "aeris");
    }

    private static GlassesVoiceTasks.Receipt receipt(String title, String assignee, String staffId, boolean self) {
        return new GlassesVoiceTasks.Receipt("task-1", title, assignee, staffId, self, "забить 14 на 12", "12", "NORMAL", "ASSIGNED");
    }

    /* ---------- recognition ---------- */

    @Test void anOrderPhraseIsAnOrderOnlyWhileTheFeatureIsOn() {
        assertThat(orders(true).isOrder(null, "забить 14 на 12")).isTrue();
        assertThat(orders(true).isOrder("task", "забить 14 на 12")).as("the task gesture with an order phrase is an order").isTrue();
        assertThat(orders(true).isOrder("assist", "забить 14 на 12")).as("an explicit question is never an order").isFalse();
        assertThat(orders(true).isOrder(null, "Что по бизнес-ланчу?")).isFalse();
        assertThat(orders(false).isOrder(null, "забить 14 на 12")).as("off: the phrase goes to assist as before").isFalse();
        assertThat(GlassesShortOrders.disabled().isOrder(null, "забить 14 на 12")).isFalse();
        assertThat(GlassesShortOrders.disabled().enabled()).isFalse();
    }

    @Test void withoutTheRelayOrTheMenuTokenTheFeatureStaysOff() {
        when(relay.configured()).thenReturn(false);
        when(dishes.configured()).thenReturn(true);
        assertThat(new GlassesShortOrders(relay, dishes, true, "aeris").enabled()).isFalse();
        when(relay.configured()).thenReturn(true);
        when(dishes.configured()).thenReturn(false);
        assertThat(new GlassesShortOrders(relay, dishes, true, "aeris").enabled()).isFalse();
        assertThat(new GlassesShortOrders(relay, dishes, true, " AERIS ").venue()).isEqualTo("aeris");
        assertThat(new GlassesShortOrders(relay, dishes, true, "").venue()).isEqualTo("aeris");
    }

    /* ---------- create ---------- */

    @Test void theNumbersBecomeDishesAndOneTaskForTheTableWithTheSpokenPhraseAsInstruction() {
        var expected = receipt("Стол 12: Борщ со сметаной ×1, Цезарь с цыплёнком ×2 (голосовой заказ)", "Анна", "anna", false);
        when(relay.create(eq(scope), eq(ID), any(), eq("order"))).thenReturn(expected);
        var orders = orders(true);

        var outcome = orders.create(scope, ID, "Забить 14, 2 два раза на 12");

        assertThat(outcome.text()).isEqualTo("Записал: стол 12, Борщ со сметаной, Цезарь с цыплёнком два");
        assertThat(outcome.task()).isEqualTo(expected);
        verify(relay, times(1)).create(scope, ID, new GlassesVoiceTasks.Draft(null, "12",
                "Стол 12: Борщ со сметаной ×1, Цезарь с цыплёнком ×2 (голосовой заказ)", "Забить 14, 2 два раза на 12", "NORMAL"), "order");
        verify(relay, never()).create(any(), any(), any());
        assertThat(orders.receipt(scope, ID)).isEqualTo(expected);
        assertThat(orders.receipt(new GlassesAccess.Scope("OTHER", "kirill"), ID)).isNull();
        assertThat(orders.receipt(scope, "00000000-0000-0000-0000-000000000000")).isNull();
    }

    @Test void anUnknownNumberIsAnsweredAndNoTaskIsCreated() {
        var orders = orders(true);
        var outcome = orders.create(scope, ID, "забить 99 на 12");
        assertThat(outcome.text()).isEqualTo("Блюда с номером 99 нет");
        assertThat(outcome.task()).isNull();
        // One wrong number spoils the whole order: nothing is recorded for the known ones either.
        var mixed = orders.create(scope, ID, "забить 14 и 99 на 12");
        assertThat(mixed.text()).isEqualTo("Блюда с номером 99 нет");
        assertThat(mixed.task()).isNull();
        verify(relay, never()).create(any(), any(), any(), any());
        assertThat(orders.receipt(scope, ID)).isNull();
    }

    @Test void aMenuWithoutNumbersSaysSoAndCreatesNothing() {
        var orders = orders(true);
        when(dishes.dishes("aeris")).thenReturn(List.of());
        var outcome = orders.create(scope, ID, "забить 14 на 12");
        assertThat(outcome.text()).isEqualTo("Номера блюд не заданы");
        assertThat(outcome.task()).isNull();
        verify(relay, never()).create(any(), any(), any(), any());
    }

    @Test void whenTheMenuOrButlerCannotBeReachedTheWearerHearsToRepeatAndNothingIsRemembered() {
        var orders = orders(true);
        when(dishes.dishes("aeris")).thenThrow(GlassesShortOrders.refused());
        assertThatThrownBy(() -> orders.create(scope, ID, "забить 14 на 12"))
                .isInstanceOfSatisfying(GlassesFailure.class, failure -> {
                    assertThat(failure.status).isEqualTo(503);
                    assertThat(failure.code).isEqualTo("ORDER_UNAVAILABLE");
                    assertThat(failure.getMessage()).isEqualTo("Не смог записать заказ, повторите");
                });
        reset(dishes);
        when(dishes.dishes("aeris")).thenReturn(menu);
        when(relay.create(any(), any(), any(), any())).thenThrow(new GlassesFailure(503, "TASK_UNAVAILABLE", "Не смог записать поручение, повторите"));
        assertThatThrownBy(() -> orders.create(scope, ID, "забить 14 на 12")).isInstanceOf(GlassesFailure.class);
        assertThat(orders.receipt(scope, ID)).isNull();
        // Words that are not an order never reach the relay either.
        assertThatThrownBy(() -> orders.create(scope, ID, "Что по ланчу?")).isInstanceOf(GlassesFailure.class);
    }

    @Test void aRetryWithTheSameRequestIdSendsTheSameEventIdAgain() {
        when(relay.create(any(), any(), any(), any())).thenReturn(receipt("Стол 12: Борщ со сметаной ×1 (голосовой заказ)", null, "kirill", true));
        var orders = orders(true);
        orders.create(scope, ID, "забить 14 на 12");
        orders.create(scope, ID, "забить 14 на 12");
        verify(relay, times(2)).create(eq(scope), eq(ID), any(), eq("order"));
    }

    /* ---------- words ---------- */

    @Test void theConfirmationNamesTheDishesAndTheCountNobodyAndNoSums() {
        when(relay.create(any(), any(), any(), any())).thenReturn(receipt("x", "Анна", "anna", false));
        var orders = orders(true);
        assertThat(orders.create(scope, ID, "14 на 12").text()).isEqualTo("Записал: стол 12, Борщ со сметаной");
        assertThat(orders.create(scope, ID, "стол 7: 14 три раза, 21 ×2, 2").text())
                .isEqualTo("Записал: стол 7, Борщ со сметаной три, Бефстроганов с картофельным пюре два, Цезарь с цыплёнком");
        assertThat(orders.create(scope, ID, "забить 14 ×9 на 12").text()).isEqualTo("Записал: стол 12, Борщ со сметаной девять");
    }

    @Test void theTitleStaysWithinButlersLimitAndKeepsItsSuffix() {
        var captor = org.mockito.ArgumentCaptor.forClass(GlassesVoiceTasks.Draft.class);
        when(relay.create(any(), any(), captor.capture(), any())).thenReturn(receipt("x", null, "kirill", true));
        var orders = orders(true);
        orders.create(scope, ID, "стол 12: 21 ×2, 21, 21, 21 два раза, 21, 14");
        String title = captor.getValue().title();
        assertThat(title).hasSizeLessThanOrEqualTo(GlassesShortOrders.TITLE_LIMIT);
        assertThat(title).startsWith("Стол 12: Бефстроганов с картофельным пюре ×2, ").contains("…").endsWith(" (голосовой заказ)");
        assertThat(captor.getValue().assignee()).isNull();
        assertThat(captor.getValue().priority()).isEqualTo("NORMAL");
        assertThat(captor.getValue().tableCode()).isEqualTo("12");
    }
}
