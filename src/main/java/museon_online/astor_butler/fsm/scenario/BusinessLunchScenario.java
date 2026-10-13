package museon_online.astor_butler.fsm.scenario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.identity.IdentityService;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.scenario.BusinessLunchDraftStorage.Draft;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business lunch: a guest, usually sent by the Concierge, picks a set and its dishes, says how many come and when,
 * reads the summary and sends it. The result is a held table with a card for the hostess and an order for the venue's
 * own system. Every step is an FSM state; nothing is placed without the guest's "да", and nothing is called confirmed here.
 * <p>With {@code astor.business-lunch.wishes.enabled} (#165) one more question follows the dishes — «Пожелания к блюдам или
 * напиткам?» — and the guest's words go to the hostess card and the Saby comment as a wish to agree with the kitchen; and
 * «состав …» / «что в …» / «аллергены …» about a lunch dish is answered from the menu file, at any step or from the main menu.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessLunchScenario implements FsmScenario {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DAY_BUTTON = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DAY_TEXT = DateTimeFormatter.ofPattern("EEEE, dd.MM", RU);
    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SEND_BUTTON = "✅ Отправить заявку";
    private static final String CHANGE_BUTTON = "✏️ Изменить";
    private static final String CANCEL_BUTTON = "↩️ Отменить";
    private static final String NEXT_BUTTON = "➡️ Дальше";
    private static final String REMOVE_BUTTON = "➖ Убрать последнее";
    private static final List<String> REMOVE_WORDS = List.of("убрать", "убери", "удалить", "удали", "минус");
    private static final Set<String> NEXT = Set.of("дальше", "далее", "пропустить", "готово", "ничего", "нет", "не надо", "не нужно");
    private static final Set<String> YES = Set.of("да", "ок", "окей", "верно", "все верно", "подтверждаю", "согласен", "согласна");
    private static final Set<String> EXIT = Set.of("отмена", "стоп", "главное меню", "выйти", "не надо", "не нужно", "передумал", "передумала", "нет");
    private static final String VENUE_WORD = "Состав и стоимость на день подтвердит команда.";
    private static final int WISH_LIMIT = BusinessLunchService.WISH_LIMIT;
    private static final int CORRECTION_WORDS = 4;
    // The question about the dishes and drinks (#165). «Нет» here is an answer, not the way out of the lunch.
    private static final String NO_WISH_BUTTON = "Нет";
    private static final Set<String> NO_WISH = Set.of("нет", "нет спасибо", "не надо", "не нужно", "ничего", "пропустить", "дальше", "далее",
            "готово", "без пожеланий", "нет пожеланий", "все ок", "все хорошо", "все устраивает");
    private static final String WISH_QUESTION = "Пожелания к блюдам или напиткам? Например: без лука, соус отдельно, лимонад кислее. Или «нет».";
    private static final String WISH_NOTED = "Пожелание записал, передам кухне вместе с заказом — подтвердят при подаче.";
    private static final String WISH_RELAYED = "Передал пожелание кухне, подтвердят при подаче.";
    // «состав борща», «что в нисуазе», «из чего котлеты», «аллергены в цезаре», «есть ли орехи в медовике».
    private static final Pattern COMPOSITION_QUESTION = Pattern.compile(
            "^(состав|что в |что во |что входит|что внутри|что там|из чего|какие аллергены|аллергены|есть ли )");
    private static final Set<String> QUESTION_WORDS = Set.of("что", "входит", "состав", "составе", "чего", "внутри", "там", "есть", "аллергены",
            "аллерген", "аллергенов", "какие", "какой", "блюде", "блюдо", "блюда", "напитке", "напиток", "этом", "это", "скажите", "подскажите",
            "пожалуйста", "можно", "узнать", "расскажите", "ингредиенты", "входят");
    private static final int COMPOSITION_ANSWERS = 3;

    private final FSMStorage fsmStorage;
    private final BusinessLunchDraftStorage draftStorage;
    private final BusinessLunchCatalog catalog;
    private final BusinessLunchService lunchService;
    private final GuestInputUnderstandingService understandingService;
    private final TableBookingDraftMerger draftMerger;
    private final BookingTimeProvider timeProvider;
    private final IdentityService identityService;

    /** The guest journey ledger (spec 2026-10-13): optional, silent while the feature is off. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.JourneyEvents journeyEvents;

    /** «Как в прошлый раз» (spec 2026-10-13, ASTOR_REPEAT_LUNCH_ENABLED): optional, off by default. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.LastLunchService lastLunchService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private museon_online.astor_butler.domain.journey.RepeatLunchProperties repeatLunchProperties;

    private static final java.util.regex.Pattern REPEAT = java.util.regex.Pattern.compile(
            "(как|так же,? как|то же,? что)\\s+в\\s+прошлый\\s+раз|как обычно|повтори(те|ть)?\\s+(заказ|ланч|прошлый)");
    private static final java.util.regex.Pattern REPEAT_WISH = java.util.regex.Pattern.compile(",?\\s+но\\s+(.+)$");
    private static final java.util.regex.Pattern AGAIN_LINK = java.util.regex.Pattern.compile("^/start(?:@\\w+)?\\s+lunch_[^\\s]*_again(?:_|$)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public void setRepeatLunch(museon_online.astor_butler.domain.journey.LastLunchService lastLunchService, museon_online.astor_butler.domain.journey.RepeatLunchProperties properties) {
        this.lastLunchService = lastLunchService;
        this.repeatLunchProperties = properties;
    }

    private boolean repeatOn() {
        return lastLunchService != null && repeatLunchProperties != null && repeatLunchProperties.isEnabled();
    }

    private boolean asksToRepeat(String text) {
        return repeatOn() && text != null && REPEAT.matcher(text.toLowerCase(java.util.Locale.ROOT)).find();
    }

    /** Fills the dishes of the guest's last lunch that are still on the menu, then asks only what is missing. */
    private OutgoingMessage repeat(IncomingMessage incoming, Draft base, String text) {
        Optional<BusinessLunchOffer> offer = catalog.find(base.venueCode());
        if (offer.isEmpty()) {
            return unavailable(incoming);
        }
        Optional<museon_online.astor_butler.domain.journey.LastLunch> last = offer.get().aLaCarte() ? lastLunchService.find(incoming.chatId(), offer.get()) : Optional.empty();
        if (last.isEmpty()) {
            return begin(incoming, base, "В прошлый раз ничего из нынешнего меню не было — соберём заново.");
        }
        Draft draft = base;
        for (String code : last.get().dishCodes()) {
            draft = draft.withPortion(code);
        }
        draft = draft.withCourseStep(offer.get().courses().size());
        java.util.regex.Matcher wish = REPEAT_WISH.matcher(text == null ? "" : text.trim());
        if (wish.find() && !wish.group(1).isBlank()) {
            draft = draft.withWishes(wish.group(1).trim());
        }
        journey(incoming, museon_online.astor_butler.domain.journey.VisitEventKind.REPEAT_ACCEPTED, null);
        return begin(incoming, draft, "Собрал как в прошлый раз: " + String.join(", ", last.get().titles()) + ".");
    }

    private void journey(IncomingMessage incoming, museon_online.astor_butler.domain.journey.VisitEventKind kind, String note) {
        if (journeyEvents != null && incoming != null) {
            journeyEvents.onChat(incoming.chatId(), kind, museon_online.astor_butler.domain.journey.JourneyEvents.GUEST, note);
        }
    }

    @Value("${astor.business-lunch.enabled:true}")
    private boolean enabled = true;

    @Value("${astor.business-lunch.default-venue-code:AERIS}")
    private String defaultVenueCode = "AERIS";

    /** #165: the question about wishes after the dishes and the answers about what is in a dish. Off by default. */
    @Value("${astor.business-lunch.wishes.enabled:false}")
    private boolean wishesEnabled = false;

    @Value("${telegram.admin.chat-id:}")
    private String adminChatId;

    @Override
    public String id() {
        return "BUSINESS_LUNCH";
    }

    @Override
    public int priority() {
        return 29;
    }

    @Override
    public boolean supports(IncomingMessage incoming, BotState currentState, String text) {
        if (!enabled || incoming == null || incoming.chatId() == null) {
            return false;
        }
        if (BusinessLunchHandoff.from(incoming, text).isPresent()) {
            return true;
        }
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        BotState state = canonical(currentState);
        if (asksToRepeat(text) && (owns(state) || isIdle(state))) {
            return true;
        }
        return owns(state) || isIdle(state) && (asksForLunch(normalized) || asksAboutALunchDish(normalized));
    }

    @Override
    public OutgoingMessage handle(IncomingMessage incoming, BotState currentState, String text) {
        Optional<BusinessLunchHandoff> handoff = BusinessLunchHandoff.from(incoming, text);
        if (handoff.isPresent()) {
            if (repeatOn() && AGAIN_LINK.matcher(text == null ? "" : text.trim()).find()) {
                return repeat(incoming, draftFrom(handoff.get()), "");
            }
            return begin(incoming, draftFrom(handoff.get()), "");
        }
        BotState state = canonical(currentState);
        if (asksToRepeat(text)) {
            Draft base = owns(state) ? draftStorage.find(incoming.chatId()).orElse(Draft.start(defaultVenueCode, "DIRECT", null))
                    : Draft.start(defaultVenueCode, "DIRECT", null);
            return repeat(incoming, base.withSet(base.setCode(), List.of()), text);
        }
        Optional<Draft> stored = draftStorage.find(incoming.chatId());
        if (!owns(state) || stored.isEmpty()) {
            Optional<OutgoingMessage> aboutADish = answerAboutADish(incoming, text);
            return aboutADish.isPresent() ? aboutADish.get() : begin(incoming, heardAtOnce(text), "");
        }
        String normalized = normalize(text);
        Draft draft = stored.get();
        Optional<BusinessLunchOffer> offer = catalog.find(draft.venueCode());
        if (offer.isEmpty()) {
            return unavailable(incoming);
        }
        // "Нет" to "what from the salads?" skips the salads, "нет" to "any wishes?" means none; anywhere else it leaves the lunch.
        boolean skipsCourse = state == BotState.BUSINESS_LUNCH_CHOOSE_DISH && offer.get().aLaCarte() && isNext(normalized);
        boolean noWish = state == BotState.BUSINESS_LUNCH_COLLECT_WISHES && isNoWish(normalized);
        if (isExit(normalized) && !skipsCourse && !noWish) {
            return leave(incoming);
        }
        // «Что в борще?» at any step: the answer, then the step's question again, with nothing changed in the order.
        if (wishesEnabled && asksComposition(normalized)) {
            List<BusinessLunchOffer.Dish> asked = dishesNamed(offer.get(), normalized);
            if (!asked.isEmpty()) {
                journey(incoming, museon_online.astor_butler.domain.journey.VisitEventKind.DISH_QUESTION, normalized);
                return advance(incoming, offer.get(), draft, composition(asked, normalized));
            }
            if (state != BotState.BUSINESS_LUNCH_COLLECT_WISHES) {
                return advance(incoming, offer.get(), draft, "Не понял, про какое блюдо спросить. Напишите, например: «состав борща».");
            }
        }
        return switch (state) {
            case BUSINESS_LUNCH_CHOOSE_SET -> onSet(incoming, offer.get(), draft, normalized);
            case BUSINESS_LUNCH_CHOOSE_DISH -> onDish(incoming, offer.get(), draft, normalized);
            case BUSINESS_LUNCH_COLLECT_WISHES -> onWishes(incoming, offer.get(), draft, text);
            case BUSINESS_LUNCH_COLLECT_PARTY_SIZE -> onPartySize(incoming, offer.get(), draft, text);
            case BUSINESS_LUNCH_COLLECT_DATE -> onDate(incoming, offer.get(), draft, text);
            case BUSINESS_LUNCH_COLLECT_TIME -> onTime(incoming, offer.get(), draft, text);
            default -> onConfirmation(incoming, offer.get(), draft, text);
        };
    }

    @Override
    public boolean owns(BotState state) {
        return switch (canonical(state)) {
            case BUSINESS_LUNCH_CHOOSE_SET,
                 BUSINESS_LUNCH_CHOOSE_DISH,
                 BUSINESS_LUNCH_COLLECT_WISHES,
                 BUSINESS_LUNCH_COLLECT_PARTY_SIZE,
                 BUSINESS_LUNCH_COLLECT_DATE,
                 BUSINESS_LUNCH_COLLECT_TIME,
                 BUSINESS_LUNCH_CONFIRMATION -> true;
            default -> false;
        };
    }

    @Override
    public boolean sideEffecting() {
        return true;
    }

    /**
     * Seen by the router before consent is checked. A guest the Concierge sent may first have to share a contact,
     * so what the Concierge passed is kept until then. A bare /start drops an unfinished lunch like any other draft.
     */
    public void rememberHandoff(IncomingMessage incoming, String text) {
        if (!enabled || incoming == null || incoming.chatId() == null) {
            return;
        }
        Optional<BusinessLunchHandoff> handoff = BusinessLunchHandoff.from(incoming, text);
        if (handoff.isPresent()) {
            draftStorage.save(incoming.chatId(), draftFrom(handoff.get()));
        } else if (isBareStart(text)) {
            draftStorage.clear(incoming.chatId());
        }
    }

    /** Once consent or a restart has brought the guest to the main menu, goes on with the lunch the guest came for. */
    public OutgoingMessage continueAfterFirstTouch(IncomingMessage incoming, OutgoingMessage firstTouch) {
        if (!enabled || incoming == null || firstTouch == null || !BotState.READY_FOR_DIALOG.name().equals(firstTouch.nextState())) {
            return firstTouch;
        }
        Optional<Draft> waiting = draftStorage.find(incoming.chatId());
        if (waiting.isEmpty()) {
            return firstTouch;
        }
        boolean contactJustShared = firstTouch.actions() != null && firstTouch.actions().contains("CONTACT_CAPTURED");
        return begin(incoming, waiting.get(), contactJustShared ? "Спасибо, контакт получил." : "");
    }

    private OutgoingMessage begin(IncomingMessage incoming, Draft draft, String lead) {
        Optional<BusinessLunchOffer> offer = catalog.find(draft.venueCode());
        if (offer.isEmpty()) {
            return unavailable(incoming);
        }
        Draft clean = withOnlyChoices(offer.get(), sanitized(offer.get(), draft));
        // The summary carries the same note about the venue's word, so the opening line leaves it out when the summary follows at once.
        boolean summaryNext = dishesChosen(offer.get(), clean) && !wishesPending(clean) && clean.partySize() != null && clean.date() != null && clean.time() != null;
        String opening = intro(offer.get()) + (needsVenueWord(offer.get()) && !summaryNext ? " " + VENUE_WORD : "");
        if (offer.get().aLaCarte() && clean.dishCodes().isEmpty()) {
            opening = opening + "\n\n" + menu(offer.get());
        }
        return advance(incoming, offer.get(), clean, join(lead, opening));
    }

    /** Asks for the first thing still missing, or shows the summary when nothing is. */
    private OutgoingMessage advance(IncomingMessage incoming, BusinessLunchOffer offer, Draft known, String lead) {
        Draft draft = withOnlyChoices(offer, known);
        if (draft.date() != null && timeChoices(offer, draft.date()).isEmpty()) {
            draft = draft.withDate(null).withTime(null);
            lead = join(lead, "На этот день время бизнес-ланча уже прошло.");
        }
        if (offer.aLaCarte() && courseStep(draft) >= offer.courses().size() && draft.dishCodes().isEmpty()) {
            draft = draft.withCourseStep(0);
            lead = join(lead, "В заказе пока ничего нет. Выберите, пожалуйста, хотя бы одно блюдо.");
        }
        draftStorage.save(incoming.chatId(), draft);

        if (offer.aLaCarte()) {
            int step = courseStep(draft);
            if (step < offer.courses().size()) {
                BusinessLunchOffer.Course course = offer.courses().get(step);
                String question = course.title() + ": что добавить? Каждое нажатие добавляет одну порцию.";
                String order = draft.dishCodes().isEmpty() ? "" : "\n" + orderLine(offer, draft);
                List<List<String>> choices = new ArrayList<>(rows(course.dishes().stream().map(this::dishButton).toList(), 1));
                choices.add(draft.dishCodes().isEmpty() ? List.of(NEXT_BUTTON) : List.of(REMOVE_BUTTON, NEXT_BUTTON));
                return ask(incoming, BotState.BUSINESS_LUNCH_CHOOSE_DISH, join(lead, question + order), choices, "ASK_LUNCH_DISH");
            }
        } else {
            if (draft.setCode() == null) {
                return ask(incoming, BotState.BUSINESS_LUNCH_CHOOSE_SET, join(lead, "Какой вариант выбираете?"), setRows(offer), "ASK_LUNCH_SET");
            }
            BusinessLunchOffer.LunchSet set = offer.set(draft.setCode()).orElseThrow();
            int slot = openSlot(draft);
            if (slot >= 0) {
                String question = offer.slotTitle(set, slot) + ": что выбираете?";
                return ask(incoming, BotState.BUSINESS_LUNCH_CHOOSE_DISH, join(lead, question), rows(titles(offer.dishesFor(set, slot)), 1), "ASK_LUNCH_DISH");
            }
        }
        if (wishesPending(draft)) {
            return ask(incoming, BotState.BUSINESS_LUNCH_COLLECT_WISHES, join(lead, WISH_QUESTION), List.of(List.of(NO_WISH_BUTTON)), "ASK_LUNCH_WISHES");
        }
        if (draft.partySize() == null) {
            return ask(incoming, BotState.BUSINESS_LUNCH_COLLECT_PARTY_SIZE, join(lead, "На сколько гостей накрыть?"), rows(List.of("1", "2", "3", "4"), 4), "ASK_PARTY_SIZE");
        }
        if (draft.date() == null) {
            return ask(incoming, BotState.BUSINESS_LUNCH_COLLECT_DATE, join(lead, "В какой день вас ждать?"), rows(dayChoices(offer), 3), "ASK_DATE");
        }
        if (draft.time() == null) {
            return ask(incoming, BotState.BUSINESS_LUNCH_COLLECT_TIME, join(lead, "Во сколько вас ждать?"), rows(timeChoices(offer, draft.date()), 4), "ASK_TIME");
        }
        String question = summary(offer, draft) + "\n\nОтправляю заявку команде? Если есть пожелания, напишите их одной строкой, я передам."
                + (offer.aLaCarte() ? " Лишнее можно убрать: напишите «убрать» и название блюда." : "");
        return ask(incoming, BotState.BUSINESS_LUNCH_CONFIRMATION, join(lead, question), List.of(List.of(SEND_BUTTON), List.of(CHANGE_BUTTON)), "ASK_LUNCH_CONFIRMATION");
    }

    private OutgoingMessage onSet(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String normalized) {
        int picked = pick(offer.sets().stream().map(BusinessLunchOffer.LunchSet::title).toList(), normalized);
        if (picked < 0) {
            return advance(incoming, offer, draft, "Не нашел такой вариант. Выберите кнопкой ниже или напишите его номер.");
        }
        BusinessLunchOffer.LunchSet set = offer.sets().get(picked);
        return advance(incoming, offer, draft.withSet(set.code(), emptySlots(set)), "");
    }

    /** À la carte: one tap adds one portion from the current course, "дальше" moves to the next course. */
    private OutgoingMessage onCourse(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String normalized) {
        int step = courseStep(draft);
        if (isRemoval(normalized)) {
            return removePortion(incoming, offer, draft, normalized);
        }
        if (isNext(normalized) || step >= offer.courses().size()) {
            return advance(incoming, offer, draft.withCourseStep(Math.min(step + 1, offer.courses().size())), "");
        }
        BusinessLunchOffer.Course course = offer.courses().get(step);
        int picked = pick(titles(course.dishes()), normalized);
        if (picked < 0) {
            return advance(incoming, offer, draft, "Не нашел такое блюдо в этом разделе. Выберите кнопкой ниже или нажмите «Дальше».");
        }
        if (draft.dishCodes().size() >= BusinessLunchService.MAX_PORTIONS) {
            return advance(incoming, offer, draft, "В одном заказе не больше %s порций. Для большой компании напишите «менеджер».".formatted(BusinessLunchService.MAX_PORTIONS));
        }
        BusinessLunchOffer.Dish dish = course.dishes().get(picked);
        return advance(incoming, offer, draft.withPortion(dish.code()).withCourseStep(step), "Добавил: " + dish.title() + ".");
    }

    /** Takes one portion out of the order: the last one added, or the last portion of the dish the guest names. */
    private OutgoingMessage removePortion(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String normalized) {
        List<String> order = draft.dishCodes();
        if (order.isEmpty()) {
            return advance(incoming, offer, draft, "В заказе пока ничего нет.");
        }
        String name = words(normalized);
        for (String word : REMOVE_WORDS) {
            name = name.startsWith(word) ? name.substring(word.length()).trim() : name;
        }
        int index = order.size() - 1;
        if (!name.isBlank() && !name.startsWith("последн")) {
            List<String> taken = order.stream().distinct().toList();
            int picked = pick(taken.stream().map(code -> offer.dish(code).map(BusinessLunchOffer.Dish::title).orElse(code)).toList(), name);
            if (picked < 0) {
                return advance(incoming, offer, draft, "Такого блюда в заказе нет.");
            }
            index = order.lastIndexOf(taken.get(picked));
        }
        String removed = offer.dish(order.get(index)).map(BusinessLunchOffer.Dish::title).orElse(order.get(index));
        Draft without = draft.withoutPortion(index);
        // With nothing left there is no summary to return to: the guest starts from the first course again.
        return advance(incoming, offer, without.dishCodes().isEmpty() ? without.withCourseStep(0) : without, "Убрал: " + removed + ".");
    }

    private OutgoingMessage onDish(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String normalized) {
        if (offer.aLaCarte()) {
            return onCourse(incoming, offer, draft, normalized);
        }
        BusinessLunchOffer.LunchSet set = offer.set(draft.setCode()).orElseThrow();
        int slot = openSlot(draft);
        List<BusinessLunchOffer.Dish> dishes = offer.dishesFor(set, slot);
        int picked = pick(titles(dishes), normalized);
        if (picked < 0) {
            return advance(incoming, offer, draft, "Не нашел такое блюдо. Выберите кнопкой ниже или напишите его номер.");
        }
        return advance(incoming, offer, draft.withDish(slot, dishes.get(picked).code()), "");
    }

    /**
     * The one wish for the whole order, in the guest's words: «без лука», «соус отдельно», «лимонад кислее». Nothing is
     * classified and nothing is promised; the kitchen agrees to it at the table. «Нет» records that there is none.
     */
    private OutgoingMessage onWishes(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String text) {
        String normalized = normalize(text);
        if (isNoWish(normalized)) {
            return advance(incoming, offer, draft.withWishes(""), "");
        }
        if (offer.aLaCarte() && isRemoval(normalized)) {
            return removePortion(incoming, offer, draft, normalized);
        }
        String wish = oneLine(text);
        if (wish.isBlank()) {
            return advance(incoming, offer, draft, "");
        }
        if (wish.length() <= WISH_LIMIT) {
            return advance(incoming, offer, draft.withWishes(wish), WISH_NOTED);
        }
        return advance(incoming, offer, draft.withWishes(wish.substring(0, WISH_LIMIT).stripTrailing()),
                "Пожелание длинное, записал первые %s символов. ".formatted(WISH_LIMIT) + WISH_NOTED);
    }

    private OutgoingMessage onPartySize(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String text) {
        Integer guests = heard(text, BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE).partySize();
        if (guests == null) {
            String lead = normalize(text).matches("\\d{2,}")
                    ? "Бизнес-ланч оформляю на компанию до %s гостей. Для большей компании напишите «менеджер», команда подберет формат.".formatted(BusinessLunchService.MAX_GUESTS)
                    : "Напишите число гостей: например, «2» или «на троих».";
            return advance(incoming, offer, draft, lead);
        }
        return advance(incoming, offer, draft.withPartySize(guests), "");
    }

    private OutgoingMessage onDate(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String text) {
        LocalDate day = heard(text, BotState.TABLE_BOOKING_COLLECT_DATE).requestedDate();
        if (day == null) {
            return advance(incoming, offer, draft, "Не смог понять день. Выберите кнопкой или напишите: «завтра», «в пятницу», «08.10».");
        }
        Optional<BusinessLunchService.WindowIssue> issue = dayIssue(offer, day);
        if (issue.isPresent()) {
            return advance(incoming, offer, draft, explain(offer, issue.get()));
        }
        return advance(incoming, offer, draft.withDate(day), "");
    }

    private OutgoingMessage onTime(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String text) {
        LocalTime time = heard(text, BotState.TABLE_BOOKING_COLLECT_TIME).requestedTime();
        if (time == null) {
            return advance(incoming, offer, draft, "Не хочу гадать со временем. Выберите кнопкой или напишите, например, 13:00.");
        }
        Optional<BusinessLunchService.WindowIssue> issue = lunchService.windowIssue(offer, draft.date(), time);
        if (issue.isPresent()) {
            return advance(incoming, offer, draft, explain(offer, issue.get()));
        }
        return advance(incoming, offer, draft.withTime(time), "");
    }

    private OutgoingMessage onConfirmation(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft, String text) {
        String normalized = normalize(text);
        if (isYes(normalized)) {
            return place(incoming, offer, draft);
        }
        if (normalized.contains("изменить") || normalized.contains("поменять") || normalized.contains("заново")) {
            Draft again = Draft.start(draft.venueCode(), draft.source(), draft.conciergeRequestId());
            return advance(incoming, offer, again, "Хорошо, соберем заново.");
        }
        if (offer.aLaCarte() && isRemoval(normalized)) {
            return removePortion(incoming, offer, draft, normalized);
        }
        String wish = oneLine(text);
        // A short reply such as "лучше в 14:30" corrects the summary. A longer one is a wish for the team and is not searched
        // for numbers: "один гость не ест лук" must not turn the party into one guest.
        if (normalized.split(" ").length > CORRECTION_WORDS) {
            return advance(incoming, offer, withWish(draft, wish), "Пожелание записал.");
        }
        TableBookingDraftStorage.Draft said = heard(text, BotState.TABLE_BOOKING_INTENT);
        Draft corrected = draft;
        String problem = "";
        if (said.partySize() != null) {
            corrected = corrected.withPartySize(said.partySize());
        }
        if (said.requestedDate() != null) {
            Optional<BusinessLunchService.WindowIssue> issue = dayIssue(offer, said.requestedDate());
            problem = issue.map(found -> explain(offer, found)).orElse(problem);
            corrected = issue.isPresent() ? corrected : corrected.withDate(said.requestedDate());
        }
        if (said.requestedTime() != null) {
            Optional<BusinessLunchService.WindowIssue> issue = lunchService.windowIssue(offer, corrected.date(), said.requestedTime());
            problem = issue.map(found -> explain(offer, found)).orElse(problem);
            corrected = issue.isPresent() ? corrected : corrected.withTime(said.requestedTime());
        }
        if (!problem.isBlank()) {
            return advance(incoming, offer, corrected, problem);
        }
        if (!corrected.equals(draft)) {
            return advance(incoming, offer, corrected, "Поправил.");
        }
        return advance(incoming, offer, withWish(draft, wish), "Пожелание записал.");
    }

    /** A wish typed at the summary: with the wishes question on it replaces the answer to that question, otherwise it is the free comment. */
    private Draft withWish(Draft draft, String wish) {
        String kept = wish.length() > WISH_LIMIT ? wish.substring(0, WISH_LIMIT).stripTrailing() : wish;
        return wishesEnabled ? draft.withWishes(kept) : draft.withComment(kept);
    }

    private OutgoingMessage place(IncomingMessage incoming, BusinessLunchOffer offer, Draft draft) {
        BusinessLunchService.Placement placement;
        try {
            placement = lunchService.place(new BusinessLunchService.Request(
                    incoming.chatId(),
                    incoming.telegramUserId(),
                    offer,
                    draft.setCode(),
                    draft.dishCodes(),
                    draft.partySize(),
                    draft.date(),
                    draft.time(),
                    guestName(incoming),
                    guestPhone(incoming),
                    draft.comment(),
                    draft.source(),
                    draft.conciergeRequestId(),
                    hasWishes(draft) ? draft.wishes() : null
            ));
        } catch (ApiException e) {
            log.warn("Business lunch was not placed: chatId={}, reason={}", incoming.chatId(), e.getMessage());
            return advance(incoming, offer, draft.withTime(null), "На это время свободного стола для вашей компании нет. Выберите, пожалуйста, другое время.");
        } catch (IllegalArgumentException e) {
            log.warn("Business lunch draft is no longer valid: chatId={}, reason={}", incoming.chatId(), e.getMessage());
            return advance(incoming, offer, draft.withTime(null), "Это время уже не подходит. Выберите, пожалуйста, другое.");
        }

        draftStorage.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        Long orderId = placement.reservation().id();
        if (placement.alreadyPlaced()) {
            // The request in the way may be this very lunch or an ordinary table a quarter of an hour apart, so its own time is named.
            ZonedDateTime heldAt = placement.reservation().requestedStartAt().atZone(BookingTimeProvider.VENUE_ZONE);
            return OutgoingMessage.of(
                    incoming,
                    "У вас уже есть заявка #%s на %s в %s. По времени она пересекается с этим ланчем, поэтому вторую не создаю. Изменить или отменить ее можно кнопкой «Изменить / отменить»."
                            .formatted(orderId, heldAt.format(DAY_BUTTON), heldAt.format(TIME_TEXT)),
                    BotState.READY_FOR_DIALOG.name(),
                    false,
                    false,
                    false,
                    false,
                    AdminAlert.none(),
                    List.of("BUSINESS_LUNCH", "LUNCH_ORDER_ALREADY_PLACED", "RETURN_MAIN_MENU")
            ).withMetadata(Map.of("scenario", id(), "tableReservationId", orderId));
        }

        ExternalLunchOrderProvider.Result external = placement.external();
        return OutgoingMessage.of(
                incoming,
                """
                Готово. Заявку #%s на бизнес-ланч передал команде %s. Как только хостес ответит, я вернусь с финальным статусом.

                %s
                """.formatted(orderId, venueName(offer), summary(offer, draft) + (hasWishes(draft) ? "\n\n" + WISH_RELAYED : "")).strip(),
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                false,
                false,
                externalAlert(offer, draft, placement),
                List.of("BUSINESS_LUNCH", "LUNCH_ORDER_PLACED", external.accepted() ? "EXTERNAL_ORDER_SENT" : "EXTERNAL_ORDER_MANUAL", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of(
                "scenario", id(),
                "tableReservationId", orderId,
                "lunchSet", draft.setCode() == null ? "A_LA_CARTE" : draft.setCode(),
                "lunchSource", draft.source() == null ? "" : draft.source(),
                "externalProvider", external.providerId(),
                "externalStatus", external.status(),
                "handoffBoundary", "HOSTESS_CONFIRMATION_REQUIRED"
        ));
    }

    /** The venue's system was switched on and still did not take the order: someone has to enter it by hand. */
    private AdminAlert externalAlert(BusinessLunchOffer offer, Draft draft, BusinessLunchService.Placement placement) {
        ExternalLunchOrderProvider.Result external = placement.external();
        if (external.accepted() || !external.attempted() || adminChatId == null || adminChatId.isBlank()) {
            return AdminAlert.none();
        }
        return new AdminAlert(true, adminChatId, """
                <b>Astor Butler / business lunch</b>
                %s не принял заказ. Внесите его вручную.

                Заявка #%s · %s в %s · гостей: %s
                %s%s
                Статус: %s
                """.formatted(
                html(external.providerId()),
                placement.reservation().id(),
                draft.date().format(DAY_BUTTON),
                draft.time().format(TIME_TEXT),
                draft.partySize(),
                html(offer.aLaCarte() ? orderLine(offer, draft) : "Комбо: " + offer.set(draft.setCode()).map(BusinessLunchOffer.LunchSet::title).orElse(draft.setCode())),
                hasWishes(draft) ? "\n" + html(BusinessLunchService.wishLine(draft.wishes())) : "",
                html(external.status())
        ));
    }

    /**
     * «Состав борща» asked from the main menu: the answer from the lunch menu file, and the guest stays where they were —
     * no lunch is started for a question. Empty when the question is not about a lunch dish the bot knows.
     */
    private Optional<OutgoingMessage> answerAboutADish(IncomingMessage incoming, String text) {
        String normalized = normalize(text);
        if (!asksAboutALunchDish(normalized)) {
            return Optional.empty();
        }
        BusinessLunchOffer offer = catalog.find(defaultVenueCode).orElseThrow();
        journey(incoming, museon_online.astor_butler.domain.journey.VisitEventKind.DISH_QUESTION, normalized);
        String answer = composition(dishesNamed(offer, normalized), normalized)
                + "\n\nЕсли захотите заказать бизнес-ланч, напишите «ланч».";
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return Optional.of(OutgoingMessage.of(
                incoming,
                answer,
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("BUSINESS_LUNCH", "LUNCH_DISH_COMPOSITION", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of("scenario", id())));
    }

    private OutgoingMessage leave(IncomingMessage incoming) {
        draftStorage.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                "Хорошо, бизнес-ланч не оформляю. Главное меню оставил под рукой.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("BUSINESS_LUNCH", "LUNCH_CANCELLED_BY_GUEST", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of("scenario", id()));
    }

    private OutgoingMessage unavailable(IncomingMessage incoming) {
        draftStorage.clear(incoming.chatId());
        fsmStorage.setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(
                incoming,
                "Бизнес-ланч для этого заведения пока не подключен. Могу помочь с бронью стола или позвать команду.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("BUSINESS_LUNCH", "LUNCH_NOT_AVAILABLE", "RETURN_MAIN_MENU")
        ).withMetadata(Map.of("scenario", id()));
    }

    private OutgoingMessage ask(IncomingMessage incoming, BotState state, String text, List<List<String>> choices, String action) {
        fsmStorage.setState(incoming.chatId(), state);
        List<List<String>> rows = new ArrayList<>(choices);
        rows.add(List.of(CANCEL_BUTTON));
        return OutgoingMessage.of(
                incoming,
                text,
                state.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("BUSINESS_LUNCH", action)
        ).withMetadata(Map.of("scenario", id(), "replyKeyboardRows", List.copyOf(rows)));
    }

    private Draft draftFrom(BusinessLunchHandoff handoff) {
        String venue = handoff.venueCode() == null ? defaultVenueCode : handoff.venueCode().toUpperCase(Locale.ROOT);
        return new Draft(venue, handoff.setRef(), List.of(), null, handoff.partySize(), handoff.date(), handoff.time(), null, BusinessLunchHandoff.SOURCE, handoff.requestId());
    }

    /** A guest who writes "бизнес-ланч завтра в 13:00 на двоих" has already answered three questions. */
    private Draft heardAtOnce(String text) {
        TableBookingDraftStorage.Draft said = heard(text, BotState.TABLE_BOOKING_INTENT);
        return new Draft(defaultVenueCode, null, List.of(), null, said.partySize(), said.requestedDate(), said.requestedTime(), null, "DIRECT", null);
    }

    /** Hears a day, a time or a party size the same way the table booking does. */
    private TableBookingDraftStorage.Draft heard(String text, BotState asIfAsked) {
        String raw = text == null ? "" : text;
        UnderstoodInput understood = understandingService.understand(raw, asIfAsked);
        return draftMerger.read(Optional.empty(), raw, asIfAsked, normalize(understood.routeText()), understood);
    }

    /** Drops whatever the offer does not allow, so that the dialogue asks for it instead of carrying a wrong value on. */
    private Draft sanitized(BusinessLunchOffer offer, Draft draft) {
        Optional<BusinessLunchOffer.LunchSet> set = setByRef(offer, draft.setCode());
        List<String> dishes = set.map(found -> keptDishes(offer, found, draft.dishCodes())).orElse(List.of());
        Integer guests = draft.partySize() != null && draft.partySize() >= 1 && draft.partySize() <= BusinessLunchService.MAX_GUESTS ? draft.partySize() : null;
        LocalDate day = draft.date() != null && dayIssue(offer, draft.date()).isEmpty() ? draft.date() : null;
        LocalTime time = draft.time();
        if (time != null && (time.isBefore(offer.from()) || !time.isBefore(offer.to()) || day != null && lunchService.windowIssue(offer, day, time).isPresent())) {
            time = null;
        }
        if (offer.aLaCarte()) {
            List<String> order = draft.dishCodes().stream().filter(code -> code != null && offer.dish(code).isPresent()).toList();
            Integer step = draft.courseStep() == null ? null : Math.max(0, Math.min(draft.courseStep(), offer.courses().size()));
            return new Draft(draft.venueCode(), null, order, step, guests, day, time, draft.comment(), draft.source(), draft.conciergeRequestId(), draft.wishes());
        }
        return new Draft(draft.venueCode(), set.map(BusinessLunchOffer.LunchSet::code).orElse(null), dishes, null, guests, day, time, draft.comment(),
                draft.source(), draft.conciergeRequestId(), draft.wishes());
    }

    private Optional<BusinessLunchOffer.LunchSet> setByRef(BusinessLunchOffer offer, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        if (ref.matches("\\d{1,2}")) {
            int number = Integer.parseInt(ref);
            return number >= 1 && number <= offer.sets().size() ? Optional.of(offer.sets().get(number - 1)) : Optional.empty();
        }
        return offer.set(ref);
    }

    private List<String> keptDishes(BusinessLunchOffer offer, BusinessLunchOffer.LunchSet set, List<String> chosen) {
        List<String> kept = emptySlots(set);
        for (int slot = 0; slot < kept.size() && slot < chosen.size(); slot++) {
            String code = chosen.get(slot);
            if (code != null && offer.dishesFor(set, slot).stream().anyMatch(dish -> dish.code().equals(code))) {
                kept.set(slot, code);
            }
        }
        return kept;
    }

    /** A slot with a single dish needs no question. */
    private Draft withOnlyChoices(BusinessLunchOffer offer, Draft draft) {
        if (offer.aLaCarte()) {
            return draft;
        }
        Optional<BusinessLunchOffer.LunchSet> set = draft.setCode() == null ? Optional.empty() : offer.set(draft.setCode());
        if (set.isEmpty()) {
            return draft.setCode() == null ? draft : draft.withSet(null, List.of());
        }
        List<String> dishes = keptDishes(offer, set.get(), draft.dishCodes());
        for (int slot = 0; slot < dishes.size(); slot++) {
            List<BusinessLunchOffer.Dish> options = offer.dishesFor(set.get(), slot);
            if (dishes.get(slot) == null && options.size() == 1) {
                dishes.set(slot, options.getFirst().code());
            }
        }
        return draft.withSet(set.get().code(), dishes);
    }

    private List<String> emptySlots(BusinessLunchOffer.LunchSet set) {
        return new ArrayList<>(Collections.nCopies(set.slots().size(), null));
    }

    private int openSlot(Draft draft) {
        return draft.dishCodes().indexOf(null);
    }

    private Optional<BusinessLunchService.WindowIssue> dayIssue(BusinessLunchOffer offer, LocalDate day) {
        return BusinessLunchChoices.dayIssue(offer, lunchService, day);
    }

    private List<List<String>> setRows(BusinessLunchOffer offer) {
        return rows(offer.sets().stream().map(set -> set.priceRub() == null ? set.title() : set.title() + " · " + set.priceRub() + " ₽").toList(), 1);
    }

    private List<String> dayChoices(BusinessLunchOffer offer) {
        return BusinessLunchChoices.days(offer, lunchService, timeProvider.today());
    }

    private List<String> timeChoices(BusinessLunchOffer offer, LocalDate day) {
        return BusinessLunchChoices.times(offer, lunchService, day);
    }

    private List<List<String>> rows(List<String> labels, int columns) {
        return BusinessLunchChoices.rows(labels, columns);
    }

    private List<String> titles(List<BusinessLunchOffer.Dish> dishes) {
        return dishes.stream().map(BusinessLunchOffer.Dish::title).toList();
    }

    /** Which of the choices the guest means: its number, its name, or the button with the name on it. -1 when none. */
    private int pick(List<String> titles, String answer) {
        String said = normalize(answer);
        if (said.matches("\\d{1,2}")) {
            int number = Integer.parseInt(said);
            return number >= 1 && number <= titles.size() ? number - 1 : -1;
        }
        List<String> names = titles.stream().map(this::normalize).toList();
        if (names.contains(said)) {
            return names.indexOf(said);
        }
        // A button may carry the price after the name, and one name may be the start of another, so the longest wins.
        Optional<String> inside = names.stream().filter(said::contains).max(Comparator.comparingInt(String::length));
        if (inside.isPresent()) {
            return names.indexOf(inside.get());
        }
        List<String> partial = said.length() < 3 ? List.of() : names.stream().filter(name -> name.contains(said)).toList();
        return partial.size() == 1 ? names.indexOf(partial.getFirst()) : -1;
    }

    private String intro(BusinessLunchOffer offer) {
        String when = "Бизнес-ланч в %s: %s с %s до %s.".formatted(venueName(offer), daysText(offer), offer.from().format(TIME_TEXT), offer.to().format(TIME_TEXT));
        String included = offer.included() == null || offer.included().isBlank() ? "" : " В ланч входит: " + offer.included().trim() + ".";
        return when + included;
    }

    private String summary(BusinessLunchOffer offer, Draft draft) {
        List<String> lines = new ArrayList<>();
        lines.add("Бизнес-ланч в " + venueName(offer));
        lines.add(capitalize(draft.date().format(DAY_TEXT)) + " в " + draft.time().format(TIME_TEXT));
        lines.add("Гостей: " + draft.partySize());
        if (offer.aLaCarte()) {
            lines.add("Заказ:");
            portions(draft).forEach((code, count) -> {
                BusinessLunchOffer.Dish dish = offer.dish(code).orElseThrow();
                lines.add("• " + dish.title() + " × " + count + " · " + dish.priceRub() * count + " ₽");
            });
            lines.add("Итого: " + total(offer, draft) + " ₽");
        } else {
            BusinessLunchOffer.LunchSet set = offer.set(draft.setCode()).orElseThrow();
            lines.add("Комбо: «" + set.title() + "»" + (set.priceRub() == null ? "" : ", " + set.priceRub() + " ₽ за ланч"));
            lines.add("Блюда: " + String.join(", ", draft.dishCodes().stream()
                    .filter(Objects::nonNull)
                    .map(code -> offer.dish(code).map(BusinessLunchOffer.Dish::title).orElse(code))
                    .toList()));
        }
        if (hasWishes(draft)) {
            lines.add("Пожелание: " + draft.wishes());
        }
        if (draft.comment() != null && !draft.comment().isBlank()) {
            lines.add("Пожелание: " + draft.comment());
        }
        if (needsVenueWord(offer)) {
            lines.add(VENUE_WORD);
        }
        return String.join("\n", lines);
    }

    /** What the menu file says is in the dishes the guest named; one line per dish, nothing made up. */
    private String composition(List<BusinessLunchOffer.Dish> dishes, String normalized) {
        boolean aboutAllergens = normalized.contains("аллерген");
        List<String> lines = new ArrayList<>();
        for (BusinessLunchOffer.Dish dish : dishes) {
            if (!dish.describesComposition()) {
                lines.add("«" + dish.title() + "»: состав в меню бота пока не заполнен — уточнит официант при подаче.");
                continue;
            }
            List<String> parts = new ArrayList<>();
            if (dish.ingredients() != null && !dish.ingredients().isBlank()) {
                parts.add("состав — " + dish.ingredients().trim() + ".");
            }
            if (dish.allergens() != null && !dish.allergens().isBlank()) {
                parts.add("Аллергены: " + dish.allergens().trim() + ".");
            } else if (aboutAllergens) {
                parts.add("Аллергены в меню не указаны — уточнит официант.");
            }
            if (!dish.canBeWithout().isEmpty()) {
                parts.add("Можно попросить без: " + String.join(", ", dish.canBeWithout()) + " — согласуем с кухней.");
            }
            lines.add("«" + dish.title() + "»: " + String.join(" ", parts));
        }
        return String.join("\n", lines);
    }

    /**
     * The lunch dishes a question names: «что в борще» finds «Борщ со сметаной», «состав лимонада» finds every lemonade.
     * A word of the question and a word of the title are the same when they share a stem; the dishes with the most words
     * in common win, at most {@link #COMPOSITION_ANSWERS} of them.
     */
    private List<BusinessLunchOffer.Dish> dishesNamed(BusinessLunchOffer offer, String normalized) {
        List<String> asked = Arrays.stream(words(normalized).split(" "))
                .filter(word -> word.length() >= 3 && !QUESTION_WORDS.contains(word))
                .toList();
        if (asked.isEmpty()) {
            return List.of();
        }
        Map<BusinessLunchOffer.Dish, Integer> scores = new LinkedHashMap<>();
        for (BusinessLunchOffer.Course course : offer.courses()) {
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                int score = (int) Arrays.stream(words(normalize(dish.title())).split(" "))
                        .filter(word -> word.length() >= 3)
                        .filter(word -> asked.stream().anyMatch(said -> sameStem(said, word)))
                        .count();
                if (score > 0) {
                    scores.put(dish, score);
                }
            }
        }
        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() == best)
                .map(Map.Entry::getKey)
                .limit(COMPOSITION_ANSWERS)
                .toList();
    }

    /** «борще» and «борщ», «цезаре» and «цезарь»: the same word in another case. */
    private static boolean sameStem(String said, String titleWord) {
        if (said.equals(titleWord)) {
            return true;
        }
        int common = 0;
        int shorter = Math.min(said.length(), titleWord.length());
        while (common < shorter && said.charAt(common) == titleWord.charAt(common)) {
            common++;
        }
        return common >= 3 && common >= shorter - 2;
    }

    /** The whole lunch menu, the way the printed one reads: courses, dishes, portion and price. */
    private String menu(BusinessLunchOffer offer) {
        List<String> lines = new ArrayList<>();
        for (BusinessLunchOffer.Course course : offer.courses()) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(course.title());
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                String portion = dish.portion() == null || dish.portion().isBlank() ? "" : ", " + dish.portion().trim();
                lines.add("• " + dish.title() + portion + " · " + dish.priceRub() + " ₽");
            }
        }
        return String.join("\n", lines);
    }

    private String dishButton(BusinessLunchOffer.Dish dish) {
        return dish.title() + " · " + dish.priceRub() + " ₽";
    }

    /** "В заказе: Нисуаз × 1, Борщ со сметаной × 2. Итого 830 ₽." */
    private String orderLine(BusinessLunchOffer offer, Draft draft) {
        List<String> parts = new ArrayList<>();
        portions(draft).forEach((code, count) -> parts.add(offer.dish(code).map(BusinessLunchOffer.Dish::title).orElse(code) + " × " + count));
        return "В заказе: " + String.join(", ", parts) + ". Итого " + total(offer, draft) + " ₽.";
    }

    /** Portions per dish, in the order the guest first took each. */
    private Map<String, Integer> portions(Draft draft) {
        Map<String, Integer> portions = new LinkedHashMap<>();
        draft.dishCodes().stream().filter(Objects::nonNull).forEach(code -> portions.merge(code, 1, Integer::sum));
        return portions;
    }

    private int total(BusinessLunchOffer offer, Draft draft) {
        return draft.dishCodes().stream()
                .filter(Objects::nonNull)
                .mapToInt(code -> offer.dish(code).map(BusinessLunchOffer.Dish::priceRub).orElse(0))
                .sum();
    }

    private int courseStep(Draft draft) {
        return draft.courseStep() == null ? 0 : draft.courseStep();
    }

    /** Whether the order itself is complete: every slot of the set filled, or every course of the menu passed with something taken. */
    private boolean dishesChosen(BusinessLunchOffer offer, Draft draft) {
        if (offer.aLaCarte()) {
            return courseStep(draft) >= offer.courses().size() && !draft.dishCodes().isEmpty();
        }
        return draft.setCode() != null && openSlot(draft) < 0;
    }

    private boolean needsVenueWord(BusinessLunchOffer offer) {
        return !offer.confirmedByVenue() || offer.sets().stream().anyMatch(set -> set.priceRub() == null);
    }

    private String explain(BusinessLunchOffer offer, BusinessLunchService.WindowIssue issue) {
        return BusinessLunchChoices.explain(offer, issue);
    }

    private String daysText(BusinessLunchOffer offer) {
        return BusinessLunchChoices.daysText(offer);
    }

    private String venueName(BusinessLunchOffer offer) {
        return BusinessLunchChoices.venueName(offer);
    }

    private boolean asksForLunch(String normalized) {
        return normalized.contains("ланч") || normalized.contains("lunch");
    }

    /** A question about what is in a dish: only the form of the sentence, not yet whether the dish is known. */
    private boolean asksComposition(String normalized) {
        String words = words(normalized);
        if (words.contains("ланч") || words.contains("заказ") || words.contains("меню") || words.contains("комбо")) {
            return false;
        }
        return COMPOSITION_QUESTION.matcher(words).find();
    }

    /** From the main menu the question is taken only when it names a dish of the default venue's lunch. */
    private boolean asksAboutALunchDish(String normalized) {
        if (!wishesEnabled || !asksComposition(normalized)) {
            return false;
        }
        return catalog.find(defaultVenueCode).map(offer -> !dishesNamed(offer, normalized).isEmpty()).orElse(false);
    }

    private boolean wishesPending(Draft draft) {
        return wishesEnabled && draft.wishes() == null;
    }

    private boolean hasWishes(Draft draft) {
        return draft.wishes() != null && !draft.wishes().isBlank();
    }

    private boolean isNoWish(String normalized) {
        return NO_WISH.contains(words(normalized));
    }

    /** The guest's text as one line: the card and the Saby comment have no room for paragraphs. */
    private String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private boolean isYes(String normalized) {
        String words = normalized.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
        return YES.contains(words) || words.startsWith("да ") || words.startsWith("отправ") || words.startsWith("подтвержд");
    }

    private boolean isNext(String normalized) {
        String words = words(normalized);
        return NEXT.contains(words) || words.startsWith("дальше") || words.startsWith("без ");
    }

    private boolean isRemoval(String normalized) {
        String words = words(normalized);
        return REMOVE_WORDS.stream().anyMatch(word -> words.equals(word) || words.startsWith(word + " "));
    }

    /** The reply without emoji and punctuation, so a button and the same words typed by hand read alike. */
    private String words(String normalized) {
        return normalized.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean isExit(String normalized) {
        String words = normalized.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ").trim();
        return EXIT.contains(words) || words.startsWith("отменить") || words.startsWith("отмени ");
    }

    private boolean isBareStart(String text) {
        String command = text == null ? "" : text.trim();
        return "/start".equalsIgnoreCase(command) || "/restart".equalsIgnoreCase(command);
    }

    private boolean isIdle(BotState state) {
        return state == BotState.READY_FOR_DIALOG || state == BotState.AI_FALLBACK;
    }

    private BotState canonical(BotState state) {
        return state == null ? BotState.UNKNOWN : state.canonical();
    }

    /**
     * The phone for the hostess card and the venue's own system. A contact in this very message wins; otherwise it is
     * the one the guest shared earlier in the dialogue, because the last step of a booking is plain text and Saby takes
     * no booking without a phone. Null when the guest never shared one: the request then stays with the hostess.
     */
    private String guestPhone(IncomingMessage incoming) {
        if (incoming.contactPhone() != null && !incoming.contactPhone().isBlank()) {
            return incoming.contactPhone();
        }
        return identityService.primaryPhone(incoming.telegramUserId()).orElse(null);
    }

    private String guestName(IncomingMessage incoming) {
        String firstName = incoming.firstName() == null ? "" : incoming.firstName().trim();
        String lastName = incoming.lastName() == null ? "" : incoming.lastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        return incoming.username() == null || incoming.username().isBlank() ? null : "@" + incoming.username().trim();
    }

    private String join(String lead, String text) {
        return lead == null || lead.isBlank() ? text : lead + "\n\n" + text;
    }

    private String capitalize(String value) {
        return value == null || value.isBlank() ? "" : value.substring(0, 1).toUpperCase(RU) + value.substring(1);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(RU).replace('ё', 'е').replaceAll("\\s+", " ");
    }

    private String html(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
