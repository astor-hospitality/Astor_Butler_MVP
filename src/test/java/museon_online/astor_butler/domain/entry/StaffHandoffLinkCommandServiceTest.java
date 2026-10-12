package museon_online.astor_butler.domain.entry;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code /ссылка} in the staff chat (#128): who may, what comes back, and that the QR is the link itself. */
class StaffHandoffLinkCommandServiceTest {

    private static final String LINK = "https://t.me/astor_butler_bot?start=lunch_aeris_hanna_n5";

    private final GuestEntryService entries = mock(GuestEntryService.class);
    private final AbsSender sender = mock(AbsSender.class);
    private final StaffHandoffLinkCommandService service = new StaffHandoffLinkCommandService(properties(true), entries, "500", "600", "700");

    @Test
    void theLinkAndADecodableQrGoToTheStaffChat() throws Exception {
        when(entries.displayName("anna")).thenReturn("Анна");
        when(entries.known("anna")).thenReturn(true);

        assertThat(service.handle(staff(500L, "/ссылка anna 5"), sender)).isTrue();

        ArgumentCaptor<SendPhoto> photo = ArgumentCaptor.forClass(SendPhoto.class);
        verify(sender).execute(photo.capture());
        SendPhoto sent = photo.getValue();
        assertThat(sent.getChatId()).isEqualTo("500");
        assertThat(sent.getParseMode()).isEqualTo("HTML");
        assertThat(sent.getCaption())
                .contains(LINK)
                .contains("Анна (anna)")
                .contains("стол 5")
                .contains("Привёл(а): Анна")
                .contains("не бонус")
                .doesNotContain("⚠️");
        assertThat(decode(sent.getPhoto())).isEqualTo(LINK);
        verify(sender, never()).execute(any(SendMessage.class));
    }

    @Test
    void theHostStandLinkThroughTheAliasFromTheAdminChat() throws Exception {
        when(entries.displayName("host")).thenReturn(GuestEntryService.HOST_STAND_NAME);
        when(entries.known("host")).thenReturn(true);

        assertThat(service.handle(staff(700L, "/link@astor_butler_bot host"), sender)).isTrue();

        ArgumentCaptor<SendPhoto> photo = ArgumentCaptor.forClass(SendPhoto.class);
        verify(sender).execute(photo.capture());
        assertThat(photo.getValue().getChatId()).isEqualTo("700");
        assertThat(photo.getValue().getCaption())
                .contains("https://t.me/astor_butler_bot?start=lunch_aeris_hhost")
                .contains("стойка хоста (host)")
                .doesNotContain("стол ");
        assertThat(decode(photo.getValue().getPhoto())).isEqualTo("https://t.me/astor_butler_bot?start=lunch_aeris_hhost");
    }

    @Test
    void anUnknownIdStillGetsALinkWithAWarning() throws Exception {
        when(entries.displayName("xyz")).thenReturn(GuestEntryService.UNKNOWN_NAME);
        when(entries.known("xyz")).thenReturn(false);

        assertThat(service.handle(staff(600L, "/ссылка xyz стол 12"), sender)).isTrue();

        ArgumentCaptor<SendPhoto> photo = ArgumentCaptor.forClass(SendPhoto.class);
        verify(sender).execute(photo.capture());
        assertThat(photo.getValue().getCaption())
                .contains("?start=lunch_aeris_hxyz_n12")
                .contains("В справочнике нет сотрудника «xyz»")
                .contains("стойка/неизвестно");
    }

    @Test
    void withoutAnIdTheCommandExplainsItself() throws Exception {
        assertThat(service.handle(staff(500L, "/ссылка"), sender)).isTrue();

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("500");
        assertThat(message.getValue().getText()).contains("/ссылка anna 5").contains("host");
        verify(sender, never()).execute(any(SendPhoto.class));
        verifyNoInteractions(entries);
    }

    @Test
    void anIdThatWouldBreakTheLinkIsRefused() throws Exception {
        assertThat(service.handle(staff(500L, "/ссылка an_na 5"), sender)).isTrue();
        assertThat(service.handle(staff(500L, "/ссылка anna 5/6"), sender)).isTrue();

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(sender, times(2)).execute(message.capture());
        assertThat(message.getAllValues()).allSatisfy(sent -> assertThat(sent.getText()).contains("латиница"));
        verify(sender, never()).execute(any(SendPhoto.class));
        verifyNoInteractions(entries);
    }

    @Test
    void aGuestOrAnUnknownGroupIsIgnored() throws Exception {
        assertThat(service.handle(staff(1001L, "/ссылка anna 5"), sender)).isFalse();
        assertThat(service.handle(staff(-100999L, "/link anna"), sender)).isFalse();
        verifyNoInteractions(sender, entries);
    }

    @Test
    void anOrdinaryStaffTextIsNotTheCommand() throws Exception {
        for (String text : new String[]{"/stop", "ссылка anna", "/links anna", "/manual", "", null}) {
            assertThat(service.handle(staff(500L, text), sender)).as(String.valueOf(text)).isFalse();
        }
        verifyNoInteractions(sender, entries);
    }

    @Test
    void withTheFlagOffTheCommandIsUnknown() throws Exception {
        StaffHandoffLinkCommandService off = new StaffHandoffLinkCommandService(properties(false), entries, "500", "600", "700");

        assertThat(off.handle(staff(500L, "/ссылка anna 5"), sender)).isFalse();
        verifyNoInteractions(sender, entries);
    }

    @Test
    void whenThePhotoFailsTheLinkStillArrivesAsText() throws Exception {
        when(entries.displayName("anna")).thenReturn("Анна");
        when(entries.known("anna")).thenReturn(true);
        when(sender.execute(any(SendPhoto.class))).thenThrow(new TelegramApiException("boom"));

        assertThat(service.handle(staff(500L, "/ссылка anna 5"), sender)).isTrue();

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(sender).execute(message.capture());
        assertThat(message.getValue().getText()).contains(LINK);
    }

    @Test
    void theQrImageIsTheLinkItself() throws Exception {
        byte[] png = QrCodeImages.png(LINK);
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(QrCodeImages.DEFAULT_SIZE);
        assertThat(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText())
                .isEqualTo(LINK);
    }

    private static String decode(InputFile file) throws Exception {
        BufferedImage image = ImageIO.read(file.getNewMediaStream());
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }

    private static StaffHandoffProperties properties(boolean enabled) {
        return new StaffHandoffProperties(enabled, "aeris", 6, "@astor_butler_bot", "AERIS");
    }

    private static IncomingMessage staff(long chatId, String text) {
        return IncomingMessage.telegram(chatId, 42L, 1, 100, text, null, "Даша", null, "dasha", "ru", false, "test");
    }
}
