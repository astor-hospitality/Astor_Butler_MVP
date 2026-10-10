package museon_online.astor_butler.api.glasses.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who a spoken name or role points at in the venue's staff directory.
 *
 * Deliberately conservative: only active members on an open shift can be meant, a name is matched by
 * its stem so «Анне» and «Анну» find «Анна», a role word («официанту») finds the one person with that
 * role, and anything that fits two people fits nobody. A task that cannot be placed stays with the
 * person who gave it; it is never guessed onto someone.
 */
public final class StaffAssignees {
    private static final int EXACT = 3;
    private static final int STEM = 2;
    private static final int ROLE = 1;

    private StaffAssignees() { }

    /** The one member the words point at, or null when none or several do. */
    public static StaffPortalService.Member resolve(List<StaffPortalService.Member> members, String spoken) {
        List<String> words = words(spoken);
        if (words.isEmpty() || members == null) return null;
        StaffPortalService.Member best = null;
        int bestScore = 0;
        boolean tie = false;
        for (StaffPortalService.Member member : members) {
            if (member == null || !member.active() || !"OPEN".equals(member.shift())) continue;
            int score = score(member, words);
            if (score == 0) continue;
            if (score > bestScore) {
                best = member;
                bestScore = score;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** Every spoken word adds its best match, so «Анне Сидоровой» outweighs the other Анна. */
    private static int score(StaffPortalService.Member member, List<String> words) {
        int score = 0;
        List<String> name = words(member.displayName());
        String id = member.staffId() == null ? "" : member.staffId().toLowerCase(Locale.ROOT);
        for (String word : words) {
            int best = word.equals(id) ? EXACT : 0;
            for (String part : name) {
                if (word.equals(part)) best = Math.max(best, EXACT);
                else if (stems(word, part)) best = Math.max(best, STEM);
            }
            StaffScope.Role role = role(word);
            if (role != null && role.name().equals(member.role())) best = Math.max(best, ROLE);
            score += best;
        }
        return score;
    }

    /**
     * «Анне» and «Анна» are one name in two cases: the same stem, and only a short vowel ending differs.
     * «Марии» is not «Марина»: what follows the common part has a consonant in it.
     */
    static boolean stems(String a, String b) {
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) common++;
        if (common < 3 || common < Math.min(a.length(), b.length()) - 2) return false;
        return ending(a.substring(common)) && ending(b.substring(common));
    }

    private static boolean ending(String suffix) {
        if (suffix.length() > 2) return false;
        for (int i = 0; i < suffix.length(); i++) if ("аеёиоуыэюяй".indexOf(suffix.charAt(i)) < 0) return false;
        return true;
    }

    static StaffScope.Role role(String word) {
        if (word.startsWith("официант")) return StaffScope.Role.WAITER;
        if (word.startsWith("хостес")) return StaffScope.Role.HOSTESS;
        if (word.startsWith("менеджер") || word.startsWith("управляющ")) return StaffScope.Role.MANAGER;
        return null;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) return words;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}-]+")) {
            if (word.length() >= 2) words.add(word);
        }
        return words;
    }
}
