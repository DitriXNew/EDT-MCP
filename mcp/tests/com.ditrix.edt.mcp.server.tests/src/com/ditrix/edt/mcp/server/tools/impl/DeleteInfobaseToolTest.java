/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link DeleteInfobaseTool}.
 * <p>
 * Covers tool metadata, schema, the confirm-preview gate, and the argument-validation
 * guards that execute BEFORE any workspace or platform-services access. The real
 * dissociate/deregister path needs a live EDT workspace and is covered by the e2e suite.
 */
public class DeleteInfobaseToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_infobase", new DeleteInfobaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteInfobaseTool.NAME, new DeleteInfobaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DeleteInfobaseTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndMentionsConfirmPreview()
    {
        String desc = new DeleteInfobaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must advertise the confirm-preview gate", //$NON-NLS-1$
            desc.toLowerCase().contains("confirm")); //$NON-NLS-1$
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('delete_infobase')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new DeleteInfobaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare infobaseName", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare deleteRegistration", //$NON-NLS-1$
            schema.contains("\"deleteRegistration\"")); //$NON-NLS-1$
        assertTrue("schema must declare deleteDatabaseFiles", //$NON-NLS-1$
            schema.contains("\"deleteDatabaseFiles\"")); //$NON-NLS-1$
        assertTrue("schema must declare the confirm gate", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredParametersInSchema()
    {
        String schema = new DeleteInfobaseTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional parameters must NOT be in the required array.
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        if (open >= 0 && close > open)
        {
            String requiredBlock = schema.substring(open, close + 1);
            assertTrue("applicationId must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"applicationId\"")); //$NON-NLS-1$
            assertTrue("infobaseName must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"infobaseName\"")); //$NON-NLS-1$
            assertTrue("deleteRegistration must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"deleteRegistration\"")); //$NON-NLS-1$
            assertTrue("deleteDatabaseFiles must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"deleteDatabaseFiles\"")); //$NON-NLS-1$
            assertTrue("confirm must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"confirm\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOutputSchemaDeclaresConfirmPreviewFields()
    {
        String schema = new DeleteInfobaseTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare confirmationRequired", //$NON-NLS-1$
            schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare infobaseName", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare deleteRegistration", //$NON-NLS-1$
            schema.contains("\"deleteRegistration\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare databaseFilesDeleted", //$NON-NLS-1$
            schema.contains("\"databaseFilesDeleted\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare applicationKind (standalone-server removals)", //$NON-NLS-1$
            schema.contains("\"applicationKind\"")); //$NON-NLS-1$
    }

    @Test
    public void testApplicationKindIsOutputOnlyNotAnInputParam()
    {
        // The application kind is AUTO-DETECTED from the resolved application — it must NOT be an input
        // parameter (a future edit must not add an input the tool ignores). It IS an output field.
        DeleteInfobaseTool tool = new DeleteInfobaseTool();
        assertTrue("applicationKind must NOT be an input parameter", //$NON-NLS-1$
            !tool.getInputSchema().contains("\"applicationKind\"")); //$NON-NLS-1$
        assertTrue("applicationKind must be declared in the output schema", //$NON-NLS-1$
            tool.getOutputSchema().contains("\"applicationKind\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputApplicationKindNamesBothKinds()
    {
        // Pin the wire vocabulary agents key off: the output applicationKind must name both kinds.
        String schema = new DeleteInfobaseTool().getOutputSchema();
        assertTrue("output applicationKind must mention 'infobase'", schema.contains("infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output applicationKind must mention 'standaloneServer'", //$NON-NLS-1$
            schema.contains("standaloneServer")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAndGuideCoverStandaloneServer()
    {
        // delete_infobase is the inverse of create_infobase for BOTH kinds; it must advertise the
        // standalone-server deletion path in its description and guide.
        DeleteInfobaseTool tool = new DeleteInfobaseTool();
        String desc = tool.getDescription();
        assertTrue("description must mention the standalone server path", //$NON-NLS-1$
            desc.toLowerCase().contains("standalone")); //$NON-NLS-1$
        String guide = tool.getGuide();
        assertTrue("guide must document the standalone-server deletion", //$NON-NLS-1$
            guide.toLowerCase().contains("standalone")); //$NON-NLS-1$
        assertTrue("guide must note the served database is removed for a server", //$NON-NLS-1$
            guide.toLowerCase().contains("database")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsConfirmPreviewAndDeletion()
    {
        String guide = new DeleteInfobaseTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document the preview phase", //$NON-NLS-1$
            guide.toLowerCase().contains("preview")); //$NON-NLS-1$
        assertTrue("guide must document the confirm parameter", guide.contains("confirm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document deleteRegistration", guide.contains("deleteRegistration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (returns before any workspace access) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "someApp"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DeleteInfobaseTool().execute(params);
        assertTrue("missing projectName must produce an error containing 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingBothApplicationIdAndInfobaseNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        // Neither applicationId nor infobaseName provided.
        String result = new DeleteInfobaseTool().execute(params);
        assertTrue("missing both applicationId and infobaseName must produce an error", //$NON-NLS-1$
            result.contains("applicationId") && result.contains("infobaseName")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
