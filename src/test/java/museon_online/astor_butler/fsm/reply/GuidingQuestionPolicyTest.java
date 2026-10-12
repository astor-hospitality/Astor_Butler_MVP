package museon_online.astor_butler.fsm.reply;

import museon_online.astor_butler.fsm.core.BotState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GuidingQuestionPolicyTest {

    private static final Path SCENARIOS = Path.of("src/main/java/museon_online/astor_butler/fsm/scenario");
    private static final Pattern SCENARIO_ID = Pattern.compile("public\\s+String\\s+id\\(\\)\\s*\\{\\s*return\\s+\"([A-Z_]+)\";");

    private final GuidingQuestionPolicy policy = new GuidingQuestionPolicy();

    @Test
    void everyScenarioFromTheSourcesHasAnExplicitEntryOrAnExplicitNone() throws IOException {
        Set<String> scenarioIds = scenarioIdsFromSources();

        assertThat(scenarioIds).hasSizeGreaterThanOrEqualTo(19).contains("TABLE_BOOKING", "BUSINESS_LUNCH", "CHANGE_CANCEL", "FIRST_TOUCH", "RECOVERY");
        for (String scenarioId : scenarioIds) {
            assertThat(policy.covers(scenarioId)).as("scenario %s has no entry in GuidingQuestionPolicy", scenarioId).isTrue();
        }
        assertThat(policy.rulesByScenario().keySet()).isSubsetOf(scenarioIds);
        assertThat(policy.silentScenarios()).isSubsetOf(scenarioIds);
        assertThat(policy.rulesByScenario().keySet()).doesNotContainAnyElementsOf(policy.silentScenarios());
        assertThat(policy.covers("NOT_A_SCENARIO")).isFalse();
    }

    @Test
    void tableBookingAsksForTheMissingSlot() {
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_COLLECT_DATE, List.of())).contains(GuidingQuestionPolicy.ASK_DATE);
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_COLLECT_TIME, List.of())).contains(GuidingQuestionPolicy.ASK_TIME);
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE, List.of())).contains(GuidingQuestionPolicy.ASK_PARTY_SIZE);
    }

    @Test
    void afterTheRequestIsSentTheBotOffersTheMenuWhileTheTeamConfirms() {
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_WAIT_HOSTESS_CONFIRMATION, List.of("TABLE_BOOKING_REQUEST_SENT")))
                .contains(GuidingQuestionPolicy.OFFER_MENU_WHILE_TEAM_CONFIRMS);
    }

    @Test
    void finishedScenarioAtHomeGetsNoQuestion() {
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.READY_FOR_DIALOG, List.of("TABLE_BOOKING_CONFIRMED"))).isEmpty();
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_CONFIRMED, List.of())).isEmpty();
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_CANCELLED, List.of())).isEmpty();
        assertThat(policy.questionFor("BUSINESS_LUNCH", BotState.READY_FOR_DIALOG, List.of())).isEmpty();
        assertThat(policy.questionFor("CHANGE_CANCEL", BotState.READY_FOR_DIALOG, List.of("ACTION_CANCELLED"))).isEmpty();
    }

    @Test
    void tableSelectionAndSummaryStepsStaySilent() {
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_SHOW_PLAN, List.of())).isEmpty();
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION, List.of())).isEmpty();
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_CONFIRMATION, List.of())).isEmpty();
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_COLLECT_SEATING_PREFERENCE, List.of())).isEmpty();
    }

    @Test
    void businessLunchAsksWhatTheGuestChoosesAfterTheMenu() {
        assertThat(policy.questionFor("BUSINESS_LUNCH", BotState.BUSINESS_LUNCH_CHOOSE_SET, List.of())).contains(GuidingQuestionPolicy.ASK_CHOICE);
        assertThat(policy.questionFor("BUSINESS_LUNCH", BotState.BUSINESS_LUNCH_CHOOSE_DISH, List.of())).contains(GuidingQuestionPolicy.ASK_CHOICE);
        assertThat(policy.questionFor("BUSINESS_LUNCH", BotState.BUSINESS_LUNCH_COLLECT_DATE, List.of())).contains(GuidingQuestionPolicy.ASK_DATE);
        assertThat(policy.questionFor("BUSINESS_LUNCH", BotState.TABLE_BOOKING_COLLECT_TIME, List.of())).contains(GuidingQuestionPolicy.ASK_TIME);
        assertThat(policy.questionFor("BUSINESS_LUNCH", BotState.BUSINESS_LUNCH_CONFIRMATION, List.of())).isEmpty();
    }

    @Test
    void changeCancelAsksWhatToChangeOnlyBeforeAnActionIsChosen() {
        assertThat(policy.questionFor("CHANGE_CANCEL", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of("CHANGE_CANCEL", "ASK_ACTIVE_ORDER_REFERENCE")))
                .contains(GuidingQuestionPolicy.ASK_WHAT_TO_CHANGE);
        assertThat(policy.questionFor("CHANGE_CANCEL", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of("CHANGE_CANCEL", "ORDER_SELECTED", "ASK_CHANGE_ACTION")))
                .contains(GuidingQuestionPolicy.ASK_WHAT_TO_CHANGE);
        assertThat(policy.questionFor("CHANGE_CANCEL", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of("CHANGE_CANCEL", "CHANGE_TIME"))).isEmpty();
        assertThat(policy.questionFor("CHANGE_CANCEL", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of("CHANGE_CANCEL", "CHANGE_PARTY_SIZE"))).isEmpty();
        assertThat(policy.questionFor("CHANGE_CANCEL", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of("CHANGE_CANCEL", "CANCEL", "ASK_ORDER_SELECTION"))).isEmpty();
        // The same state without the scenario tag belongs to the booking, which has no rule for it.
        assertThat(policy.questionFor("TABLE_BOOKING", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of())).isEmpty();
    }

    @Test
    void silentScenariosAndUnknownScenariosNeverGetAQuestion() {
        assertThat(policy.questionFor("MANAGER_HELP", BotState.MANAGER_HELP_COLLECT_REASON, List.of())).isEmpty();
        assertThat(policy.questionFor("SMART_TIP", BotState.TIP_COLLECT_AMOUNT, List.of())).isEmpty();
        assertThat(policy.questionFor("MENU_ASSETS", BotState.MENU_ASSETS_CLARIFY, List.of())).isEmpty();
        assertThat(policy.questionFor("FIRST_TOUCH", BotState.CONSENT_REQUIRED, List.of())).isEmpty();
        assertThat(policy.questionFor("SOMETHING_ELSE", BotState.TABLE_BOOKING_COLLECT_DATE, List.of())).isEmpty();
        assertThat(policy.questionFor(null, BotState.TABLE_BOOKING_COLLECT_DATE, List.of())).isEmpty();
        assertThat(policy.questionFor("TABLE_BOOKING", null, List.of())).isEmpty();
    }

    @Test
    void ownerOfAStateIsTheScenarioThatDrivesIt() {
        assertThat(policy.ownerOf(BotState.TABLE_BOOKING_COLLECT_DATE)).contains("TABLE_BOOKING");
        assertThat(policy.ownerOf(BotState.BUSINESS_LUNCH_CHOOSE_DISH)).contains("BUSINESS_LUNCH");
        assertThat(policy.ownerOf(BotState.TIP_COLLECT_AMOUNT)).contains("SMART_TIP");
        assertThat(policy.ownerOf(BotState.PREFERENCE_COLLECT_TEXT)).contains("PREFERENCE_MAP");
        assertThat(policy.ownerOf(BotState.READY_FOR_DIALOG)).isEmpty();
        assertThat(policy.ownerOf(BotState.AI_FALLBACK)).isEmpty();
        assertThat(policy.ownerOf(BotState.CONSENT_REQUIRED)).isEmpty();
        // Legacy MENU is canonical READY_FOR_DIALOG: the guest is at home, nobody owns the state.
        assertThat(policy.ownerOf(BotState.MENU)).isEmpty();
        assertThat(policy.ownerOf(null)).isEmpty();
    }

    @Test
    void everyQuestionIsAShortRussianQuestionWithoutPricesOrAvailability() {
        Stream<String> questions = policy.rulesByScenario().values().stream().flatMap(List::stream).map(GuidingQuestionPolicy.Rule::question);
        questions.forEach(question -> {
            assertThat(question).endsWith("?");
            assertThat(question.length()).isLessThanOrEqualTo(70);
            assertThat(question).doesNotContainPattern("[0-9₽$€%]");
            assertThat(question.toLowerCase()).doesNotContain("скидк", "цена", "свободн", "гарантир", "только сегодня", "успейте");
        });
    }

    private static Set<String> scenarioIdsFromSources() throws IOException {
        Set<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.list(SCENARIOS)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("Scenario.java")).toList()) {
                Matcher matcher = SCENARIO_ID.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
        }
        return ids;
    }
}
