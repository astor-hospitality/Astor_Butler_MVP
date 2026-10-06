package com.astor.glasses.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Base64;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AssistTest {
    private static final String PATH = "/api/glasses/assist";

    @Test public void endpointIsHttpsOnlyWithNothingExtra() throws Exception {
        assertEquals("https://c3ag.ru/api/glasses/assist", Assist.endpoint(" https://c3ag.ru ", PATH, false).toString());
        assertEquals("https://c3ag.ru/api/glasses/assist", Assist.endpoint("https://c3ag.ru/", PATH, false).toString());
        assertEquals("https://host.example:8443/astor/api/glasses/assist", Assist.endpoint("HTTPS://host.example:8443/astor//", PATH, false).toString());
        for (String bad : new String[]{"", null, "c3ag.ru", "http://c3ag.ru", "ftp://c3ag.ru", "https://user:pass@c3ag.ru", "https://c3ag.ru?x=1",
                "https://c3ag.ru/#frag", "https://", "http://10.0.2.2:8091", "not a url"}) {
            assertThrows(String.valueOf(bad), Assist.Refused.class, () -> Assist.endpoint(bad, PATH, false));
        }
        // A debug build may talk to a stand-in server on the developer's own computer, and to nothing else.
        assertEquals("http://10.0.2.2:8091/api/glasses/assist", Assist.endpoint("http://10.0.2.2:8091", PATH, true).toString());
        assertThrows(Assist.Refused.class, () -> Assist.endpoint("http://c3ag.ru", PATH, true));
        assertThrows(Assist.Refused.class, () -> Assist.endpoint("http://192.168.1.5:8091", PATH, true));
    }

    @Test public void bodyCarriesExactlyWhatTheContractAllows() throws Exception {
        String id = Assist.newRequestId();
        JSONObject text = new JSONObject(Assist.body(id, "Что по плану?", null, null, null));
        assertEquals(id, text.getString("requestId"));
        assertEquals("Что по плану?", text.getString("text"));
        assertEquals(2, text.length());

        byte[] media = {1, 2, 3};
        JSONObject voice = new JSONObject(Assist.body(id, "", null, media, null));
        assertEquals("audio/mp4", voice.getString("audioMimeType"));
        assertArrayEquals(media, Base64.getDecoder().decode(voice.getString("audioBase64")));
        assertFalse(voice.has("imageBase64"));

        JSONObject wire = new JSONObject().put("sessionId", id).put("scenarioCode", "BUSINESS_LUNCH_TWO").put("stageCode", "PLACE_SETTINGS").put("revision", 2);
        JSONObject photo = new JSONObject(Assist.body(id, "prompt", media, null, wire));
        assertEquals("image/jpeg", photo.getString("imageMimeType"));
        assertEquals(wire.toString(), photo.getJSONObject("photoContext").toString());

        assertThrows(Assist.Refused.class, () -> Assist.body(id, "  ", null, null, null));
        assertThrows(Assist.Refused.class, () -> Assist.body("not-a-uuid", "вопрос", null, null, null));
        assertThrows(Assist.Refused.class, () -> Assist.body(id, "x".repeat(Assist.MAX_TEXT + 1), null, null, null));
        assertThrows(Assist.Refused.class, () -> Assist.body(id, "", new byte[Assist.MAX_MEDIA_BYTES + 1], null, null));
        assertThrows(Assist.Refused.class, () -> Assist.body(id, "", null, new byte[0], null));
        assertThrows(Assist.Refused.class, () -> Assist.body(id, "", media, media, null));
    }

    @Test public void onlyTheReplyToThisRequestWithARealAnswerIsAccepted() throws Exception {
        String id = UUID.randomUUID().toString().toUpperCase(), other = UUID.randomUUID().toString();
        assertEquals("server canonical casing is accepted", "Ответ",
                Assist.answer(new JSONObject().put("requestId", id.toLowerCase()).put("text", "Ответ"), id));
        assertNull("an unrelated response cannot replace the current answer",
                Assist.answer(new JSONObject().put("requestId", other).put("text", "Чужой ответ"), id));
        JSONObject[] malformed = {new JSONObject(), new JSONObject().put("requestId", id).put("text", " \n"),
                new JSONObject().put("requestId", 42).put("text", "Ответ"), new JSONObject().put("requestId", id).put("text", 42),
                new JSONObject().put("requestId", "invalid").put("text", "Ответ"),
                new JSONObject().put("requestId", id).put("text", new JSONArray()),
                new JSONObject().put("requestId", id).put("text", "x".repeat(Assist.MAX_ANSWER_CHARS + 1))};
        for (JSONObject reply : malformed) assertNull(reply.toString().substring(0, Math.min(60, reply.toString().length())), Assist.answer(reply, id));
        assertNull(Assist.answer(null, id));
        assertNull(Assist.answer(new JSONObject().put("requestId", "1-1-1-1-1").put("text", "Ответ"), "1-1-1-1-1"));
    }

    @Test public void serverSpeechIsPlayedOnlyWhenItIsTheAgreedVoiceAndSize() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[]{9, 8, 7});
        JSONObject good = new JSONObject().put("audioBase64", encoded).put("audioMimeType", "audio/mpeg").put("audioVoiceGender", "male");
        assertArrayEquals(new byte[]{9, 8, 7}, Assist.speech(good));
        assertNull(Assist.speech(new JSONObject(good.toString()).put("audioVoiceGender", "female")));
        assertNull(Assist.speech(new JSONObject(good.toString()).put("audioMimeType", "audio/wav")));
        assertNull(Assist.speech(new JSONObject(good.toString()).put("audioBase64", "***")));
        assertNull(Assist.speech(new JSONObject(good.toString()).put("audioBase64", "")));
        assertNull(Assist.speech(new JSONObject()));
    }
}
