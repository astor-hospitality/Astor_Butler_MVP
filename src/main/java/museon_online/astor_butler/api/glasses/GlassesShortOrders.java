package museon_online.astor_butler.api.glasses;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The second thing the glasses do (#184): a waiter at the table says «забить 14 на 12», and the table's waiter gets
 * a staff task «Стол 12: Борщ ×1 (голосовой заказ)» — nothing is written to Saby/Presto; the waiter still enters
 * the order there as before. The phrase is recognised by {@link GlassesShortOrderGrammar} alone, without a model,
 * the numbers become dishes through Butler's lunch menu ({@link GlassesLunchDishes}), and the task is created
 * through the same relay as a spoken task — with {@code kind: "order"}, so Butler picks the table's waiter of the
 * day as the assignee. The runtime still holds no task, acknowledges nothing and changes no state.
 *
 * Off by default ({@code ASTOR_GLASSES_SHORT_ORDERS_ENABLED}); off, the phrase goes to assist as before.
 */
@Component
public class GlassesShortOrders {
    static final String FAILURE_CODE = "ORDER_UNAVAILABLE";
    static final String FAILURE_MESSAGE = "Не смог записать заказ, повторите";
    static final String NO_NUMBERS = "Номера блюд не заданы";
    static final String SUFFIX = " (голосовой заказ)";
    static final String KIND = "order";
    static final int TITLE_LIMIT = 120;
    private static final int RECEIPT_LIMIT = 32;
    private static final String[] SPOKEN_COUNTS = {"", "", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private final GlassesStaffTaskRelay relay;
    private final GlassesLunchDishes dishes;
    private final boolean enabled;
    private final String venue;
    // Receipts of the last orders, so a cached repeat of the same request id can still answer with its task.
    private final Map<String, GlassesVoiceTasks.Receipt> receipts = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, GlassesVoiceTasks.Receipt> eldest) { return size() > RECEIPT_LIMIT; }
    };

    /**
     * What the wearer hears: the confirmation with Butler's receipt, or a refusal in words («Блюда с номером 14 нет»)
     * with no task — a wrong number is answered, not recorded.
     */
    public record Outcome(String text, GlassesVoiceTasks.Receipt task) { }

    @Autowired
    public GlassesShortOrders(GlassesStaffTaskRelay relay, GlassesLunchDishes dishes,
                              @Value("${astor.glasses.short-orders-enabled:false}") boolean enabled,
                              @Value("${astor.glasses.short-order-venue:aeris}") String venue) {
        this.relay = relay;
        this.dishes = dishes;
        this.enabled = enabled;
        this.venue = venue == null || venue.isBlank() ? "aeris" : venue.strip().toLowerCase(Locale.ROOT);
    }

    public static GlassesShortOrders disabled() {
        return new GlassesShortOrders(GlassesStaffTaskRelay.disabled(), GlassesLunchDishes.disabled(), false, "aeris");
    }

    /** On, and the relay to Butler is configured: the same token serves the menu and the task. */
    boolean enabled() { return enabled && relay.configured() && dishes.configured(); }

    String venue() { return venue; }

    /**
     * Whether these words are a short order. An explicit question ({@code intent: "assist"}) never is; the task
     * gesture ({@code intent: "task"}) with an order phrase is an order — it is a task for the table's waiter.
     */
    boolean isOrder(String intent, String text) {
        if (GlassesVoiceTasks.INTENT_ASSIST.equals(intent)) return false;
        return enabled() && GlassesShortOrderGrammar.parse(text) != null;
    }

    /** Resolves the numbers, asks Butler for the task and keeps the receipt for a repeat of the request. */
    Outcome create(GlassesAccess.Scope scope, String requestId, String text) {
        GlassesShortOrderGrammar.Order order = GlassesShortOrderGrammar.parse(text);
        if (order == null) throw refused();
        List<GlassesLunchDishes.Dish> menu = dishes.dishes(venue);
        if (menu.isEmpty()) return new Outcome(NO_NUMBERS, null);
        List<Line> lines = new ArrayList<>();
        for (GlassesShortOrderGrammar.Item item : order.items()) {
            Optional<GlassesLunchDishes.Dish> dish = menu.stream().filter(d -> d.number() == item.number()).findFirst();
            if (dish.isEmpty()) return new Outcome("Блюда с номером " + item.number() + " нет", null);
            lines.add(new Line(dish.get(), item.quantity()));
        }
        GlassesVoiceTasks.Draft draft = new GlassesVoiceTasks.Draft(null, order.tableCode(), title(order.tableCode(), lines),
                cut(text.strip(), GlassesVoiceTasks.INSTRUCTION_LIMIT), "NORMAL");
        GlassesVoiceTasks.Receipt receipt = relay.create(scope, requestId, draft, KIND);
        synchronized (receipts) {
            receipts.put(key(scope, requestId), receipt);
        }
        return new Outcome(confirmation(order.tableCode(), lines), receipt);
    }

    GlassesVoiceTasks.Receipt receipt(GlassesAccess.Scope scope, String requestId) {
        synchronized (receipts) {
            return receipts.get(key(scope, requestId));
        }
    }

    private record Line(GlassesLunchDishes.Dish dish, int quantity) { }

    /** «Стол 12: Борщ ×1, Цезарь ×2 (голосовой заказ)» — within Butler's title limit, the suffix always kept. */
    static String title(String tableCode, List<Line> lines) {
        String head = "Стол " + tableCode + ": ";
        List<String> parts = new ArrayList<>();
        for (Line line : lines) parts.add(line.dish().title() + " ×" + line.quantity());
        String body = String.join(", ", parts);
        int room = TITLE_LIMIT - head.length() - SUFFIX.length();
        if (body.length() > room) body = body.substring(0, Math.max(0, room - 1)).strip() + "…";
        return head + body + SUFFIX;
    }

    /** «Записал: стол 12, Борщ, Цезарь два» — the earpiece hears the dishes and the count, no sums, no names. */
    static String confirmation(String tableCode, List<Line> lines) {
        StringBuilder text = new StringBuilder("Записал: стол ").append(tableCode);
        for (Line line : lines) {
            text.append(", ").append(line.dish().title());
            if (line.quantity() > 1) text.append(' ').append(spokenCount(line.quantity()));
        }
        return text.toString();
    }

    private static String spokenCount(int quantity) {
        return quantity < SPOKEN_COUNTS.length ? SPOKEN_COUNTS[quantity] : String.valueOf(quantity);
    }

    static GlassesFailure refused() {
        return new GlassesFailure(503, FAILURE_CODE, FAILURE_MESSAGE);
    }

    private static String cut(String text, int limit) {
        if (text.length() <= limit) return text;
        String cut = text.substring(0, limit);
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) cut = cut.substring(0, cut.length() - 1);
        return cut.strip();
    }

    private static String key(GlassesAccess.Scope scope, String requestId) {
        return scope.tenant() + "\n" + scope.staff() + "\n" + requestId;
    }
}
