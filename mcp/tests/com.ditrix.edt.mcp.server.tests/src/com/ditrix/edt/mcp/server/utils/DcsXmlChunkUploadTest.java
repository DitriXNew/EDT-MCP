/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern ISSUED = Pattern.compile("\\*\\*Upload id:\\*\\* `([^`]+)`"); //$NON-NLS-1$

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

    /** A chunk body; {@code id == null} omits uploadId, as a new first chunk does. */
    private static JsonObject chunk(String id, String text, int offset, int total)
    {
        JsonObject body = new JsonObject();
        body.addProperty("xmlChunk", text); //$NON-NLS-1$
        body.addProperty("offset", offset); //$NON-NLS-1$
        body.addProperty("totalChars", total); //$NON-NLS-1$
        if (id != null)
        {
            body.addProperty("uploadId", id); //$NON-NLS-1$
        }
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

    /** Starts a new upload with the first {@code length} characters of {@code DOC}; returns the issued id. */
    private String begin(int length)
    {
        return issued(send(null, DOC.substring(0, length), 0, DOC.length()));
    }

    private static String issued(DcsXmlChunkUpload.Step step)
    {
        assertFalse(step.response(), step.isComplete());
        assertFalse(step.response(), ToolResult.isErrorPayload(step.response()));
        Matcher matcher = ISSUED.matcher(step.response());
        assertTrue(step.response(), matcher.find());
        return matcher.group(1);
    }

    private static String error(DcsXmlChunkUpload.Step step)
    {
        assertFalse(step.isComplete());
        assertTrue(step.response(), ToolResult.isErrorPayload(step.response()));
        return JsonParser.parseString(step.response()).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    /** Sends {@code doc} as consecutive chunks of {@code size} and returns the last step. */
    private DcsXmlChunkUpload.Step upload(String doc, int size)
    {
        String id = null;
        DcsXmlChunkUpload.Step step = null;
        for (int offset = 0; offset < doc.length(); offset += size)
        {
            step = send(id, doc.substring(offset, Math.min(doc.length(), offset + size)), offset, doc.length());
            if (offset + size < doc.length())
            {
                id = issued(step);
            }
        }
        return step;
    }

    @Test
    public void testConsecutiveChunksAssembleTheExactDocumentOnlyOnTheLastChunk()
    {
        String id = begin(7);
        for (int offset = 7; offset < DOC.length(); offset += 7)
        {
            DcsXmlChunkUpload.Step step = send(id, DOC.substring(offset, Math.min(DOC.length(), offset + 7)),
                offset, DOC.length());
            if (offset + 7 < DOC.length())
            {
                assertEquals("the issued id stays the same", id, issued(step)); //$NON-NLS-1$
                continue;
            }
            assertTrue(step.isComplete());
            assertEquals(DOC, step.xml());
            assertNull(step.response());
        }
        assertEquals("the completed upload leaves staging", -1, uploads.receivedChars(id)); //$NON-NLS-1$
    }

    @Test
    public void testANonFinalChunkSaysItIsStagedNotWrittenAndHandsOutItsId()
    {
        DcsXmlChunkUpload.Step step = send(null, DOC.substring(0, 10), 0, DOC.length());
        String id = issued(step);
        String text = step.response();
        assertTrue(text, text.contains("chunk staged, not written")); //$NON-NLS-1$
        assertTrue(text, text.contains("**Received:** `10` of `" + DOC.length() + "` characters")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("**Next offset:** `10`")); //$NON-NLS-1$
        assertTrue(text, text.contains("body.uploadId=\"" + id + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("Nothing was written")); //$NON-NLS-1$
        assertEquals(10, uploads.receivedChars(id));
    }

    @Test
    public void testEveryNewUploadGetsItsOwnUnguessableId()
    {
        String first = begin(10);
        String second = begin(10);
        assertNotEquals(first, second);
        assertTrue(first, first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")); //$NON-NLS-1$
        assertEquals(Arrays.asList(first, second), uploads.uploadIds());
    }

    /** #662 P1: two callers starting the same document can no longer reach each other's staging. */
    @Test
    public void testASecondCallerCannotReplaceOrContinueAnotherCallersUpload()
    {
        String a = begin(10);
        String b = issued(send(null, "<Other", 0, DOC.length())); //$NON-NLS-1$
        assertNotEquals(a, b);
        // A continues its own staging; B's prefix is never mixed in.
        String rest = DOC.substring(10);
        DcsXmlChunkUpload.Step done = send(a, rest, 10, DOC.length());
        assertTrue(done.isComplete());
        assertEquals(DOC, done.xml());
        assertEquals(6, uploads.receivedChars(b));
    }

    @Test
    public void testAnUploadIdThisServerNeverIssuedIsRefusedAtAnyOffset()
    {
        for (int offset : new int[] {0, 10})
        {
            String text = error(send("client-chosen", DOC.substring(offset, offset + 5), offset, DOC.length())); //$NON-NLS-1$
            assertTrue(text, text.contains("Upload 'client-chosen' is not in progress")); //$NON-NLS-1$
            assertTrue(text, text.contains("this server never issued it")); //$NON-NLS-1$
            assertTrue(text, text.contains("Start again at body.offset=0 without uploadId.")); //$NON-NLS-1$
        }
        assertEquals("a refused id creates nothing", List.of(), uploads.uploadIds()); //$NON-NLS-1$
        assertEquals("an unknown id never reaches the probe", 0, probes.get()); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownIdOnACompletingFirstChunkIsRefusedToo()
    {
        String text = error(send("guess", DOC, 0, DOC.length())); //$NON-NLS-1$
        assertTrue(text, text.contains("Upload 'guess' is not in progress")); //$NON-NLS-1$
    }

    @Test
    public void testALaterChunkWithoutUploadIdIsRefused()
    {
        begin(10);
        String text = error(send(null, DOC.substring(10, 15), 10, DOC.length()));
        assertTrue(text, text.contains("body.uploadId is required for body.offset=10")); //$NON-NLS-1$
    }

    @Test
    public void testAMalformedUploadIdIsRefused()
    {
        JsonObject body = chunk(null, "<a", 0, 5); //$NON-NLS-1$
        body.addProperty("uploadId", 7); //$NON-NLS-1$
        String text = error(uploads.accept(request(body), probe(HASH)));
        assertTrue(text, text.contains("body.uploadId '7' is not an upload id this server issued")); //$NON-NLS-1$
    }

    @Test
    public void testASingleCompleteChunkCommitsWithoutAProbeOrAnId()
    {
        DcsXmlChunkUpload.Step step = send(null, DOC, 0, DOC.length());
        assertTrue(step.isComplete());
        assertEquals(DOC, step.xml());
        assertEquals("the commit checks the hash itself", 0, probes.get()); //$NON-NLS-1$
        assertEquals(List.of(), uploads.uploadIds());
    }

    @Test
    public void testTheFirstChunkIsCheckedAgainstTheCurrentHash()
    {
        String text = error(uploads.accept(request(chunk(null, "<a", 0, 5)), probe(OTHER_HASH))); //$NON-NLS-1$
        assertTrue(text, text.contains("expectedHash '" + HASH + "' does not match current hash '" //$NON-NLS-1$ //$NON-NLS-2$
            + OTHER_HASH + "'")); //$NON-NLS-1$
        assertTrue(text, text.contains("body.offset=0")); //$NON-NLS-1$
        assertEquals(List.of(), uploads.uploadIds());
        assertEquals(1, probes.get());
    }

    @Test
    public void testOnlyTheFirstChunkIsProbed()
    {
        upload(DOC, 5);
        assertEquals(1, probes.get());
    }

    @Test
    public void testAProbeErrorIsReturnedAndNothingIsStaged()
    {
        String readError = ToolResult.error("Project 'X' not found.").toJson(); //$NON-NLS-1$
        DcsXmlChunkUpload.Step step = uploads.accept(request(chunk(null, "<a", 0, 5)), //$NON-NLS-1$
            () -> DcsXmlChunkUpload.ProbeResult.error(readError));
        assertEquals(readError, step.response());
        assertEquals(List.of(), uploads.uploadIds());
    }

    @Test
    public void testProbeReadsTheHashHeaderOfAMarkdownGet()
    {
        DcsXmlChunkUpload.ProbeResult ok = DcsXmlChunkUpload.ProbeResult.fromRootRead(
            "**Hash:** `" + HASH + "`\n\n# DCS schema"); //$NON-NLS-1$ //$NON-NLS-2$
        issued(uploads.accept(request(chunk(null, "<a", 0, 5)), () -> ok)); //$NON-NLS-1$

        DcsXmlChunkUpload.ProbeResult unreadable = DcsXmlChunkUpload.ProbeResult.fromRootRead("# no hash"); //$NON-NLS-1$
        assertTrue(ToolResult.isErrorPayload(uploads.accept(request(chunk(null, "<a", 0, 5)), //$NON-NLS-1$
            () -> unreadable).response()));
    }

    @Test
    public void testAGapOrOverlapIsRefusedAndDiscardsTheUpload()
    {
        for (int wrongOffset : new int[] {9, 11})
        {
            String id = begin(10);
            String text = error(send(id, DOC.substring(wrongOffset, wrongOffset + 5), wrongOffset, DOC.length()));
            assertTrue(text, text.contains("body.offset=" + wrongOffset + " is not where upload '" + id + "' continues")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(text, text.contains("must start at body.offset=10")); //$NON-NLS-1$
            assertTrue(text, text.contains("was discarded; Start again at body.offset=0 without uploadId.")); //$NON-NLS-1$
            assertEquals(-1, uploads.receivedChars(id));
            String after = error(send(id, DOC.substring(10, 15), 10, DOC.length()));
            assertTrue(after, after.contains("Upload '" + id + "' is not in progress")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testEveryBindingMismatchIsRefusedAndDiscardsTheUpload()
    {
        Map<String, String[]> mismatches = new HashMap<>();
        mismatches.put("projectName 'Other' differs from 'Project'", new String[] {"Other", TARGET, HASH, "0"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        mismatches.put("fqn 'Report.Other' differs from 'Report.Sales'", new String[] {PROJECT, "Report.Other", HASH, "0"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        mismatches.put("expectedHash '" + OTHER_HASH + "' differs from '" + HASH + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new String[] {PROJECT, TARGET, OTHER_HASH, "0"}); //$NON-NLS-1$
        mismatches.put("body.totalChars=" + (DOC.length() + 1) + " differs from " + DOC.length(), //$NON-NLS-1$ //$NON-NLS-2$
            new String[] {PROJECT, TARGET, HASH, "1"}); //$NON-NLS-1$
        for (Map.Entry<String, String[]> mismatch : mismatches.entrySet())
        {
            String id = begin(10);
            String[] v = mismatch.getValue();
            String text = error(uploads.accept(request(v[0], v[1], v[2],
                chunk(id, DOC.substring(10, 15), 10, DOC.length() + Integer.parseInt(v[3]))), probe(HASH)));
            assertTrue(text, text.contains(mismatch.getKey()));
            assertTrue(text, text.contains("Upload '" + id + "' was discarded; Start again at body.offset=0 " //$NON-NLS-1$ //$NON-NLS-2$
                + "without uploadId. Nothing was written.")); //$NON-NLS-1$
            assertEquals(mismatch.getKey(), -1, uploads.receivedChars(id));
        }
    }

    @Test
    public void testAChunkPastTotalCharsIsRefusedAndDiscardsTheUpload()
    {
        String id = begin(10);
        String text = error(send(id, DOC.substring(10) + "x", 10, DOC.length())); //$NON-NLS-1$
        assertTrue(text, text.contains("past body.totalChars=" + DOC.length())); //$NON-NLS-1$
        assertEquals(-1, uploads.receivedChars(id));
    }

    @Test
    public void testResendingTheFinalChunkAfterTheCommitIsRefusedNotReplayed()
    {
        String id = begin(20);
        send(id, DOC.substring(20, 40), 20, DOC.length());
        assertTrue(send(id, DOC.substring(40), 40, DOC.length()).isComplete());
        String text = error(send(id, DOC.substring(40), 40, DOC.length()));
        assertTrue(text, text.contains("Upload '" + id + "' is not in progress")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("re-run dcs action='get' first")); //$NON-NLS-1$
    }

    @Test
    public void testAnOffsetZeroChunkWithItsIdRestartsTheUpload()
    {
        String id = begin(10);
        send(id, DOC.substring(10, 20), 10, DOC.length());
        assertEquals(id, issued(send(id, DOC.substring(0, 4), 0, DOC.length())));
        assertEquals(4, uploads.receivedChars(id));
        assertEquals(List.of(id), uploads.uploadIds());
    }

    @Test
    public void testARestartWhoseUploadEndsWhileTheHashIsReadIsRefused()
    {
        String id = begin(10);
        DcsXmlChunkUpload.Step step = uploads.accept(request(chunk(id, DOC.substring(0, 4), 0, DOC.length())), () -> {
            now[0] += TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES);
            return DcsXmlChunkUpload.ProbeResult.hash(HASH);
        });
        String text = error(step);
        assertTrue(text, text.contains("Upload '" + id + "' is not in progress")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the expired id is not resurrected", List.of(), uploads.uploadIds()); //$NON-NLS-1$
    }

    @Test
    public void testAnIdleUploadExpiresAndAFreshOneSurvivesTheSweep()
    {
        String old = begin(10);
        now[0] += TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES) - 1;
        String young = begin(10);
        assertEquals(Arrays.asList(old, young), uploads.uploadIds());
        now[0] += 1;
        assertEquals(List.of(young), uploads.uploadIds());
        String text = error(send(old, DOC.substring(10, 15), 10, DOC.length()));
        assertTrue(text, text.contains("idle for " + DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES + " minutes")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEachChunkRenewsTheIdleExpiry()
    {
        long step = TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES) - 1;
        String id = begin(10);
        now[0] += step;
        send(id, DOC.substring(10, 20), 10, DOC.length());
        now[0] += step;
        assertEquals(20, uploads.receivedChars(id));
    }

    @Test
    public void testTheNumberOfSimultaneousUploadsIsBoundedAndTheRefusalListsNoIds()
    {
        String[] ids = new String[DcsXmlChunkUpload.MAX_UPLOADS];
        for (int i = 0; i < ids.length; i++)
        {
            ids[i] = begin(10);
        }
        String text = error(send(null, DOC.substring(0, 10), 0, DOC.length()));
        assertTrue(text, text.contains(DcsXmlChunkUpload.MAX_UPLOADS + " chunked uploads are already in progress")); //$NON-NLS-1$
        for (String id : ids)
        {
            assertFalse("another caller's id must not leak: " + text, text.contains(id)); //$NON-NLS-1$
        }
        assertEquals(ids.length, uploads.uploadIds().size());
        // Restarting one's own upload needs no new slot.
        assertEquals(ids[0], issued(send(ids[0], DOC.substring(0, 3), 0, DOC.length())));
        // A slot frees when its upload expires.
        now[0] += TimeUnit.MINUTES.toNanos(DcsXmlChunkUpload.IDLE_EXPIRY_MINUTES);
        assertNotNull(begin(10));
    }

    @Test
    public void testTotalCharsIsBounded()
    {
        String text = error(send(null, "<", 0, DcsXmlChunkUpload.MAX_TOTAL_CHARS + 1)); //$NON-NLS-1$
        assertTrue(text, text.contains("exceeds the chunked-upload limit of " + DcsXmlChunkUpload.MAX_TOTAL_CHARS)); //$NON-NLS-1$
        issued(send(null, "<", 0, DcsXmlChunkUpload.MAX_TOTAL_CHARS)); //$NON-NLS-1$
        assertEquals(4 * 1024 * 1024, DcsXmlChunkUpload.MAX_TOTAL_CHARS);
    }

    @Test
    public void testAChunkBoundaryInsideASurrogatePairReassemblesTheExactDocument()
    {
        String doc = "<a>x😀y</a>"; //$NON-NLS-1$
        int split = doc.indexOf('\uDE00');
        String id = issued(send(null, doc.substring(0, split), 0, doc.length()));
        DcsXmlChunkUpload.Step last = send(id, doc.substring(split), split, doc.length());
        assertTrue(last.isComplete());
        assertEquals(doc, last.xml());
        // ...and the whole-document body the commit re-enters with carries the pair intact.
        String body = last.wholeDocumentParams(Map.of("body", "{}")).get("body"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(doc, JsonParser.parseString(body).getAsJsonObject().get("xml").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testWholeDocumentParamsReplaceOnlyTheBody()
    {
        DcsXmlChunkUpload.Step last = send(null, DOC, 0, DOC.length());
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("fqn", TARGET); //$NON-NLS-1$
        params.put("action", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("type", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", HASH); //$NON-NLS-1$
        params.put("body", chunk(null, DOC, 0, DOC.length()).toString()); //$NON-NLS-1$
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
        JsonObject both = chunk(null, "<a", 10, 50); //$NON-NLS-1$
        both.addProperty("xml", "<a/>"); //$NON-NLS-1$ //$NON-NLS-2$
        bodies.put("body.xmlChunk and body.xml are mutually exclusive", both); //$NON-NLS-1$
        JsonObject extra = chunk(null, "<a", 10, 50); //$NON-NLS-1$
        extra.addProperty("insertPosition", 1); //$NON-NLS-1$
        bodies.put("also contains: insertPosition", extra); //$NON-NLS-1$
        bodies.put("body.xmlChunk '\"\"' must be a non-empty string", chunk(null, "", 10, 50)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject fraction = chunk(null, "<a", 10, 50); //$NON-NLS-1$
        fraction.addProperty("offset", 10.5); //$NON-NLS-1$
        bodies.put("body.offset '10.5' must be a whole number >= 0", fraction); //$NON-NLS-1$
        JsonObject text = chunk(null, "<a", 10, 50); //$NON-NLS-1$
        text.addProperty("totalChars", "50"); //$NON-NLS-1$ //$NON-NLS-2$
        bodies.put("body.totalChars '\"50\"' must be a whole number >= 1", text); //$NON-NLS-1$
        for (Map.Entry<String, JsonObject> bad : bodies.entrySet())
        {
            String id = issued(send(null, DOC.substring(0, 10), 0, 50));
            JsonObject body = bad.getValue().deepCopy();
            body.addProperty("uploadId", id); //$NON-NLS-1$
            String message = error(uploads.accept(request(body), probe(HASH)));
            assertTrue(message, message.contains(bad.getKey()));
            assertTrue(message, message.contains("Upload '" + id + "' was discarded")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(bad.getKey(), -1, uploads.receivedChars(id));
        }
    }

    @Test
    public void testChunksAreOnlyForAReplaceOfABareSchemaRootWithAHash()
    {
        JsonObject body = chunk(null, "<a", 0, 5); //$NON-NLS-1$
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
        assertEquals("no refusal reached the probe", 0, probes.get()); //$NON-NLS-1$
    }

    @Test
    public void testDiscardIfRefusedEndsTheNamedUploadOnlyOnARefusal()
    {
        String id = begin(10);
        String body = chunk(id, DOC.substring(10, 15), 10, DOC.length()).toString();
        uploads.discardIfRefused(body, "**Action:** `replace`"); //$NON-NLS-1$
        assertEquals("a success keeps the upload", 10, uploads.receivedChars(id)); //$NON-NLS-1$
        uploads.discardIfRefused("{\"xml\":\"<a/>\"}", ToolResult.error("x").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a whole-document refusal names no upload", 10, uploads.receivedChars(id)); //$NON-NLS-1$
        uploads.discardIfRefused(body, ToolResult.error("Unknown format 'bogus'.").toJson()); //$NON-NLS-1$
        assertEquals(-1, uploads.receivedChars(id));
    }

    /** #989 P2-1: a refused call naming an active upload ends it even without a usable xmlChunk. */
    @Test
    public void testARefusedCallNamingAnUploadDiscardsItWhateverElseTheBodyHolds()
    {
        String refusal = ToolResult.error("refused").toJson(); //$NON-NLS-1$
        for (String shape : new String[] {
            "{\"xmlchunk\":\"<a\",\"offset\":10,\"totalChars\":57,\"uploadId\":\"%s\"}", //$NON-NLS-1$
            "{\"offset\":10,\"totalChars\":57,\"uploadId\":\"%s\"}", //$NON-NLS-1$
            "{\"xml\":\"<a/>\",\"uploadId\":\"%s\"}", //$NON-NLS-1$
            "{\"xmlChunk\":42,\"uploadId\":\"%s\"}", //$NON-NLS-1$
            "{\"dataSets\":[],\"uploadId\":\"%s\"}"}) //$NON-NLS-1$
        {
            String id = begin(10);
            uploads.discardIfRefused(String.format(shape, id), refusal);
            assertEquals(shape, -1, uploads.receivedChars(id));
        }
        String kept = begin(10);
        for (String body : new String[] {null, "not json {", "[\"" + kept + "\"]", "\"" + kept + "\"", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"uploadId\":7}", "{}"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            uploads.discardIfRefused(body, refusal);
            assertEquals("a body that names no upload id ends nothing: " + body, 10, uploads.receivedChars(kept)); //$NON-NLS-1$
        }
    }

    /** #989 P2-2: two simultaneous completing restarts of one upload commit at most once. */
    @Test
    public void testConcurrentCompletingRestartsOfOneUploadCommitAtMostOnce() throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            for (int round = 0; round < 500; round++)
            {
                String id = begin(10);
                CyclicBarrier start = new CyclicBarrier(2);
                Callable<Boolean> restart = () -> {
                    start.await();
                    return Boolean.valueOf(send(id, DOC, 0, DOC.length()).isComplete());
                };
                Future<Boolean> a = pool.submit(restart);
                Future<Boolean> b = pool.submit(restart);
                int commits = (a.get().booleanValue() ? 1 : 0) + (b.get().booleanValue() ? 1 : 0);
                assertEquals("round " + round + ": exactly one restart may reach the commit", 1, commits); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    /** #989 P2-2: a staged restart loses to a call that changed the upload while its hash was read. */
    @Test
    public void testAStagedRestartRacingAnotherRestartIsRefusedAndEndsTheUpload()
    {
        String id = begin(10);
        DcsXmlChunkUpload.Step loser = uploads.accept(request(chunk(id, DOC.substring(0, 4), 0, DOC.length())), () -> {
            // Another call of the same holder restarts the upload while this one reads the hash.
            issued(send(id, DOC.substring(0, 6), 0, DOC.length()));
            return DcsXmlChunkUpload.ProbeResult.hash(HASH);
        });
        String text = error(loser);
        assertTrue(text, text.contains("changed while this restart was being checked")); //$NON-NLS-1$
        assertTrue(text, text.contains("Start again at body.offset=0 without uploadId.")); //$NON-NLS-1$
        assertEquals("neither prefix survives to be continued", -1, uploads.receivedChars(id)); //$NON-NLS-1$
    }

    /** #989 P2-2: a staged restart loses to a chunk appended while its hash was read. */
    @Test
    public void testAStagedRestartRacingAnAppendIsRefusedAndEndsTheUpload()
    {
        String id = begin(10);
        DcsXmlChunkUpload.Step loser = uploads.accept(request(chunk(id, DOC.substring(0, 4), 0, DOC.length())), () -> {
            issued(send(id, DOC.substring(10, 20), 10, DOC.length()));
            return DcsXmlChunkUpload.ProbeResult.hash(HASH);
        });
        assertTrue(error(loser), error(loser).contains("changed while this restart was being checked")); //$NON-NLS-1$
        assertEquals(-1, uploads.receivedChars(id));
    }

    /** #989 P2-A: a restart keeps the upload's project and target; switching either ends the upload. */
    @Test
    public void testARestartToAnotherTargetOrProjectIsRefusedAndEndsTheUpload()
    {
        String[][] switches = {
            {PROJECT, "Report.Other", "fqn 'Report.Other' differs from 'Report.Sales'"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Other", TARGET, "projectName 'Other' differs from 'Project'"}}; //$NON-NLS-1$ //$NON-NLS-2$
        for (String[] change : switches)
        {
            for (boolean completing : new boolean[] {true, false})
            {
                String id = begin(10);
                int probesBefore = probes.get();
                String text = completing ? DOC : DOC.substring(0, 4);
                DcsXmlChunkUpload.Step step = uploads.accept(request(change[0], change[1], HASH,
                    chunk(id, text, 0, DOC.length())), probe(HASH));
                String ctx = change[2] + (completing ? " (completing)" : " (staged)"); //$NON-NLS-1$ //$NON-NLS-2$
                String message = error(step);
                assertTrue(ctx + ": " + message, message.contains(change[2] + ", which started upload '" + id + "'")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(ctx + ": " + message, message.contains("Upload '" + id + "' was discarded")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(ctx, -1, uploads.receivedChars(id));
                assertEquals(ctx + ": refused before the hash read", probesBefore, probes.get()); //$NON-NLS-1$
            }
        }
    }

    /** #989 P2-A: a restart is a new document of the same target - its hash and length may change. */
    @Test
    public void testARestartMayCarryANewHashAndANewLength()
    {
        String id = begin(10);
        String other = DOC + "<!-- longer -->"; //$NON-NLS-1$
        DcsXmlChunkUpload.Step staged = uploads.accept(request(PROJECT, TARGET, OTHER_HASH,
            chunk(id, other.substring(0, 5), 0, other.length())), probe(OTHER_HASH));
        assertEquals(id, issued(staged));
        assertTrue(staged.response(), staged.response().contains("of `" + other.length() + "` characters")); //$NON-NLS-1$ //$NON-NLS-2$
        DcsXmlChunkUpload.Step done = uploads.accept(request(PROJECT, TARGET, HASH,
            chunk(id, DOC, 0, DOC.length())), probe(HASH));
        assertTrue(done.isComplete());
        assertEquals(DOC, done.xml());
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
