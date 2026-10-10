package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptLanguageDetectorTest {

    private final ScriptLanguageDetector detector = new ScriptLanguageDetector(8);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Здравствуйте, хочу забронировать стол на вечер | ru",
            "Дякую, все було чудово, їжа дуже смачна | uk",
            "Добры дзень, мы хочам прыйсці ўвечары | be",
            "Сәлеметсіз бе, үстел брондағым келеді | kk",
            "Здраво, желим да резервишем сто за вечерас, хвала, ћао | sr",
            "Hello, I would like to book a table for two tonight | en",
            "Ich heiße Anna und möchte einen Tisch für morgen | de",
            "Merhaba, yarın akşam için iki kişilik masa ayırtmak istiyorum | tr",
            "Hola, ¿tienen una mesa para mañana? | es",
            "Xin chào, tôi muốn đặt bàn cho hai người | vi",
            "Dzień dobry, chciałbym zarezerwować stolik | pl",
            "我想预订今晚两个人的桌子 | zh",
            "今晩二人で予約したいです | ja",
            "안녕하세요 오늘 저녁 두 명 예약하고 싶어요 | ko",
            "مرحبا، أريد حجز طاولة لشخصين | ar",
            "سلام، می‌خواهم یک میز برای دو نفر رزرو کنم | fa",
            "Γεια σας, θα ήθελα ένα τραπέζι | el",
            "שלום, אני רוצה להזמין שולחן | he",
            "Բարև, ուզում եմ սեղան ամրագրել | hy",
            "გამარჯობა, მინდა მაგიდის დაჯავშნა | ka",
            "नमस्ते, मैं आज रात के लिए टेबल बुक करना चाहता हूँ | hi",
            "สวัสดีครับ ต้องการจองโต๊ะ | th"
    })
    void recognisesTheLanguageOfAFullSentence(String text, String expected) {
        Optional<DetectedLanguage> detected = detector.detect(text, List.of());

        assertThat(detected).isPresent();
        assertThat(detected.get().language()).isEqualTo(expected);
        assertThat(detected.get().confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(detected.get().method()).isEqualTo(ScriptLanguageDetector.METHOD);
    }

    @ParameterizedTest(name = "\"{0}\" says nothing")
    @CsvSource(delimiter = '|', value = {
            "ok",
            "19:00",
            "Привет",
            "👍👍",
            "https://example.com/booking/table",
            "Privet, hochu stolik na vecher",
            "Quiero reservar una mesa para dos personas",
            "Хочу table на двоих please"
    })
    void staysSilentWhenTheTextIsTooShortOrAmbiguous(String text) {
        assertThat(detector.detect(text, List.of())).isEmpty();
    }

    @Test
    void ownAlphabetsNeedOnlyAFewLetters() {
        assertThat(detector.detect("你好", List.of())).map(DetectedLanguage::language).contains("zh");
        assertThat(detector.detect("Γεια", List.of())).map(DetectedLanguage::language).contains("el");
    }

    @Test
    void plainCyrillicIsNotTrustedWhenTheGuestUsesAnotherCyrillicLanguage() {
        DetectedLanguage detected = detector.detect("Добър вечер, искам маса за двама", List.of("bg")).orElseThrow();

        assertThat(detected.language()).isEqualTo("ru");
        assertThat(detected.confidence()).isLessThan(0.7);
    }

    @Test
    void russianTextFromAUkrainianInterfaceStaysRussian() {
        DetectedLanguage detected = detector.detect("Добрый вечер, можно столик на двоих?", List.of("uk")).orElseThrow();

        assertThat(detected.language()).isEqualTo("ru");
        assertThat(detected.confidence()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void hintsBreakTiesBetweenLanguagesOfOneScript() {
        assertThat(detector.detect("मला आज रात्री टेबल हवे आहे", List.of("mr")))
                .map(DetectedLanguage::language).contains("mr");
        assertThat(detector.detect("मला आज रात्री टेबल हवे आहे", List.of()))
                .map(DetectedLanguage::language).contains("hi");
        assertThat(detector.detect("今夜予約", List.of("ja")))
                .map(DetectedLanguage::language).contains("ja");
    }

    @Test
    void ignoresLinksMentionsAndCommands() {
        assertThat(detector.detect("@aeris_bot /start https://aeris.example/menu Здравствуйте, хочу меню", List.of()))
                .map(DetectedLanguage::language).contains("ru");
    }

    @Test
    void nullAndBlankAreSilent() {
        assertThat(detector.detect(null, null)).isEmpty();
        assertThat(detector.detect("   ", List.of())).isEmpty();
    }
}
