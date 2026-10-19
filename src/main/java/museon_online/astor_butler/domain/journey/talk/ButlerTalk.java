package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.journey.VisitEventKind;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.integration.saby.SabyNutrition;
import museon_online.astor_butler.service.message.IncomingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The butler as a useful conversation partner (spec 2026-10-13 §3.3): table requests become staff cards, questions about
 * a dish's weight and nutrition are answered from Saby, questions about the venue from confirmed facts (an unknown one is
 * said to be unknown and goes to the staff chat).
 * Anything else stays with the scenarios. Off while {@code ASTOR_BUTLER_TALK_ENABLED} is off.
 */
public class ButlerTalk {

    /**
     * The answer when the venue has no confirmed fact. The question still goes to the staff chat so that the fact gets
     * written down, but no answer comes back to the guest from there, so nothing is promised.
     */
    public static final String ASK_TEAM = "Точных сведений об этом у меня пока нет.";

    private static final Pattern NUTRITION = Pattern.compile(
            "калори|кбжу|ккал|белк|жир[аыо]|углевод|сколько\\s+(грамм|весит)|\\bвес\\b|граммовк", Pattern.UNICODE_CHARACTER_CLASS);

    /** «Счёт» belongs to the bill flow; a request with «не нужно» is no request. */
    private static final Pattern NOT_A_TABLE_REQUEST = Pattern.compile(
            "сч[её]т(?!\\S)|сч[её]т[аеу]\\b|\\bне\\s+(нужн|надо)", Pattern.UNICODE_CHARACTER_CLASS);

    /** Weight and nutrition by the dish's Saby name. */
    public interface Nutrition {
        Optional<SabyNutrition> of(String sabyName);
    }

    /** A guest's question the bot cannot answer goes to the staff chat. */
    public interface TeamQuestions {
        void ask(long chatId, String question);
    }

    private final ButlerTalkProperties properties;
    private final VenueFactsCatalog facts;
    private final Nutrition nutrition;
    private final BusinessLunchCatalog menu;
    private final TableRequestRouter router;
    private final TeamQuestions team;
    private final JourneyEvents events;
    private final String venueCode;

    public ButlerTalk(ButlerTalkProperties properties, VenueFactsCatalog facts, Nutrition nutrition, BusinessLunchCatalog menu,
                      TableRequestRouter router, TeamQuestions team, JourneyEvents events, String venueCode) {
        this.properties = properties;
        this.facts = facts;
        this.nutrition = nutrition;
        this.menu = menu;
        this.router = router;
        this.team = team;
        this.events = events;
        this.venueCode = venueCode;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** The guest's visit whose window is now, by chat. */
    public interface VisitLookup {
        Optional<GuestVisit> active(long chatId);
    }

    private VisitLookup visitLookup = chatId -> Optional.empty();

    public void setVisitLookup(VisitLookup visitLookup) {
        this.visitLookup = visitLookup == null ? chatId -> Optional.empty() : visitLookup;
    }

    /** As {@link #answer(IncomingMessage, BotState, Optional)}, with the guest's visit looked up by chat. */
    public Optional<String> answer(IncomingMessage incoming, BotState state) {
        if (!properties.isEnabled() || incoming == null || incoming.chatId() == null || incoming.chatId() <= 0) {
            return Optional.empty();
        }
        Optional<GuestVisit> visit;
        try {
            visit = visitLookup.active(incoming.chatId());
        } catch (RuntimeException e) {
            visit = Optional.empty();
        }
        return answer(incoming, state, visit);
    }

    public Optional<String> answer(IncomingMessage incoming, BotState state, Optional<GuestVisit> visit) {
        if (!properties.isEnabled() || incoming == null || incoming.chatId() == null || incoming.text() == null) {
            return Optional.empty();
        }
        String text = incoming.text().trim();
        Optional<TableRequest> request = NOT_A_TABLE_REQUEST.matcher(text.toLowerCase(Locale.ROOT)).find()
                ? Optional.empty() : TableRequest.recognise(text);
        if (request.isPresent() && visit.isPresent() && visit.get().stage() == JourneyStage.SEATED) {
            events.onChat(incoming.chatId(), VisitEventKind.TABLE_REQUEST, JourneyEvents.GUEST, request.get().name());
            return Optional.of(router.route(visit.get().reservationId(), request.get(), text));
        }
        if (!idle(state)) {
            return Optional.empty();
        }
        String said = text.toLowerCase(Locale.ROOT);
        if (NUTRITION.matcher(said).find()) {
            List<String> answers = nutritionAnswers(said);
            if (!answers.isEmpty()) {
                events.onChat(incoming.chatId(), VisitEventKind.DISH_QUESTION, JourneyEvents.GUEST, text);
                return Optional.of(String.join("\n", answers));
            }
            return Optional.empty();
        }
        Optional<VenueFactsCatalog.Topic> topic = VenueFactsCatalog.Topic.of(said);
        if (topic.isPresent()) {
            events.onChat(incoming.chatId(), VisitEventKind.VENUE_QUESTION, JourneyEvents.GUEST, topic.get().name());
            Optional<String> fact = facts.fact(topic.get());
            if (fact.isPresent()) {
                return fact;
            }
            team.ask(incoming.chatId(), text);
            return Optional.of(ASK_TEAM);
        }
        return Optional.empty();
    }

    private List<String> nutritionAnswers(String said) {
        List<String> answers = new ArrayList<>();
        Optional<BusinessLunchOffer> offer = menu.find(venueCode);
        if (offer.isEmpty()) {
            return answers;
        }
        for (BusinessLunchOffer.Course course : offer.get().courses()) {
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                if (answers.size() < 3 && named(said, dish.title())) {
                    nutrition.of(dish.sabyLookupName()).ifPresent(n -> answers.add("%s: %d г, около %d ккал (Б %d · Ж %d · У %d)."
                            .formatted(dish.title(), n.grams(), n.kcal(), n.protein(), n.fat(), n.carbs())));
                }
            }
        }
        return answers;
    }

    /** «пасте», «пасты» name «Паста болоньезе»: the first word of the title without its ending. */
    static boolean named(String said, String title) {
        String first = title.toLowerCase(Locale.ROOT).split("\\s+")[0];
        String stem = first.length() > 4 ? first.substring(0, first.length() - 1) : first;
        return said.contains(stem);
    }

    private static boolean idle(BotState state) {
        return state == null || state == BotState.READY_FOR_DIALOG || state == BotState.UNKNOWN;
    }
}
