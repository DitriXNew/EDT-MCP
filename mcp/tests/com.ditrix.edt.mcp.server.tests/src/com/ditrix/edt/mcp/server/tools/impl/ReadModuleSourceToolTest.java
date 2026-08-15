/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ReadModuleSourceTool}.
 * <p>
 * Tests cover: tool metadata, parameter validation, result file name generation,
 * and output formatting (formatOutput helper).
 * <p>
 * Note: tests that require Eclipse workspace (actual file I/O) are covered by E2E tests.
 */
public class ReadModuleSourceToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        assertEquals("read_module_source", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }

    @Test
    public void testDescriptionDoesNotMentionLineNumberPrefix()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        String desc = tool.getDescription();

        // New contract: line numbers live in frontmatter, not inside the code block
        assertFalse("description should not say 'with line numbers'", //$NON-NLS-1$
            desc.toLowerCase().contains("with line numbers")); //$NON-NLS-1$
        assertTrue("description should mention frontmatter", //$NON-NLS-1$
            new ReadModuleSourceTool().getGuide().toLowerCase().contains("frontmatter")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        String guide = tool.getGuide();

        assertNotNull(guide);
        assertFalse("guide must not be empty", guide.isEmpty()); //$NON-NLS-1$
        // Detail moved out of description/schema must now live in the guide.
        assertTrue("guide should explain contentHash round-trip", //$NON-NLS-1$
            guide.contains("contentHash")); //$NON-NLS-1$
        assertTrue("guide should mention expectedHash round-trip target", //$NON-NLS-1$
            guide.contains("expectedHash")); //$NON-NLS-1$
        assertTrue("guide should document the truncation/continuation behavior", //$NON-NLS-1$
            guide.contains("truncated")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsRequiredParameters()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"startLine\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"endLine\"")); //$NON-NLS-1$
    }

    // ==================== Result file name ====================

    @Test
    public void testResultFileNameWithModulePath()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String fileName = tool.getResultFileName(params);
        assertEquals("source-commonmodules-mymodule-module.bsl.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithoutModulePath()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();

        String fileName = tool.getResultFileName(params);
        assertEquals("module-source.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameNormalizesBackslashes()
    {
        // Both '/' and '\\' separators collapse to '-' and the name is lower-cased.
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "CommonModules\\MyModule\\Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String fileName = tool.getResultFileName(params);
        assertEquals("source-commonmodules-mymodule-module.bsl.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithBlankModulePath()
    {
        // An empty modulePath is treated as absent → the generic file name.
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("module-source.md", tool.getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithNullParamsIsSafe()
    {
        // extractStringArgument tolerates a null params map → generic file name, no NPE.
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        assertEquals("module-source.md", tool.getResultFileName(null)); //$NON-NLS-1$
    }

    // ==================== Schema typing / output schema ====================

    @Test
    public void testInputSchemaTypesLineRangeAsOptionalIntegers()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        String schema = tool.getInputSchema();

        // startLine/endLine are integer-typed (not strings).
        assertTrue("schema must type line params as integer", schema.contains("\"integer\"")); //$NON-NLS-1$ //$NON-NLS-2$

        // ...and they must NOT be in the required array (only projectName + modulePath are).
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("modulePath must be required", requiredBlock.contains("\"modulePath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("startLine must NOT be required", requiredBlock.contains("\"startLine\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("endLine must NOT be required", requiredBlock.contains("\"endLine\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // MARKDOWN tools return content, not structuredContent → no output schema.
        assertNull(new ReadModuleSourceTool().getOutputSchema());
    }

    // ==================== Required parameter validation ====================

    @Test
    public void testExecuteMissingProjectName()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingModulePath()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteBlankProjectName()
    {
        // A blank value is rejected exactly like a missing one.
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue("blank projectName must be rejected", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteBlankModulePath()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", ""); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue("blank modulePath must be rejected", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameCheckedBeforeModulePath()
    {
        // projectName is validated first; with both absent the error names projectName only.
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();

        String result = tool.execute(params);
        assertTrue("first failure must be projectName", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("must not report modulePath before projectName is satisfied", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameErrorCarriesDiscoveryHint()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();

        String result = tool.execute(params);
        assertTrue("projectName error must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePathErrorCarriesExampleDetail()
    {
        // modulePath uses the two-arg requireArgument with a custom example suffix,
        // NOT the canonical discovery hint.
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue("modulePath error must include the example path", //$NON-NLS-1$
            result.contains("CommonModules/MyModule/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testValidationErrorIsStructuredFailureJson()
    {
        ReadModuleSourceTool tool = new ReadModuleSourceTool();
        Map<String, String> params = new HashMap<>();

        String result = tool.execute(params);
        assertTrue("error payload must carry success:false", //$NON-NLS-1$
            result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error payload must carry an error field", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== formatOutput ====================

    @Test
    public void testFormatOutputNormal()
    {
        List<String> lines = Arrays.asList(
            "Процедура МояПроцедура()", //$NON-NLS-1$
            "    Сообщить(\"Привет\");", //$NON-NLS-1$
            "КонецПроцедуры"); //$NON-NLS-1$

        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/MyModule/Module.bsl", lines, 1, 3, 3, false, "deadbeefdeadbeef"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("must contain frontmatter start", result.startsWith("---\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain projectName", result.contains("projectName: MyProject\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain module", //$NON-NLS-1$
            result.contains("module: CommonModules/MyModule/Module.bsl\n")); //$NON-NLS-1$
        assertTrue("must contain contentHash", result.contains("contentHash: deadbeefdeadbeef\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain startLine", result.contains("startLine: 1\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain endLine", result.contains("endLine: 3\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain totalLines", result.contains("totalLines: 3\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain truncated when false", result.contains("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain bsl code fence", result.contains("```bsl\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("body must contain raw line 1", //$NON-NLS-1$
            result.contains("Процедура МояПроцедура()\n")); //$NON-NLS-1$
        assertTrue("body must contain raw line 2", //$NON-NLS-1$
            result.contains("    Сообщить(\"Привет\");\n")); //$NON-NLS-1$
        assertFalse("body must NOT contain line-number prefix", result.contains("1: ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("body must NOT contain line-number prefix", result.contains("2: ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must end with closing fence + newline", result.endsWith("```\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatOutputTruncated()
    {
        List<String> lines = Arrays.asList(
            "line1", "line2", "line3", "line4", "line5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        // Simulate: file has 15000 lines, we return 1-5000 truncated
        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/Big/Module.bsl", lines, 1, 5, 15000, true, "deadbeefdeadbeef"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("must contain truncated: true", result.contains("truncated: true\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain totalLines", result.contains("totalLines: 15000\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain startLine", result.contains("startLine: 1\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain endLine", result.contains("endLine: 5\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain nextStartLine = endLine + 1", //$NON-NLS-1$
            result.contains("nextStartLine: 6\n")); //$NON-NLS-1$
        assertTrue("must contain hint mentioning get_module_structure", //$NON-NLS-1$
            result.contains("get_module_structure")); //$NON-NLS-1$
        assertTrue("must contain hint mentioning concrete startLine value", //$NON-NLS-1$
            result.contains("startLine=6")); //$NON-NLS-1$
    }

    @Test
    public void testFormatOutputEmptyFile()
    {
        List<String> lines = Collections.emptyList();

        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/Empty/Module.bsl", lines, 0, 0, 0, false, "e3b0c44298fc1c14"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("must contain projectName", result.contains("projectName: MyProject\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain contentHash even for empty file", //$NON-NLS-1$
            result.contains("contentHash: e3b0c44298fc1c14\n")); //$NON-NLS-1$
        assertTrue("must contain totalLines: 0", result.contains("totalLines: 0\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain startLine on empty file", result.contains("startLine")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain endLine on empty file", result.contains("endLine")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain truncated on empty file", result.contains("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
        // Empty bsl block: opening fence, no body, closing fence
        assertTrue("must contain empty bsl block", result.contains("```bsl\n```\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatOutputOmitsContentHashWhenNull()
    {
        // A null contentHash (no token available) must simply omit the field, not
        // render an empty/garbage line.
        List<String> lines = Arrays.asList("x = 1;"); //$NON-NLS-1$
        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/MyModule/Module.bsl", lines, 1, 1, 1, false, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("must NOT contain contentHash when null", result.contains("contentHash")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("other frontmatter still present", result.contains("totalLines: 1\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatOutputEmitsOnlyRequestedRange()
    {
        // A range read (from=2, to=4 over a 5-line file) must slice the body to those
        // lines only — line1 and line5 are excluded, but totalLines still reports 5.
        List<String> lines = Arrays.asList(
            "line1", "line2", "line3", "line4", "line5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/Mid/Module.bsl", lines, 2, 4, 5, false, "abc123"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("must report the requested window start", result.contains("startLine: 2\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must report the requested window end", result.contains("endLine: 4\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must report the FULL file length", result.contains("totalLines: 5\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("body must include line2", result.contains("line2\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("body must include line3", result.contains("line3\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("body must include line4", result.contains("line4\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("body must exclude line before the window", result.contains("line1\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("body must exclude line after the window", result.contains("line5\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatOutputSingleLineRange()
    {
        // A single-line window (from == to) yields exactly that one body line.
        List<String> lines = Arrays.asList("alpha", "beta", "gamma"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String result = ReadModuleSourceTool.formatOutput(
            "P", "CommonModules/One/Module.bsl", lines, 2, 2, 3, false, "h"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("body must include the single requested line", result.contains("beta\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("body must exclude the preceding line", result.contains("alpha\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("body must exclude the following line", result.contains("gamma\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
