/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link FrontMatter}.
 * Verifies YAML frontmatter building, value escaping, and content wrapping.
 */
public class FrontMatterTest
{
    // ========== create / build ==========

    @Test
    public void testCreateReturnsNonNull()
    {
        assertNotNull(FrontMatter.create());
    }

    @Test
    public void testEmptyBuild()
    {
        String result = FrontMatter.create().build();
        assertEquals("---\n---\n", result); //$NON-NLS-1$
    }

    // ========== put (String) ==========

    @Test
    public void testPutString()
    {
        String result = FrontMatter.create()
            .put("projectName", "MyProject") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        assertEquals("---\nprojectName: MyProject\n---\n", result); //$NON-NLS-1$
    }

    @Test
    public void testPutMultipleStrings()
    {
        String result = FrontMatter.create()
            .put("tool", "write_module_source") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", "Demo") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        assertEquals("---\ntool: write_module_source\nprojectName: Demo\n---\n", result); //$NON-NLS-1$
    }

    // ========== put (int) ==========

    @Test
    public void testPutInt()
    {
        String result = FrontMatter.create()
            .put("linesAfter", 75) //$NON-NLS-1$
            .build();
        assertEquals("---\nlinesAfter: 75\n---\n", result); //$NON-NLS-1$
    }

    @Test
    public void testPutIntZero()
    {
        String result = FrontMatter.create()
            .put("count", 0) //$NON-NLS-1$
            .build();
        assertEquals("---\ncount: 0\n---\n", result); //$NON-NLS-1$
    }

    // ========== put (long) ==========

    @Test
    public void testPutLong()
    {
        String result = FrontMatter.create()
            .put("size", 123456789L) //$NON-NLS-1$
            .build();
        assertEquals("---\nsize: 123456789\n---\n", result); //$NON-NLS-1$
    }

    // ========== put (boolean) ==========

    @Test
    public void testPutBooleanTrue()
    {
        String result = FrontMatter.create()
            .put("newFile", true) //$NON-NLS-1$
            .build();
        assertEquals("---\nnewFile: true\n---\n", result); //$NON-NLS-1$
    }

    @Test
    public void testPutBooleanFalse()
    {
        String result = FrontMatter.create()
            .put("newFile", false) //$NON-NLS-1$
            .build();
        assertEquals("---\nnewFile: false\n---\n", result); //$NON-NLS-1$
    }

    // ========== wrapContent ==========

    @Test
    public void testWrapContent()
    {
        String result = FrontMatter.create()
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("File written successfully"); //$NON-NLS-1$
        assertEquals("---\nstatus: success\n---\nFile written successfully", result); //$NON-NLS-1$
    }

    @Test
    public void testWrapContentEmpty()
    {
        String result = FrontMatter.create()
            .put("tool", "test") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent(""); //$NON-NLS-1$
        assertEquals("---\ntool: test\n---\n", result); //$NON-NLS-1$
    }

    // ========== field insertion order ==========

    @Test
    public void testInsertionOrderPreserved()
    {
        String result = FrontMatter.create()
            .put("tool", "write_module_source") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", "Demo") //$NON-NLS-1$ //$NON-NLS-2$
            .put("modulePath", "CommonModules/Test/Module.bsl") //$NON-NLS-1$ //$NON-NLS-2$
            .put("mode", "replace") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("linesAfter", 10) //$NON-NLS-1$
            .put("syntaxCheck", "passed") //$NON-NLS-1$ //$NON-NLS-2$
            .put("newFile", true) //$NON-NLS-1$
            .build();

        String expected = "---\n" //$NON-NLS-1$
            + "tool: write_module_source\n" //$NON-NLS-1$
            + "projectName: Demo\n" //$NON-NLS-1$
            + "modulePath: CommonModules/Test/Module.bsl\n" //$NON-NLS-1$
            + "mode: replace\n" //$NON-NLS-1$
            + "status: success\n" //$NON-NLS-1$
            + "linesAfter: 10\n" //$NON-NLS-1$
            + "syntaxCheck: passed\n" //$NON-NLS-1$
            + "newFile: true\n" //$NON-NLS-1$
            + "---\n"; //$NON-NLS-1$
        assertEquals(expected, result);
    }

    // ========== fluent chaining ==========

    @Test
    public void testFluentChainingReturnsSameInstance()
    {
        FrontMatter fm = FrontMatter.create();
        assertSame(fm, fm.put("a", "b")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(fm, fm.put("c", 1)); //$NON-NLS-1$
        assertSame(fm, fm.put("d", true)); //$NON-NLS-1$
        assertSame(fm, fm.put("e", 100L)); //$NON-NLS-1$
    }

    // ========== mixed types ==========

    @Test
    public void testMixedTypes()
    {
        String result = FrontMatter.create()
            .put("name", "Test") //$NON-NLS-1$ //$NON-NLS-2$
            .put("count", 42) //$NON-NLS-1$
            .put("active", true) //$NON-NLS-1$
            .build();
        String expected = "---\n" //$NON-NLS-1$
            + "name: Test\n" //$NON-NLS-1$
            + "count: 42\n" //$NON-NLS-1$
            + "active: true\n" //$NON-NLS-1$
            + "---\n"; //$NON-NLS-1$
        assertEquals(expected, result);
    }

    // ========== escapeYamlValue ==========

    @Test
    public void testEscapeYamlValueNull()
    {
        assertEquals("", FrontMatter.escapeYamlValue(null)); //$NON-NLS-1$
    }

    @Test
    public void testEscapeYamlValueEmpty()
    {
        assertEquals("\"\"", FrontMatter.escapeYamlValue("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValuePlainString()
    {
        assertEquals("MyProject", FrontMatter.escapeYamlValue("MyProject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueWithColon()
    {
        assertEquals("\"key: value\"", FrontMatter.escapeYamlValue("key: value")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueWithHash()
    {
        assertEquals("\"comment # here\"", FrontMatter.escapeYamlValue("comment # here")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueWithBrackets()
    {
        assertEquals("\"[array]\"", FrontMatter.escapeYamlValue("[array]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueWithBraces()
    {
        assertEquals("\"{map}\"", FrontMatter.escapeYamlValue("{map}")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueWithQuotes()
    {
        assertEquals("\"she said \\\"hello\\\"\"", //$NON-NLS-1$
            FrontMatter.escapeYamlValue("she said \"hello\"")); //$NON-NLS-1$
    }

    @Test
    public void testEscapeYamlValueWithBackslash()
    {
        assertEquals("\"path\\\\to\\\\file\"", //$NON-NLS-1$
            FrontMatter.escapeYamlValue("path\\to\\file")); //$NON-NLS-1$
    }

    @Test
    public void testEscapeYamlValueWithNewline()
    {
        assertEquals("\"line1\\nline2\"", //$NON-NLS-1$
            FrontMatter.escapeYamlValue("line1\nline2")); //$NON-NLS-1$
    }

    @Test
    public void testEscapeYamlValueLeadingWhitespace()
    {
        assertEquals("\" leading\"", FrontMatter.escapeYamlValue(" leading")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueTrailingWhitespace()
    {
        assertEquals("\"trailing \"", FrontMatter.escapeYamlValue("trailing ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ========== YAML reserved words ==========

    @Test
    public void testEscapeYamlValueReservedTrue()
    {
        assertEquals("\"true\"", FrontMatter.escapeYamlValue("true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueReservedFalse()
    {
        assertEquals("\"false\"", FrontMatter.escapeYamlValue("false")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueReservedNull()
    {
        assertEquals("\"null\"", FrontMatter.escapeYamlValue("null")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueReservedYes()
    {
        assertEquals("\"yes\"", FrontMatter.escapeYamlValue("yes")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueReservedNo()
    {
        assertEquals("\"no\"", FrontMatter.escapeYamlValue("no")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueReservedTrueUpperCase()
    {
        assertEquals("\"TRUE\"", FrontMatter.escapeYamlValue("TRUE")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueReservedNullCapitalized()
    {
        assertEquals("\"Null\"", FrontMatter.escapeYamlValue("Null")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ========== non-reserved strings that look similar ==========

    @Test
    public void testEscapeYamlValueNotReserved()
    {
        // "trueValue" is not a reserved word, should not be quoted
        assertEquals("trueValue", FrontMatter.escapeYamlValue("trueValue")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueSimplePath()
    {
        // Forward-slash paths don't need quoting
        assertEquals("Documents/MyDoc/ObjectModule.bsl", //$NON-NLS-1$
            FrontMatter.escapeYamlValue("Documents/MyDoc/ObjectModule.bsl")); //$NON-NLS-1$
    }

    // ========== put null string value ==========

    @Test
    public void testPutNullStringValue()
    {
        String result = FrontMatter.create()
            .put("key", (String)null) //$NON-NLS-1$
            .build();
        assertEquals("---\nkey: \n---\n", result); //$NON-NLS-1$
    }

    // ========== null key protection ==========

    @Test(expected = IllegalArgumentException.class)
    public void testPutNullKeyString()
    {
        FrontMatter.create().put(null, "value"); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPutNullKeyInt()
    {
        FrontMatter.create().put(null, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPutNullKeyBoolean()
    {
        FrontMatter.create().put(null, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPutNullKeyLong()
    {
        FrontMatter.create().put(null, 100L);
    }

    // ========== numeric string quoting ==========

    @Test
    public void testEscapeYamlValueNumericInteger()
    {
        assertEquals("\"123\"", FrontMatter.escapeYamlValue("123")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueNumericDecimal()
    {
        assertEquals("\"3.14\"", FrontMatter.escapeYamlValue("3.14")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueNumericNegative()
    {
        assertEquals("\"-42\"", FrontMatter.escapeYamlValue("-42")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueNumericScientific()
    {
        assertEquals("\"1e10\"", FrontMatter.escapeYamlValue("1e10")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeYamlValueNotNumeric()
    {
        // "123abc" is not purely numeric — should not be quoted for numeric reason
        assertEquals("123abc", FrontMatter.escapeYamlValue("123abc")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
