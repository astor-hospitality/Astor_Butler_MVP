package museon_online.astor_butler.domain.roster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who a name written in the schedule is in the staff directory — and when that is not certain, who it might be.
 *
 * <p>Certain (MATCHED) is only: a name form a person already settled with a button, or every word of the name equal
 * to a word of exactly one member's name (or the staff_id), initials agreeing. Everything softer — a case ending
 * («Анне»), a short form («Аня»), one letter misread («Анма»), a prefix («Алекс») — and a name that fits two people
 * exactly only makes candidates (AMBIGUOUS): the bot asks, it does not guess. Nobody close is UNKNOWN.
 */
public final class StaffNameMatcher {

    static final int MAX_CANDIDATES = 3;

    /** Short and familiar forms → the full names they stand for. */
    private static final Map<String, Set<String>> SHORT_FORMS = shortForms(
            "аня=анна", "анечка=анна", "нюра=анна", "даша=дарья", "саша=александр,александра", "шура=александр,александра",
            "маша=мария", "катя=екатерина", "настя=анастасия", "лена=елена", "оля=ольга", "юля=юлия",
            "женя=евгений,евгения", "дима=дмитрий", "миша=михаил", "леша=алексей", "алеша=алексей", "сережа=сергей",
            "ваня=иван", "таня=татьяна", "наташа=наталья,наталия", "света=светлана", "ира=ирина", "вика=виктория",
            "ксюша=ксения", "лиза=елизавета", "соня=софья,софия", "даня=даниил,данила", "рома=роман", "паша=павел",
            "костя=константин", "вова=владимир", "володя=владимир", "слава=вячеслав,ярослав,владислав", "влад=владислав",
            "гриша=григорий", "надя=надежда", "люба=любовь", "галя=галина", "валя=валентина,валентин", "тема=артем",
            "коля=николай", "витя=виктор", "толя=анатолий", "лера=валерия", "поля=полина", "мила=людмила,милана",
            "люда=людмила", "кристи=кристина", "стас=станислав", "эля=эльвира,элина", "геля=ангелина", "уля=ульяна", "макс=максим", "кирюша=кирилл", "ника=вероника");

    private StaffNameMatcher() {
    }

    public record Member(String staffId, String displayName, StaffRole role) {
    }

    public enum Status { MATCHED, AMBIGUOUS, UNKNOWN }

    /** MATCHED has exactly one candidate; AMBIGUOUS one to {@value #MAX_CANDIDATES}; UNKNOWN none. */
    public record Match(Status status, List<Member> candidates) {

        public Member member() {
            return status == Status.MATCHED ? candidates.get(0) : null;
        }

        static Match unknown() {
            return new Match(Status.UNKNOWN, List.of());
        }
    }

    /**
     * @param role    the role the schedule gives this row, or null; candidates of that role come first
     * @param aliases settled name forms ({@link #normalize(String)} → staff_id)
     */
    public static Match match(String name, StaffRole role, List<Member> members, Map<String, String> aliases) {
        String normalized = normalize(name);
        List<String> words = words(normalized);
        if (words.isEmpty() || members == null || members.isEmpty()) {
            return Match.unknown();
        }
        String settled = aliases == null ? null : aliases.get(normalized);
        if (settled != null) {
            for (Member member : members) {
                if (member.staffId().equals(settled)) {
                    return new Match(Status.MATCHED, List.of(member));
                }
            }
        }
        List<Member> exact = new ArrayList<>();
        List<Member> close = new ArrayList<>();
        for (Member member : members) {
            Fit fit = fit(words, member);
            if (fit == Fit.EXACT) {
                exact.add(member);
            } else if (fit == Fit.CLOSE) {
                close.add(member);
            }
        }
        if (exact.size() == 1) {
            return new Match(Status.MATCHED, List.of(exact.get(0)));
        }
        List<Member> candidates = exact.isEmpty() ? close : exact;
        if (candidates.isEmpty()) {
            return Match.unknown();
        }
        List<Member> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing((Member member) -> role != null && member.role() != role)
                .thenComparing(Member::displayName));
        return new Match(Status.AMBIGUOUS, List.copyOf(ordered.subList(0, Math.min(MAX_CANDIDATES, ordered.size()))));
    }

    /** Lower case, ё as е, letters and digits only, single spaces: the form names are compared and remembered in. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}_]+", " ").trim().replaceAll("\\s+", " ");
    }

    private enum Fit { NONE, CLOSE, EXACT }

    /**
     * EXACT: every word of the schedule's name is a word of the member's name (or the staff_id), initials agreeing.
     * CLOSE: every word fits at least loosely; or the member's whole name is in the schedule's name with words to
     * spare («Анна Смирнова» for a directory that only knows «Анна»), which cannot be checked.
     */
    private static Fit fit(List<String> words, Member member) {
        List<String> name = words(normalize(member.displayName()));
        String id = member.staffId() == null ? "" : member.staffId().toLowerCase(Locale.ROOT);
        boolean anyFull = false;
        boolean allExact = true;
        int spare = 0;
        Set<Integer> used = new HashSet<>();
        for (String word : words) {
            if (word.length() == 1) {
                // An initial: «Смирнова А.» — some other word of the name starts with it.
                int at = indexOf(name, used, part -> part.charAt(0) == word.charAt(0));
                if (at < 0) {
                    spare++;
                } else {
                    used.add(at);
                }
                continue;
            }
            if (word.equals(id)) {
                anyFull = true;
                continue;
            }
            int at = indexOf(name, used, word::equals);
            if (at < 0) {
                at = indexOf(name, used, part -> close(word, part));
                if (at < 0) {
                    spare++;
                    continue;
                }
                allExact = false;
            }
            anyFull = true;
            used.add(at);
        }
        if (!anyFull) {
            return Fit.NONE;
        }
        if (spare > 0) {
            return used.size() == name.size() ? Fit.CLOSE : Fit.NONE;
        }
        return allExact ? Fit.EXACT : Fit.CLOSE;
    }

    private static int indexOf(List<String> name, Set<Integer> used, java.util.function.Predicate<String> test) {
        for (int i = 0; i < name.size(); i++) {
            if (!used.contains(i) && test.test(name.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** One name in another form: short form, case ending, one misread letter, a cut-off word. */
    static boolean close(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return false;
        }
        Set<String> fullA = fullNames(a);
        for (String full : fullNames(b)) {
            if (fullA.contains(full)) {
                return true;
            }
        }
        if (stems(a, b)) {
            return true;
        }
        if (Math.min(a.length(), b.length()) >= 4 && distance(a, b) <= 1) {
            return true;
        }
        return Math.min(a.length(), b.length()) >= 4 && (a.startsWith(b) || b.startsWith(a));
    }

    private static Set<String> fullNames(String word) {
        Set<String> names = new HashSet<>();
        names.add(word);
        names.addAll(SHORT_FORMS.getOrDefault(word, Set.of()));
        return names;
    }

    /** «Анне» and «Анна»: a common part of three letters or more, then only a short vowel ending (as StaffAssignees). */
    static boolean stems(String a, String b) {
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        if (common < 3 || common < Math.min(a.length(), b.length()) - 2) {
            return false;
        }
        return ending(a.substring(common)) && ending(b.substring(common));
    }

    private static boolean ending(String suffix) {
        if (suffix.length() > 2) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if ("аеиоуыэюяй".indexOf(suffix.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static List<String> words(String normalized) {
        List<String> words = new ArrayList<>();
        for (String word : normalized.split(" ")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static Map<String, Set<String>> shortForms(String... pairs) {
        Map<String, Set<String>> forms = new HashMap<>();
        for (String pair : pairs) {
            String[] parts = pair.split("=");
            Set<String> full = new HashSet<>();
            for (String name : parts[1].split(",")) {
                full.add(name.replace('ё', 'е'));
            }
            forms.computeIfAbsent(parts[0].replace('ё', 'е'), key -> new HashSet<>()).addAll(full);
        }
        return Map.copyOf(forms);
    }
}
