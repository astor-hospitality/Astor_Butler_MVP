package museon_online.astor_butler.capability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Reads tool arguments as the model sends them: strings or numbers. A value that cannot be read is a bad argument. */
public final class CapabilityArguments {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

    private CapabilityArguments() {
    }

    /** Thrown by the readers; tools answer {@link CapabilityResult#BAD_ARGUMENT}. */
    public static final class BadArgument extends RuntimeException {
        public BadArgument(String name) {
            super(name);
        }
    }

    public static String text(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public static LocalDate date(Map<String, Object> args, String name) {
        String text = text(args, name);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BadArgument(name);
        }
    }

    public static LocalTime time(Map<String, Object> args, String name) {
        String text = text(args, name);
        if (text == null) {
            return null;
        }
        try {
            return LocalTime.parse(text, TIME);
        } catch (DateTimeParseException e) {
            throw new BadArgument(name);
        }
    }

    public static Integer integer(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            if (number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new BadArgument(name);
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new BadArgument(name);
        }
    }
}
