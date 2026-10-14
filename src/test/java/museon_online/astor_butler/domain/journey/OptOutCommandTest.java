package museon_online.astor_butler.domain.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OptOutCommandTest {

    private static final long CHAT = 421441838L;

    private JourneyTestDatabase database;
    private GuestVisitRepository repository;
    private UsefulnessPolicyProperties properties;
    private JourneyEvents events;
    private OptOutCommand command;

    @BeforeEach
    void setUp() {
        database = JourneyTestDatabase.create();
        repository = new GuestVisitRepository(database.jdbc());
        properties = new UsefulnessPolicyProperties();
        properties.setEnabled(true);
        events = mock(JourneyEvents.class);
        command = new OptOutCommand(repository, properties, events);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void optOutPhraseStopsBotFirstMessages() {
        assertThat(command.tryHandle(CHAT, "Пожалуйста, больше не пишите мне")).contains(OptOutCommand.REPLY);

        assertThat(repository.optedOut(CHAT)).isTrue();
        verify(events).onChat(eq(CHAT), eq(VisitEventKind.OPTED_OUT), eq(JourneyEvents.GUEST), any());
    }

    @Test
    void ordinaryTextIsNotAnOptOut() {
        assertThat(command.tryHandle(CHAT, "не пишите сыр в пасту")).isEmpty();
        assertThat(command.tryHandle(CHAT, "хочу ланч")).isEmpty();
        assertThat(repository.optedOut(CHAT)).isFalse();
    }

    @Test
    void flagOffIgnoresThePhrase() {
        properties.setEnabled(false);

        assertThat(command.tryHandle(CHAT, "не пишите мне")).isEmpty();
        assertThat(repository.optedOut(CHAT)).isFalse();
    }

    @Test
    void groupChatsNeverOptOut() {
        assertThat(command.tryHandle(-1002768239841L, "не пишите мне")).isEmpty();
    }
}
