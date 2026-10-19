package museon_online.astor_butler.domain.roster;

import java.util.Locale;

/** The roles of the staff directory ({@code astor_staff_members.role}) and how a schedule or a person names them. */
public enum StaffRole {
    HOSTESS("хостес", "хостес"),
    MANAGER("менеджер", "менеджеры"),
    WAITER("официант", "официанты");

    private final String one;
    private final String many;

    StaffRole(String one, String many) {
        this.one = one;
        this.many = many;
    }

    public String label() {
        return one;
    }

    public String label(int count) {
        return count == 1 ? one : many;
    }

    /** The directory's own value; null for anything else. */
    public static StaffRole of(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A role word from a schedule. «Администратор зала» is the hostess, a plain «администратор» the shift manager, as
     * AERIS names them. Null for a blank word and for a role the directory does not have (бармен, повар): see
     * {@link #isOtherRole(String)}.
     */
    public static StaffRole parse(String word) {
        String text = normalize(word);
        if (text.isEmpty()) {
            return null;
        }
        StaffRole own = of(text);
        if (own != null) {
            return own;
        }
        if (text.contains("хостес") || text.contains("hostess") || text.contains("host")
                || text.contains("администратор зала") || text.contains("встреч")) {
            return HOSTESS;
        }
        if (text.contains("официант") || text.contains("waiter") || text.contains("server") || text.contains("раннер")) {
            return WAITER;
        }
        if (text.contains("менеджер") || text.contains("manager") || text.contains("управляющ")
                || text.contains("администратор") || text.contains("старш") || text.contains("admin")) {
            return MANAGER;
        }
        return null;
    }

    /** A role was named, and it is not one of ours (бармен, повар, кухня): such a row is not kept. */
    public static boolean isOtherRole(String word) {
        return !normalize(word).isEmpty() && parse(word) == null;
    }

    private static String normalize(String word) {
        return word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }
}
