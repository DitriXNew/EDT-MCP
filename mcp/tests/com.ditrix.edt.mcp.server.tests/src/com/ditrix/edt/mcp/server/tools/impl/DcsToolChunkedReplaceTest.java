/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.ditrix.edt.mcp.server.utils.DcsXmlChunkUpload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The chunked {@code replace type='schema'} routes through {@link DcsTool} (#662). */
public class DcsToolChunkedReplaceTest
{
    private static final String HASH = "0123456789abcdef0123"; //$NON-NLS-1$
    private static final String DOC = "<DataCompositionSchema>0123456789</DataCompositionSchema>"; //$NON-NLS-1$

    /** Records instead of writing: the commit seam and the first-chunk hash read. */
    private static final class Recording extends DcsTool
    {
        final List<Map<String, String>> commits = new ArrayList<>();
        int probes;

        @Override
        String commitAssembled(Map<String, String> wholeDocumentParams)
        {
            commits.add(wholeDocumentParams);
            return "COMMITTED"; //$NON-NLS-1$
        }

        @Override
        DcsXmlChunkUpload.ProbeResult probeCurrentHash(String projectName, DcsAddress address, String language)
        {
            probes++;
            return DcsXmlChunkUpload.ProbeResult.hash(HASH);
        }
    }

    private static String uniqueId()
    {
        return "t-" + UUID.randomUUID(); //$NON-NLS-1$
    }

    private static Map<String, String> chunkCall(String id, String text, int offset, int total)
    {
        JsonObject body = new JsonObject();
        body.addProperty("xmlChunk", text); //$NON-NLS-1$
        body.addProperty("offset", offset); //$NON-NLS-1$
        body.addProperty("totalChars", total); //$NON-NLS-1$
        body.addProperty("uploadId", id); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", "Report.Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("type", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", HASH); //$NON-NLS-1$
        params.put("body", body.toString()); //$NON-NLS-1$
        return params;
    }

    @Test
    public void testNothingIsCommittedBeforeTheLastChunkWhichCommitsTheWholeDocumentOnce()
    {
        Recording tool = new Recording();
        String id = uniqueId();
        String first = tool.execute(chunkCall(id, DOC.substring(0, 20), 0, DOC.length()));
        String second = tool.execute(chunkCall(id, DOC.substring(20, 40), 20, DOC.length()));
        assertTrue(first, first.contains("chunk staged, not written")); //$NON-NLS-1$
        assertTrue(second, second.contains("**Received:** `40` of `" + DOC.length() + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(second, ToolResult.isErrorPayload(second));
        assertEquals("no write before the last chunk", 0, tool.commits.size()); //$NON-NLS-1$
        assertEquals("only the first chunk reads the current hash", 1, tool.probes); //$NON-NLS-1$

        Map<String, String> last = chunkCall(id, DOC.substring(40), 40, DOC.length());
        assertEquals("COMMITTED", tool.execute(last)); //$NON-NLS-1$
        assertEquals(1, tool.commits.size());
        Map<String, String> committed = new HashMap<>(tool.commits.get(0));
        JsonObject body = JsonParser.parseString(committed.remove("body")).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("the commit is the whole-document body.xml call", 1, body.size()); //$NON-NLS-1$
        assertEquals(DOC, body.get("xml").getAsString()); //$NON-NLS-1$
        Map<String, String> expected = new HashMap<>(last);
        expected.remove("body"); //$NON-NLS-1$
        assertEquals(expected, committed);
    }

    @Test
    public void testTheCommitReentersTheWholeDocumentReplace()
    {
        // A whitespace-only document fails the whole-document body.xml validation before any EDT
        // access, so the real commit can be compared with a direct body.xml call byte for byte.
        String blank = "   "; //$NON-NLS-1$
        Map<String, String> direct = chunkCall("unused", blank, 0, blank.length()); //$NON-NLS-1$
        JsonObject xmlBody = new JsonObject();
        xmlBody.addProperty("xml", blank); //$NON-NLS-1$
        direct.put("body", xmlBody.toString()); //$NON-NLS-1$
        String expected = new DcsTool().execute(direct);
        assertTrue(expected, expected.contains("body.xml must be a non-empty string")); //$NON-NLS-1$

        String chunked = new DcsTool().execute(chunkCall(uniqueId(), blank, 0, blank.length()));
        assertEquals(expected, chunked);
    }

    @Test
    public void testXmlAndXmlChunkTogetherAreRefusedWithoutWriting()
    {
        Recording tool = new Recording();
        Map<String, String> params = chunkCall(uniqueId(), DOC, 0, DOC.length());
        JsonObject body = JsonParser.parseString(params.get("body")).getAsJsonObject(); //$NON-NLS-1$
        body.addProperty("xml", DOC); //$NON-NLS-1$
        params.put("body", body.toString()); //$NON-NLS-1$
        String result = tool.execute(params);
        assertTrue(result, ToolResult.isErrorPayload(result));
        assertTrue(result, result.contains("body.xmlChunk and body.xml are mutually exclusive")); //$NON-NLS-1$
        assertEquals(0, tool.commits.size());
        assertEquals(0, tool.probes);
    }

    @Test
    public void testATopLevelOffsetIsRefusedForAChunk()
    {
        Recording tool = new Recording();
        Map<String, String> params = chunkCall(uniqueId(), DOC.substring(0, 5), 0, DOC.length());
        params.put("offset", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = tool.execute(params);
        assertTrue(result, result.contains("goes in body.offset")); //$NON-NLS-1$
        assertEquals(0, tool.commits.size());
    }

    @Test
    public void testAChunkForAnotherActionIsRefused()
    {
        Recording tool = new Recording();
        Map<String, String> params = chunkCall(uniqueId(), DOC.substring(0, 5), 0, DOC.length());
        params.put("action", "upsert"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = tool.execute(params);
        assertTrue(result, result.contains("body.xmlChunk is not allowed with action='upsert'")); //$NON-NLS-1$
        assertEquals(0, tool.commits.size());
        assertEquals(0, tool.probes);
    }
}
