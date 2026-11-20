/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Staging rules of the chunked {@code dcs replace type='schema'} (#662). */
public class DcsXmlChunkUploadTest
{
    private static final String HASH = "0123456789abcdef0123"; //$NON-NLS-1$
    private static final String OTHER_HASH = "fedcba9876543210fedc"; //$NON-NLS-1$
    private static final String PROJECT = "Project"; //$NON-NLS-1$
    private static final String TARGET = "Report.Sales"; //$NON-NLS-1$
    private static final String DOC = "<DataCompositionSchema>abcdefghij</DataCompositionSchema>"; //$NON-NLS-1$

    private final long[] now = {1_000L};
    private final AtomicInteger probes = new AtomicInteger();
    private final DcsXmlChunkUpload uploads = new DcsXmlChunkUpload(() -> now[0]);

    private DcsXmlChunkUpload.HashProbe probe(String hash)
    {
        return () -> {
            probes.incrementAndGet();
            return DcsXmlChunkUpload.ProbeResult.hash(hash);
        };
    }

    private static JsonObject chunk(String id, String text, int offset, int total)
    {
        JsonObject body = new JsonObject();
        body.addProperty("xmlChunk", text); //$NON-NLS-1$
        body.addProperty("offset", offset); //$NON-NLS-1$
        body.addProperty("totalChars", total); //$NON-NLS-1$
        body.addProperty("uploadId", id); //$NON-NLS-1$
        return body;
    }

    private static DcsXmlChunkUpload.Request request(JsonObject body)
    {
        return request(PROJECT, TARGET, HASH, body);
    }

    private static DcsXmlChunkUpload.Request request(String project, String target, String hash, JsonObject body)
    {
        return new DcsXmlChunkUpload.Request(project, target, "replace", "schema", false, hash, false, body); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private DcsXmlChunkUpload.Step send(String id, String text, int offset, int total)
    {
        return uploads.accept(request(chunk(id, text, offset, total)), probe(HASH));
    }

    private static String error(DcsXmlChunkUpload.Step step)
    {
        assertFalse(step.isComplete());
        assertTrue(step.response(), ToolResult.isErrorPayload(step.response()));
        return JsonParser.parseString(step.response()).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    /** Sends {@code doc} as consecutive chunks of {@code size} and returns the last step. */
    private DcsXmlChunkUpload.Step upload(String id, String doc, int size)
    {
        DcsXmlChunkUpload.Step step = null;
        for (int offset = 0; offset < doc.length(); offset += size)
        {
            step = send(id, doc.substring(offset, Math.min(doc.length(), offset + size)), offset, doc.length());
            if (offset + size < doc.length())
            {
                assertFalse("chunk at " + offset + " must not complete", step.isComplete()); //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse(step.response(), ToolResult.isErrorPayload(step.response()));
            }
        }
        return step;
    }

    @Test
    public void testConsecutiveChunksAssembleTheExactDocumentOnlyOnTheLastChunk()
    {
        DcsXmlChunkUpload.Step last = upload("u1", DOC, 7); //$NON-NLS-1$
        assertTrue(last.isComplete());
        assertEquals(DOC, last.xml());
        assertNull(last.response());
        assertEquals("the completed upload leaves staging", -1, uploads.receivedChars("u1")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testANonFinalChunkSaysItIsStagedNotWrittenAndNamesTheNextOffset()
    {
        DcsXmlChunkUpload.Step step = send("u2", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        assertFalse(step.isComplete());
        String text = step.response();
        assertFalse(text, ToolResult.isErrorPayload(text));
        assertTrue(text, text.contains("chunk staged, not written")); //$NON-NLS-1$
        assertTrue(text, text.contains("**Received:** `10` of `" + DOC.length() + "` characters")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("**Next offset:** `10`")); //$NON-NLS-1$
        assertTrue(text, text.contains("Nothing was written")); //$NON-NLS-1$
        assertEquals(10, uploads.receivedChars("u2")); //$NON-NLS-1$
    }

    @Test
    public void testASingleCompleteChunkCommitsWithoutAProbe()
    {
        DcsXmlChunkUpload.Step step = send("one", DOC, 0, DOC.length()); //$NON-NLS-1$
        assertTrue(step.isComplete());
        assertEquals(DOC, step.xml());
        assertEquals("the commit checks the hash itself", 0, probes.get()); //$NON-NLS-1$
    }

    @Test
    public void testTheFirstChunkIsCheckedAgainstTheCurrentHash()
    {
        String text = error(uploads.accept(request(chunk("h", "<a", 0, 5)), probe(OTHER_HASH))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("expectedHash '" + HASH + "' does not match current hash '" //$NON-NLS-1$ //$NON-NLS-2$
            + OTHER_HASH + "'")); //$NON-NLS-1$
        assertTrue(text, text.contains("body.offset=0")); //$NON-NLS-1$
        assertEquals(-1, uploads.receivedChars("h")); //$NON-NLS-1$
        assertEquals(1, probes.get());
    }

    @Test
    public void testOnlyTheFirstChunkIsProbed()
    {
        upload("p", DOC, 5); //$NON-NLS-1$
        assertEquals(1, probes.get());
    }

    @Test
    public void testAProbeErrorIsReturnedAndNothingIsStaged()
    {
        String readError = ToolResult.error("Project 'X' not found.").toJson(); //$NON-NLS-1$
        DcsXmlChunkUpload.Step step = uploads.accept(request(chunk("pe", "<a", 0, 5)), //$NON-NLS-1$ //$NON-NLS-2$
            () -> DcsXmlChunkUpload.ProbeResult.error(readError));
        assertEquals(readError, step.response());
        assertEquals(-1, uploads.receivedChars("pe")); //$NON-NLS-1$
    }

    @Test
    public void testProbeReadsTheHashHeaderOfAMarkdownGet()
    {
        DcsXmlChunkUpload.ProbeResult ok = DcsXmlChunkUpload.ProbeResult.fromRootRead(
            "**Hash:** `" + HASH + "`\n\n# DCS schema"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsXmlChunkUpload.Step step = uploads.accept(request(chunk("ph", "<a", 0, 5)), () -> ok); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(step.response(), ToolResult.isErrorPayload(step.response()));

        DcsXmlChunkUpload.ProbeResult unreadable = DcsXmlChunkUpload.ProbeResult.fromRootRead("# no hash"); //$NON-NLS-1$
        assertTrue(ToolResult.isErrorPayload(uploads.accept(request(chunk("pu", "<a", 0, 5)), //$NON-NLS-1$ //$NON-NLS-2$
            () -> unreadable).response()));
    }

    @Test
    public void testAGapOrOverlapIsRefusedAndDiscardsTheUpload()
    {
        for (int wrongOffset : new int[] {9, 11})
        {
            String id = "gap" + wrongOffset; //$NON-NLS-1$
            send(id, DOC.substring(0, 10), 0, DOC.length());
            String text = error(send(id, DOC.substring(wrongOffset, wrongOffset + 5), wrongOffset, DOC.length()));
            assertTrue(text, text.contains("body.offset=" + wrongOffset + " is not where upload '" + id + "' continues")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(text, text.contains("must start at body.offset=10")); //$NON-NLS-1$
            assertTrue(text, text.contains("was discarded; restart it at body.offset=0")); //$NON-NLS-1$
            assertEquals(-1, uploads.receivedChars(id));
            String after = error(send(id, DOC.substring(10, 15), 10, DOC.length()));
            assertTrue(after, after.contains("No chunked upload '" + id + "' is in progress")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testEveryBindingMismatchIsRefusedAndDiscardsTheUpload()
    {
        Map<String, DcsXmlChunkUpload.Request> mismatches = new HashMap<>();
        mismatches.put("projectName 'Other' differs from 'Project'", //$NON-NLS-1$
            request("Other", TARGET, HASH, chunk("b", DOC.substring(10, 15), 10, DOC.length()))); //$NON-NLS-1$ //$NON-NLS-2$
        mismatches.put("fqn 'Report.Other' differs from 'Report.Sales'", //$NON-NLS-1$
            request(PROJECT, "Report.Other", HASH, chunk("b", DOC.substring(10, 15), 10, DOC.length()))); //$NON-NLS-1$ //$NON-NLS-2$
        mismatches.put("expectedHash '" + OTHER_HASH + "' differs from '" + HASH + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            request(PROJECT, TARGET, OTHER_HASH, chunk("b", DOC.substring(10, 15), 10, DOC.length()))); //$NON-NLS-1$
        mismatches.put("body.totalChars=" + (DOC.length() + 1) + " differs from " + DOC.length(), //$NON-NLS-1$ //$NON-NLS-2$
            request(chunk("b", DOC.substring(10, 15), 10, DOC.length() + 1))); //$NON-NLS-1$
        for (Map.Entry<String, DcsXmlChunkUpload.Request> mismatch : mismatches.entrySet())
        {
            send("b", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
            String text = error(uploads.accept(mismatch.getValue(), probe(HASH)));
            assertTrue(text, text.contains(mismatch.getKey()));
            assertTrue(text, text.contains("Upload 'b' was discarded; restart it at body.offset=0. Nothing was written.")); //$NON-NLS-1$
            assertEquals(mismatch.getKey(), -1, uploads.receivedChars("b")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAChunkPastTotalCharsIsRefusedAndDiscardsTheUpload()
    {
        send("over", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        String text = error(send("over", DOC.substring(10) + "x", 10, DOC.length())); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("past body.totalChars=" + DOC.length())); //$NON-NLS-1$
        assertEquals(-1, uploads.receivedChars("over")); //$NON-NLS-1$
    }

    @Test
    public void testResendingTheFinalChunkAfterTheCommitIsRefusedNotReplayed()
    {
        assertTrue(upload("again", DOC, 20).isComplete()); //$NON-NLS-1$
        int lastOffset = (DOC.length() - 1) / 20 * 20;
        String text = error(send("again", DOC.substring(lastOffset), lastOffset, DOC.length())); //$NON-NLS-1$
        assertTrue(text, text.contains("No chunked upload 'again' is in progress")); //$NON-NLS-1$
        assertTrue(text, text.contains("re-run dcs action='get' first")); //$NON-NLS-1$
    }

    @Test
    public void testAnOffsetZeroChunkRestartsTheUpload()
    {
        send("r", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        send("r", DOC.substring(10, 20), 10, DOC.length()); //$NON-NLS-1$
        send("r", DOC.substring(0, 4), 0, DOC.length()); //$NON-NLS-1$
        assertEquals(4, uploads.receivedChars("r")); //$NON-NLS-1$
    }

    @Test
    public void testAnIdleUploadExpiresAndAFreshOneSurvivesTheSweep()
    {
        send("old", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        now[0] += TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES) - 1;
        send("young", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        assertEquals(Arrays.asList("old", "young"), uploads.uploadIds()); //$NON-NLS-1$ //$NON-NLS-2$
        now[0] += 1;
        assertEquals(List.of("young"), uploads.uploadIds()); //$NON-NLS-1$
        String text = error(send("old", DOC.substring(10, 15), 10, DOC.length())); //$NON-NLS-1$
        assertTrue(text, text.contains("expired after " + DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES + " idle minutes")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEachChunkRenewsTheIdleExpiry()
    {
        long step = TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES) - 1;
        send("renew", DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        now[0] += step;
        send("renew", DOC.substring(10, 20), 10, DOC.length()); //$NON-NLS-1$
        now[0] += step;
        assertEquals(20, uploads.receivedChars("renew")); //$NON-NLS-1$
    }

    @Test
    public void testTheNumberOfSimultaneousUploadsIsBounded()
    {
        for (int i = 0; i < DcsXmlChunkUpload.MAX_UPLOADS; i++)
        {
            send("slot" + i, DOC.substring(0, 10), 0, DOC.length()); //$NON-NLS-1$
        }
        String text = error(send("extra", DOC.substring(0, 10), 0, DOC.length())); //$NON-NLS-1$
        assertTrue(text, text.contains(DcsXmlChunkUpload.MAX_UPLOADS + " chunked uploads are already in progress")); //$NON-NLS-1$
        assertTrue(text, text.contains("slot0")); //$NON-NLS-1$
        assertEquals(-1, uploads.receivedChars("extra")); //$NON-NLS-1$
        // Restarting a staged id needs no new slot.
        assertFalse(ToolResult.isErrorPayload(send("slot0", DOC.substring(0, 3), 0, DOC.length()).response())); //$NON-NLS-1$
        // A slot frees when its upload expires.
        now[0] += TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES);
        assertFalse(ToolResult.isErrorPayload(send("extra", DOC.substring(0, 10), 0, DOC.length()).response())); //$NON-NLS-1$
    }

    @Test
    public void testTotalCharsIsBounded()
    {
        String text = error(send("big", "<", 0, DcsXmlChunkUpload.MAX_TOTAL_CHARS + 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("exceeds the chunked-upload limit of " + DcsXmlChunkUpload.MAX_TOTAL_CHARS)); //$NON-NLS-1$
        assertFalse(ToolResult.isErrorPayload(send("max", "<", 0, DcsXmlChunkUpload.MAX_TOTAL_CHARS).response())); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(4 * 1024 * 1024, DcsXmlChunkUpload.MAX_TOTAL_CHARS);
    }

    @Test
    public void testAChunkBoundaryInsideASurrogatePairReassemblesTheExactDocument()
    {
        String doc = "<a>x😀y</a>"; //$NON-NLS-1$
        int split = doc.indexOf('\uDE00');
        send("sur", doc.substring(0, split), 0, doc.length()); //$NON-NLS-1$
        DcsXmlChunkUpload.Step last = send("sur", doc.substring(split), split, doc.length()); //$NON-NLS-1$
        assertTrue(last.isComplete());
        assertEquals(doc, last.xml());
        // ...and the whole-document body the commit re-enters with carries the pair intact.
        String body = last.wholeDocumentParams(Map.of("body", "{}")).get("body"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(doc, JsonParser.parseString(body).getAsJsonObject().get("xml").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testWholeDocumentParamsReplaceOnlyTheBody()
    {
        DcsXmlChunkUpload.Step last = send("w", DOC, 0, DOC.length()); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("fqn", TARGET); //$NON-NLS-1$
        params.put("action", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("type", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", HASH); //$NON-NLS-1$
        params.put("body", chunk("w", DOC, 0, DOC.length()).toString()); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> whole = last.wholeDocumentParams(params);
        JsonObject body = JsonParser.parseString(whole.get("body")).getAsJsonObject(); //$NON-NLS-1$
        assertEquals(1, body.size());
        assertEquals(DOC, body.get("xml").getAsString()); //$NON-NLS-1$
        Map<String, String> rest = new HashMap<>(whole);
        rest.remove("body"); //$NON-NLS-1$
        Map<String, String> expected = new HashMap<>(params);
        expected.remove("body"); //$NON-NLS-1$
        assertEquals(expected, rest);
    }

    @Test
    public void testShapeRefusalsNameTheBadValueAndDiscardTheUpload()
    {
        Map<String, JsonObject> bodies = new HashMap<>();
        JsonObject both = chunk("s", "<a", 10, 50); //$NON-NLS-1$ //$NON-NLS-2$
        both.addProperty("xml", "<a/>"); //$NON-NLS-1$ //$NON-NLS-2$
        bodies.put("body.xmlChunk and body.xml are mutually exclusive", both); //$NON-NLS-1$
        JsonObject extra = chunk("s", "<a", 10, 50); //$NON-NLS-1$ //$NON-NLS-2$
        extra.addProperty("insertPosition", 1); //$NON-NLS-1$
        bodies.put("also contains: insertPosition", extra); //$NON-NLS-1$
        JsonObject empty = chunk("s", "", 10, 50); //$NON-NLS-1$ //$NON-NLS-2$
        bodies.put("body.xmlChunk '\"\"' must be a non-empty string", empty); //$NON-NLS-1$
        JsonObject fraction = chunk("s", "<a", 10, 50); //$NON-NLS-1$ //$NON-NLS-2$
        fraction.addProperty("offset", 10.5); //$NON-NLS-1$
        bodies.put("body.offset '10.5' must be a whole number >= 0", fraction); //$NON-NLS-1$
        JsonObject text = chunk("s", "<a", 10, 50); //$NON-NLS-1$ //$NON-NLS-2$
        text.addProperty("totalChars", "50"); //$NON-NLS-1$ //$NON-NLS-2$
        bodies.put("body.totalChars '\"50\"' must be a whole number >= 1", text); //$NON-NLS-1$
        for (Map.Entry<String, JsonObject> bad : bodies.entrySet())
        {
            send("s", DOC.substring(0, 10), 0, 50); //$NON-NLS-1$
            assertEquals(10, uploads.receivedChars("s")); //$NON-NLS-1$
            String message = error(uploads.accept(request(bad.getValue()), probe(HASH)));
            assertTrue(message, message.contains(bad.getKey()));
            assertTrue(message, message.contains("Upload 's' was discarded")); //$NON-NLS-1$
            assertEquals(bad.getKey(), -1, uploads.receivedChars("s")); //$NON-NLS-1$
        }
    }

    @Test
    public void testChunksAreOnlyForAReplaceOfABareSchemaRootWithAHash()
    {
        JsonObject body = chunk("t", "<a", 0, 5); //$NON-NLS-1$ //$NON-NLS-2$
        String wrongAction = error(uploads.accept(new DcsXmlChunkUpload.Request(PROJECT, TARGET, "upsert", "schema", //$NON-NLS-1$ //$NON-NLS-2$
            false, HASH, false, body), probe(HASH)));
        assertTrue(wrongAction, wrongAction.contains("body.xmlChunk is not allowed with action='upsert'")); //$NON-NLS-1$
        String wrongType = error(uploads.accept(new DcsXmlChunkUpload.Request(PROJECT, TARGET, "replace", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            false, HASH, false, body), probe(HASH)));
        assertTrue(wrongType, wrongType.contains("type='dataSet'")); //$NON-NLS-1$
        String pointer = error(uploads.accept(new DcsXmlChunkUpload.Request(PROJECT, TARGET + "#/dataSets", "replace", //$NON-NLS-1$ //$NON-NLS-2$
            "schema", true, HASH, false, body), probe(HASH))); //$NON-NLS-1$
        assertTrue(pointer, pointer.contains("bare root FQN")); //$NON-NLS-1$
        String paging = error(uploads.accept(new DcsXmlChunkUpload.Request(PROJECT, TARGET, "replace", "schema", //$NON-NLS-1$ //$NON-NLS-2$
            false, HASH, true, body), probe(HASH)));
        assertTrue(paging, paging.contains("goes in body.offset")); //$NON-NLS-1$
        String noHash = error(uploads.accept(request(PROJECT, TARGET, null, body), probe(HASH)));
        assertTrue(noHash, noHash.contains("expectedHash is required for a chunked replace of 'Report.Sales'")); //$NON-NLS-1$
        String badHash = error(uploads.accept(request(PROJECT, TARGET, "ABC", body), probe(HASH))); //$NON-NLS-1$
        assertTrue(badHash, badHash.contains("expectedHash 'ABC' is invalid")); //$NON-NLS-1$
        String badId = error(uploads.accept(request(chunk("has space", "<a", 0, 5)), probe(HASH))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(badId, badId.contains("body.uploadId '\"has space\"' is not a valid upload id")); //$NON-NLS-1$
        assertEquals("no refusal reached the probe", 0, probes.get()); //$NON-NLS-1$
    }

    @Test
    public void testIsChunkBodyRecognizesOnlyAnObjectWithXmlChunk()
    {
        assertTrue(DcsXmlChunkUpload.isChunkBody("{\"xmlChunk\":\"<a\"}")); //$NON-NLS-1$
        assertFalse(DcsXmlChunkUpload.isChunkBody("{\"xml\":\"<a/>\"}")); //$NON-NLS-1$
        assertFalse(DcsXmlChunkUpload.isChunkBody("[\"xmlChunk\"]")); //$NON-NLS-1$
        assertFalse(DcsXmlChunkUpload.isChunkBody("not json {")); //$NON-NLS-1$
        assertFalse(DcsXmlChunkUpload.isChunkBody(null));
    }
}
