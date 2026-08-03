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

    /**
     * Two SIBLING modules under the same directory (the engine's unit of analysis is the
     * whole containing directory, not one file): {@code Module.bsl} carries one Information
     * finding, {@code Helper.bsl} carries one Warning finding. Models a {@code modulePath}
     * review scoped to {@code Module.bsl} alone, where the engine's report still includes
     * {@code Helper.bsl}'s finding as a directory sibling.
     */
    private static final String SAMPLE_TWO_SIBLING_MODULES = "{"
        + "\"fileinfos\":["
        + "  {\"path\":\"file:///C:/proj/src/CommonModules/Calc/Module.bsl\",\"mdoRef\":\"CommonModule.Calc\","
        + "   \"diagnostics\":["
        + "     {\"code\":\"MagicNumber\","
        + "      \"codeDescription\":{\"href\":\"https://1c-syntax.github.io/bsl-language-server/diagnostics/MagicNumber\"},"
        + "      \"message\":\"Assign this magic number to a constant\","
        + "      \"range\":{\"start\":{\"character\":20,\"line\":5},\"end\":{\"character\":21,\"line\":5}},"
        + "      \"severity\":\"Information\",\"tags\":[]}"
        + "   ],\"metrics\":{\"cyclomaticComplexity\":2}},"
        + "  {\"path\":\"file:///C:/proj/src/CommonModules/Calc/Helper.bsl\",\"mdoRef\":\"CommonModule.CalcHelper\","
        + "   \"diagnostics\":["
        + "     {\"code\":\"UnusedLocalVariable\","
        + "      \"codeDescription\":{\"href\":\"https://1c-syntax.github.io/bsl-language-server/diagnostics/UnusedLocalVariable\"},"
        + "      \"message\":\"Remove unused variable\","
        + "      \"range\":{\"start\":{\"character\":1,\"line\":5},\"end\":{\"character\":10,\"line\":5}},"
        + "      \"severity\":\"Warning\",\"tags\":[\"Unnecessary\"]}"
        + "   ],\"metrics\":{\"cyclomaticComplexity\":1}}"
        + "],\"sourceDir\":\"C:/proj/src\"}";

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
            new File("."), null, null, null, null, 100); //$NON-NLS-1$
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
            new File("."), null, "warning", null, null, 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("diagnostics/UnusedLocalVariable")); //$NON-NLS-1$
        assertFalse("MagicNumber (Information) row must be filtered out at minSeverity=warning", //$NON-NLS-1$
            md.contains("diagnostics/MagicNumber")); //$NON-NLS-1$
    }

    @Test
    public void testRenderRuleFilterKeepsOnlyMatching()
    {
        String md = CodeReviewTool.render(BslLsReport.parse(SAMPLE), "MyProject", null, //$NON-NLS-1$
            new File("."), null, null, "Magic", null, 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("diagnostics/MagicNumber")); //$NON-NLS-1$
        assertFalse("UnusedLocalVariable row must be filtered out by rule=Magic", //$NON-NLS-1$
            md.contains("diagnostics/UnusedLocalVariable")); //$NON-NLS-1$
    }

    @Test
    public void testRenderExcludeRuleFilterDropsMatching()
    {
        // The mirror of rule=Magic above: excludeRule drops the matching rule and KEEPS the rest -
        // this is the mechanism for dodging duplicate reporting against get_project_errors.
        String md = CodeReviewTool.render(BslLsReport.parse(SAMPLE), "MyProject", null, //$NON-NLS-1$
            new File("."), null, null, null, "Magic", 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("MagicNumber row must be filtered out by excludeRule=Magic", //$NON-NLS-1$
            md.contains("diagnostics/MagicNumber")); //$NON-NLS-1$
        assertTrue("UnusedLocalVariable must survive an unrelated excludeRule", //$NON-NLS-1$
            md.contains("diagnostics/UnusedLocalVariable")); //$NON-NLS-1$
    }

    @Test
    public void testRenderCleanProject()
    {
        String md = CodeReviewTool.render(BslLsReport.parse(EMPTY), "MyProject", null, //$NON-NLS-1$
            new File("."), null, null, null, null, 100); //$NON-NLS-1$
        assertTrue(md.contains("No BSL code-quality issues found")); //$NON-NLS-1$
        assertFalse("clean project must not render a table header", md.contains("| Severity |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== modulePath scoping: summary must match the filtered rows ====================

    @Test
    public void testRenderModuleScopedSummaryExcludesSiblingModuleFindings()
    {
        // The engine analyzes the whole containing directory, so its report also carries
        // Helper.bsl's (sibling) Warning finding. The review was scoped to Module.bsl alone, so
        // BOTH the summary counts and the table must reflect only Module.bsl's ONE finding.
        BslLsReport report = BslLsReport.parse(SAMPLE_TWO_SIBLING_MODULES);
        String targetAbsPath = findPathEnding(report, "Module.bsl"); //$NON-NLS-1$
        assertNotNull("fixture must contain a Module.bsl finding", targetAbsPath); //$NON-NLS-1$

        String md = CodeReviewTool.render(report, "MyProject", "CommonModules/Calc/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            new File("."), targetAbsPath, null, null, null, 100); //$NON-NLS-1$

        assertTrue("summary must count only the target module's finding, not its sibling's", //$NON-NLS-1$
            md.contains("**1** finding(s)")); //$NON-NLS-1$
        assertTrue(md.contains("1 information")); //$NON-NLS-1$
        assertTrue("the sibling's Warning must not inflate the scoped warning count", //$NON-NLS-1$
            md.contains("0 warning")); //$NON-NLS-1$
        assertTrue(md.contains("diagnostics/MagicNumber")); //$NON-NLS-1$
        assertFalse("the sibling module's finding must not leak into the scoped table", //$NON-NLS-1$
            md.contains("diagnostics/UnusedLocalVariable")); //$NON-NLS-1$
    }

    @Test
    public void testRenderModuleScopedCleanFileAmongDirtySiblingsReportsClean()
    {
        // Scope to the sibling that HAS NO findings of its own (Helper.bsl has one, but we target
        // a path that matches neither -> equivalent to "this exact module is clean"). The summary
        // must say "No BSL code-quality issues found", not surface the sibling's non-zero total.
        BslLsReport report = BslLsReport.parse(SAMPLE_TWO_SIBLING_MODULES);
        String targetAbsPath = findPathEnding(report, "Module.bsl").replace("Module.bsl", "OtherClean.bsl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String md = CodeReviewTool.render(report, "MyProject", "CommonModules/Calc/OtherClean.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            new File("."), targetAbsPath, null, null, null, 100); //$NON-NLS-1$

        assertTrue("a module with no findings of its own must report clean, not the directory's total", //$NON-NLS-1$
            md.contains("No BSL code-quality issues found")); //$NON-NLS-1$
        assertTrue(md.contains("**0** finding(s)")); //$NON-NLS-1$
    }

    private static String findPathEnding(BslLsReport report, String suffix)
    {
        for (BslLsReport.Finding f : report.findings())
        {
            String normalized = f.path() == null ? null : f.path().replace('\\', '/');
            if (normalized != null && normalized.endsWith(suffix))
            {
                return f.path();
            }
        }
        return null;
    }

    // ==================== modulePath scoping: must stay inside the project's own src/ ====================

    @Test
    public void testIsWithinSrcAcceptsSrcRootItself()
    {
        File srcRoot = new File("C:/proj/src"); //$NON-NLS-1$
        assertTrue(CodeReviewTool.isWithinSrc(srcRoot, srcRoot));
    }

    @Test
    public void testIsWithinSrcAcceptsDescendantModulePath()
    {
        File srcRoot = new File("C:/proj/src"); //$NON-NLS-1$
        File module = new File(srcRoot, "CommonModules/Calc/Module.bsl"); //$NON-NLS-1$
        assertTrue(CodeReviewTool.isWithinSrc(srcRoot, module));
    }

    @Test
    public void testIsWithinSrcRejectsAbsolutePathOutsideProject()
    {
        // Models an absolute modulePath resolving into a DIFFERENT project's src/ within the
        // same Eclipse workspace (BslModuleUtils.resolveModuleFile resolves an absolute path
        // against the whole workspace, not just the requested project).
        File srcRoot = new File("C:/workspace/ProjectA/src"); //$NON-NLS-1$
        File otherProjectFile = new File("C:/workspace/ProjectB/src/CommonModules/Calc/Module.bsl"); //$NON-NLS-1$
        assertFalse(CodeReviewTool.isWithinSrc(srcRoot, otherProjectFile));
    }

    @Test
    public void testIsWithinSrcRejectsDotDotTraversalEscapingSrcRoot()
    {
        // "../../ProjectB/src/Module.bsl" resolved relative to ProjectA/src must NOT be accepted:
        // it normalizes to a location outside ProjectA's own src/ entirely.
        File srcRoot = new File("C:/workspace/ProjectA/src"); //$NON-NLS-1$
        File escaping = new File(srcRoot, "../../ProjectB/src/CommonModules/Calc/Module.bsl"); //$NON-NLS-1$
        assertFalse(CodeReviewTool.isWithinSrc(srcRoot, escaping));
    }

    @Test
    public void testIsWithinSrcRejectsSiblingPathWithSharedPrefix()
    {
        // A naive String.startsWith("C:/proj/src") would wrongly accept "C:/proj/src-evil/...".
        // isWithinSrc must compare path SEGMENTS (java.nio.file.Path#startsWith), not raw strings.
        File srcRoot = new File("C:/proj/src"); //$NON-NLS-1$
        File lookalike = new File("C:/proj/src-evil/Module.bsl"); //$NON-NLS-1$
        assertFalse(CodeReviewTool.isWithinSrc(srcRoot, lookalike));
    }

    @Test
    public void testIsWithinSrcRejectsNullArguments()
    {
        assertFalse(CodeReviewTool.isWithinSrc(null, new File("C:/proj/src/Module.bsl"))); //$NON-NLS-1$
        assertFalse(CodeReviewTool.isWithinSrc(new File("C:/proj/src"), null)); //$NON-NLS-1$
        assertFalse(CodeReviewTool.isWithinSrc(null, null));
    }
}
