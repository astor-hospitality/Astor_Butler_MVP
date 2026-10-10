package museon_online.astor_butler.max.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MAX update JSON (shapes from dev.max.ru and the api-schema) into the channel-neutral pipeline input. */
class MaxUpdateMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MaxUpdateMapper mapper = new MaxUpdateMapper();

    @Test
    void dialogTextBecomesAMaxIncomingMessageWithoutTelegramIds() throws Exception {
        MaxInbound inbound = mapper.read(json("""
                {"update_type":"message_created","timestamp":1739184000000,"user_locale":"ru-RU",
                 "message":{"sender":{"user_id":54321,"first_name":"Иван","last_name":"Петров","username":"ivan","is_bot":false,"name":"Иван Петров"},
                            "recipient":{"chat_id":-100000000,"chat_type":"dialog","user_id":12345},
                            "timestamp":1739184000000,
                            "body":{"mid":"mid.abc","seq":1,"text":"Стол на завтра в 20:00","markup":[{"type":"strong","from":0,"length":4}]}}}
                """)).orElseThrow();

        assertThat(inbound.dialog()).isTrue();
        assertThat(inbound.chatId()).isEqualTo(-100000000L);
        assertThat(inbound.eventKey()).isEqualTo("mid.abc");

        IncomingMessage incoming = mapper.toIncoming(inbound, 8_000_000_000_005L);
        assertThat(incoming.channel()).isEqualTo(MessageChannel.MAX);
        assertThat(incoming.chatId()).isEqualTo(8_000_000_000_005L);
        assertThat(incoming.externalUserId()).isEqualTo("54321");
        assertThat(incoming.telegramUserId()).isNull();
        assertThat(incoming.telegramMessageId()).isNull();
        assertThat(incoming.text()).isEqualTo("Стол на завтра в 20:00");
        assertThat(incoming.firstName()).isEqualTo("Иван");
        assertThat(incoming.lastName()).isEqualTo("Петров");
        assertThat(incoming.languageCode()).isEqualTo("ru");
        assertThat(incoming.correlationId()).isEqualTo("max:mid.abc");
        assertThat(incoming.hasMessengerUser()).isTrue();
        assertThat(incoming.payload()).containsEntry("maxChatId", -100000000L).containsEntry("maxUserId", 54321L)
                .containsEntry("maxMessageId", "mid.abc").containsEntry("maxChatType", "dialog");
    }

    @Test
    void botStartedIsTheStartCommandAndCarriesTheDeepLinkPayload() throws Exception {
        MaxInbound plain = mapper.read(json("""
                {"update_type":"bot_started","timestamp":1573226679188,"chat_id":1234567890,
                 "user":{"user_id":1234567890,"name":"Иван","username":"ivan_petrov"}}
                """)).orElseThrow();
        MaxInbound lunch = mapper.read(json("""
                {"update_type":"bot_started","timestamp":1573226679189,"chat_id":1234567890,
                 "user":{"user_id":1234567890,"first_name":"Иван"},"payload":"lunch_aeris_p2_d20261007_t1300_r7f3a","user_locale":"ru"}
                """)).orElseThrow();

        assertThat(plain.text()).isEqualTo("/start");
        assertThat(plain.firstName()).isEqualTo("Иван");
        assertThat(plain.dialog()).isTrue();
        // The same text Telegram delivers for t.me/bot?start=..., so BusinessLunchHandoff reads it unchanged.
        assertThat(lunch.text()).isEqualTo("/start lunch_aeris_p2_d20261007_t1300_r7f3a");
        assertThat(lunch.payload()).containsEntry("startPayload", "lunch_aeris_p2_d20261007_t1300_r7f3a");
        assertThat(lunch.eventKey()).isNotEqualTo(plain.eventKey());
    }

    @Test
    void contactFromTheConsentButtonGivesThePhone() throws Exception {
        MaxInbound inbound = mapper.read(json("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":77,"first_name":"Анна","is_bot":false},
                            "recipient":{"chat_id":500,"chat_type":"dialog"},
                            "body":{"mid":"mid.c","seq":2,"text":null,
                                    "attachments":[{"type":"contact","payload":{
                                       "vcf_info":"BEGIN:VCARD\\r\\nVERSION:3.0\\r\\nTEL;TYPE=cell:79990000000\\r\\nFN:Анна\\r\\nEND:VCARD\\r\\n",
                                       "max_info":{"user_id":77,"first_name":"Анна"},"hash":"abc"}}]}}}
                """)).orElseThrow();

        assertThat(inbound.contactPhone()).isEqualTo("79990000000");
        assertThat(inbound.text()).isEmpty();
    }

    @Test
    void literalLineBreaksInTheVcardAreUnderstood() throws Exception {
        JsonNode contact = json("{\"vcf_info\":\"BEGIN:VCARD\\\\r\\\\nTEL:+7 999 000-00-00\\\\r\\\\nEND:VCARD\",\"hash\":\"h\"}");

        assertThat(MaxUpdateMapper.contactPhone(contact)).isEqualTo("+7 999 000-00-00");
    }

    @Test
    void someoneElsesContactIsNotConsent() throws Exception {
        MaxInbound inbound = mapper.read(json("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":77,"first_name":"Анна"},"recipient":{"chat_id":500,"chat_type":"dialog"},
                            "body":{"mid":"mid.d","seq":3,"attachments":[{"type":"contact","payload":{
                               "vcf_info":"BEGIN:VCARD\\r\\nTEL:79991112233\\r\\nEND:VCARD","max_info":{"user_id":99}}}]}}}
                """)).orElseThrow();

        assertThat(inbound.contactPhone()).isNull();
        assertThat(inbound.payload()).containsEntry("maxContactIgnored", "NOT_OWN_CONTACT");
    }

    @Test
    void voiceWithMaxTranscriptionIsReadAsTextAndWithoutItAsksTheGatewayForText() throws Exception {
        MaxInbound transcribed = mapper.read(json("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":7},"recipient":{"chat_id":5,"chat_type":"dialog"},
                            "body":{"mid":"mid.v1","seq":1,"attachments":[{"type":"audio","payload":{"url":"https://media/v1","token":"t"},"transcription":" покажи меню "}]}}}
                """)).orElseThrow();
        MaxInbound silent = mapper.read(json("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":7},"recipient":{"chat_id":5,"chat_type":"dialog"},
                            "body":{"mid":"mid.v2","seq":2,"attachments":[{"type":"audio","payload":{"url":"https://media/v2"},"transcription":null}]}}}
                """)).orElseThrow();

        assertThat(transcribed.text()).isEqualTo("покажи меню");
        assertThat(transcribed.payload()).containsEntry("mediaKind", "VOICE").containsEntry("transcriptionProvider", "max");
        assertThat(silent.text()).isEmpty();
        assertThat(silent.payload()).containsEntry("mediaKind", "VOICE").containsEntry("transcriptionReason", "STT disabled")
                .containsEntry("maxAudioUrl", "https://media/v2");
    }

    @Test
    void callbackWithOurTextPayloadIsTheGuestsWordsOthersAreNot() throws Exception {
        String template = """
                {"update_type":"message_callback","timestamp":1,"user_locale":"ru",
                 "callback":{"timestamp":1,"callback_id":"cb-%s","payload":"%s","user":{"user_id":54321,"first_name":"Иван"}},
                 "message":{"recipient":{"chat_id":-100000000,"chat_type":"dialog","user_id":54321},
                            "sender":{"user_id":12345,"is_bot":true},"body":{"mid":"mid.k","seq":0,"text":"..."}}}
                """;
        MaxInbound ours = mapper.read(json(template.formatted("1", "text:Бронь стола"))).orElseThrow();
        MaxInbound foreign = mapper.read(json(template.formatted("2", "utm_view_6"))).orElseThrow();

        assertThat(ours.text()).isEqualTo("Бронь стола");
        assertThat(ours.callbackId()).isEqualTo("cb-1");
        assertThat(ours.userId()).isEqualTo(54321L);
        assertThat(ours.fromBot()).isFalse();
        assertThat(ours.eventKey()).isEqualTo("callback:cb-1");
        assertThat(foreign.text()).isNull();
        assertThat(foreign.callbackId()).isEqualTo("cb-2");
    }

    @Test
    void groupChatsAndBotsAreRecognisedAndOtherUpdatesIgnored() throws Exception {
        MaxInbound group = mapper.read(json("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":1,"is_bot":true},"recipient":{"chat_id":-42,"chat_type":"chat"},"body":{"mid":"mid.g","seq":1,"text":"hi"}}}
                """)).orElseThrow();

        assertThat(group.dialog()).isFalse();
        assertThat(group.fromBot()).isTrue();
        assertThat(mapper.read(json("{\"update_type\":\"bot_added\",\"timestamp\":1,\"chat_id\":-42,\"user\":{\"user_id\":1}}"))).isEmpty();
        assertThat(mapper.read(json("{\"update_type\":\"message_created\",\"message\":{\"body\":{}}}"))).isEmpty();
        assertThat(mapper.read(null)).isEmpty();
    }

    @Test
    void localesBecomeTwoLetterLanguageCodes() {
        assertThat(MaxUpdateMapper.language("ru-RU")).isEqualTo("ru");
        assertThat(MaxUpdateMapper.language("en_US")).isEqualTo("en");
        assertThat(MaxUpdateMapper.language("ru")).isEqualTo("ru");
        assertThat(MaxUpdateMapper.language(" ")).isNull();
    }

    private JsonNode json(String value) throws Exception {
        return json.readTree(value);
    }
}
