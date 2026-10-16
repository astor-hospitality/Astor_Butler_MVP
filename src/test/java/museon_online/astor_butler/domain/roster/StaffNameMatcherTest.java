package museon_online.astor_butler.domain.roster;

import museon_online.astor_butler.domain.roster.StaffNameMatcher.Match;
import museon_online.astor_butler.domain.roster.StaffNameMatcher.Member;
import museon_online.astor_butler.domain.roster.StaffNameMatcher.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A name from the schedule to the directory: certain only when nothing is guessed. All names are made up. */
class StaffNameMatcherTest {

    private static final List<Member> DIRECTORY = List.of(
            new Member("alisa", "Алиса Тестова", StaffRole.HOSTESS),
            new Member("boris", "Борис Примеров", StaffRole.MANAGER),
            new Member("vera", "Вера Образцова", StaffRole.WAITER),
            new Member("gleb", "Глеб Макетов", StaffRole.WAITER),
            new Member("dina.sh", "Дина Шаблонова", StaffRole.WAITER),
            new Member("dina.pr", "Дина Пробная", StaffRole.HOSTESS),
            new Member("egor", "Егор", StaffRole.WAITER),
            new Member("anna", "Анна Черновая", StaffRole.WAITER),
            new Member("sasha.m", "Александр Моделев", StaffRole.WAITER),
            new Member("sasha.f", "Александра Фиктивная", StaffRole.HOSTESS));

    @ParameterizedTest
    @CsvSource({
            "Борис Примеров, boris",
            "Примеров Борис, boris",
            "борис,          boris",
            "Примеров,       boris",
            "Алиса Т.,       alisa",
            "Тестова А.,     alisa",
            "АЛИСА  ТЕСТОВА, alisa",
            "Егор,           egor",
            "vera,           vera",
    })
    void everyWordOfTheNameInOneMembersNameIsCertain(String name, String staffId) {
        Match match = StaffNameMatcher.match(name, null, DIRECTORY, Map.of());

        assertThat(match.status()).isEqualTo(Status.MATCHED);
        assertThat(match.member().staffId()).isEqualTo(staffId);
    }

    @Test
    void aNameThatFitsTwoPeopleIsAskedNotGuessed() {
        Match match = StaffNameMatcher.match("Дина", StaffRole.HOSTESS, DIRECTORY, Map.of());

        assertThat(match.status()).isEqualTo(Status.AMBIGUOUS);
        // The one with the schedule's role first.
        assertThat(match.candidates()).extracting(Member::staffId).containsExactly("dina.pr", "dina.sh");
        assertThat(match.member()).isNull();
    }

    @Test
    void theRoleOrdersTheCandidatesButDoesNotDecide() {
        Match match = StaffNameMatcher.match("Дина", StaffRole.WAITER, DIRECTORY, Map.of());

        assertThat(match.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(match.candidates()).extracting(Member::staffId).containsExactly("dina.sh", "dina.pr");
    }

    @ParameterizedTest
    @CsvSource({
            // one misread letter
            "Глеп Макетов,   gleb",
            "Вера Обрацова,  vera",
            // a case ending
            "Борису,         boris",
            // a short form
            "Аня,            anna",
            "Аня Черновая,   anna",
            // the directory knows only the first name: the surname cannot be checked
            "Егор Пробный,   egor",
    })
    void aLooseFitIsOnlyACandidate(String name, String staffId) {
        Match match = StaffNameMatcher.match(name, null, DIRECTORY, Map.of());

        assertThat(match.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(match.candidates()).extracting(Member::staffId).containsExactly(staffId);
    }

    @Test
    void aShortFormOfTwoFullNamesOffersBoth() {
        Match match = StaffNameMatcher.match("Саша", StaffRole.HOSTESS, DIRECTORY, Map.of());

        assertThat(match.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(match.candidates()).extracting(Member::staffId).containsExactly("sasha.f", "sasha.m");
    }

    @ParameterizedTest
    @CsvSource({
            "Пётр Неизвестный",
            "Олеся",
            "Иван Примеров",
            "Д. Т.",
            "''",
    })
    void nobodyCloseIsUnknown(String name) {
        Match match = StaffNameMatcher.match(name, null, DIRECTORY, Map.of());

        assertThat(match.status()).isEqualTo(Status.UNKNOWN);
        assertThat(match.candidates()).isEmpty();
    }

    @Test
    void aNameFormSettledWithAButtonIsCertainNextTime() {
        Map<String, String> aliases = Map.of(StaffNameMatcher.normalize("Глеп Макетов"), "gleb",
                StaffNameMatcher.normalize("Дина"), "dina.pr");

        assertThat(StaffNameMatcher.match("Глеп  Макетов", null, DIRECTORY, aliases).member().staffId()).isEqualTo("gleb");
        assertThat(StaffNameMatcher.match("Дина", null, DIRECTORY, aliases).member().staffId()).isEqualTo("dina.pr");
    }

    @Test
    void aSettledFormOfSomeoneNoLongerInTheDirectoryIsIgnored() {
        Map<String, String> aliases = Map.of("дина", "gone");

        assertThat(StaffNameMatcher.match("Дина", null, DIRECTORY, aliases).status()).isEqualTo(Status.AMBIGUOUS);
    }

    @Test
    void noMoreThanThreeCandidates() {
        List<Member> many = List.of(
                new Member("a1", "Анна Первая", StaffRole.WAITER),
                new Member("a2", "Анна Вторая", StaffRole.WAITER),
                new Member("a3", "Анна Третья", StaffRole.HOSTESS),
                new Member("a4", "Анна Четвёртая", StaffRole.WAITER));

        Match match = StaffNameMatcher.match("Анна", StaffRole.HOSTESS, many, Map.of());

        assertThat(match.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(match.candidates()).hasSize(StaffNameMatcher.MAX_CANDIDATES);
        assertThat(match.candidates().get(0).staffId()).isEqualTo("a3");
    }

    @Test
    void anEmptyDirectoryKnowsNobody() {
        assertThat(StaffNameMatcher.match("Анна", null, List.of(), Map.of()).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void namesAreComparedWithoutCaseYoAndPunctuation() {
        assertThat(StaffNameMatcher.normalize("  Пётр  ИВАНОВ-Сидоров. ")).isEqualTo("петр иванов сидоров");
        assertThat(StaffNameMatcher.distance("глеп", "глеб")).isEqualTo(1);
        assertThat(StaffNameMatcher.stems("анне", "анна")).isTrue();
        assertThat(StaffNameMatcher.stems("марии", "марина")).isFalse();
    }
}
