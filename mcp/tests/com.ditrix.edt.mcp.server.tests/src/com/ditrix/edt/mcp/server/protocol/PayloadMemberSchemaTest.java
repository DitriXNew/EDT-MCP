/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.routemap.RouteMapRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The payload parameters of modify_metadata, create_metadata, merge_rules and business_process_route_map
 * declare their members, the validator refuses a wrong member before the tool runs, and the compacted
 * tools/list still carries their prose.
 */
public class PayloadMemberSchemaTest
{

    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    // ---- shape pins ----------------------------------------------------------------------------

    @Test
    public void propertiesDeclaresNameValueLanguageInBothMetadataTools()
    {
        for (String tool : List.of("modify_metadata", "create_metadata")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject item = items(param(tool, "properties")); //$NON-NLS-1$
            assertMembers(item, Set.of("name", "value", "language"), Set.of("name"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals("value is untyped: its form depends on the property", 0, //$NON-NLS-1$
                item.getAsJsonObject("properties").getAsJsonObject("value").size()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void roleRightsTemplatesAndPropertiesDeclareTheirMembers()
    {
        JsonObject rights = items(param("modify_metadata", "rights")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(rights, Set.of("object", "right", "value", "rls", "rlsFields"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            Set.of("object", "right"), true); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("[true,false,\"set\",\"unset\",\"provided\"]", //$NON-NLS-1$
            member(rights, "value").get("enum").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("array", member(rights, "rlsFields").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject templates = items(param("modify_metadata", "templates")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(templates, Set.of("op", "name", "condition"), Set.of("name"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("[\"add\",\"edit\",\"delete\"]", member(templates, "op").get("enum").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject roleProperties = param("modify_metadata", "roleProperties"); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(roleProperties, Set.of("setForNewObjects", "setForAttributesByDefault", //$NON-NLS-1$ //$NON-NLS-2$
            "independentRightsOfChildObjects"), Set.of(), true); //$NON-NLS-1$
    }

    @Test
    public void contentAndCommandsDeclareTheirMembers()
    {
        JsonObject content = items(param("modify_metadata", "content")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(content, Set.of("op", "metadata", "use", "autoRecord"), Set.of("metadata"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("[\"Use\",\"DontUse\",\"Auto\"]", member(content, "use").get("enum").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject commands = items(param("modify_metadata", "commands")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(commands, Set.of("command", "visible", "roles", "group", "after", "before"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            Set.of("command"), true); //$NON-NLS-1$
        assertMembers(items(member(commands, "roles")), Set.of("role", "visible"), Set.of("role", "visible"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void templateDeclaresTextAndSpreadsheetMembersLeavingTheMisplacedKeysToTheWriter()
    {
        JsonObject template = param("modify_metadata", "template"); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(template, Set.of("text", "mode", "oldText", "expectedHash", "expectedSource", "overwrite", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "cells", "merges", "areas", "columnWidths", "rowHeights"), Set.of(), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        JsonObject cells = items(member(template, "cells")); //$NON-NLS-1$
        assertMembers(cells, Set.of("row", "col", "replace", "text", "parameter", "bold", "fontSize", "hAlign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "vAlign", "wrap", "textOrientation", "autoIndent", "autoMarkIncomplete"), Set.of("row", "col"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        for (String lenient : List.of("bold", "autoMarkIncomplete")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertFalse(lenient + " reads yes/1 too, so it stays untyped", member(cells, lenient).has("type")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertMembers(items(member(template, "merges")), Set.of("fromRow", "fromCol", "toRow", "toCol"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            Set.of("fromRow", "fromCol", "toRow", "toCol"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertMembers(items(member(template, "areas")), Set.of("name", "fromRow", "fromCol", "toRow", "toCol"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            Set.of("name", "fromRow", "fromCol", "toRow", "toCol"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        JsonObject columns = items(member(template, "columnWidths")); //$NON-NLS-1$
        assertMembers(columns, Set.of("col", "width", "autoWidthCalculation", "widthWeightFactor"), Set.of("col"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            false);
        assertFalse(member(columns, "autoWidthCalculation").has("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(items(member(template, "rowHeights")), Set.of("row", "height"), Set.of("row", "height"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            false);
    }

    @Test
    public void decisionsDeclarePathAndTheFourAuthorableRules()
    {
        JsonObject decisions = items(param("merge_rules", "decisions")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(decisions, Set.of("path", "rule"), Set.of("path", "rule"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("[\"GetFromOther\",\"DoNotMerge\",\"MergePrioritizingMain\",\"MergePrioritizingOther\"]", //$NON-NLS-1$
            member(decisions, "rule").get("enum").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("[] addresses the whole configuration", member(decisions, "path").has("minItems")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void routeMapItemsDeclareTheWrittenMembersAndStayOpenForTheReader()
    {
        JsonObject points = items(param("business_process_route_map", "points")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(points, new LinkedHashSet<>(RouteMapRequest.POINT_KEYS), Set.of("name", "type"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the reader names the deferred point types", member(points, "type").has("enum")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject connections = items(param("business_process_route_map", "connections")); //$NON-NLS-1$ //$NON-NLS-2$
        assertMembers(connections, new LinkedHashSet<>(RouteMapRequest.CONNECTION_KEYS), Set.of("from", "to"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the reader names the port range", member(connections, "fromPort").has("maximum")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void compactedToolsListKeepsEveryPayloadDescription()
    {
        Map<String, List<String>> payloads = Map.of(
            "modify_metadata", List.of("properties", "rights", "templates", "roleProperties", "content", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "commands", "template"), //$NON-NLS-1$ //$NON-NLS-2$
            "create_metadata", List.of("properties"), //$NON-NLS-1$ //$NON-NLS-2$
            "merge_rules", List.of("decisions"), //$NON-NLS-1$ //$NON-NLS-2$
            "business_process_route_map", List.of("points", "connections")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Map.Entry<String, List<String>> entry : payloads.entrySet())
        {
            IMcpTool tool = tool(entry.getKey());
            JsonObject compacted = InputSchemaCompactor.compact(tool.getName(),
                JsonParser.parseString(tool.getInputSchema())).getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
            for (String name : entry.getValue())
            {
                JsonObject param = compacted.getAsJsonObject(name);
                assertTrue(entry.getKey() + "." + name + " lost its prose", //$NON-NLS-1$ //$NON-NLS-2$
                    param.has("description")); //$NON-NLS-1$
                JsonObject shape = param.has("items") ? param.getAsJsonObject("items") : param; //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse(entry.getKey() + "." + name + " publishes the members the server checks", //$NON-NLS-1$ //$NON-NLS-2$
                    shape.has("properties") || shape.has("required") || shape.has("additionalProperties")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertEquals(entry.getKey() + "." + name, "object", shape.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            assertEquals(new java.util.TreeSet<>(entry.getValue()),
                new java.util.TreeSet<>(InputSchemaCompactor.MEMBERS_CHECKED_ON_SERVER.get(entry.getKey())));
        }
    }

    // ---- the validator refuses a wrong member before the tool runs -----------------------------

    @Test
    public void wrongMembersAreRefusedNamingThePath()
    {
        assertRefused("modify_metadata", "{\"rights\":[{\"object\":\"Catalog.A\",\"right\":\"Read\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"rls\":\"WHERE TRUE\",\"rlsFields\":\"Owner\"}]}", "rights[0].rlsFields must be an array"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("modify_metadata", "{\"rights\":[{\"object\":\"Catalog.A\",\"right\":\"Read\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"value\":\"maybe\"}]}", "rights[0].value must be one of"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("modify_metadata", "{\"rights\":[{\"metadata\":\"Catalog.A\",\"right\":\"Read\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "rights[0].metadata is not a known key"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"content\":[{\"op\":\"move\",\"metadata\":\"Catalog.A\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "content[0].op must be one of"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"content\":[{\"metadata\":\"Catalog.A\",\"autoRecord\":\"Sometimes\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "content[0].autoRecord must be one of"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"templates\":[{\"op\":\"add\",\"condition\":\"TRUE\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "templates[0].name is required"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"roleProperties\":{\"setForNewObjects\":\"yes\"}}", //$NON-NLS-1$ //$NON-NLS-2$
            "roleProperties.setForNewObjects must be true or false"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"roleProperties\":{\"setForNewObject\":true}}", //$NON-NLS-1$ //$NON-NLS-2$
            "roleProperties.setForNewObject is not a known key"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"commands\":[{\"command\":\"CommonCommand.Print\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"roles\":[{\"role\":\"Role.Manager\",\"visible\":\"maybe\"}]}]}", //$NON-NLS-1$
            "commands[0].roles[0].visible must be one of"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"properties\":[{\"name\":\"synonym\",\"value\":\"X\",\"lang\":\"en\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "properties[0].lang is not a known key"); //$NON-NLS-1$
        assertRefused("create_metadata", "{\"properties\":[{\"value\":\"X\"}]}", "properties[0].name is required"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused("merge_rules", "{\"decisions\":[{\"path\":[],\"rule\":\"CustomMerge\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions[0].rule must be one of"); //$NON-NLS-1$
        assertRefused("merge_rules", "{\"decisions\":[{\"path\":\"commonModules\",\"rule\":\"DoNotMerge\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions[0].path must be an array"); //$NON-NLS-1$
        assertRefused("business_process_route_map", "{\"points\":[{\"name\":\"A\",\"type\":\"Start\",\"x\":\"left\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "points[0].x must be an integer"); //$NON-NLS-1$
        assertRefused("business_process_route_map", "{\"connections\":[{\"from\":\"A\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
            "connections[0].to is required"); //$NON-NLS-1$
    }

    @Test
    public void wrongTemplateMembersAreRefused()
    {
        assertRefused("modify_metadata", "{\"template\":{\"cell\":[]}}", "template.cell is not a known key"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused("modify_metadata", "{\"template\":{\"cells\":[{\"row\":-1,\"col\":0,\"text\":\"A\"}]}}", //$NON-NLS-1$ //$NON-NLS-2$
            "template.cells[0].row must be at least 0"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"template\":{\"cells\":[{\"row\":0,\"col\":0,\"hAlign\":\"Middle\"}]}}", //$NON-NLS-1$ //$NON-NLS-2$
            "template.cells[0].hAlign must be one of"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"template\":{\"cells\":[{\"row\":0,\"col\":0,\"fontSize\":10.5}]}}", //$NON-NLS-1$ //$NON-NLS-2$
            "template.cells[0].fontSize must be an integer"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"template\":{\"cells\":[{\"row\":0,\"col\":0,\"textOrientation\":3601}]}}", //$NON-NLS-1$ //$NON-NLS-2$
            "template.cells[0].textOrientation must be at most 3600"); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"template\":{\"merges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":1," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"toCol\":1,\"name\":\"M\"}]}}", "template.merges[0].name is not a known key"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("modify_metadata", "{\"template\":{\"rowHeights\":[{\"row\":0}]}}", //$NON-NLS-1$ //$NON-NLS-2$
            "template.rowHeights[0].height is required"); //$NON-NLS-1$
    }

    @Test
    public void correctFormsPass()
    {
        assertAccepted("modify_metadata", "{\"properties\":[{\"name\":\"synonym\",\"value\":\"X\",\"language\":\"en\"}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"name\":\"type\",\"value\":{\"types\":[\"String\"]}}]}"); //$NON-NLS-1$
        assertAccepted("modify_metadata", "{\"rights\":[{\"object\":\"Catalog.A\",\"right\":\"Read\",\"value\":true}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"object\":\"Catalog.A\",\"right\":\"Update\",\"value\":\"Provided\",\"rls\":\"WHERE TRUE\"," //$NON-NLS-1$
            + "\"rlsFields\":[\"Owner\"]}],\"templates\":[{\"name\":\"T\",\"condition\":\"TRUE\"}," //$NON-NLS-1$
            + "{\"op\":\"\",\"name\":\"U\",\"condition\":\"TRUE\"}],\"roleProperties\":{\"setForNewObjects\":false}}"); //$NON-NLS-1$
        assertAccepted("modify_metadata", "{\"content\":[{\"metadata\":\"Catalog.A\",\"use\":\"dontuse\"}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"op\":\"remove\",\"metadata\":\"Catalog.B\"},{\"metadata\":\"Catalog.C\",\"autoRecord\":\"Allow\"}]}"); //$NON-NLS-1$
        assertAccepted("modify_metadata", "{\"commands\":[{\"command\":\"CommonCommand.Print\",\"visible\":false," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"roles\":[{\"role\":\"Role.A\",\"visible\":true},{\"role\":\"Role.B\",\"visible\":\"default\"}]," //$NON-NLS-1$
            + "\"group\":\"NavigationPanelImportant\",\"after\":\"CommonCommand.Other\"}]}"); //$NON-NLS-1$
        assertAccepted("modify_metadata", "{\"template\":{\"mode\":\"replace\",\"text\":\"T\",\"overwrite\":true}}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertAccepted("modify_metadata", "{\"template\":{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"bold\":\"yes\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"wrap\":true,\"hAlign\":\"center\",\"autoMarkIncomplete\":1},{\"row\":1,\"col\":0,\"wrap\":\"Cut\"}]," //$NON-NLS-1$
            + "\"merges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":1}]," //$NON-NLS-1$
            + "\"areas\":[{\"name\":\"Header\",\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":1}]," //$NON-NLS-1$
            + "\"columnWidths\":[{\"col\":0,\"autoWidthCalculation\":\"1\",\"widthWeightFactor\":2}]," //$NON-NLS-1$
            + "\"rowHeights\":[{\"row\":0,\"height\":20}]}}"); //$NON-NLS-1$
        assertAccepted("create_metadata", "{\"properties\":[{\"name\":\"comment\",\"value\":\"Goods\"}]}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertAccepted("merge_rules", "{\"decisions\":[{\"path\":[],\"rule\":\"GetFromOther\"}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"path\":[\"commonModules\",\"A:A:A\"],\"rule\":\"DoNotMerge\"}]}"); //$NON-NLS-1$
        assertAccepted("business_process_route_map", "{\"points\":[{\"name\":\"A\",\"type\":\"Start\",\"x\":10}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"name\":\"T\",\"type\":\"Activity\",\"group\":true,\"taskDescription\":\"Do\"}]," //$NON-NLS-1$
            + "\"connections\":[{\"from\":\"A\",\"to\":\"T\",\"fromPort\":4,\"toPort\":2}]}"); //$NON-NLS-1$
    }

    @Test
    public void keysTheReadersExplainThemselvesPassTheValidator()
    {
        // A misplaced column key on a cell, a deferred route-map key and a point type the reader
        // refuses with a follow-up note: each keeps the reader's tailored message.
        assertAccepted("modify_metadata", "{\"template\":{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"widthWeightFactor\":2}],\"columnWidths\":[{\"col\":0,\"width\":5,\"textOrientation\":900}]}}"); //$NON-NLS-1$
        assertAccepted("business_process_route_map", "{\"points\":[{\"name\":\"S\",\"type\":\"Switch\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"bounds\":{}}],\"connections\":[{\"from\":\"S\",\"to\":\"S\",\"fromPort\":9,\"line\":[]}]}"); //$NON-NLS-1$
    }

    // ---- helpers -------------------------------------------------------------------------------

    private IMcpTool tool(String name)
    {
        IMcpTool tool = registry.getTool(name);
        assertNotNull(name, tool);
        return tool;
    }

    private JsonObject param(String toolName, String name)
    {
        JsonObject param = JsonParser.parseString(tool(toolName).getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties").getAsJsonObject(name); //$NON-NLS-1$
        assertNotNull(toolName + "." + name, param); //$NON-NLS-1$
        return param;
    }

    private static JsonObject items(JsonObject arraySchema)
    {
        assertEquals("array", arraySchema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        return arraySchema.getAsJsonObject("items"); //$NON-NLS-1$
    }

    private static JsonObject member(JsonObject objectSchema, String name)
    {
        return objectSchema.getAsJsonObject("properties").getAsJsonObject(name); //$NON-NLS-1$
    }

    private static void assertMembers(JsonObject objectSchema, Set<String> members, Set<String> required,
        boolean closed)
    {
        assertEquals("object", objectSchema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(members, objectSchema.getAsJsonObject("properties").keySet()); //$NON-NLS-1$
        Set<String> declaredRequired = new LinkedHashSet<>();
        JsonElement names = objectSchema.get("required"); //$NON-NLS-1$
        if (names != null)
        {
            names.getAsJsonArray().forEach(n -> declaredRequired.add(n.getAsString()));
        }
        assertEquals(required, declaredRequired);
        JsonElement additional = objectSchema.get("additionalProperties"); //$NON-NLS-1$
        assertEquals(objectSchema.toString(), closed, additional != null && !additional.getAsBoolean());
    }

    /** JSON-in-a-string keeps an explicit null, which resets a role override; an absent one is still missing. */
    @Test
    public void anExplicitNullRoleVisibilityResetsAndAMissingOneIsRefused()
    {
        Map<String, Object> reset = Map.of("commands", //$NON-NLS-1$
            "[{\"command\":\"CommonCommand.Print\",\"roles\":[{\"role\":\"Role.A\",\"visible\":null}]}]"); //$NON-NLS-1$
        assertNull(ToolArgumentValidator.validate("modify_metadata", //$NON-NLS-1$
            tool("modify_metadata").getInputSchema(), reset)); //$NON-NLS-1$
        assertRefused("modify_metadata", "{\"commands\":[{\"command\":\"CommonCommand.Print\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"roles\":[{\"role\":\"Role.A\"}]}]}", "commands[0].roles[0].visible is required."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String validate(String toolName, String argsJson)
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> args = GsonProvider.get().fromJson(argsJson, Map.class);
        return ToolArgumentValidator.validate(toolName, tool(toolName).getInputSchema(), args);
    }

    private void assertRefused(String toolName, String argsJson, String expected)
    {
        String message = validate(toolName, argsJson);
        assertNotNull(argsJson, message);
        assertTrue(message, message.contains(expected));
    }

    private void assertAccepted(String toolName, String argsJson)
    {
        assertNull(argsJson, validate(toolName, argsJson));
    }
}
