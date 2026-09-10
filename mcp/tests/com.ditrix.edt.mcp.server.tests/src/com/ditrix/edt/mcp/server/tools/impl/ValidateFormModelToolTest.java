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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Ratchet tests for {@link ValidateFormModelTool}.
 *
 * <p>Covers the contract that holds without a live EDT: name, response type, the
 * description-steers-to-guide rule, schema&lt;-&gt;execute parameter parity with the lowerCamelCase
 * convention, the required array, the declared output keys, and the two Display-free refusals
 * {@code execute} reaches before any BM access. The findings themselves need a real form model and
 * are covered by {@code FormModelValidatorTest} (the engine) and the e2e suite (the wire).</p>
 */
public class ValidateFormModelToolTest
{
    /** The exact set of input parameters {@code execute()} reads. Keep in lockstep with the schema. */
    private static final String[] EXECUTE_PARAMS = {"projectName", "formFqn"}; //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void testName()
    {
        assertEquals("validate_form_model", new ValidateFormModelTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ValidateFormModelTool.NAME, new ValidateFormModelTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // The result is branched on (valid / code), not read as prose, so it is JSON and carries an
        // output schema - a MARKDOWN tool would leave structuredContent empty.
        assertEquals(ResponseType.JSON, new ValidateFormModelTool().getResponseType());
    }

    @Test
    public void testDescriptionSteersToTheGuide()
    {
        String description = new ValidateFormModelTool().getDescription();
        assertTrue("the description must point at the guide: " + description, //$NON-NLS-1$
            description.contains("get_tool_guide('validate_form_model')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionSeparatesItFromGetProjectErrors()
    {
        // The two verbs read as the same thing unless the description says which one is CURRENT;
        // an agent that picks the stale one after an edit gets a wrong answer, not a slow one.
        String description = new ValidateFormModelTool().getDescription();
        assertTrue("the description must distinguish it from get_project_errors: " + description, //$NON-NLS-1$
            description.contains("get_project_errors")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresEveryParameterExecuteReads()
    {
        String schema = new ValidateFormModelTool().getInputSchema();
        for (String param : EXECUTE_PARAMS)
        {
            assertTrue("schema must declare '" + param + "': " + schema, //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testSchemaRequiresBothParameters()
    {
        String schema = new ValidateFormModelTool().getInputSchema();
        int required = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("the schema must carry a required array: " + schema, required >= 0); //$NON-NLS-1$
        String tail = schema.substring(required);
        assertTrue("projectName must be required: " + tail, tail.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formFqn must be required: " + tail, tail.contains("formFqn")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresTheKeysTheResultCarries()
    {
        String schema = new ValidateFormModelTool().getOutputSchema();
        assertNotNull("a JSON tool must declare its output schema", schema); //$NON-NLS-1$
        for (String key : new String[]{"valid", "formPath", "errors", "warnings", "findings"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("the output schema must declare '" + key + "': " + schema, //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains("\"" + key + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testMissingArgumentsAreRefusedBeforeAnyModelAccess()
    {
        String result = new ValidateFormModelTool().execute(new HashMap<>());
        assertTrue("a call with no arguments must be refused: " + result, //$NON-NLS-1$
            result.contains("projectName")); //$NON-NLS-1$
        assertFalse("and the refusal must not claim success: " + result, //$NON-NLS-1$
            result.contains("\"success\": true")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownProjectIsNamedBack()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_validate_form_model"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", "Catalog.Goods.Form.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ValidateFormModelTool().execute(params);
        assertTrue("the refusal must name the project that was not found: " + result, //$NON-NLS-1$
            result.contains("NoSuchProject_validate_form_model")); //$NON-NLS-1$
    }
}
