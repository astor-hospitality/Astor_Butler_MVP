package museon_online.astor_butler.domain.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisitTurnRepositoryTest {

    private LedgerTestDatabase database;
    private VisitTurnRepository repository;

    @BeforeEach
    void setUp() {
        database = LedgerTestDatabase.create();
        repository = new VisitTurnRepository(database.jdbc(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void appendsAndReadsInOrder() {
        long first = repository.append(new VisitTurnDraft(7L, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE,
                "столик на двоих", null, null));
        long second = repository.append(new VisitTurnDraft(7L, null, TurnSide.BUTLER, TurnChannel.BUTLER_TG, TurnKind.OFFER,
                "есть у окна", Map.of("table", 12), "BOOK"));
        repository.append(new VisitTurnDraft(8L, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE, "другой", null, null));

        List<VisitTurn> turns = repository.ofVisit(7L);

        assertThat(turns).extracting(VisitTurn::id).containsExactly(first, second);
        VisitTurn offer = turns.get(1);
        assertThat(offer.visitId()).isEqualTo(7L);
        assertThat(offer.side()).isEqualTo(TurnSide.BUTLER);
        assertThat(offer.channel()).isEqualTo(TurnChannel.BUTLER_TG);
        assertThat(offer.kind()).isEqualTo(TurnKind.OFFER);
        assertThat(offer.text()).isEqualTo("есть у окна");
        assertThat(offer.payload()).containsEntry("table", 12);
        assertThat(offer.goal()).isEqualTo("BOOK");
        assertThat(offer.at()).isNotNull();
        assertThat(turns.get(0).payload()).isNull();
    }

    @Test
    void linkSessionMovesConciergeTurnsToTheVisit() {
        repository.append(new VisitTurnDraft(null, "c-1", TurnSide.GUEST, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "хочу ужин", null, null));
        repository.append(new VisitTurnDraft(null, "c-1", TurnSide.CONCIERGE, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "уточню", null, null));
        repository.append(new VisitTurnDraft(null, "c-2", TurnSide.GUEST, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "чужая", null, null));

        assertThat(repository.linkSession("c-1", 7L)).isEqualTo(2);

        assertThat(repository.ofVisit(7L)).extracting(VisitTurn::text).containsExactly("хочу ужин", "уточню");
        assertThat(repository.ofSession("c-1")).extracting(VisitTurn::visitId).containsOnly(7L);
        assertThat(repository.ofSession("c-2")).extracting(VisitTurn::visitId).containsOnlyNulls();
    }

    @Test
    void theSessionOfAVisitIsTheOneLinkedToIt() {
        repository.append(new VisitTurnDraft(7L, null, TurnSide.STAFF, TurnChannel.STAFF_GROUP, TurnKind.STAFF_ACTION, "SEATED", null, null));
        repository.append(new VisitTurnDraft(null, "c-1", TurnSide.GUEST, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "хочу ужин", null, null));
        repository.linkSession("c-1", 7L);

        repository.append(new VisitTurnDraft(null, "c-2", TurnSide.GUEST, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "второй", null, null));
        repository.linkSession("c-2", 7L);

        assertThat(repository.existsLink(7L, "c-1")).isTrue();
        assertThat(repository.existsLink(7L, "c-2")).isTrue();
        assertThat(repository.existsLink(7L, "c-3")).isFalse();
        assertThat(repository.existsLink(8L, "c-1")).isFalse();
    }

    @Test
    void theVisitOfASessionIsTheOneItWasLinkedTo() {
        repository.append(new VisitTurnDraft(null, "c-1", TurnSide.CONCIERGE, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "ужин", null, null));
        assertThat(repository.visitOfSession("c-1")).isEmpty();

        repository.linkSession("c-1", 7L);

        assertThat(repository.visitOfSession("c-1")).contains(7L);
        assertThat(repository.visitOfSession("c-2")).isEmpty();
        assertThat(repository.visitOfSession(null)).isEmpty();
    }

    @Test
    void countsOnlyGuestMessages() {
        repository.append(new VisitTurnDraft(7L, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE, "раз", null, null));
        repository.append(new VisitTurnDraft(7L, null, TurnSide.GUEST, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, null, Map.of("hidden", true), null));
        repository.append(new VisitTurnDraft(7L, null, TurnSide.BUTLER, TurnChannel.BUTLER_TG, TurnKind.MESSAGE, "ответ", null, null));
        repository.append(new VisitTurnDraft(7L, null, TurnSide.STAFF, TurnChannel.STAFF_GROUP, TurnKind.STAFF_ACTION, "SEATED", null, null));
        repository.append(new VisitTurnDraft(8L, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE, "другой визит", null, null));

        assertThat(repository.countGuestMessages(7L)).isEqualTo(2);
    }

    @Test
    void longTextIsClipped() {
        long id = repository.append(new VisitTurnDraft(7L, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE,
                "а".repeat(5000), null, null));

        assertThat(repository.ofVisit(7L)).singleElement().satisfies(turn -> {
            assertThat(turn.id()).isEqualTo(id);
            assertThat(turn.text()).hasSize(4000);
        });
    }
}
