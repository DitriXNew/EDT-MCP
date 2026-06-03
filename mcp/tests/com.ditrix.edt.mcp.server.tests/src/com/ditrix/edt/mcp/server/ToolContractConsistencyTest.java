/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Registry-driven contract linter: enforces a single input-schema contract
 * across <em>every</em> registered {@link IMcpTool}, so a newly added tool that
 * violates the conventions fails the build automatically rather than drifting
 * silently (card: tool-contract-consistency-tests).
 *
 * <p>These checks are intentionally limited to what is verifiable purely at
 * runtime from {@code getInputSchema()} / {@code getName()}. The complementary
 * direction — "every key read in {@code execute()} is declared in the schema"
 * (CLAUDE.md don't #6) — requires source/bytecode introspection of string
 * literals and is therefore not asserted here; it is tracked on the card for a
 * build-time check or the typed params-accessor refactor.</p>
 */
public class ToolContractConsistencyTest
{
    /** MCP tool names are snake_case: lowercase, digits, underscores; must start with a letter. */
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]*$"); //$NON-NLS-1$

    /** JSON property names use lowerCamelCase (the project's parameter convention, e.g. projectName, modulePath). */
    private static final Pattern PARAM_NAME = Pattern.compile("^[a-z][a-zA-Z0-9]*$"); //$NON-NLS-1$

    private static final Set<String> VALID_TYPES = new HashSet<>();
    static
    {
        VALID_TYPES.add("string"); //$NON-NLS-1$
        VALID_TYPES.add("integer"); //$NON-NLS-1$
        VALID_TYPES.add("number"); //$NON-NLS-1$
        VALID_TYPES.add("boolean"); //$NON-NLS-1$
        VALID_TYPES.add("array"); //$NON-NLS-1$
        VALID_TYPES.add("object"); //$NON-NLS-1$
    }

    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        // registerTools() is package-private; populate the singleton with the
        // exact production tool set (same call Activator uses for the prefs UI).
        registry = McpToolRegistry.getInstance();
        new McpServer().registerTools();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    /** Sanity: the production registration actually populated tools (guards against an empty sweep passing vacuously). */
    @Test
    public void testRegistryIsPopulated()
    {
        assertTrue("registerTools() should register a non-trivial set of tools", //$NON-NLS-1$
            registry.getToolCount() >= 50);
    }

    @Test
    public void testToolNamesAreSnakeCaseAndUnique()
    {
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String name = tool.getName();
            if (name == null || !TOOL_NAME.matcher(name).matches())
            {
                problems.add(tool.getClass().getSimpleName() + ": invalid tool name '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (!seen.add(name))
            {
                problems.add("duplicate tool name '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertNoProblems("tool name contract", problems); //$NON-NLS-1$
    }

    @Test
    public void testEveryToolHasANonEmptyDescription()
    {
        // The tool description is the always-loaded, client-facing text in tools/list
        // (it is what an agent reads to pick a tool). An empty/blank one is a contract
        // hole — a JSON-validity-clean schema with no description still ships a useless
        // entry. (Per-property descriptions are checked separately below.)
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String desc = tool.getDescription();
            if (desc == null || desc.trim().isEmpty())
            {
                problems.add(describe(tool) + ": getDescription() is null/empty"); //$NON-NLS-1$
            }
        }
        assertNoProblems("tool description contract", problems); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaIsAWellFormedObjectSchema()
    {
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String where = describe(tool);
            String raw = tool.getInputSchema();
            if (raw == null || raw.trim().isEmpty())
            {
                problems.add(where + ": getInputSchema() is null/empty"); //$NON-NLS-1$
                continue;
            }
            JsonElement parsed;
            try
            {
                parsed = JsonParser.parseString(raw);
            }
            catch (RuntimeException e)
            {
                problems.add(where + ": getInputSchema() is not valid JSON: " + e.getMessage()); //$NON-NLS-1$
                continue;
            }
            if (!parsed.isJsonObject())
            {
                problems.add(where + ": schema root is not a JSON object"); //$NON-NLS-1$
                continue;
            }
            JsonObject schema = parsed.getAsJsonObject();
            JsonElement type = schema.get("type"); //$NON-NLS-1$
            if (type == null || !"object".equals(type.getAsString())) //$NON-NLS-1$
            {
                problems.add(where + ": schema 'type' must be \"object\""); //$NON-NLS-1$
            }
        }
        assertNoProblems("schema well-formedness", problems); //$NON-NLS-1$
    }

    @Test
    public void testEveryRequiredParamIsADeclaredProperty()
    {
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = parseObjectSchema(tool);
            if (schema == null)
            {
                continue; // well-formedness is asserted by its own test
            }
            Set<String> props = propertyNames(schema);
            JsonElement required = schema.get("required"); //$NON-NLS-1$
            if (required == null || !required.isJsonArray())
            {
                continue; // a missing 'required' array means "no required params" — allowed
            }
            for (JsonElement r : required.getAsJsonArray())
            {
                String req = r.getAsString();
                if (!props.contains(req))
                {
                    problems.add(describe(tool) + ": required param '" + req //$NON-NLS-1$
                        + "' is not declared in properties"); //$NON-NLS-1$
                }
            }
        }
        assertNoProblems("required-param declaration", problems); //$NON-NLS-1$
    }

    @Test
    public void testEveryPropertyHasValidTypeAndNonEmptyDescription()
    {
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = parseObjectSchema(tool);
            if (schema == null)
            {
                continue;
            }
            JsonElement propsEl = schema.get("properties"); //$NON-NLS-1$
            if (propsEl == null || !propsEl.isJsonObject())
            {
                continue; // a parameterless tool is allowed
            }
            JsonObject props = propsEl.getAsJsonObject();
            for (String paramName : props.keySet())
            {
                String at = describe(tool) + " param '" + paramName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
                if (!PARAM_NAME.matcher(paramName).matches())
                {
                    problems.add(at + ": name is not lowerCamelCase"); //$NON-NLS-1$
                }
                JsonElement defEl = props.get(paramName);
                if (!defEl.isJsonObject())
                {
                    problems.add(at + ": property definition is not an object"); //$NON-NLS-1$
                    continue;
                }
                JsonObject def = defEl.getAsJsonObject();
                JsonElement type = def.get("type"); //$NON-NLS-1$
                if (type == null || !type.isJsonPrimitive() || !VALID_TYPES.contains(type.getAsString()))
                {
                    problems.add(at + ": missing/invalid 'type'"); //$NON-NLS-1$
                }
                JsonElement desc = def.get("description"); //$NON-NLS-1$
                if (desc == null || desc.getAsString().trim().isEmpty())
                {
                    problems.add(at + ": missing/empty 'description'"); //$NON-NLS-1$
                }
            }
        }
        assertNoProblems("property type/description contract", problems); //$NON-NLS-1$
    }

    // === helpers ===

    private static String describe(IMcpTool tool)
    {
        return tool.getClass().getSimpleName() + " (" + tool.getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject parseObjectSchema(IMcpTool tool)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(tool.getInputSchema());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Set<String> propertyNames(JsonObject schema)
    {
        Set<String> names = new HashSet<>();
        JsonElement propsEl = schema.get("properties"); //$NON-NLS-1$
        if (propsEl != null && propsEl.isJsonObject())
        {
            names.addAll(propsEl.getAsJsonObject().keySet());
        }
        return names;
    }

    private static void assertNoProblems(String contract, Collection<String> problems)
    {
        assertTrue(contract + " violations:\n  " + String.join("\n  ", problems), //$NON-NLS-1$ //$NON-NLS-2$
            problems.isEmpty());
    }
}
