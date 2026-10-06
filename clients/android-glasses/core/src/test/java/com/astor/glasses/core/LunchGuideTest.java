package com.astor.glasses.core;

import org.json.JSONObject;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The same checks as tests/lunch-guide.m of the iPhone client. */
public class LunchGuideTest {

    private static JSONObject receipt(String requestId, boolean archived, JSONObject context) throws Exception {
        return new JSONObject().put("requestId", requestId).put("archived", archived).put("context", context);
    }

    @Test public void trainingStartsOnlyExplicitlyAndInventsNoPortalIdentifiers() throws Exception {
        LunchGuide guide = new LunchGuide();
        assertFalse(guide.active());
        assertFalse(guide.advance());
        assertNull(guide.photoContext());

        guide.start();
        LunchGuide.PhotoContext first = guide.photoContext();
        assertTrue(guide.acceptsPhotoContext(first));
        assertTrue(guide.brief().startsWith("Учебный"));
        JSONObject wire = first.wire();
        assertEquals(4, wire.length());
        assertFalse(wire.has("taskId") || wire.has("tableId") || wire.has("stageId"));
        assertEquals("BUSINESS_LUNCH_TWO", wire.getString("scenarioCode"));
    }

    @Test public void lateCaptureCannotBelongToANewStepOrANewSession() throws Exception {
        LunchGuide guide = new LunchGuide();
        guide.start();
        LunchGuide.PhotoContext first = guide.photoContext();
        assertTrue(guide.advance());
        assertFalse(guide.acceptsPhotoContext(first));
        LunchGuide.PhotoContext second = guide.photoContext();
        assertTrue(guide.acceptsPhotoContext(second));

        guide.stop();
        guide.start();
        assertFalse("restart invalidates even a capture of the same step index", guide.acceptsPhotoContext(first));
        assertFalse(guide.acceptsPhotoContext(second));
        assertFalse(guide.acceptsPhotoContext(null));
    }

    @Test public void requiredPhotoStepsOpenOnlyOnAMatchingStoredReceipt() throws Exception {
        LunchGuide guide = new LunchGuide();
        guide.start();
        LunchGuide.PhotoContext stale = guide.photoContext();
        for (int i = 0; i < LunchGuide.STEPS.size(); i++) {
            assertTrue(guide.active());
            assertEquals(i, guide.stepIndex());
            assertTrue(guide.brief().startsWith("Учебный"));
            assertTrue(guide.photoContext().prompt.length() < Assist.MAX_TEXT);
            if (guide.photoRequired()) {
                LunchGuide.PhotoContext context = guide.photoContext();
                String request = UUID.randomUUID().toString().toUpperCase();
                assertFalse(guide.canAdvance());
                assertFalse("no manual advance before a server receipt", guide.advance());
                JSONObject good = receipt(request.toLowerCase(), true, context.wire());
                assertFalse("a previous step cannot accept a late receipt", guide.acceptPhotoReceipt(good, stale, request));
                assertFalse("receipt must match this upload", guide.acceptPhotoReceipt(good, context, UUID.randomUUID().toString()));
                assertFalse("analysis without storage closes nothing",
                        guide.acceptPhotoReceipt(receipt(request, false, context.wire()), context, request));
                assertFalse("receipt step and session must match exactly",
                        guide.acceptPhotoReceipt(receipt(request, true, new JSONObject()), context, request));
                assertFalse(guide.acceptPhotoReceipt(receipt(request, true, context.wire().put("revision", context.revision + 1)), context, request));
                assertFalse(guide.acceptPhotoReceipt(new JSONObject().put("requestId", request).put("archived", "true")
                        .put("context", context.wire()), context, request));
                assertFalse(guide.acceptPhotoReceipt(null, context, request));
                assertTrue(guide.acceptPhotoReceipt(good, context, request));
                assertTrue(guide.canAdvance());
            }
            assertTrue(guide.advance());
        }
        assertEquals("two required photo checkpoints are collected separately", 2, guide.photoCount());
        assertTrue(guide.finished());
        assertFalse(guide.active());
        assertNull(guide.photoContext());
        assertFalse(guide.advance());
        guide.stop();
        assertFalse(guide.finished());
        assertEquals(0, guide.photoCount());
    }

    @Test public void briefsAlwaysSayThatThisIsTraining() throws Exception {
        LunchGuide guide = new LunchGuide();
        assertTrue(guide.brief().startsWith("Учебный"));
        assertEquals("Начните учебный показ на телефоне.", guide.compactBrief());
        guide.start();
        assertEquals("Учебный шаг 1: подготовьте чистый стол на двоих.", guide.compactBrief());
        assertEquals("Фото необязательно; снимите, если нужна подсказка.", guide.photoStatus());
        guide.advance();
        assertEquals("Нужно фото этого шага. Переход откроется после подтверждения сохранения.", guide.photoStatus());
    }
}
