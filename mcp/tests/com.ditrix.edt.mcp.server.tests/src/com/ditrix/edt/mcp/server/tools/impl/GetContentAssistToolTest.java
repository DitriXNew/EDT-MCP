/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetContentAssistTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live EDT access. {@code execute()} validates
 * projectName, filePath, and the numeric/positive line+column before the first
 * {@code ResourcesPlugin.getWorkspace()} / {@code PlatformUI.getWorkbench()}
 * call. The actual content-assist invocation needs a live workbench/editor and
 * is covered by the E2E suite.
 */
public class GetContentAssistToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_content_assist", new GetContentAssistTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetContentAssistTool.NAME, new GetContentAssistTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetContentAssistTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetContentAssistTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetContentAssistTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$ deprecated alias
        assertTrue(schema.contains("\"line\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"column\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFilePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericLineOrColumn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // line/column omitted -> parse fails -> "Invalid line or column number"
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    @Test
    public void testLineColumnMustBePositive()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        // Source message is "Line and column must be >= 1"; assert on the prefix
        // without '>': ToolResult.toJson() (Gson) escapes '>' as > in JSON.
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }

    // ==================== formatProposals (filter / offset / limit) ====================
    // Pure result-shaping over a mocked proposal array. Result JSON is built by
    // the shared ToolResult/Gson path (compact "key":value).

    private static ICompletionProposal proposal(String displayString)
    {
        ICompletionProposal p = mock(ICompletionProposal.class);
        when(p.getDisplayString()).thenReturn(displayString);
        return p;
    }

    @Test
    public void testFormatProposalsBasic()
    {
        ICompletionProposal[] props = {proposal("AddRow"), proposal("InsertRow"), proposal("CountRows")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json = GetContentAssistTool.formatProposals(props, 10, 0, null, false, 5, 2, "/P/src/Foo.bsl"); //$NON-NLS-1$
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":3")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":3")); //$NON-NLS-1$
        assertTrue(json.contains("\"filteredOut\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"skipped\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"line\":5")); //$NON-NLS-1$
        assertTrue(json.contains("\"column\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"AddRow\"")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsLimit()
    {
        ICompletionProposal[] props = {proposal("A"), proposal("B"), proposal("C"), proposal("D")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String json = GetContentAssistTool.formatProposals(props, 2, 0, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":4")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"A\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"displayString\":\"C\"")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsOffset()
    {
        ICompletionProposal[] props = {proposal("A"), proposal("B"), proposal("C"), proposal("D")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String json = GetContentAssistTool.formatProposals(props, 10, 2, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"skipped\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"C\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"D\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"displayString\":\"A\"")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsContainsFilter()
    {
        ICompletionProposal[] props = {proposal("GetName"), proposal("SetName"), proposal("GetValue")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // case-insensitive substring filter on "get"
        String json = GetContentAssistTool.formatProposals(props, 10, 0, "get", false, 1, 1, "/f"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.contains("\"filteredOut\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("GetName")); //$NON-NLS-1$
        assertTrue(json.contains("GetValue")); //$NON-NLS-1$
        assertFalse(json.contains("SetName")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsEmpty()
    {
        String json = GetContentAssistTool.formatProposals(new ICompletionProposal[0], 10, 0, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"proposals\":[]")); //$NON-NLS-1$
    }
}
