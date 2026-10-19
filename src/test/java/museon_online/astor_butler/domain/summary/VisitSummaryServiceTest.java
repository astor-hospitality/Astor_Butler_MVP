package museon_online.astor_butler.domain.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.model.ModelUnavailableException;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VisitSummaryServiceTest {

    private static final Instant DAY = Instant.parse("2026-10-15T07:30:00Z");
    private static final String SUMMARY_JSON = """
            ```json
            {"guestWanted": "Столик на двоих у окна в 13:00",
             "venueOffered": "Стол 7 у окна",
             "outcome": "Бронь подтверждена, гость пришёл",
             "stuck": ["Гость дважды переспрашивал время"],
             "nextTimeGuest": ["Любит стол у окна"],
             "nextTimeAgents": ["CONCIERGE: сразу называть свободные столы у окна", "BUTLER: не переспрашивать время брони"]}
            ```
            """;

    private SummaryTestDatabase database;
    private ModelGateway model;
    private SummaryProperties properties;
    private VisitSummaryRepository summaries;
    private AgentLessonRepository lessons;
    private VisitSummaryService service;

    @BeforeEach
    void setUp() {
        database = SummaryTestDatabase.create();
        ObjectMapper mapper = new ObjectMapper();
        model = mock(ModelGateway.class);
        properties = new SummaryProperties();
        properties.setEnabled(true);
        summaries = new VisitSummaryRepository(database.jdbc(), mapper);
        lessons = new AgentLessonRepository(database.jdbc());
        service = new VisitSummaryService(new VisitTurnRepository(database.jdbc(), mapper), new GuestVisitRepository(database.jdbc()),
                summaries, lessons, model, properties, mapper, ZoneId.of("Asia/Yekaterinburg"));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void summaryFromTheLedgerIsStoredAndLessonsProposed() {
        long visit = finishedVisitWithTurns();
        answer(SUMMARY_JSON);

        Optional<VisitSummary> summary = service.summarize(visit);

        assertThat(summary).isPresent();
        VisitSummary stored = summaries.find(visit).orElseThrow();
        assertThat(stored.visitId()).isEqualTo(visit);
        assertThat(stored.guestWanted()).isEqualTo("Столик на двоих у окна в 13:00");
        assertThat(stored.venueOffered()).isEqualTo("Стол 7 у окна");
        assertThat(stored.outcome()).isEqualTo("Бронь подтверждена, гость пришёл");
        assertThat(stored.stuck()).containsExactly("Гость дважды переспрашивал время");
        assertThat(stored.nextTimeGuest()).containsExactly("Любит стол у окна");
        assertThat(stored.nextTimeAgents()).hasSize(2);
        assertThat(stored.createdAt()).isNotNull();

        List<AgentLesson> proposed = lessons.withStatus(AgentLesson.Status.PROPOSED);
        assertThat(proposed).extracting(AgentLesson::side).containsExactlyInAnyOrder("CONCIERGE", "BUTLER");
        assertThat(proposed).extracting(AgentLesson::text)
                .containsExactlyInAnyOrder("сразу называть свободные столы у окна", "не переспрашивать время брони");
        assertThat(proposed).allSatisfy(lesson -> assertThat(lesson.visitId()).isEqualTo(visit));
        assertThat(lessons.accepted("BUTLER")).isEmpty();

        // A rebuild replaces the summary and does not propose the same lessons twice.
        service.summarize(visit);
        assertThat(lessons.withStatus(AgentLesson.Status.PROPOSED)).hasSize(2);
    }

    @Test
    void brokenModelJsonStoresNothing() {
        long visit = finishedVisitWithTurns();
        answer("Гость хотел стол у окна, всё прошло хорошо.");

        assertThat(service.summarize(visit)).isEmpty();

        answer("{\"guestWanted\": \"стол\", \"venueOffered\": \"стол 7\", \"outcome\": \"пришёл\"}");
        assertThat(service.summarize(visit)).isEmpty();

        assertThat(summaries.find(visit)).isEmpty();
        assertThat(lessons.withStatus(AgentLesson.Status.PROPOSED)).isEmpty();
    }

    @Test
    void disabledDoesNothing() {
        long visit = finishedVisitWithTurns();
        properties.setEnabled(false);

        assertThat(service.summarize(visit)).isEmpty();
        service.summarizeFinishedVisits();

        verifyNoInteractions(model);
        assertThat(summaries.find(visit)).isEmpty();
    }

    @Test
    void promptHidesPhones() {
        long visit = database.visit("+79123456760", 100L, "AFTER", DAY);
        database.turn(visit, "GUEST", "CONCIERGE_TG", "MESSAGE", "мой номер 8 (912) 345-67-60, запасной +442079460958123",
                null, DAY);
        database.turn(visit, "GUEST", "CONCIERGE_TG", "MESSAGE", null, "{\"hidden\":true}", DAY.plusSeconds(60));
        database.turn(visit, "BUTLER", "BUTLER_TG", "MESSAGE", "Записал 89123456760, ждём вас", null, DAY.plusSeconds(120));
        answer(SUMMARY_JSON);

        service.summarize(visit);

        ArgumentCaptor<ModelTextRequest> request = ArgumentCaptor.forClass(ModelTextRequest.class);
        verify(model).generateText(request.capture());
        String prompt = request.getValue().prompt();
        assertThat(prompt).doesNotContainPattern("\\d{10,}");
        assertThat(prompt).contains("Ты аналитик ресторана");
        assertThat(prompt).contains("[12:30] ГОСТЬ/CONCIERGE_TG: мой номер +7 912 ***-**-60");
        assertThat(prompt).contains("[12:31] ГОСТЬ/CONCIERGE_TG: [скрыто]");
        assertThat(prompt).contains("[12:32] БАТЛЕР/BUTLER_TG: Записал +7 912 ***-**-60");
    }

    @Test
    void theScheduledRunTakesFinishedVisitsWithTurnsAndOneFailureDoesNotStopIt() {
        long first = finishedVisitWithTurns();
        long second = finishedVisitWithTurns();
        long seated = database.visit("+79120000061", 101L, "SEATED", DAY);
        database.turn(seated, "GUEST", "BUTLER_TG", "MESSAGE", "счёт, пожалуйста", null, DAY);
        database.visit("+79120000062", 102L, "AFTER", DAY);
        when(model.generateText(any())).thenThrow(new IllegalStateException("model down"))
                .thenReturn(ModelTextResponse.text(SUMMARY_JSON, "stub", "stub", Duration.ZERO));

        service.summarizeFinishedVisits();

        verify(model, times(2)).generateText(any());
        assertThat(summaries.find(first)).isEmpty();
        assertThat(summaries.find(second)).isPresent();
        assertThat(summaries.find(seated)).isEmpty();
    }

    @Test
    void withAToolCallingModelTheSummaryAsksForItsOwnTokenBudget() {
        long visit = finishedVisitWithTurns();
        ToolCallingModel chat = mock(ToolCallingModel.class);
        when(chat.chat(any())).thenReturn(new ModelChatResponse(SUMMARY_JSON, Optional.empty(), "stub", "stub", Duration.ZERO));
        service.setChatModel(chat);

        assertThat(service.summarize(visit)).isPresent();
        properties.setModel("GigaChat-2-Max");
        service.summarize(visit);

        ArgumentCaptor<ModelChatRequest> request = ArgumentCaptor.forClass(ModelChatRequest.class);
        verify(chat, times(2)).chat(request.capture());
        assertThat(request.getAllValues()).allSatisfy(sent -> {
            assertThat(sent.maxTokens()).isEqualTo(VisitSummaryService.MAX_TOKENS).isEqualTo(900);
            assertThat(sent.tools()).isEmpty();
            assertThat(sent.messages().getFirst().content()).contains("Ты аналитик ресторана");
        });
        assertThat(request.getAllValues().get(0).model()).isNull();
        assertThat(request.getAllValues().get(1).model()).isEqualTo("GigaChat-2-Max");
        verify(model, never()).generateText(any());
    }

    @Test
    void aSmallTokenBudgetIsReportedOnlyWhenTheTextPathIsUsed() {
        MockEnvironment small = new MockEnvironment().withProperty("astor.model.provider", "cloudru")
                .withProperty("astor.model.cloudru.max-tokens", "256");
        MockEnvironment large = new MockEnvironment().withProperty("astor.model.provider", "cloudru")
                .withProperty("astor.model.cloudru.max-tokens", "1024");

        assertThat(service.tokenBudgetTooSmall(small)).isTrue();
        assertThat(service.tokenBudgetTooSmall(large)).isFalse();
        service.setChatModel(mock(ToolCallingModel.class));
        assertThat(service.tokenBudgetTooSmall(small)).isFalse();
        properties.setEnabled(false);
        service.setChatModel(null);
        assertThat(service.tokenBudgetTooSmall(small)).isFalse();
    }

    @Test
    void anOutageDoesNotParkTheVisit() {
        long visit = finishedVisitWithTurns();
        when(model.generateText(any())).thenThrow(new ModelUnavailableException("quota", null))
                .thenThrow(new ModelUnavailableException("quota", null)).thenThrow(new ModelUnavailableException("quota", null))
                .thenThrow(new IllegalStateException("timeout"))
                .thenReturn(ModelTextResponse.text(SUMMARY_JSON, "stub", "stub", Duration.ZERO));

        for (int run = 0; run < 5; run++) {
            service.summarizeFinishedVisits();
        }

        verify(model, times(5)).generateText(any());
        assertThat(summaries.find(visit)).isPresent();
    }

    @Test
    void threeBrokenAnswersParkTheVisitWithOneWarning() {
        long visit = finishedVisitWithTurns();
        answer("не JSON");
        Logger logger = (Logger) LoggerFactory.getLogger(VisitSummaryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int run = 0; run < 5; run++) {
                service.summarizeFinishedVisits();
            }
        } finally {
            logger.detachAppender(appender);
        }

        verify(model, times(VisitSummaryService.MAX_ATTEMPTS)).generateText(any());
        assertThat(summaries.find(visit)).isEmpty();
        assertThat(appender.list).filteredOn(event -> event.getFormattedMessage().contains("parked")).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("visitId=" + visit));
    }

    private long finishedVisitWithTurns() {
        long visit = database.visit("+79120000060", 100L, "AFTER", DAY);
        database.turn(visit, "GUEST", "CONCIERGE_TG", "MESSAGE", "Столик на двоих у окна на 13:00", null, DAY);
        database.turn(visit, "BUTLER", "BUTLER_TG", "MESSAGE", "Стол 7 у окна свободен, записал", null,
                DAY.plus(2, ChronoUnit.MINUTES));
        return visit;
    }

    private void answer(String text) {
        when(model.generateText(any())).thenReturn(ModelTextResponse.text(text, "stub", "stub", Duration.ZERO));
    }
}
