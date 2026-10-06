package com.astor.glasses.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The training business lunch for two guests. Local training progress only: this object never
 * represents a portal task or its acknowledgement.
 *
 * Same rules as AstorLunchGuide in the iPhone client: steps two and four need a photo that the server
 * confirmed as stored for exactly this session, step and request before the step can be left.
 */
public final class LunchGuide {

    public static final String SCENARIO = "BUSINESS_LUNCH_TWO";

    public static final class Step {
        public final String title, stageCode, hint, shortHint;

        Step(String title, String stageCode, String hint, String shortHint) {
            this.title = title;
            this.stageCode = stageCode;
            this.hint = hint;
            this.shortHint = shortHint;
        }
    }

    /** What a photo belongs to. A capture is accepted only while the guide is still at this exact point. */
    public static final class PhotoContext {
        public final String sessionId, stageCode, prompt;
        public final int revision, stepIndex;

        PhotoContext(String sessionId, String stageCode, int revision, int stepIndex, String prompt) {
            this.sessionId = sessionId;
            this.stageCode = stageCode;
            this.revision = revision;
            this.stepIndex = stepIndex;
            this.prompt = prompt;
        }

        /** The part that goes to the server and comes back in the receipt. No task or table ids are invented. */
        public JSONObject wire() {
            try {
                return new JSONObject().put("sessionId", sessionId).put("scenarioCode", SCENARIO)
                        .put("stageCode", stageCode).put("revision", revision);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        /** True when the server echoed exactly this context: the same four fields and nothing else. */
        boolean echoedBy(JSONObject echoed) {
            return echoed != null && echoed.length() == 4 && sessionId.equals(echoed.opt("sessionId"))
                    && SCENARIO.equals(echoed.opt("scenarioCode")) && stageCode.equals(echoed.opt("stageCode"))
                    && Integer.valueOf(revision).equals(echoed.opt("revision"));
        }
    }

    public static final List<Step> STEPS = List.of(
            new Step("Подготовить стол", "TABLE_PREPARE",
                    "Проверьте чистоту стола, два свободных места и удобный проход для гостей. Фото можно сделать при необходимости.",
                    "подготовьте чистый стол на двоих"),
            new Step("Накрыть на двоих", "PLACE_SETTINGS",
                    "Подготовьте два комплекта приборов, две салфетки и два бокала. Направьте очки на весь стол и сделайте фото для проверки.",
                    "положите два комплекта приборов, салфеток и бокалов. Затем снимите сервировку"),
            new Step("Вода и меню", "WATER_MENU",
                    "Подготовьте меню и воду по стандарту ресторана. Пожелания гостей уточните при встрече. Фото можно сделать при необходимости.",
                    "подготовьте воду и меню"),
            new Step("Проверить перед встречей", "FINAL_CHECK",
                    "Посмотрите на стол целиком: два места готовы, сервировка аккуратна. Снимите финальный вид стола; замечания Butler проверьте сами.",
                    "снимите финальный вид стола и проверьте замечания перед встречей гостей"));

    private boolean active, finished;
    private int stepIndex, revision;
    private String sessionId;
    private final Map<String, String> receipts = new HashMap<>();

    public boolean active() { return active; }
    public boolean finished() { return finished; }
    public int stepIndex() { return stepIndex; }
    public int revision() { return revision; }
    public String sessionId() { return sessionId; }
    public Step step() { return active ? STEPS.get(stepIndex) : null; }
    public int photoCount() { return receipts.size(); }
    public Map<String, String> receipts() { return Collections.unmodifiableMap(receipts); }

    public void start() {
        active = true;
        finished = false;
        stepIndex = 0;
        revision = 1;
        sessionId = UUID.randomUUID().toString();
        receipts.clear();
    }

    public boolean advance() {
        if (!canAdvance()) return false;
        if (stepIndex + 1 < STEPS.size()) {
            stepIndex++;
        } else {
            active = false;
            finished = true;
        }
        revision++;
        return true;
    }

    public void stop() {
        active = false;
        finished = false;
        stepIndex = 0;
        revision++;
        sessionId = null;
        receipts.clear();
    }

    public String brief() {
        if (finished) return "Учебный план пройден. Для реального обслуживания откройте поручение от Butler.";
        if (!active) return "Учебный бизнес-ланч на двоих ещё не начат. Откройте Astor Glasses и нажмите «Начать тренировку».";
        return String.format(Locale.ROOT, "Учебный бизнес-ланч. Два гостя. Шаг %d из %d: %s. %s",
                stepIndex + 1, STEPS.size(), step().title, step().hint);
    }

    public String compactBrief() {
        if (finished) return "Учебный показ завершён.";
        if (!active) return "Начните учебный показ на телефоне.";
        return String.format(Locale.ROOT, "Учебный шаг %d: %s.", stepIndex + 1, step().shortHint);
    }

    public PhotoContext photoContext() {
        if (!active) return null;
        String prompt = "Учебная сервировка бизнес-ланча для двух гостей. Текущий шаг: " + step().title + ". Подсказка: " + step().hint
                + ". Рассмотри только приложенное фото. Ответь кратко по-русски: что видно и что стоит проверить на этом шаге. "
                + "Если детали неразличимы, скажи об этом. Не определяй личность гостей или номер стола. "
                + "Не утверждай, что поручение выполнено или сервировка подтверждена: это подсказка для учебного сценария.";
        return new PhotoContext(sessionId, step().stageCode, revision, stepIndex, prompt);
    }

    public boolean acceptsPhotoContext(PhotoContext context) {
        return context != null && active && context.sessionId.equals(sessionId) && context.revision == revision
                && context.stepIndex == stepIndex && context.stageCode.equals(step().stageCode);
    }

    public boolean photoRequired() { return active && (stepIndex == 1 || stepIndex == 3); }
    public boolean photoReceived() { return active && receipts.containsKey(step().stageCode); }
    public boolean canAdvance() { return active && (!photoRequired() || photoReceived()); }

    public String photoStatus() {
        if (!active) return "Фото сохранено на сервере: " + photoCount();
        if (photoReceived()) return "Фото этого шага сохранено на сервере. Проверьте ответ и перейдите дальше.";
        return photoRequired() ? "Нужно фото этого шага. Переход откроется после подтверждения сохранения."
                : "Фото необязательно; снимите, если нужна подсказка.";
    }

    /**
     * A receipt closes the photo requirement only when the server says the photo is stored, for exactly
     * the context it was taken in and the request it was sent with. An answer alone is not a receipt.
     */
    public boolean acceptPhotoReceipt(JSONObject receipt, PhotoContext context, String requestId) {
        if (!acceptsPhotoContext(context) || receipt == null || !Boolean.TRUE.equals(receipt.opt("archived"))) return false;
        if (!context.echoedBy(receipt.optJSONObject("context"))) return false;
        UUID expected = Uuids.parse(requestId), received = Uuids.parse(receipt.opt("requestId"));
        if (expected == null || !expected.equals(received)) return false;
        receipts.put(step().stageCode, expected.toString());
        return true;
    }
}
