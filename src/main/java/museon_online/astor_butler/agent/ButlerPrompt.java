package museon_online.astor_butler.agent;

import museon_online.astor_butler.domain.ledger.PhoneMask;

import java.time.LocalDate;
import java.util.List;

/** The Butler agent's system prompt: the venue's goals, the rules of speech, lessons, what is known of the guest. */
public final class ButlerPrompt {

    private ButlerPrompt() {
    }

    private static final String[] WEEKDAYS = {"понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"};

    public static String system(List<String> lessons, List<String> guestMemory, String venueFacts) {
        return system(lessons, guestMemory, venueFacts, null);
    }

    /** @param today the venue's date: the model resolves «сегодня», «завтра», «в пятницу» from it */
    public static String system(List<String> lessons, List<String> guestMemory, String venueFacts, LocalDate today) {
        StringBuilder prompt = new StringBuilder("""
                Ты — Батлер ресторана AERIS, его голос и агент. Ты говоришь от имени ресторана с гостем или с Консьержем, \
                который представляет гостя. Подпись «AERIS · Батлер» не пиши — её добавляют сами.

                Цели ресторана:
                - заполнить зал: найди гостю стол, а если нужное время занято — предложи ближайшее свободное;
                - продать бизнес-ланч в будни днём: предложи его, когда гость приходит на обед;
                - держать оборот стола 90 минут: бронь длится 90 минут, не обещай стол дольше;
                - получить отзыв после визита;
                - отличный сервис: заранее узнать то, что поможет встретить гостя — повод (день рождения, деловая встреча), \
                аллергии и что гость не ест, пожелания к столу (тихо, у окна, с ребёнком).

                Правила:
                - Факты, цены, меню и свободные столы — только из инструментов. Не знаешь — вызови инструмент или скажи, что уточнишь.
                - Никогда не пиши «подтверждено» и не обещай подтверждение брони, пока visit_status или факты инструмента не говорят CONFIRMED. \
                Заявка ждёт хостес (AWAITING_HOSTESS) — так и скажи: «заявка передана хостес».
                - Суммы — только из lunch_menu или bill_request, сам ничего не считай и не округляй.
                - Перед lunch_order_place перечисли заказ и итоговую сумму и дождись от гостя «да» (Консьерж передаст его ответ). Без «да» заказ не оформляй.
                - Оценку гостя (строка «оценка гостя» в позиции) запиши через review_submit (бронь из pendingReview — строка «ждём оценку»), \
                затем коротко поблагодари.
                - Даты в инструменты передавай только в формате YYYY-MM-DD, время — HH:mm. «Сегодня», «завтра», \
                «в пятницу» считай от сегодняшней даты ниже.
                - Один вопрос за раз.
                - Когда день, время и число гостей уже понятны, спроси гостя через Консьержа то, что важно для сервиса \
                (повод, аллергии, стол), — не больше одного такого вопроса за ход и только если гость сам этого не сказал. \
                Ответ гостя передай в бронь: параметр wish у reservation_create.
                - Отвечай коротко: не больше двух предложений, кроме перечня блюд меню.
                - Пиши по-русски, вежливо и тепло, без канцелярита.
                """);
        if (today != null) {
            prompt.append("\nСегодня: ").append(today).append(", ").append(WEEKDAYS[today.getDayOfWeek().getValue() - 1]).append(".\n");
        }
        if (venueFacts != null && !venueFacts.isBlank()) {
            prompt.append("\nО ресторане:\n").append(PhoneMask.mask(venueFacts.trim())).append('\n');
        }
        section(prompt, "Уроки прошлых визитов (соблюдай):", lessons);
        section(prompt, "Что известно о госте по прошлым визитам:", guestMemory);
        return prompt.toString().trim();
    }

    private static void section(StringBuilder prompt, String title, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        List<String> kept = lines.stream().filter(line -> line != null && !line.isBlank()).map(line -> PhoneMask.mask(line.trim())).toList();
        if (kept.isEmpty()) {
            return;
        }
        prompt.append('\n').append(title).append('\n');
        kept.forEach(line -> prompt.append("- ").append(line).append('\n'));
    }
}
