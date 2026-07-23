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
 * Tests for {@link GetMetadataObjectsTool}.
 * <p>
 * Covers tool metadata (name/constant, response type, description, input schema,
 * output schema, result file name, guide) and the {@code projectName}
 * required-argument validation in {@code execute(Map)} that returns BEFORE the
 * first {@code PlatformUI.getWorkbench().getDisplay()} call. Everything past that
 * call (project/configuration resolution, the metadataType switch including the
 * "Unknown metadata type" branch, collection and formatting) needs a live EDT
 * workspace and is covered by the E2E suite.
 */
public class GetMetadataObjectsToolTest
{
    // ==================== Metadata: name / response type ====================

    @Test
    public void testName()
    {
        assertEquals("get_metadata_objects", new GetMetadataObjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMetadataObjectsTool.NAME, new GetMetadataObjectsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMetadataObjectsTool().getResponseType());
    }

    // ==================== Metadata: description ====================

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMetadataObjectsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testDescriptionSteersToGuideAndSiblingTool()
    {
        // The lean description must point at the on-demand guide for the full parameter
        // set and at get_metadata_details for the single-object drill-down.
        String desc = new GetMetadataObjectsTool().getDescription();
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('get_metadata_objects')")); //$NON-NLS-1$
        assertTrue("description must point at get_metadata_details for one object", //$NON-NLS-1$
            desc.contains("get_metadata_details")); //$NON-NLS-1$
    }

    // ==================== Metadata: input schema ====================

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMetadataObjectsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"metadataType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"nameFilter\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresLimitAndLanguageParameters()
    {
        String schema = new GetMetadataObjectsTool().getInputSchema();
        assertTrue("schema must declare the limit parameter", schema.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare the language parameter", //$NON-NLS-1$
            schema.contains("\"language\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsRequiredInSchema()
    {
        // projectName is the only required parameter; the optional filters must NOT be
        // in the required array.
        String schema = new GetMetadataObjectsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("metadataType must NOT be required", //$NON-NLS-1$
            !requiredBlock.contains("\"metadataType\"")); //$NON-NLS-1$
        assertFalse("nameFilter must NOT be required", requiredBlock.contains("\"nameFilter\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("limit must NOT be required", requiredBlock.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", requiredBlock.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Metadata: output schema ====================

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // This is a MARKDOWN tool: it returns content, not structuredContent, so it must
        // inherit the IMcpTool default null output schema (over-declaring one would lie to
        // clients about a structured envelope that never arrives).
        assertNull("markdown tool must not declare an output schema", //$NON-NLS-1$
            new GetMetadataObjectsTool().getOutputSchema());
    }

    // ==================== Metadata: result file name (both branches, no workspace) ====================

    @Test
    public void testResultFileNameUsesLowercasedProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata-myproject.md", //$NON-NLS-1$
            new GetMetadataObjectsTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallbackWhenProjectNameMissing()
    {
        // No projectName -> the generic file name.
        Map<String, String> params = new HashMap<>();
        assertEquals("metadata-objects.md", //$NON-NLS-1$
            new GetMetadataObjectsTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallbackWhenProjectNameEmpty()
    {
        // An empty projectName is treated like a missing one for the file name.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata-objects.md", //$NON-NLS-1$
            new GetMetadataObjectsTool().getResultFileName(params));
    }

    // ==================== Metadata: guide ====================

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive per-tool detail moved out of the always-loaded
        // description/schema and into the on-demand guide channel. The guide
        // must be non-empty and still carry the migrated specifics (the full
        // metadataType enum, the Name-only filter rule, the synonym-by-code note).
        String guide = new GetMetadataObjectsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("eventSubscriptions")); //$NON-NLS-1$
        assertTrue(guide.contains("nameFilter")); //$NON-NLS-1$
        assertTrue(guide.contains("ManagerModule")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any workbench access) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetMetadataObjectsTool().execute(params);
        // Genuine errors now return a ToolResult.error JSON payload (success=false,
        // error=<message>). "projectName is required" has no delimiter characters,
        // so Gson does not unicode-escape it and the substring survives verbatim.
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"error\"")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The required-argument guard appends an actionable discovery hint pointing the
        // caller at list_projects; pin it so the lean error stays self-service.
        Map<String, String> params = new HashMap<>();
        String result = new GetMetadataObjectsTool().execute(params);
        assertTrue("error must steer the caller to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyProjectNameIsError()
    {
        // An empty (blank) projectName is rejected by the same required-argument guard,
        // before any workbench access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMetadataObjectsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameIgnoresOtherArgs()
    {
        // Supplying metadataType/nameFilter/limit/language without a projectName still
        // trips the projectName guard first (the guard runs before defaults and before
        // the workbench is touched), so the metadataType value is never validated here.
        Map<String, String> params = new HashMap<>();
        params.put("metadataType", "catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("nameFilter", "Prod"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("limit", "50"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("language", "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMetadataObjectsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue("an invalid metadataType must NOT leak through before the projectName guard", //$NON-NLS-1$
            !result.contains("Unknown metadata type")); //$NON-NLS-1$
    }

    // ==================== issue #289: metadataType normalization (pure logic, no ======
    // ==================== EDT/workbench dependency - see normalizeMetadataType javadoc)

    @Test
    public void testNormalizeMetadataTypeAcceptsLegacyCategoryTokens()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("all", tool.normalizeMetadataType("all")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("scheduledjobs", tool.normalizeMetadataType("scheduledJobs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("catalogs", tool.normalizeMetadataType("Catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("commonmodules", tool.normalizeMetadataType("COMMONMODULES")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsEnglishTypeNameToken()
    {
        // The core #289 fix: a standard FQN type-name token (singular, as an AI would
        // naturally send it) now resolves to the same category as the legacy plural.
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("scheduledjobs", tool.normalizeMetadataType("ScheduledJob")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("scheduledjobs", tool.normalizeMetadataType("scheduledjob")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("documents", tool.normalizeMetadataType("Document")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("commonmodules", tool.normalizeMetadataType("CommonModule")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsRussianTypeNameToken()
    {
        // Bilingual side of the resolver (MetadataTypeUtils), both singular forms.
        // Escaped so the RU tokens survive a non-UTF-8 Tycho build (see CLAUDE.md hard
        // don't #7); same escape sequences as MetadataTypeUtils.MetadataTypeInfo.
        String ruScheduledJob = // РегламентноеЗадание (ScheduledJob)
            "\u0420\u0435\u0433\u043B\u0430\u043C\u0435\u043D\u0442\u043D\u043E\u0435\u0417\u0430\u0434\u0430\u043D\u0438\u0435"; //$NON-NLS-1$
        String ruCatalog = // Справочник (Catalog)
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A"; //$NON-NLS-1$
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("scheduledjobs", tool.normalizeMetadataType(ruScheduledJob)); //$NON-NLS-1$
        assertEquals("catalogs", tool.normalizeMetadataType(ruCatalog)); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeRejectsRecognizedButUncollectedTypeName()
    {
        // MetadataTypeUtils recognizes far more type names than this tool has
        // collectors for; a type it does NOT collect (Subsystem, XDTOPackage) must
        // fall through to "not recognized" here rather than silently mis-mapping.
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertNull(tool.normalizeMetadataType("Subsystem")); //$NON-NLS-1$
        assertNull(tool.normalizeMetadataType("XDTOPackage")); //$NON-NLS-1$
        assertNull(tool.normalizeMetadataType("Role")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeRejectsUnrecognizedValue()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertNull(tool.normalizeMetadataType("bogusType_e2e")); //$NON-NLS-1$
        assertNull(tool.normalizeMetadataType("")); //$NON-NLS-1$
        assertNull(tool.normalizeMetadataType(null));
    }
}
