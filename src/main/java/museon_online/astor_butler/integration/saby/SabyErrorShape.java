package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a Saby answer looked like, safe for the log: the names of its fields and the values of code-like fields only
 * («error.code=-32000», «status=500»). Free text («message», «details», names, phones, comments) is never copied, only
 * its length is told, so a failure explains itself without personal data reaching the log (16.10, PUT order/update 500).
 */
final class SabyErrorShape {

    /** Fields whose short, plain value says which error it is. Anything else is named, never quoted. */
    private static final Set<String> CODE_FIELDS = Set.of("code", "errorcode", "error_code", "errcode", "status", "type",
            "kind", "classid", "state", "productstate", "paystate", "wotable");
    /** A code is a short token of Latin letters, digits and signs: a Cyrillic name or a phone never matches. */
    private static final Pattern CODE_VALUE = Pattern.compile("-?[A-Za-z0-9_.:-]{1,40}");
    private static final Pattern DIGITS = Pattern.compile("\\d{5,}");
    private static final int MAX_FIELDS = 40;
    private static final int MAX_DEPTH = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SabyErrorShape() {
    }

    /** «fields=[error, error.code, error.message] codes={error.code=-32000} textChars={error.message=57}»; never null. */
    static String of(String body) {
        if (body == null || body.isBlank()) {
            return "empty body";
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (Exception e) {
            return "non-JSON body, " + body.length() + " chars";
        }
        return of(json);
    }

    static String of(JsonNode json) {
        if (json == null || json.isMissingNode() || json.isNull()) {
            return "empty body";
        }
        if (!json.isContainerNode()) {
            return "scalar body";
        }
        List<String> fields = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        walk(json, "", 0, fields, codes, texts);
        return "fields=" + fields + " codes={" + String.join(", ", codes) + "} textChars={" + String.join(", ", texts) + "}";
    }

    /** The field names only, one level down: what the booking Saby returned holds, with no value at all. */
    static List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        if (json != null && json.isObject()) {
            json.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static void walk(JsonNode node, String path, int depth, List<String> fields, List<String> codes, List<String> texts) {
        if (node.isArray()) {
            if (!node.isEmpty() && depth < MAX_DEPTH) {
                walk(node.get(0), path + "[]", depth + 1, fields, codes, texts);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
        while (entries.hasNext() && fields.size() < MAX_FIELDS) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String name = safeName(entry.getKey());
            String child = path.isEmpty() ? name : path + "." + name;
            fields.add(child);
            JsonNode value = entry.getValue();
            if (value.isContainerNode()) {
                if (depth < MAX_DEPTH) {
                    walk(value, child, depth + 1, fields, codes, texts);
                }
            } else if (isCode(entry.getKey(), value)) {
                codes.add(child + "=" + value.asText());
            } else if (value.isTextual()) {
                texts.add(child + "=" + value.asText().length());
            }
        }
    }

    private static boolean isCode(String name, JsonNode value) {
        if (!CODE_FIELDS.contains(name.toLowerCase())) {
            return false;
        }
        if (value.isNumber() || value.isBoolean()) {
            return true;
        }
        String text = value.asText();
        return value.isTextual() && CODE_VALUE.matcher(text).matches() && !DIGITS.matcher(text).find();
    }

    /** A field name is the contract's, but a map keyed by a phone or a name must not leak through its keys either. */
    private static String safeName(String name) {
        return CODE_VALUE.matcher(name).matches() && !DIGITS.matcher(name).find() ? name : "?";
    }
}
