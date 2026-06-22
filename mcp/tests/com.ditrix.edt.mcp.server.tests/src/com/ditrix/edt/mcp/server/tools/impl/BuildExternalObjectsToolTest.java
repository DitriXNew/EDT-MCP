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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Ratchet tests for {@link BuildExternalObjectsTool} that exercise tool metadata, the
 * JSON input/output schemas (including schema/execute parameter parity and the
 * lowerCamelCase convention), and the argument/path-validation guards that fire BEFORE
 * any live external-object dump — so they run headless.
 * <p>
 * {@code execute()} validates the required arguments and normalizes {@code outputDir}
 * (a pure {@code java.nio.file} check that rejects a file path) up front, then resolves
 * the project (an unresolved name yields a value-naming "Project not found" error). The
 * real build needs a live workspace + 1C runtime and is covered by the E2E suite.
 */
public class BuildExternalObjectsToolTest
{
    /** The exact set of input parameters {@code execute()} reads. Keep in lockstep with the schema. */
    private static final String[] EXECUTE_PARAMS =
        {"projectName", "objectName", "outputDir", "recordBuildTime"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** The input parameters that are required. */
    private static final String[] REQUIRED_PARAMS = {"projectName", "outputDir"}; //$NON-NLS-1$ //$NON-NLS-2$

    // ==================== Metadata ====================

    @Test
    public void testName()
    {
        assertEquals("build_external_objects", new BuildExternalObjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(BuildExternalObjectsTool.NAME, new BuildExternalObjectsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // The tool returns a structured object->path payload, so it must be JSON.
        assertEquals(ResponseType.JSON, new BuildExternalObjectsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new BuildExternalObjectsTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new BuildExternalObjectsTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('build_external_objects')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new BuildExternalObjectsTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
    }

    @Test
    public void testGuideNotesGoldenRegeneration()
    {
        // The golden tools_list snapshot must be regenerated on the stand; the guide records this.
        String guide = new BuildExternalObjectsTool().getGuide();
        assertTrue("guide must note the golden snapshot must be regenerated", //$NON-NLS-1$
            guide.toLowerCase().contains("golden")); //$NON-NLS-1$
    }

    // ==================== Input schema ====================

    @Test
    public void testInputSchemaDeclaresAllExecuteParams()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        assertNotNull(schema);
        for (String param : EXECUTE_PARAMS)
        {
            assertTrue("input schema must declare execute() param: " + param, //$NON-NLS-1$
                schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testInputSchemaDeclaresNoExtraParams()
    {
        // Parity (schema -> execute): every property name in the schema must be one execute() reads.
        String schema = new BuildExternalObjectsTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("input schema declares a param execute() does not read: " + declared, //$NON-NLS-1$
                contains(EXECUTE_PARAMS, declared));
        }
    }

    @Test
    public void testRequiredParams()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        for (String required : REQUIRED_PARAMS)
        {
            assertTrue(required + " must be required", tail.contains("\"" + required + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        // objectName is optional (omit = build all): it must NOT be in the required array.
        assertFalse("objectName must be optional (omit = build all)", //$NON-NLS-1$
            tail.contains("\"objectName\"")); //$NON-NLS-1$
        // recordBuildTime is optional (default true): it must NOT be in the required array.
        assertFalse("recordBuildTime must be optional (default true)", //$NON-NLS-1$
            tail.contains("\"recordBuildTime\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresRecordBuildTime()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        assertTrue("input schema must declare recordBuildTime", //$NON-NLS-1$
            schema.contains("\"recordBuildTime\"")); //$NON-NLS-1$
    }

    // ==================== recordBuildTime parsing (default true, opt-out) ====================

    @Test
    public void testRecordBuildTimeDefaultsTrueWhenAbsentOrBlank()
    {
        // Absent (null) and blank both keep the #202 behaviour: stamp the build time.
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime(null));
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("")); //$NON-NLS-1$
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("   ")); //$NON-NLS-1$
    }

    @Test
    public void testRecordBuildTimeTrueValuesEnableStamping()
    {
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("true")); //$NON-NLS-1$
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("TRUE")); //$NON-NLS-1$
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime(" True ")); //$NON-NLS-1$
    }

    @Test
    public void testRecordBuildTimeFalseValuesDisableStamping()
    {
        // false (or any non-true token) opts out of mutating the object's Comment.
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("false")); //$NON-NLS-1$
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("FALSE")); //$NON-NLS-1$
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("0")); //$NON-NLS-1$
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("no")); //$NON-NLS-1$
    }

    @Test
    public void testAllParamsLowerCamelCase()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("param must be lowerCamelCase: " + declared, isLowerCamelCase(declared)); //$NON-NLS-1$
        }
    }

    // ==================== Output schema ====================

    @Test
    public void testOutputSchemaShape()
    {
        String schema = new BuildExternalObjectsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare outputDir", schema.contains("\"outputDir\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare results", schema.contains("\"results\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare built", schema.contains("\"built\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare failed", schema.contains("\"failed\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissive()
    {
        // An outputSchema must stay permissive so a conformant client never rejects a valid payload.
        String schema = new BuildExternalObjectsTool().getOutputSchema();
        assertFalse("outputSchema must not set additionalProperties:false", //$NON-NLS-1$
            schema.replace(" ", "").contains("\"additionalProperties\":false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ==================== Early validation (returns before any live dump) ====================

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        // No projectName -> required-argument error, returned before any EDT access.
        Map<String, String> params = new HashMap<>();
        params.put("outputDir", "C:/tmp/out"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new BuildExternalObjectsTool().execute(params);
        assertTrue("missing projectName must produce a 'projectName is required' error", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExecuteMissingOutputDirIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyExternalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new BuildExternalObjectsTool().execute(params);
        assertTrue("missing outputDir must produce an 'outputDir is required' error", //$NON-NLS-1$
            result.contains("outputDir is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteOutputDirIsAFileIsError() throws IOException
    {
        // The "exists but is not a directory" guard runs purely on java.nio.file, before any EDT
        // lookup — so it is reachable headless. outputDir is validated first.
        Path tempFile = Files.createTempFile("build-ext-not-a-dir", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "MyExternalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("outputDir", tempFile.toString()); //$NON-NLS-1$
            String result = new BuildExternalObjectsTool().execute(params);
            assertTrue("a file outputDir must be rejected as not a directory", //$NON-NLS-1$
                result.contains("is not a directory")); //$NON-NLS-1$
            assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testExecuteUnknownProjectIsNamedError() throws IOException
    {
        // A non-external (here: nonexistent) project resolves to a value-naming error that names
        // the bad project AND points at the discovery tool — reached after the pure outputDir check,
        // before any live dump.
        Path tempDir = Files.createTempDirectory("build-ext-out"); //$NON-NLS-1$
        try
        {
            String badProject = "NoSuchExternalObjectsProject_" + System.nanoTime(); //$NON-NLS-1$
            Map<String, String> params = new HashMap<>();
            params.put("projectName", badProject); //$NON-NLS-1$
            params.put("outputDir", tempDir.toString()); //$NON-NLS-1$
            String result = new BuildExternalObjectsTool().execute(params);
            assertTrue("error must name the bad project value", result.contains(badProject)); //$NON-NLS-1$
            assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("error must indicate failure", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(tempDir);
        }
    }

    // ==================== helpers ====================

    /** Extracts the property names declared under the schema's {@code "properties"} object. */
    private static java.util.List<String> declaredPropertyNames(String schema)
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        int propsIdx = schema.indexOf("\"properties\""); //$NON-NLS-1$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (propsIdx < 0)
        {
            return names;
        }
        // The "properties" object precedes "required" in JsonSchemaBuilder.build(); scope the scan.
        String propsBlock = requiredIdx > propsIdx ? schema.substring(propsIdx, requiredIdx)
            : schema.substring(propsIdx);
        // Each declared property is a key immediately followed by an object opening: "name":{
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\\{").matcher(propsBlock); //$NON-NLS-1$
        // Skip the leading "properties":{ match itself.
        boolean first = true;
        while (matcher.find())
        {
            if (first)
            {
                first = false;
                continue;
            }
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean isLowerCamelCase(String name)
    {
        return name.matches("[a-z][a-zA-Z0-9]*"); //$NON-NLS-1$
    }

    private static boolean contains(String[] array, String value)
    {
        for (String item : array)
        {
            if (item.equals(value))
            {
                return true;
            }
        }
        return false;
    }
}
