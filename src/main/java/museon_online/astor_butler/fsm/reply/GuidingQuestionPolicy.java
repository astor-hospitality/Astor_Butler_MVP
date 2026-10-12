package museon_online.astor_butler.fsm.reply;

import museon_online.astor_butler.fsm.core.BotState;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Направляющий вопрос в конце ответа бота (#101): детерминированная таблица «сценарий + состояние/шаг → вопрос или ничего».
 * Решает FSM и доменная политика, не модель: вопрос ведёт к цели сценария — недостающему слоту, выбору или следующему
 * шагу, пока команда подтверждает. Никакого давления, цен, наличия и скрытого профиля: все формулировки — константы ниже.
 * Завершённый сценарий (гость дома в {@link BotState#READY_FOR_DIALOG}) вопроса не получает.
 *
 * <p>Каждый сценарий из {@code ScenarioRouter} обязан быть в таблице явно: с правилами или в списке «молчащих»;
 * это проверяет тест, который перечисляет id сценариев по исходникам. Таблица продублирована в
 * {@code docs/product/GUIDING_QUESTION.md}.
 */
@Component
public class GuidingQuestionPolicy {

    public static final String ASK_DATE = "На какую дату?";
    public static final String ASK_TIME = "На какое время?";
    public static final String ASK_PARTY_SIZE = "Сколько вас будет?";
    public static final String ASK_CHOICE = "Что выбираете?";
    public static final String OFFER_MENU_WHILE_TEAM_CONFIRMS = "Хотите, расскажу про меню, пока команда подтверждает?";
    public static final String ASK_WHAT_TO_CHANGE = "Что меняем: время, стол или число гостей?";

    /** Шаги изменения брони, на которых гость уже выбрал действие или заявку: вопрос «что меняем» неуместен. */
    private static final Set<String> CHANGE_CANCEL_VALUE_STEPS = Set.of(
            "CHANGE_TIME", "CHANGE_DATE", "CHANGE_TABLE", "CHANGE_PARTY_SIZE", "ASK_ORDER_SELECTION"
    );

    /**
     * Одно правило таблицы: состояние после ответа и, при необходимости, действия (шаг) ответа, при которых вопрос не
     * задаётся.
     */
    public record Rule(BotState state, Set<String> unlessActions, String question) {

        static Rule of(BotState state, String question) {
            return new Rule(state, Set.of(), question);
        }

        boolean matches(BotState state, Collection<String> actions) {
            if (this.state != state) {
                return false;
            }
            if (unlessActions.isEmpty() || actions == null) {
                return true;
            }
            return actions.stream().noneMatch(unlessActions::contains);
        }
    }

    private static final Map<String, List<Rule>> RULES = rules();

    /** Сценарии, после ответа которых направляющий вопрос не задаётся никогда (явное «none»). */
    private static final Set<String> SILENT_SCENARIOS = Set.of(
            "FIRST_TOUCH",      // согласие и контакт: вопрос там свой
            "MAIN_MENU",        // гость дома, READY_FOR_DIALOG
            "RECOVERY",         // восстановление после непонятого ввода
            "EVENT_BOOKING",    // детали мероприятия — одним свободным сообщением; после отправки — см. открытые вопросы
            "MANAGER_HELP",     // гость позвал человека: не подталкиваем
            "FEEDBACK",         // отзыв: без наводящих вопросов
            "PREFERENCE_MAP",   // предпочтение сохраняется из явных слов гостя
            "CONCIERGE",        // запрос уходит команде, дальше отвечает человек
            "SAFE_PLAY",        // сабраж и вино: ответ уже содержит свой следующий шаг
            "MERCH",            // запрос команде без подтверждения ботом
            "MENU_ASSETS",      // уточнение «какое меню» — свой вопрос, выдача — без вопроса
            "QUIET_GUIDE",      // видео-тур и концепция — без вопроса
            "IMPACT_METER",     // пожертвование: деньги, без подталкивания
            "SMART_TIP",        // чаевые: деньги, без подталкивания
            "HIDDEN_HEART",     // игра: свой сценарий реплик
            "ART_AUCTION"       // ставка: деньги, без подталкивания
    );

    /** Владелец состояния для ответов, в которых сценарий не отметил себя в {@code metadata.scenario}. */
    private static final Map<String, String> OWNER_BY_STATE_PREFIX = ownersByStatePrefix();

    private static Map<String, List<Rule>> rules() {
        Map<String, List<Rule>> rules = new LinkedHashMap<>();
        rules.put("TABLE_BOOKING", List.of(
                Rule.of(BotState.TABLE_BOOKING_COLLECT_DATE, ASK_DATE),
                Rule.of(BotState.TABLE_BOOKING_COLLECT_TIME, ASK_TIME),
                Rule.of(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, ASK_PARTY_SIZE),
                Rule.of(BotState.TABLE_BOOKING_WAIT_HOSTESS_CONFIRMATION, OFFER_MENU_WHILE_TEAM_CONFIRMS)
        ));
        rules.put("BUSINESS_LUNCH", List.of(
                Rule.of(BotState.BUSINESS_LUNCH_CHOOSE_SET, ASK_CHOICE),
                Rule.of(BotState.BUSINESS_LUNCH_CHOOSE_DISH, ASK_CHOICE),
                Rule.of(BotState.BUSINESS_LUNCH_COLLECT_PARTY_SIZE, ASK_PARTY_SIZE),
                Rule.of(BotState.BUSINESS_LUNCH_COLLECT_DATE, ASK_DATE),
                Rule.of(BotState.BUSINESS_LUNCH_COLLECT_TIME, ASK_TIME),
                // Ланч передаёт шаги стола сценарию брони, но отмечает ответ своим id.
                Rule.of(BotState.TABLE_BOOKING_COLLECT_DATE, ASK_DATE),
                Rule.of(BotState.TABLE_BOOKING_COLLECT_TIME, ASK_TIME),
                Rule.of(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, ASK_PARTY_SIZE)
        ));
        rules.put("CHANGE_CANCEL", List.of(
                new Rule(BotState.TABLE_BOOKING_CHANGE_REQUESTED, CHANGE_CANCEL_VALUE_STEPS, ASK_WHAT_TO_CHANGE)
        ));
        return Map.copyOf(rules);
    }

    private static Map<String, String> ownersByStatePrefix() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put("TABLE_BOOKING_", "TABLE_BOOKING");
        owners.put("BUSINESS_LUNCH_", "BUSINESS_LUNCH");
        owners.put("EVENT_BOOKING_", "EVENT_BOOKING");
        owners.put("MENU_ASSETS_", "MENU_ASSETS");
        owners.put("QUIET_GUIDE_", "QUIET_GUIDE");
        owners.put("TIP_", "SMART_TIP");
        owners.put("DONATION_", "IMPACT_METER");
        owners.put("AUCTION_", "ART_AUCTION");
        owners.put("MANAGER_HELP_", "MANAGER_HELP");
        owners.put("FEEDBACK_", "FEEDBACK");
        owners.put("PREFERENCE_", "PREFERENCE_MAP");
        owners.put("CONCIERGE_", "CONCIERGE");
        owners.put("MERCH_", "MERCH");
        owners.put("SAFE_PLAY_", "SAFE_PLAY");
        return Map.copyOf(owners);
    }

    /**
     * Вопрос для ответа сценария {@code scenarioId}, который переводит гостя в {@code state} с действиями {@code actions}.
     * Пусто — вопрос не нужен (сценарий завершён, шаг не требует уточнения или сценарий «молчащий»).
     */
    public Optional<String> questionFor(String scenarioId, BotState state, Collection<String> actions) {
        if (scenarioId == null || state == null) {
            return Optional.empty();
        }
        List<Rule> rules = RULES.get(scenarioId);
        if (rules == null) {
            return Optional.empty();
        }
        BotState canonical = state.canonical();
        return rules.stream()
                .filter(rule -> rule.matches(canonical, actions))
                .map(Rule::question)
                .findFirst();
    }

    /** Сценарий, которому принадлежит состояние, когда ответ не отметил себя в {@code metadata.scenario}. */
    public Optional<String> ownerOf(BotState state) {
        if (state == null) {
            return Optional.empty();
        }
        String name = state.canonical().name();
        return OWNER_BY_STATE_PREFIX.entrySet().stream()
                .filter(entry -> name.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** Есть ли у сценария явная запись в таблице: правила или явное «none». */
    public boolean covers(String scenarioId) {
        return scenarioId != null && (RULES.containsKey(scenarioId) || SILENT_SCENARIOS.contains(scenarioId));
    }

    /** Сценарии с правилами и их правила: для документации и тестов. */
    public Map<String, List<Rule>> rulesByScenario() {
        return RULES;
    }

    /** Сценарии с явным «none». */
    public Set<String> silentScenarios() {
        return SILENT_SCENARIOS;
    }
}
