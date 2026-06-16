/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetSubsystemContentTool}.
 * <p>
 * Covers tool metadata, the input/output schema, the on-demand guide, the pure
 * {@code getResultFileName} file-name derivation, and the projectName/subsystemFqn
 * required-argument validation that returns before the first
 * {@code PlatformUI.getWorkbench()} call (the EDT boundary). Resolving the subsystem
 * and formatting its content need a live configuration and are covered by the E2E suite.
 */
public class GetSubsystemContentToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_subsystem_content", new GetSubsystemContentTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetSubsystemContentTool.NAME, new GetSubsystemContentTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetSubsystemContentTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetSubsystemContentTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetSubsystemContentTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"subsystemFqn\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresOptionalParameters()
    {
        // recursive (boolean) and language (string) are the two optional inputs the tool reads.
        String schema = new GetSubsystemContentTool().getInputSchema();
        assertTrue("schema must declare the recursive flag", schema.contains("\"recursive\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare the language code", schema.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredArrayHasProjectNameAndSubsystemFqn()
    {
        // The two required inputs are the only ones in the schema's required array; the optional
        // recursive/language must NOT be required.
        String schema = new GetSubsystemContentTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("subsystemFqn must be required", requiredBlock.contains("\"subsystemFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("recursive must NOT be required", requiredBlock.contains("\"recursive\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", requiredBlock.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionSteersToGuide()
    {
        // The slimmed description must advertise the on-demand guide entry point.
        String desc = new GetSubsystemContentTool().getDescription();
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('get_subsystem_content')")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // A MARKDOWN tool returns content, not structuredContent, so it leaves the output schema null.
        assertNull(new GetSubsystemContentTool().getOutputSchema());
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new GetSubsystemContentTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail moved out of the slimmed description/schema into the on-demand guide.
        assertTrue(guide.contains("recursive")); //$NON-NLS-1$
        assertTrue(guide.contains("FQN format")); //$NON-NLS-1$
        assertTrue(guide.contains("deduplicated")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsParametersAndSections()
    {
        // Pin the guide vocabulary agents key off: it must document the two optional params and the
        // three Markdown output sections the formatter emits.
        String guide = new GetSubsystemContentTool().getGuide();
        assertTrue("guide must document the language param", guide.contains("language")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document subsystemFqn", guide.contains("subsystemFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the Properties section", guide.contains("Properties")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the Content section", guide.contains("Content")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the Child Subsystems section", //$NON-NLS-1$
            guide.contains("Child Subsystems")); //$NON-NLS-1$
    }

    // ==================== getResultFileName (pure helper, no live workbench needed) =================

    @Test
    public void testResultFileNameDefaultWhenFqnAbsent()
    {
        // No subsystemFqn → the static default file name.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("subsystem-content.md", //$NON-NLS-1$
            new GetSubsystemContentTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDefaultWhenFqnEmpty()
    {
        // A blank FQN is treated as absent and falls back to the default file name.
        Map<String, String> params = new HashMap<>();
        params.put("subsystemFqn", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("subsystem-content.md", //$NON-NLS-1$
            new GetSubsystemContentTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDerivedFromFqn()
    {
        // FQN present → dots become dashes and the whole name is lower-cased.
        Map<String, String> params = new HashMap<>();
        params.put("subsystemFqn", "Subsystem.Sales.Subsystem.Orders"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("subsystem-subsystem-sales-subsystem-orders.md", //$NON-NLS-1$
            new GetSubsystemContentTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameLowerCasesAndKeepsExtension()
    {
        // A single-segment FQN with mixed case is lower-cased; only dots (not other chars) are mapped.
        Map<String, String> params = new HashMap<>();
        params.put("subsystemFqn", "Subsystem.SalesAndCRM"); //$NON-NLS-1$ //$NON-NLS-2$
        String name = new GetSubsystemContentTool().getResultFileName(params);
        assertEquals("subsystem-subsystem-salesandcrm.md", name); //$NON-NLS-1$
        assertTrue("result file name must keep the .md extension", name.endsWith(".md")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // projectName is an enumerable parameter, so its required-error carries the list_projects hint.
        Map<String, String> params = new HashMap<>();
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue("projectName error must point at list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testMissingSubsystemFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue(result.contains("subsystemFqn is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingSubsystemFqnCarriesExampleHint()
    {
        // The subsystemFqn guard appends an inline example so the caller learns the FQN shape.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue("subsystemFqn error must include the 'Subsystem.Sales' example", //$NON-NLS-1$
            result.contains("Subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testBlankProjectNameIsTreatedAsMissing()
    {
        // A blank projectName is rejected by the same required-argument guard (empty == missing),
        // returning before the workbench is touched.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("subsystemFqn", "Subsystem.Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue("blank projectName must fail the required-argument guard", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testValidationErrorIsStructuredJson()
    {
        // The required-argument guard returns a structured ToolResult error payload, not Markdown,
        // so the protocol layer can divert it to an isError result.
        Map<String, String> params = new HashMap<>();
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue("validation error must be a JSON object", //$NON-NLS-1$
            result.trim().startsWith("{") && result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
