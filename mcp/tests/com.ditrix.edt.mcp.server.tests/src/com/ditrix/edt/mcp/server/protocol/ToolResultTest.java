/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Tests for {@link ToolResult}.
 */
public class ToolResultTest
{
    @Test
    public void testSuccessResult()
    {
        String json = ToolResult.success().toJson();
        JsonElement element = JsonParser.parseString(json);
        assertTrue(element.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    public void testErrorResult()
    {
        String json = ToolResult.error("Something went wrong").toJson();
        JsonElement element = JsonParser.parseString(json);
        assertFalse(element.getAsJsonObject().get("success").getAsBoolean());
        assertEquals("Something went wrong", element.getAsJsonObject().get("error").getAsString());
    }

    @Test
    public void testErrorResultNullMessageStillCarriesErrorField()
    {
        // A null message (e.g. an exception with getMessage()==null) must not drop
        // the "error" key: the default Gson omits null fields, so error(null) is
        // coalesced to a non-null fallback so the contract always has a message.
        String json = ToolResult.error(null).toJson();
        JsonElement element = JsonParser.parseString(json);
        assertFalse(element.getAsJsonObject().get("success").getAsBoolean());
        assertTrue("error key must be present even for a null message",
            element.getAsJsonObject().has("error"));
        assertFalse(element.getAsJsonObject().get("error").isJsonNull());
    }

    @Test
    public void testErrorAfterMutationCarriesStructuralMarkerIndependentOfMessage()
    {
        JsonElement first = JsonParser.parseString(
            ToolResult.errorAfterMutation("force export failed").toJson()); //$NON-NLS-1$
        JsonElement renamed = JsonParser.parseString(
            ToolResult.errorAfterMutation("post-commit verification disagreed").toJson()); //$NON-NLS-1$

        assertTrue(first.getAsJsonObject().get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertTrue(renamed.getAsJsonObject().get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertFalse(first.getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(renamed.getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testExistingPlainErrorCanBeMarkedAtTheReturnChokePoint()
    {
        String plain = ToolResult.error("generic catch").toJson(); //$NON-NLS-1$

        JsonElement marked = JsonParser.parseString(ToolResult.markErrorAfterMutation(plain));

        assertFalse(marked.getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(marked.getAsJsonObject().get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertEquals("generic catch", marked.getAsJsonObject().get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testReturnChokePointLeavesNonErrorsByteIdentical()
    {
        String success = ToolResult.success().put("value", "same").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        String markdown = "# not JSON"; //$NON-NLS-1$

        assertSame(success, ToolResult.markErrorAfterMutation(success));
        assertSame(markdown, ToolResult.markErrorAfterMutation(markdown));
    }

    @Test
    public void testOpaqueMutationFailureHasItsOwnStructuralMarker()
    {
        JsonElement error = JsonParser.parseString(
            ToolResult.errorWithUnknownMutationOutcome("opaque extension threw").toJson()); //$NON-NLS-1$

        assertFalse(error.getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(error.getAsJsonObject().get("mutationOutcomeUnknown").getAsBoolean()); //$NON-NLS-1$
        assertFalse("unknown must not be overclaimed as committed", //$NON-NLS-1$
            error.getAsJsonObject().has("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void testKnownCommitOutranksAnEarlierOrLaterUnknownOutcome()
    {
        String unknown = ToolResult.errorWithUnknownMutationOutcome("second project failed").toJson(); //$NON-NLS-1$
        JsonElement upgraded = JsonParser.parseString(ToolResult.markErrorAfterMutation(unknown));
        assertTrue(upgraded.getAsJsonObject().get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertFalse(upgraded.getAsJsonObject().has("mutationOutcomeUnknown")); //$NON-NLS-1$

        String committed = ToolResult.errorAfterMutation("first project committed").toJson(); //$NON-NLS-1$
        JsonElement notWeakened = JsonParser.parseString(
            ToolResult.markErrorWithUnknownMutationOutcome(committed));
        assertTrue(notWeakened.getAsJsonObject().get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertFalse(notWeakened.getAsJsonObject().has("mutationOutcomeUnknown")); //$NON-NLS-1$
    }

    @Test
    public void testPutString()
    {
        String json = ToolResult.success()
            .put("name", "TestProject")
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals("TestProject", element.getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void testPutInt()
    {
        String json = ToolResult.success()
            .put("count", 42)
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals(42, element.getAsJsonObject().get("count").getAsInt());
    }

    @Test
    public void testPutLong()
    {
        String json = ToolResult.success()
            .put("bigCount", 123456789L)
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals(123456789L, element.getAsJsonObject().get("bigCount").getAsLong());
    }

    @Test
    public void testPutBoolean()
    {
        String json = ToolResult.success()
            .put("active", true)
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertTrue(element.getAsJsonObject().get("active").getAsBoolean());
    }

    @Test
    public void testPutList()
    {
        String json = ToolResult.success()
            .put("items", List.of("a", "b", "c"))
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals(3, element.getAsJsonObject().get("items").getAsJsonArray().size());
    }

    @Test
    public void testChaining()
    {
        String json = ToolResult.success()
            .put("name", "Test")
            .put("count", 5)
            .put("active", true)
            .toJson();

        JsonElement element = JsonParser.parseString(json);
        var obj = element.getAsJsonObject();
        assertTrue(obj.get("success").getAsBoolean());
        assertEquals("Test", obj.get("name").getAsString());
        assertEquals(5, obj.get("count").getAsInt());
        assertTrue(obj.get("active").getAsBoolean());
    }

    @Test
    public void testToJsonStatic()
    {
        String json = ToolResult.toJsonStatic(List.of(1, 2, 3));
        assertNotNull(json);
        assertTrue(json.startsWith("["));
    }
}
