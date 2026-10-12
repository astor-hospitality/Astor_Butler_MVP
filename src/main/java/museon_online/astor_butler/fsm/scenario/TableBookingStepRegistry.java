package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.fsm.core.BotState;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TableBookingStepRegistry {

    public Optional<Step> nextMissingStep(TableBookingDraftStorage.Draft draft) {
        if (draft.partySize() == null) {
            return Optional.of(new Step(
                    BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE,
                    "ASK_PARTY_SIZE",
                    "С удовольствием помогу с посадкой. Сначала подскажу по размеру стола: на сколько гостей готовим место?"
            ));
        }
        if (!hasTableSelection(draft)) {
            return Optional.of(new Step(
                    BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION,
                    "ASK_TABLE_SELECTION",
                    "Отправляю план зала AERIS. Выберите номер стола или зону: «4 стол у окна», «винная комната», «у бара», «VIP-зона» или «подбери сам». Если доверите выбор мне, пойду по лучшим свободным столам под вашу компанию."
            ));
        }
        if (draft.requestedDate() == null) {
            return Optional.of(new Step(
                    BotState.TABLE_BOOKING_COLLECT_DATE,
                    "ASK_DATE",
                    "Принял. На какой день держим стол?"
            ));
        }
        if (draft.requestedTime() == null) {
            return Optional.of(new Step(
                    BotState.TABLE_BOOKING_COLLECT_TIME,
                    "ASK_TIME",
                    "Хорошо. Во сколько вас ждать?"
            ));
        }
        return Optional.empty();
    }

    public boolean hasTableSelection(TableBookingDraftStorage.Draft draft) {
        return draft != null
                && (hasText(draft.tableCode())
                || hasText(draft.preferredZone())
                || leavesTheChoiceToUs(draft.seatingPreference()));
    }

    /** "Подбери сам" and the like: the guest chose no table and no zone and left the choice to the team. */
    public boolean leavesTheChoiceToUs(String seatingPreference) {
        return containsAny(normalize(seatingPreference), "выбери сам", "подбери сам", "любой", "на твой выбор", "где удобно", "на ваше усмотрение");
    }

    public boolean hasSeatingPreferenceDecision(TableBookingDraftStorage.Draft draft) {
        return draft != null
                && (Boolean.TRUE.equals(draft.seatingPreferenceResolved()) || hasText(draft.seatingPreference()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean containsAny(String text, String... variants) {
        for (String variant : variants) {
            if (text.contains(variant)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }

    public record Step(BotState state, String action, String deterministicPrompt) {
    }
}
