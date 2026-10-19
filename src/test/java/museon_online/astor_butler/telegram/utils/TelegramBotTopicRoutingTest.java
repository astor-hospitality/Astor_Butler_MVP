package museon_online.astor_butler.telegram.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import museon_online.astor_butler.config.TelegramBotConfig;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.billing.VisitReview;
import museon_online.astor_butler.domain.billing.VisitReviewStaffCard;
import museon_online.astor_butler.domain.booking.BookingAutoConfirmPolicy;
import museon_online.astor_butler.domain.booking.BookingMorningCheckNotifier;
import museon_online.astor_butler.domain.booking.GuestWishNotifier;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationPendingIntentService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.entry.GuestEntryService;
import museon_online.astor_butler.domain.entry.StaffHandoffLinkCommandService;
import museon_online.astor_butler.domain.entry.StaffHandoffProperties;
import museon_online.astor_butler.domain.telegram.TelegramGuestContextRepository;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real bot against a stub Bot API: what leaves Butler for the staff group goes to the bookings topic, whichever
 * service sends it; a message that names its topic keeps it; a guest's chat and a blank setting are as before.
 */
class TelegramBotTopicRoutingTest {

    private static final String STAFF_CHAT = "-1002768239841";
    private static final long GUEST_CHAT = 1773317437L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Call(String method, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The topic of a JSON call, or of a multipart upload; null when none was sent. */
        Integer thread() {
            if (body.trim().startsWith("{")) {
                JsonNode thread = json().get("message_thread_id");
                return thread == null || thread.isNull() ? null : thread.asInt();
            }
            int field = body.indexOf("name=\"message_thread_id\"");
            if (field < 0) {
                return null;
            }
            String rest = body.substring(body.indexOf("\r\n\r\n", field) + 4);
            return Integer.parseInt(rest.substring(0, rest.indexOf("\r\n")).trim());
        }

        String chat() {
            if (body.trim().startsWith("{")) {
                return json().get("chat_id").asText();
            }
            int field = body.indexOf("name=\"chat_id\"");
            String rest = body.substring(body.indexOf("\r\n\r\n", field) + 4);
            return rest.substring(0, rest.indexOf("\r\n")).trim();
        }
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startStubBotApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            String method = path.substring(path.lastIndexOf('/') + 1);
            calls.add(new Call(method, body));
            String result = switch (method) {
                case "answercallbackquery", "editmessagetext" -> "true";
                default -> "{\"message_id\":" + (900 + calls.size()) + ",\"date\":0,\"chat\":{\"id\":" + STAFF_CHAT + ",\"type\":\"supergroup\"}}";
            };
            byte[] answer = ("{\"ok\":true,\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStubBotApi() {
        server.stop(0);
    }

    @Test
    void everyKindOfStaffMessageGoesToTheBookingsTopic() throws Exception {
        TelegramBot bot = bot("6");
        TableReservationNotificationService cards = notifications(bot);
        TableReservationOrder order = order(STAFF_CHAT);

        cards.notifyHostessApprovalRequest(order, null);          // the request card with «Да / Нет»
        cards.notifyHostessConfirmed(order);                      // confirmed card
        cards.notifyHostessAcknowledged(order);                   // «Принял»
        cards.notifyHostessGuestCancelled(order);                 // the guest cancelled
        cards.notifyHostessSeatedByVenue(order);                  // «🪑 Гость сел»
        cards.notifyHostessGuestAskedForBill(order);              // «Счёт»
        new BookingMorningCheckNotifier(provider(bot), true, true, STAFF_CHAT)
                .hostessReconfirmed(order, LocalTime.of(13, 0));  // the morning check summary line
        new TelegramSender(bot).sendText(Long.parseLong(STAFF_CHAT), "Сводка за день");

        assertThat(calls).hasSize(8);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("sendmessage");
            assertThat(call.chat()).isEqualTo(STAFF_CHAT);
            assertThat(call.thread()).isEqualTo(6);
        });
    }

    @Test
    void theAnswerToAStaffCommandWithAQrPhotoGoesToTheBookingsTopicToo() {
        TelegramBot bot = bot("6");
        GuestEntryService entries = mock(GuestEntryService.class);
        when(entries.displayName("anna")).thenReturn("Анна");
        when(entries.known("anna")).thenReturn(true);
        StaffHandoffLinkCommandService links = new StaffHandoffLinkCommandService(
                new StaffHandoffProperties(true, "aeris", 6, "@astor_butler_bot", "AERIS"), entries, STAFF_CHAT, "600", "700");

        assertThat(links.handle(IncomingMessage.telegram(Long.parseLong(STAFF_CHAT), 42L, 1, 100, "/ссылка anna 5", null,
                "Даша", null, "dasha", "ru", false, "test"), bot)).isTrue();

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("sendphoto");
            assertThat(call.chat()).isEqualTo(STAFF_CHAT);
            assertThat(call.thread()).isEqualTo(6);
        });
    }

    @Test
    void aMessageThatNamesItsTopicKeepsIt() throws Exception {
        TelegramBot bot = bot("6");
        TableReservationRepository reservations = mock(TableReservationRepository.class);
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order(STAFF_CHAT)));
        VisitReviewStaffCard reviews = new VisitReviewStaffCard(provider(bot), reservations, true, true, STAFF_CHAT, "340");

        reviews.publish(bill(), new VisitReview(3L, 7L, GUEST_CHAT, NOW, 5, null, null, NOW, NOW, NOW), null);
        bot.execute(SendMessage.builder().chatId(STAFF_CHAT).messageThreadId(1).text("General").build());

        assertThat(calls).extracting(Call::thread).containsExactly(340, 1);
        assertThat(calls.getFirst().body()).doesNotContain("+79991234567");
    }

    @Test
    void aBookingWithAWishGivesTheCardInBookingsAndTheNoteInWishes() {
        TelegramBot bot = bot("6");
        TableReservationNotificationService cards = notifications(bot);
        cards.setWishNotifier(new GuestWishNotifier(provider(bot), true, true, STAFF_CHAT, "15"));
        TableReservationOrder base = order(STAFF_CHAT);
        TableReservationOrder withWish = new TableReservationOrder(base.id(), base.chatId(), base.telegramUserId(), null,
                base.tableId(), base.tableCode(), base.tableDisplayName(), null, "без лука, детский стул",
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM", base.requestedStartAt(), base.requestedEndAt(),
                base.partySize(), base.guestName(), base.guestPhone(), null, null, null, STAFF_CHAT, null, NOW, NOW);

        cards.notifyHostessApprovalRequest(withWish, null);

        assertThat(calls).extracting(Call::thread).containsExactly(6, 15);
        assertThat(calls.getFirst().body()).contains("+79991234567"); // the card in «Брони» stays as it is
        assertThat(calls.get(1).body()).doesNotContain("+79991234567");
    }

    @Test
    void theGuestsChatAndOtherChatsAreLeftAsTheyWere() throws Exception {
        TelegramBot bot = bot("6");

        notifications(bot).notifyGuestConfirmed(order(STAFF_CHAT));
        new BookingMorningCheckNotifier(provider(bot), true, true, STAFF_CHAT).ask(order(STAFF_CHAT), LocalTime.of(13, 0));
        bot.execute(SendMessage.builder().chatId("-100555").text("system chat").build());

        assertThat(calls).hasSize(3);
        assertThat(calls).extracting(Call::chat).containsExactly(String.valueOf(GUEST_CHAT), String.valueOf(GUEST_CHAT), "-100555");
        assertThat(calls).extracting(Call::thread).containsOnlyNulls();
    }

    @Test
    void editsAndCallbackAnswersCarryNoTopic() throws Exception {
        TelegramBot bot = bot("6");

        bot.execute(EditMessageText.builder().chatId(STAFF_CHAT).messageId(901).text("Обновлено").build());
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId("q1").text("Готово").build());

        assertThat(calls).extracting(Call::method).containsExactly("editmessagetext", "answercallbackquery");
        assertThat(calls).extracting(Call::thread).containsOnlyNulls();
    }

    @Test
    void aBlankSettingKeepsTheStaffChatInGeneralAsBefore() throws Exception {
        TelegramBot bot = bot("");

        notifications(bot).notifyHostessConfirmed(order(STAFF_CHAT));
        bot.execute(SendMessage.builder().chatId(STAFF_CHAT).text("Сводка").build());

        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(Call::thread).containsOnlyNulls();
    }

    private static final Instant NOW = Instant.parse("2026-10-16T08:30:00Z");

    private TelegramBot bot(String hostessThreadId) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/bot";
        TelegramBotConfig config = new TelegramBotConfig() {
            @Override
            public DefaultBotOptions botOptions() {
                DefaultBotOptions options = new DefaultBotOptions();
                options.setBaseUrl(baseUrl);
                return options;
            }
        };
        config.setUsername("astor_butler_bot");
        config.setToken("TEST");
        return new TelegramBot(mock(TelegramRouter.class), config, new HostessTopicRouter(STAFF_CHAT, hostessThreadId));
    }

    private static TableReservationNotificationService notifications(TelegramBot bot) {
        TableReservationNotificationService service = new TableReservationNotificationService(provider(bot),
                mock(TableReservationPendingIntentService.class), mock(TelegramGuestContextRepository.class), mock(BookingAutoConfirmPolicy.class), null);
        ReflectionTestUtils.setField(service, "telegramEnabled", true);
        ReflectionTestUtils.setField(service, "notificationsEnabled", true);
        ReflectionTestUtils.setField(service, "defaultHostessChatId", STAFF_CHAT);
        return service;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TelegramBot> provider(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private static TableReservationOrder order(String hostessChatId) {
        return new TableReservationOrder(41L, GUEST_CHAT, GUEST_CHAT, null, 5L, "5", "Table 5", null, null,
                TableReservationStatus.CONFIRMED, "TELEGRAM", NOW, NOW.plusSeconds(7200), 2, "Ирина", "+79991234567",
                null, null, null, hostessChatId, null, NOW, NOW);
    }

    private static GuestBill bill() {
        return new GuestBill(7L, GUEST_CHAT, GUEST_CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", 41L, null, null,
                GuestBillStatus.ISSUED, null, 54000L, null, "RUB", "menu", NOW, null, null, NOW, NOW);
    }
}
