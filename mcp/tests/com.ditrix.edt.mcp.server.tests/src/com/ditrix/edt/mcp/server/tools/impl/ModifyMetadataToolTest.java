/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;

/**
 * Lightweight contract tests for {@link ModifyMetadataTool}: tool metadata and JSON schema, without
 * the Eclipse/EDT runtime. The execute() path (validation + BM write) needs a live workbench and BM
 * model, so the validation / apply behaviour is covered by the E2E suite.
 */
public class ModifyMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("modify_metadata", new ModifyMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(ModifyMetadataTool.NAME, new ModifyMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new ModifyMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new ModifyMetadataTool().getDescription();
        assertNotNull(desc);
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('modify_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAdvertisesFormHandlerAndCommandRebind()
    {
        // The form event-handler procedure rebind + the button command rebind are part of the tool
        // surface, so the description must advertise the 'procedure' and 'command' rebind properties.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should advertise the handler 'procedure' rebind", //$NON-NLS-1$
            desc.contains("procedure")); //$NON-NLS-1$
        assertTrue("description should advertise the button 'command' rebind", //$NON-NLS-1$
            desc.contains("command")); //$NON-NLS-1$
    }

    @Test
    public void testGuideExplainsHandlerAndButtonCommandRebind()
    {
        // The rebind contract is documented: REBIND an existing handler's procedure / re-point a button.
        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should explain the handler procedure rebind", //$NON-NLS-1$
            guide.contains("procedure")); //$NON-NLS-1$
        assertTrue("guide should explain re-pointing a button at a form command", //$NON-NLS-1$
            guide.contains("command")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAndGuideAdvertiseStyleItemValue()
    {
        // Setting a StyleItem's Color / Font value is part of the tool surface, so the description
        // and the guide must advertise the 'value' property with its color / font shape.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should advertise the StyleItem value", //$NON-NLS-1$
            desc.contains("StyleItem")); //$NON-NLS-1$
        assertTrue("description should mention the color shape", desc.contains("color")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention the font shape", desc.contains("font")); //$NON-NLS-1$ //$NON-NLS-2$

        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should explain setting a StyleItem value", //$NON-NLS-1$
            guide.contains("StyleItem")); //$NON-NLS-1$
        assertTrue("guide should show the color value shape", guide.contains("color")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the font value shape", guide.contains("font")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
        // The ё->е normalization toggle must be declared (execute() reads it; schema parity).
        assertTrue("schema must declare the normalizeYo toggle", //$NON-NLS-1$
            schema.contains("\"normalizeYo\"")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeYoIsOptional()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("normalizeYo must not be required (defaults true)", //$NON-NLS-1$
            tail.contains("\"normalizeYo\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("properties must be required", tail.contains("\"properties\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideCarriesValidationDetail()
    {
        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        // the actionable-validation contract is documented
        assertTrue("guide should explain the allowed-values validation", //$NON-NLS-1$
            guide.contains("allowed")); //$NON-NLS-1$
        assertTrue("guide should steer discovery to get_metadata_details(assignable:true)", //$NON-NLS-1$
            guide.contains("assignable:true")); //$NON-NLS-1$
        // renaming is refused with a pointer to rename_metadata_object
        assertTrue("guide should point a rename at rename_metadata_object", //$NON-NLS-1$
            guide.contains("rename_metadata_object")); //$NON-NLS-1$
    }

    // ---- a handler rebind must not be mixed with other property changes ---------------------------

    private static JsonObject prop(String name, String value)
    {
        JsonObject o = new JsonObject();
        o.addProperty("name", name); //$NON-NLS-1$
        o.addProperty("value", value); //$NON-NLS-1$
        return o;
    }

    /**
     * The mix detector behind the handler-rebind rejection: a call that carries ONLY the rebind
     * property ({@code procedure} / {@code handler} alias, any case) is clean; any other property in
     * the same call is reported by name so the rebind path REJECTS instead of silently dropping it -
     * the same no-mixing policy the move ('parent'/'position') and button-command ('command')
     * branches enforce.
     */
    @Test
    public void testHandlerRebindMixDetection()
    {
        assertNull(ModifyMetadataTool.firstNonHandlerRebindProperty(
            Collections.singletonList(prop("procedure", "MyProc")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ModifyMetadataTool.firstNonHandlerRebindProperty(
            Collections.singletonList(prop("Handler", "MyProc")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ModifyMetadataTool.firstNonHandlerRebindProperty(
            Arrays.asList(prop("PROCEDURE", "A"), prop("handler", "B")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // The first foreign property is reported by name, wherever it sits in the list.
        assertEquals("title", ModifyMetadataTool.firstNonHandlerRebindProperty( //$NON-NLS-1$
            Arrays.asList(prop("procedure", "MyProc"), prop("title", "T")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("visible", ModifyMetadataTool.firstNonHandlerRebindProperty( //$NON-NLS-1$
            Arrays.asList(prop("visible", "false"), prop("handler", "MyProc")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
