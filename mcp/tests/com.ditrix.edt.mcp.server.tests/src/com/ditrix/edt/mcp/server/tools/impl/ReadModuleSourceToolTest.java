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
            desc.toLowerCase().contains("frontmatter")); //$NON-NLS-1$
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

    // ==================== formatOutput ====================

    @Test
    public void testFormatOutputNormal()
    {
        List<String> lines = Arrays.asList(
            "Процедура МояПроцедура()", //$NON-NLS-1$
            "    Сообщить(\"Привет\");", //$NON-NLS-1$
            "КонецПроцедуры"); //$NON-NLS-1$

        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/MyModule/Module.bsl", lines, 1, 3, 3, false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("must contain frontmatter start", result.startsWith("---\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain projectName", result.contains("projectName: MyProject\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain module", //$NON-NLS-1$
            result.contains("module: CommonModules/MyModule/Module.bsl\n")); //$NON-NLS-1$
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
            "MyProject", "CommonModules/Big/Module.bsl", lines, 1, 5, 15000, true); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("must contain truncated: true", result.contains("truncated: true\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain totalLines", result.contains("totalLines: 15000\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain startLine", result.contains("startLine: 1\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain endLine", result.contains("endLine: 5\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatOutputEmptyFile()
    {
        List<String> lines = Collections.emptyList();

        String result = ReadModuleSourceTool.formatOutput(
            "MyProject", "CommonModules/Empty/Module.bsl", lines, 0, 0, 0, false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("must contain projectName", result.contains("projectName: MyProject\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must contain totalLines: 0", result.contains("totalLines: 0\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain startLine on empty file", result.contains("startLine")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain endLine on empty file", result.contains("endLine")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must NOT contain truncated on empty file", result.contains("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
        // Empty bsl block: opening fence, no body, closing fence
        assertTrue("must contain empty bsl block", result.contains("```bsl\n```\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
