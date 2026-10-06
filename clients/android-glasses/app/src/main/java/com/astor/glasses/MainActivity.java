package com.astor.glasses;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.astor.glasses.core.Assist;
import com.astor.glasses.core.LunchGuide;
import com.astor.glasses.core.Policies;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * The staff screen: ask Astor by text or voice, hear the answer, walk through the training lunch.
 *
 * Mirrors the request flow of the iPhone client. A reply is accepted only for the request that is
 * current and for the training step it was sent from; a late or foreign reply changes nothing.
 * Questions, answers and the token stay out of the on-screen log.
 */
public final class MainActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 7;
    private static final long PENDING_PHOTO_MS = 110_000;

    private final LunchGuide lunch = new LunchGuide();
    private final AssistClient client = new AssistClient();
    private final Photos photos = new Photos();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TokenStore tokens;
    private VoiceRecorder voice;
    private AudioManager audio;
    private SharedPreferences prefs;
    private TextToSpeech speech;
    private boolean speechReady;
    private MediaPlayer answerAudio;
    private AudioManager.OnModeChangedListener callWatcher;

    private boolean busy;
    private String lastAnswer;
    // A training photo that reached the phone but not yet the server: it can be re-sent without a new capture.
    private byte[] pendingJpeg;
    private LunchGuide.PhotoContext pendingContext, captureContext;
    private String pendingRequestId;

    private EditText endpoint, question;
    private TextView status, answer, lunchBrief, lunchPhoto, logView;
    private Button voiceButton, lunchStart, lunchNext, lunchShoot, lunchRetry, lunchStop;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        tokens = new TokenStore(this);
        voice = new VoiceRecorder(this);
        audio = getSystemService(AudioManager.class);
        prefs = getSharedPreferences("astor-settings", MODE_PRIVATE);
        speech = new TextToSpeech(this, result -> {
            speechReady = result == TextToSpeech.SUCCESS
                    && speech.setLanguage(new Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE;
            if (!speechReady) log("Русский голос для озвучки на телефоне не найден. Ответы будут только текстом.");
        });
        // A real call takes the headset away from us at once; our own recording is not a call.
        callWatcher = mode -> {
            if (callActive() && voice.recording()) {
                voice.cancel();
                refresh();
                log("Звонок: запись вопроса остановлена.");
            }
        };
        audio.addOnModeChangedListener(getMainExecutor(), callWatcher);
        buildUi();
        refresh();
    }

    /* ---------- Screen ---------- */

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setInputType(inputType);
        view.setTextColor(Color.WHITE);
        view.setHintTextColor(0xFF7C8794);
        return view;
    }

    private LinearLayout card(String title, View... items) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF10141A);
        background.setCornerRadius(dp(14));
        box.setBackground(background);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(label(title, 13, 0xFF9AA5B1));
        for (View item : items) box.addView(item);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        box.setLayoutParams(params);
        return box;
    }

    private LinearLayout row(View... items) {
        LinearLayout line = new LinearLayout(this);
        for (View item : items) line.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return line;
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(20), dp(16), dp(24));
        page.addView(label("Astor Glasses", 24, Color.WHITE));
        status = label("", 14, 0xFFE3E8ED);
        page.addView(status);

        endpoint = field("https://адрес сервера", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(prefs.getString("backendURL", ""));
        page.addView(card("Сервер", endpoint, row(button("Сохранить адрес", this::saveEndpoint), button("Токен", this::askToken)),
                button("Проверить связь", this::checkServer)));

        question = field("Вопрос для Astor", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        voiceButton = button("Голос", this::toggleVoice);
        answer = label("", 16, Color.WHITE);
        page.addView(card("Вопрос", question, row(button("Спросить", this::ask), voiceButton), answer,
                button("Повторить ответ", this::repeatAnswer)));

        lunchBrief = label("", 15, Color.WHITE);
        lunchPhoto = label("", 13, 0xFF9AA5B1);
        lunchStart = button("Начать тренировку", this::lunchStart);
        lunchShoot = button("Фото", this::lunchPhoto);
        lunchNext = button("Дальше", this::lunchNext);
        lunchRetry = button("Отправить фото ещё раз", this::lunchRetry);
        lunchStop = button("Завершить", this::lunchStop);
        page.addView(card("Учебный бизнес-ланч", lunchBrief, lunchPhoto, lunchStart, row(lunchShoot, lunchNext), lunchRetry, lunchStop));

        page.addView(card("Очки", label("Для Android очки сейчас работают как Bluetooth-гарнитура: микрофон и динамик. "
                + "Камера очков, жесты, датчик надевания и заряд требуют SDK производителя под Android, его пока нет. "
                + "Поэтому фото в тренировке снимает камера телефона.", 13, 0xFFE3E8ED)));

        logView = label("", 12, 0xFF9AA5B1);
        page.addView(card("Журнал", logView));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF080A0D);
        scroll.addView(page);
        setContentView(scroll);
    }

    private void log(String message) {
        lines.addFirst(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message);
        while (lines.size() > 40) lines.removeLast();
        logView.setText(String.join("\n", lines));
    }

    private void refresh() {
        AudioDeviceInfo headset = voice.headset();
        String device = headset == null ? "гарнитура не подключена"
                : VoiceRecorder.isGlasses(headset) ? "очки подключены как гарнитура" : "гарнитура: " + headset.getProductName();
        status.setText((busy ? "Жду ответ Astor" : voice.recording() ? "Слушаю" : "Готов") + " · " + device);
        voiceButton.setText(voice.recording() ? "Готово" : "Голос");

        lunchBrief.setText(lunch.brief());
        lunchPhoto.setText(lunch.active() || lunch.finished() ? lunch.photoStatus() : "");
        lunchStart.setVisibility(lunch.active() ? View.GONE : View.VISIBLE);
        for (Button button : new Button[]{lunchShoot, lunchNext, lunchStop}) button.setVisibility(lunch.active() ? View.VISIBLE : View.GONE);
        lunchNext.setEnabled(lunch.canAdvance());
        lunchRetry.setVisibility(pendingJpeg != null && lunch.acceptsPhotoContext(pendingContext) ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(question.getWindowToken(), 0);
    }

    /* ---------- Server ---------- */

    private void saveEndpoint() {
        prefs.edit().putString("backendURL", endpoint.getText().toString().trim()).apply();
        hideKeyboard();
        log("Адрес сохранён.");
    }

    private void askToken() {
        EditText input = field("Токен доступа", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(Color.BLACK);
        new AlertDialog.Builder(this).setTitle("Доступ к Astor")
                .setMessage("Токен тестового API от команды backend. Хранится на этом телефоне в зашифрованном виде.")
                .setView(input).setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    log(!tokens.save(value) ? "Ошибка сохранения токена." : value.isEmpty() ? "Токен удалён." : "Токен сохранён.");
                }).show();
    }

    /** The address for a backend path, or null with the reason in the log when the settings are not usable. */
    private URI url(String path) {
        if (tokens.load() == null) {
            log("Сохраните токен доступа.");
            return null;
        }
        try {
            return Assist.endpoint(endpoint.getText().toString(), path, BuildConfig.DEBUG);
        } catch (Assist.Refused refused) {
            log(refused.getMessage());
            return null;
        }
    }

    private void checkServer() {
        URI url = url("/api/glasses/capabilities");
        if (url == null || busy) return;
        hideKeyboard();
        busy = true;
        refresh();
        client.send(url, tokens.load(), null, (code, reply) -> {
            busy = false;
            refresh();
            if (code == 200 && reply != null) {
                log("Сервер отвечает. Текст: " + yes(reply, "text") + ", голос: " + yes(reply, "voice") + ", фото: " + yes(reply, "vision") + ".");
            } else {
                log(code == 0 ? "Нет связи с сервером." : code == 401 || code == 403 ? "Сервер не принял токен: HTTP " + code + "." : "Сервер ответил HTTP " + code + ".");
            }
        });
    }

    private static String yes(JSONObject reply, String key) {
        return reply.optBoolean(key) ? "да" : "нет";
    }

    private boolean callActive() {
        int mode = audio.getMode();
        return Policies.callShouldInterrupt(mode == AudioManager.MODE_IN_CALL, mode == AudioManager.MODE_RINGTONE, false, voice.recording());
    }

    private void ask() {
        hideKeyboard();
        send(question.getText().toString().trim(), null, null, null, Assist.newRequestId());
    }

    private void send(String text, byte[] jpeg, byte[] aac, LunchGuide.PhotoContext context, String requestId) {
        if (callActive()) { log("Дождитесь окончания звонка перед запросом."); return; }
        if (busy) { log("Запрос уже выполняется."); return; }
        if (context != null && !lunch.acceptsPhotoContext(context)) { log("Шаг изменился; фото не отправлено."); return; }
        URI url = url("/api/glasses/assist");
        if (url == null) return;
        String body;
        try {
            body = Assist.body(requestId, text, jpeg, aac, context == null ? null : context.wire());
        } catch (Assist.Refused refused) {
            log(refused.getMessage());
            return;
        }
        busy = true;
        refresh();
        log("Отправляю запрос Astor…");
        client.send(url, tokens.load(), body, (code, reply) -> {
            busy = false;
            refresh();
            if (context != null && !lunch.acceptsPhotoContext(context)) { log("Ответ прежнего шага не принят."); return; }
            if (code == 0) {
                log(context != null ? "Ошибка соединения. Можно отправить это фото ещё раз без новой съёмки." : "Ошибка соединения с Astor.");
                return;
            }
            if (code != 200) { log("Astor: HTTP " + code + "." + (context != null ? " Фото шага не подтверждено." : "")); return; }
            String text1 = Assist.answer(reply, requestId);
            if (text1 == null) { log("Ответ не соответствует запросу или согласованному контракту."); return; }
            if (context != null) {
                if (!lunch.acceptPhotoReceipt(reply.optJSONObject("photoReceipt"), context, requestId)) {
                    log("Нет подтверждения сохранения фото для этого шага. Переход остаётся закрыт.");
                    return;
                }
                clearPendingPhoto();
                log("Фото текущего учебного шага сохранено сервером. Всего шагов с фото: " + lunch.photoCount() + ".");
            }
            lastAnswer = text1;
            answer.setText(text1);
            refresh();
            byte[] prepared = Assist.speech(reply);
            if (prepared == null || !play(prepared)) speak(text1);
        });
    }

    /* ---------- Voice ---------- */

    private void toggleVoice() {
        if (voice.recording()) { voice.finish(); return; }
        if (busy) { log("Дождитесь ответа на предыдущий запрос."); return; }
        if (callActive()) { log("Во время звонка запись не запускается."); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            return;
        }
        AudioDeviceInfo headset = voice.headset();
        if (headset == null && !BuildConfig.DEBUG) {
            log("Подключите очки как Bluetooth-гарнитуру: микрофон гарнитуры недоступен.");
            return;
        }
        stopSpeaking();
        if (!voice.start(headset, this::voiceFinished)) { log("Микрофон не готов к записи."); return; }
        log(headset == null ? "Отладочная сборка: слушаю микрофон телефона." : "Слушаю. Запись закончится сама после паузы.");
        refresh();
    }

    private void voiceFinished(byte[] aac) {
        refresh();
        if (aac == null) { log("Запись пуста или слишком велика."); return; }
        log("Голосовая запись получена; временный файл удалён.");
        send("", null, aac, null, Assist.newRequestId());
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != MICROPHONE_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleVoice();
        else log("Разрешите микрофон в настройках приложения.");
    }

    private void repeatAnswer() {
        if (lastAnswer == null) log("Ответов пока нет.");
        else speak(lastAnswer);
    }

    private void speak(String text) {
        if (speechReady) speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    private boolean play(byte[] audioBytes) {
        try {
            File file = new File(getCacheDir(), "answer-audio");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(audioBytes); }
            stopSpeaking();
            answerAudio = new MediaPlayer();
            answerAudio.setDataSource(file.getPath());
            answerAudio.setOnCompletionListener(player -> file.delete());
            answerAudio.prepare();
            answerAudio.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopSpeaking() {
        speech.stop();
        if (answerAudio != null) {
            answerAudio.release();
            answerAudio = null;
        }
    }

    /* ---------- Training ---------- */

    private void clearPendingPhoto() {
        pendingJpeg = null;
        pendingContext = null;
        pendingRequestId = null;
    }

    private void lunchStart() {
        if (busy || voice.recording()) return;
        clearPendingPhoto();
        lunch.start();
        refresh();
        speak(lunch.brief());
    }

    private void lunchNext() {
        if (busy || voice.recording()) return;
        if (!lunch.advance()) { log("Сначала получите подтверждение сохранения фото этого шага."); return; }
        clearPendingPhoto();
        refresh();
        speak(lunch.brief());
    }

    private void lunchStop() {
        client.cancel();
        busy = false;
        clearPendingPhoto();
        lunch.stop();
        refresh();
    }

    private void lunchPhoto() {
        if (busy || voice.recording()) { log("Дождитесь запроса или отмените его."); return; }
        if (callActive()) { log("Во время звонка фото не запускается."); return; }
        if (url("/api/glasses/assist") == null) return;
        captureContext = lunch.photoContext();
        if (captureContext == null) return;
        clearPendingPhoto();
        if (!photos.capture(this)) {
            captureContext = null;
            log("Камера телефона недоступна.");
        } else {
            log("Снимаю один кадр камерой телефона.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != Photos.REQUEST) return;
        LunchGuide.PhotoContext context = captureContext;
        captureContext = null;
        byte[] jpeg = photos.result(result);
        if (jpeg == null) { log("Фото не получено."); return; }
        if (!lunch.acceptsPhotoContext(context)) { log("Учебный шаг изменился. Кадр не отправлен."); return; }
        String requestId = Assist.newRequestId();
        pendingJpeg = jpeg;
        pendingContext = context;
        pendingRequestId = requestId;
        // The server keeps a finished answer for a retry for 120 seconds; after that the same bytes are a new request.
        main.postDelayed(() -> {
            if (requestId.equals(pendingRequestId)) {
                clearPendingPhoto();
                refresh();
            }
        }, PENDING_PHOTO_MS);
        refresh();
        send(context.prompt, jpeg, null, context, requestId);
    }

    private void lunchRetry() {
        if (pendingJpeg == null || !lunch.acceptsPhotoContext(pendingContext)) { clearPendingPhoto(); refresh(); return; }
        // Same bytes, same request id, same context: the server answers from its cache instead of asking the model again.
        send(pendingContext.prompt, pendingJpeg, null, pendingContext, pendingRequestId);
    }

    /* ---------- Lifecycle ---------- */

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        // The camera application pauses us too; a recording is the only thing that must not outlive the screen.
        if (voice.recording()) {
            voice.cancel();
            log("Запись остановлена: приложение свернуто.");
        }
    }

    @Override protected void onDestroy() {
        client.cancel();
        photos.discard();
        audio.removeOnModeChangedListener(callWatcher);
        stopSpeaking();
        speech.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
