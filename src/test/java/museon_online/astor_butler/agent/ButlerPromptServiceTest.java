package museon_online.astor_butler.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** In a negotiation the Butler finds out what the restaurant needs from the guest for great service. */
class ButlerPromptServiceTest {

    private final String prompt = ButlerPrompt.system(List.of(), List.of(), null, LocalDate.of(2026, 10, 15));

    @Test
    void serviceIsAGoalAndTheButlerAsksForWhatMatters() {
        assertThat(prompt).contains("отличный сервис");
        assertThat(prompt).contains("повод", "аллергии", "стол");
        assertThat(prompt).contains("спроси гостя о том, что важно для сервиса");
    }

    @Test
    void whatTheGuestSaidGoesIntoTheReservationWish() {
        assertThat(prompt).contains("reservation_create", "wish");
    }

    @Test
    void theButlerDoesNotInterrogateTheGuest() {
        assertThat(prompt).contains("не больше одного такого вопроса за ход");
    }

    /**
     * Measured live on 2026-10-15 (SBER500_LLM_ACCEPTANCE.md): without this rule gigachat-3-pro created a request in 5 of
     * 5 first turns «Есть стол?», gpt-4o-mini in 1 of 5; with it, 0 of 5 for both, and the request still follows «да».
     */
    @Test
    void aTableRequestWaitsForTheGuestsYes() {
        assertThat(prompt).contains("Перед reservation_create назови день, время и число гостей и дождись от гостя «да»");
        assertThat(prompt).contains("На вопрос «есть ли стол?» – только table_availability");
    }

    /**
     * Run 16.10.2026: the Concierge forwards the reply word for word, and the guest read «Подтверди… хочешь», «дай знать»
     * and «Не нашла брони… для [имя гостя]».
     */
    @Test
    void theReplyIsWrittenToTheGuestOnVy() {
        assertThat(prompt).contains("Твой ответ гость читает дословно", "Консьерж пересылает его без правок");
        assertThat(prompt).contains("Обращайся к гостю и только на «вы»", "Никогда на «ты»");
        assertThat(prompt).contains("Не обращайся к Консьержу и не пиши о госте в третьем лице");
        assertThat(prompt).doesNotContain("с гостем или с Консьержем", "спроси гостя через Консьержа");
    }

    /** One voice with the bot's fixed texts («Добавил», «передал хостес»): masculine, never «нашла». */
    @Test
    void theButlerSpeaksOfHimselfInTheMasculine() {
        assertThat(prompt).contains("О себе – в мужском роде: «передал», «нашёл»", "не «нашла»");
    }

    /** Run 16.10: thirteen drinks became «кофе, чай и лимонады», so the cranberry mors looked made up. */
    @Test
    void theMenuIsShownWordForWordFromTheTool() {
        assertThat(prompt).contains("Меню показывай дословно из поля text инструмента lunch_menu",
                "ничего не сокращай и не обобщай", "Блюда называй так, как в меню");
    }

    /** Run 16.10: «Есть 14:00, 14:15 и 14:30. Записать вас на 14:00?» – three times in words and a yes/no about one. */
    @Test
    void alternativesAreListedNotAskedOneByOne() {
        assertThat(prompt).contains("перечисли их все и спроси, какой удобнее", "Не спрашивай «да/нет» про один из них");
    }

    @Test
    void aGuestWhoCameForTheLunchIsNotOfferedItAgain() {
        assertThat(prompt).contains("предложи его, когда гость приходит на обед и сам о ланче не сказал",
                "Если гость пришёл за бизнес-ланчем, не предлагай его заново");
    }

    /** Run 16.10: Friday, «завтра» is Saturday, and the restaurant offered a Saturday lunch. */
    @Test
    void theLunchIsOnlyOfferedOnTheDaysItIsServed() {
        assertThat(prompt).contains("Вызывай lunch_menu с датой визита", "served=false",
                "предложи ближайший день из nextServedDay", "Ланч на такой день не предлагай и не оформляй");
    }

    /** Run 16.10: «нет, это тест» to the order became a search for a booking to cancel. */
    @Test
    void aNoToTheOrderIsNotACancellation() {
        assertThat(prompt).contains("«Нет» на перечень заказа – не отмена: ответь «Хорошо, заказ не отправляю.» и спроси, что поменять",
                "«Нет» на предложение стола – тоже не отмена", "только когда гость прямо просит отменить уже созданную бронь");
    }

    /** Run 16.10: «Уточню у команды про парковку» – the agent has no way to ask the team. */
    @Test
    void anUnknownFactIsNotPromised() {
        assertThat(prompt).contains("честно скажи, что таких сведений у нас пока нет", "Не обещай «уточню у команды»");
        assertThat(prompt).doesNotContain("скажи, что уточнишь");
    }

    @Test
    void theGuestTextsUseTheEnDashAndYo() {
        assertThat(prompt).contains("Тире – короткое «–», буква «ё» на месте").doesNotContain("—");
    }
}
