package museon_online.astor_butler.telegram.utils;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendLocation;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HostessThreadRouterTest {

    private static final String HOSTESS = "-1001234567890";

    private static SendMessage message(String chatId) {
        return SendMessage.builder().chatId(chatId).text("card").build();
    }

    @Test
    void hostessMessageGoesIntoConfiguredTopic() {
        SendMessage m = message(HOSTESS);
        new HostessThreadRouter(" " + HOSTESS + " ", "6").apply(m);
        assertThat(m.getMessageThreadId()).isEqualTo(6);
    }

    @Test
    void otherChatIsUntouched() {
        SendMessage m = message("876857557");
        new HostessThreadRouter(HOSTESS, "6").apply(m);
        assertThat(m.getMessageThreadId()).isNull();
    }

    @Test
    void explicitThreadIsKept() {
        SendMessage m = message(HOSTESS);
        m.setMessageThreadId(42);
        new HostessThreadRouter(HOSTESS, "6").apply(m);
        assertThat(m.getMessageThreadId()).isEqualTo(42);
    }

    @Test
    void emptyOrInvalidConfigChangesNothing() {
        for (String thread : new String[]{"", null, "  ", "abc", "0", "-3"}) {
            SendMessage m = message(HOSTESS);
            new HostessThreadRouter(HOSTESS, thread).apply(m);
            assertThat(m.getMessageThreadId()).as("thread=%s", thread).isNull();
        }
        SendMessage m = message(HOSTESS);
        new HostessThreadRouter("", "6").apply(m);
        assertThat(m.getMessageThreadId()).isNull();
    }

    @Test
    void editsAreNeverTouched() {
        EditMessageText edit = EditMessageText.builder().chatId(HOSTESS).messageId(10).text("x").build();
        new HostessThreadRouter(HOSTESS, "6").apply(edit);
        assertThat(edit.getChatId()).isEqualTo(HOSTESS);
        // EditMessageText has no thread: the router must simply leave it alone (no exception).
    }

    @Test
    void locationAndMediaGroupAlsoGoIntoTopic() {
        SendLocation location = SendLocation.builder().chatId(HOSTESS).latitude(1.0).longitude(2.0).build();
        SendMediaGroup group = SendMediaGroup.builder().chatId(HOSTESS).medias(List.of()).build();
        HostessThreadRouter router = new HostessThreadRouter(HOSTESS, "6");
        router.apply(location);
        router.apply(group);
        assertThat(location.getMessageThreadId()).isEqualTo(6);
        assertThat(group.getMessageThreadId()).isEqualTo(6);
    }
}
