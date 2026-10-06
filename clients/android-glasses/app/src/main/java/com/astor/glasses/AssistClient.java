package com.astor.glasses;

import android.os.Handler;
import android.os.Looper;

import com.astor.glasses.core.Assist;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One request at a time to the Astor backend. Redirects are never followed, nothing is cached, and
 * neither the token nor the content of questions and answers is written to any log.
 */
final class AssistClient {

    interface Callback {
        /** status 0 means the connection failed; reply is null unless the body was a JSON object. */
        void done(int status, JSONObject reply);
    }

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;

    /** Drops the answer of the request in flight: its callback will not be called. */
    void cancel() {
        generation++;
    }

    void send(URI url, String token, String jsonBody, Callback callback) {
        int mine = ++generation;
        network.execute(() -> {
            int status = 0;
            JSONObject reply = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Accept", "application/json");
                if (jsonBody != null) {
                    byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    connection.getOutputStream().write(body);
                }
                status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                reply = stream == null ? null : parse(stream);
            } catch (Exception e) {
                if (status == 0) reply = null;
            } finally {
                if (connection != null) connection.disconnect();
            }
            int finalStatus = status;
            JSONObject finalReply = reply;
            main.post(() -> {
                if (mine == generation) callback.done(finalStatus, finalReply);
            });
        });
    }

    private static JSONObject parse(InputStream stream) {
        try (InputStream in = stream) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int read; (read = in.read(chunk)) > 0; ) {
                if (bytes.size() + read > Assist.MAX_REPLY_BYTES) return null;
                bytes.write(chunk, 0, read);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
