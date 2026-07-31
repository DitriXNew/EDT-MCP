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

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BslLsReport;

/**
 * Tests for {@link CodeReviewTool}.
 * <p>
 * Covers the tool contract (name, MARKDOWN response type, guide pointer, schema↔param
 * parity, required array, read-only annotation), the Display-free argument validation
 * that returns a {@code ToolResult.error} JSON BEFORE any workspace access, and the
 * Markdown rendering — the latter exercised directly through
 * {@link CodeReviewTool#render} on a {@link BslLsReport} built from a captured engine
 * sample, so the formatting (summary counts, severity/rule filtering, the fix-and-verify
 * steering, the clean-project message) is verified without spawning the engine. The live
 * subprocess path (real jar + Java 21) is covered by the E2E suite.
 */
public class CodeReviewToolTest
{
    /** Two findings (MagicNumber = Information, UnusedLocalVariable = Warning) + one clean file. */
    private static final String SAMPLE = "{"
        + "\"fileinfos\":["
        + "  {\"path\":\"file:///C:/proj/src/CommonModules/Calc/Module.bsl\",\"mdoRef\":\"CommonModule.Calc\","
        + "   \"diagnostics\":["
        + "     {\"code\":\"MagicNumber\","
        + "      \"codeDescription\":{\"href\":\"https://1c-syntax.github.io/bsl-language-server/diagnostics/MagicNumber\"},"
        + "      \"message\":\"Assign this magic number to a constant\","
        + "      \"range\":{\"start\":{\"character\":20,\"line\":5},\"end\":{\"character\":21,\"line\":5}},"
        + "      \"severity\":\"Information\",\"tags\":[]},"
        + "     {\"code\":\"UnusedLocalVariable\","
        + "      \"codeDescription\":{\"href\":\"https://1c-syntax.github.io/bsl-language-server/diagnostics/UnusedLocalVariable\"},"
        + "      \"message\":\"Remove unused variable\","
        + "      \"range\":{\"start\":{\"character\":1,\"line\":5},\"end\":{\"character\":10,\"line\":5}},"
        + "      \"severity\":\"Warning\",\"tags\":[\"Unnecessary\"]}"
        + "   ],\"metrics\":{\"cyclomaticComplexity\":2}}"
        + "],\"sourceDir\":\"C:/proj/src\"}";

    private static final String EMPTY = "{\"fileinfos\":[]}";

    @Test
    public void testName()
    {
        assertEquals("code_review", new CodeReviewTool().getName()); //$NON-NLS-1$
        assertEquals(CodeReviewTool.NAME, new CodeReviewTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new CodeReviewTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsAtGuide()
    {
        String desc = new CodeReviewTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.contains("get_tool_guide('code_review')")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new CodeReviewTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"severity\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"rule\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"limit\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CodeReviewTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("modulePath must NOT be required", requiredBlock.contains("\"modulePath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("severity must NOT be required", requiredBlock.contains("\"severity\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testReadOnlyAnnotation()
    {
        assertEquals(Boolean.TRUE, new CodeReviewTool().getAnnotations().getReadOnlyHint());
    }

    @Test
    public void testGuideHasEngineSetupDetail()
    {
        String guide = new CodeReviewTool().getGuide();
        assertNotNull(guide);
        // The one-time engine setup detail lives in the guide, not the slim description.
        assertTrue(guide.contains("EDT_MCP_BSL_LS_JAR")); //$NON-NLS-1$
        assertTrue(guide.contains("EDT_MCP_BSL_LS_JAVA")); //$NON-NLS-1$
        assertTrue(guide.contains(".bsl-language-server.json")); //$NON-NLS-1$
        assertTrue(guide.contains("write_module_source")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any workspace access) ====================

    @Test
    public void testMissingProjectName()
    {
        String result = new CodeReviewTool().execute(new HashMap<>());
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidSeverityRejectedBeforeWorkspaceAccess()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("severity", "catastrophic"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CodeReviewTool().execute(params);
        assertTrue(result.contains("Invalid severity")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    // ==================== Rendering (headless, via BslLsReport.parse) ====================

    @Test
    public void testRenderSummaryTableAndSteering()
    {
        String md = CodeReviewTool.render(BslLsReport.parse(SAMPLE), "MyProject", null, //$NON-NLS-1$
            new File("."), null, null, null, 100); //$NON-NLS-1$
        assertTrue(md.contains("Code review — MyProject")); //$NON-NLS-1$
        // Summary counts (full report): 1 warning + 1 information.
        assertTrue(md.contains("**2** finding(s)")); //$NON-NLS-1$
        assertTrue(md.contains("1 warning")); //$NON-NLS-1$
        assertTrue(md.contains("1 information")); //$NON-NLS-1$
        // Both rules present in the table, plus the fix-and-verify steering.
        assertTrue(md.contains("MagicNumber")); //$NON-NLS-1$
        assertTrue(md.contains("UnusedLocalVariable")); //$NON-NLS-1$
        assertTrue(md.contains("write_module_source")); //$NON-NLS-1$
        assertTrue(md.contains("re-run code_review")); //$NON-NLS-1$
        // Location is actionable: the module file appears in the Module path column.
        assertTrue(md.contains("Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testRenderSeverityFilterDropsLowerSeverity()
    {
        // Minimum severity = warning: the Information-level MagicNumber row must be excluded. Assert on
        // the Docs href, which appears only in a table row (the rule name itself also occurs in the
        // fix-and-verify steering text, so a bare-word check would be a false positive).
        String md = CodeReviewTool.render(BslLsReport.parse(SAMPLE), "MyProject", null, //$NON-NLS-1$
            new File("."), null, "warning", null, 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("diagnostics/UnusedLocalVariable")); //$NON-NLS-1$
        assertFalse("MagicNumber (Information) row must be filtered out at minSeverity=warning", //$NON-NLS-1$
            md.contains("diagnostics/MagicNumber")); //$NON-NLS-1$
    }

    @Test
    public void testRenderRuleFilterKeepsOnlyMatching()
    {
        String md = CodeReviewTool.render(BslLsReport.parse(SAMPLE), "MyProject", null, //$NON-NLS-1$
            new File("."), null, null, "Magic", 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("diagnostics/MagicNumber")); //$NON-NLS-1$
        assertFalse("UnusedLocalVariable row must be filtered out by rule=Magic", //$NON-NLS-1$
            md.contains("diagnostics/UnusedLocalVariable")); //$NON-NLS-1$
    }

    @Test
    public void testRenderCleanProject()
    {
        String md = CodeReviewTool.render(BslLsReport.parse(EMPTY), "MyProject", null, //$NON-NLS-1$
            new File("."), null, null, null, 100); //$NON-NLS-1$
        assertTrue(md.contains("No BSL code-quality issues found")); //$NON-NLS-1$
        assertFalse("clean project must not render a table header", md.contains("| Severity |")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
