/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSupport;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceTestSections;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the command-interface surface of {@link ModifyMetadataTool} (issue #666): the declared
 * {@code commands} payload, its refusals and the success shape.
 */
public class ModifyMetadataCommandInterfaceTest
{
    @Test
    public void testSchemaDeclaresCommandsAsAnOptionalPayload()
    {
        JsonObject schema = JsonParser.parseString(new ModifyMetadataTool().getInputSchema()).getAsJsonObject();
        JsonObject commands = schema.getAsJsonObject("properties").getAsJsonObject("commands"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("array", commands.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String description = commands.get("description").getAsString(); //$NON-NLS-1$
        assertTrue(description, description.contains("{command, visible?, roles?, group?, after? | before?}")); //$NON-NLS-1$
        assertTrue(description, description.contains("'Subsystem.<Name>.CommandInterface'")); //$NON-NLS-1$
        assertTrue(description, description.contains("get_metadata_details")); //$NON-NLS-1$
        assertFalse("commands is an alternative to properties, never schema-required", //$NON-NLS-1$
            schema.getAsJsonArray("required").toString().contains("commands")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresTheCommandInterfaceResult()
    {
        JsonObject properties = JsonParser.parseString(new ModifyMetadataTool().getOutputSchema())
            .getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(properties.has("commands")); //$NON-NLS-1$
        assertTrue(properties.has("stored")); //$NON-NLS-1$
        assertTrue(properties.has("unchanged")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAdvertisesTheCommandInterface()
    {
        assertTrue(new ModifyMetadataTool().getDescription().contains("command interface")); //$NON-NLS-1$
    }

    @Test
    public void testCommandsRefusedOnAnObjectWithoutASection()
    {
        String error = ModifyMetadataTool.commandsOnlyForCommandInterfaceError("Catalog.Products.CommandInterface"); //$NON-NLS-1$
        assertTrue(error, error.contains("'Subsystem.<Name>.CommandInterface'")); //$NON-NLS-1$
        assertTrue(error, error.contains("'Configuration.MainSectionCommandInterface'")); //$NON-NLS-1$
        assertTrue(error, error.endsWith("only a subsystem and the configuration have a section command interface.")); //$NON-NLS-1$
    }

    @Test
    public void testCommandsRefusedOnAPlainObjectFqn()
    {
        String error = ModifyMetadataTool.commandsOnlyForCommandInterfaceError("Справочник.Товары"); //$NON-NLS-1$
        assertTrue(error, error.contains("'Справочник.Товары' does not qualify: it is not a command interface address.")); //$NON-NLS-1$
    }

    @Test
    public void testCommandsArgIsParsedStrictly()
    {
        List<JsonObject> entries = new ArrayList<>();
        assertNull(ModifyMetadataTool.parseCommandsArg(Collections.emptyMap(), entries));
        assertTrue(entries.isEmpty());

        assertNull(ModifyMetadataTool.parseCommandsArg(
            Map.of("commands", "[{command:'CommonCommand.Print', visible:false}]"), entries)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, entries.size());

        String notArray = ModifyMetadataTool.parseCommandsArg(Map.of("commands", "{command:'X'}"), //$NON-NLS-1$ //$NON-NLS-2$
            new ArrayList<>());
        assertTrue(notArray, notArray.contains("'commands' must be an array")); //$NON-NLS-1$

        // A non-object entry is refused rather than skipped: the batch is validated as a whole.
        String skipped = ModifyMetadataTool.parseCommandsArg(
            Map.of("commands", "[{command:'CommonCommand.Print', visible:false}, 'CommonCommand.Other']"), //$NON-NLS-1$ //$NON-NLS-2$
            new ArrayList<>());
        assertTrue(skipped, skipped.contains("commands[1] must be an object")); //$NON-NLS-1$
    }

    @Test
    public void testResultOfAChange()
    {
        CommandInterfaceSupport.EditResult edit = new CommandInterfaceSupport.EditResult();
        edit.plan = plan("[{command:'CommonCommand.Print', visible:false}, " //$NON-NLS-1$
            + "{command:'CommonCommand.Print', group:'NavigationPanelImportant'}]"); //$NON-NLS-1$
        edit.writtenFqn = "Subsystem.Sales.CommandInterface"; //$NON-NLS-1$
        edit.stored = JsonParser.parseString("{commands:{'CommonCommand.Print':{visible:{common:false,roles:{}}," //$NON-NLS-1$
            + "group:'NavigationPanelImportant'}},order:{}}").getAsJsonObject(); //$NON-NLS-1$
        JsonObject result = JsonParser.parseString(
            ModifyMetadataTool.buildCommandInterfaceResult("Subsystem.Sales.CommandInterface", edit, true)) //$NON-NLS-1$
            .getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("modified", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Subsystem.Sales.CommandInterface", result.get("fqn").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"visibility\":1,\"placement\":1,\"order\":0}", result.get("commands").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.get("persisted").getAsBoolean()); //$NON-NLS-1$
        assertEquals("NavigationPanelImportant", result.getAsJsonObject("stored").getAsJsonObject("commands") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject("CommonCommand.Print").get("group").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("nothing was a no-op", result.get("unchanged")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Modified Subsystem.Sales.CommandInterface (visibility: 1, placement: 1, order: 0)", //$NON-NLS-1$
            result.get("message").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testResultOfANoOpSaysUnchanged()
    {
        CommandInterfaceSupport.EditResult edit = new CommandInterfaceSupport.EditResult();
        edit.plan = plan("[{command:'CommonCommand.Print', visible:true}]"); //$NON-NLS-1$
        JsonObject result = JsonParser.parseString(
            ModifyMetadataTool.buildCommandInterfaceResult("Subsystem.Sales.CommandInterface", edit, false)) //$NON-NLS-1$
            .getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("unchanged", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("[\"CommonCommand.Print\"]", result.get("unchanged").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("persisted").getAsBoolean()); //$NON-NLS-1$
        assertNull(result.get("stored")); //$NON-NLS-1$
    }

    private static CommandInterfaceSection.Plan plan(String json)
    {
        List<JsonObject> entries = new ArrayList<>();
        JsonParser.parseString(json).getAsJsonArray().forEach(e -> entries.add(e.getAsJsonObject()));
        CommandInterfaceSection.PlanResult result =
            CommandInterfaceTestSections.salesSection().plan(entries, ref -> null);
        assertNull(result.error, result.error);
        return result.plan;
    }
}
