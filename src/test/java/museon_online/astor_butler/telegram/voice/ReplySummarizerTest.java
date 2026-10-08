package museon_online.astor_butler.telegram.voice;

import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplySummarizerTest {

    private static final String LONG_ANSWER = """
            Добрый вечер! Сегодня в AERIS шеф рекомендует тартар из тунца с юдзу и ризотто с белыми грибами.
            Из бара советуем коктейль «Сабраж» и бокал Chablis Premier Cru 2021.
            Меню кухни: https://aeris.bar/menu.pdf
            Винная карта: https://aeris.bar/wine.pdf
            Если захотите стол у окна, напишите время и количество гостей — хостес подтвердит бронь.
            Напоминаю, что по пятницам и субботам после 21:00 играет живой диджей, поэтому в зале громче обычного.
            """;

    private final ModelGateway modelGateway = mock(ModelGateway.class);

    @Test
    void shortAnswerIsCompleteWithoutModelCall() {
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));

        ReplySummary summary = summarizer.summarize("Стол на 20:00 забронирован. Ждём вас!");

        assertThat(summary.complete()).isTrue();
        assertThat(summary.text()).isEqualTo("Стол на 20:00 забронирован. Ждём вас!");
        verify(modelGateway, never()).generateText(any());
    }

    @Test
    void modelSummaryIsUsedAndKeepsLinks() {
        when(modelGateway.generateText(any())).thenReturn(ModelTextResponse.text(
                "Шеф советует тартар из тунца и ризотто с белыми грибами, из бара — «Сабраж» и Chablis.\n"
                        + "https://aeris.bar/menu.pdf\nhttps://aeris.bar/wine.pdf",
                "test", "model", Duration.ZERO));
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));

        ReplySummary summary = summarizer.summarize(LONG_ANSWER);

        assertThat(summary.source()).isEqualTo("model");
        assertThat(summary.complete()).isFalse();
        assertThat(summary.text()).startsWith("Шеф советует").contains("https://aeris.bar/menu.pdf", "https://aeris.bar/wine.pdf");
        assertThat(summary.text().length()).isLessThanOrEqualTo(400);
        ArgumentCaptor<ModelTextRequest> request = ArgumentCaptor.forClass(ModelTextRequest.class);
        verify(modelGateway).generateText(request.capture());
        assertThat(request.getValue().purpose()).isEqualTo(ReplySummarizer.PURPOSE);
        assertThat(request.getValue().prompt()).contains("2–4 строки", "тартар из тунца");
    }

    @Test
    void linksTheModelDroppedAreAppended() {
        when(modelGateway.generateText(any())).thenReturn(ModelTextResponse.text(
                "Шеф советует тартар и ризотто.", "test", "model", Duration.ZERO));
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));

        ReplySummary summary = summarizer.summarize(LONG_ANSWER);

        assertThat(summary.text()).isEqualTo("Шеф советует тартар и ризотто.\n• https://aeris.bar/menu.pdf\n• https://aeris.bar/wine.pdf");
        assertThat(summary.links()).extracting(ReplyText.Link::url)
                .containsExactly("https://aeris.bar/menu.pdf", "https://aeris.bar/wine.pdf");
    }

    @Test
    void blankModelAnswerFallsBackDeterministically() {
        when(modelGateway.generateText(any())).thenReturn(ModelTextResponse.text("  ", "test", "model", Duration.ZERO));
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));

        ReplySummary summary = summarizer.summarize(LONG_ANSWER);

        assertThat(summary.source()).isEqualTo("fallback");
        assertThat(summary.text()).startsWith("Добрый вечер! Сегодня в AERIS шеф рекомендует");
        assertThat(summary.text()).endsWith("• https://aeris.bar/menu.pdf\n• https://aeris.bar/wine.pdf");
        assertThat(summary.text().length()).isLessThanOrEqualTo(400);
    }

    @Test
    void modelFailureFallsBackAndModelCanBeDisabled() {
        when(modelGateway.generateText(any())).thenThrow(new IllegalStateException("provider down"));
        ReplySummarizer failing = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));
        assertThat(failing.summarize(LONG_ANSWER).source()).isEqualTo("fallback");

        ModelGateway untouched = mock(ModelGateway.class);
        TelegramVoiceReplyConfig noModel = new TelegramVoiceReplyConfig(VoiceRepliesMode.ON, Duration.ofSeconds(8), 400, 900,
                4000, 1 << 20, 3, 3, false, Duration.ofDays(7));
        ReplySummary summary = new ReplySummarizer(untouched, noModel).summarize(LONG_ANSWER);
        assertThat(summary.source()).isEqualTo("fallback");
        verify(untouched, never()).generateText(any());
    }

    @Test
    void overlongModelAnswerIsCutToTheCap() {
        when(modelGateway.generateText(any())).thenReturn(ModelTextResponse.text(
                "Очень длинная строка. ".repeat(60), "test", "model", Duration.ZERO));
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));

        ReplySummary summary = summarizer.summarize(LONG_ANSWER);

        assertThat(summary.text().length()).isLessThanOrEqualTo(400);
        assertThat(summary.text()).contains("https://aeris.bar/menu.pdf");
    }

    @Test
    void htmlAnswerWithImageLinksYieldsPhotos() {
        ReplySummarizer summarizer = new ReplySummarizer(modelGateway, TelegramVoiceReplyConfig.defaults(VoiceRepliesMode.ON));

        ReplySummary summary = summarizer.summarize(
                "<b>Зал у окна</b>: <a href=\"https://aeris.bar/img/window.jpg\">фото</a>, свободен в 20:00.");

        assertThat(summary.complete()).isTrue();
        assertThat(summary.text()).isEqualTo("Зал у окна: фото https://aeris.bar/img/window.jpg, свободен в 20:00.");
        assertThat(summary.photos()).singleElement().satisfies(photo -> {
            assertThat(photo.url()).isEqualTo("https://aeris.bar/img/window.jpg");
            assertThat(photo.label()).isEqualTo("фото");
        });
    }
}
