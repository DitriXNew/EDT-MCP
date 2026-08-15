/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Drops the prose from a tool's {@code inputSchema} parameters before it is serialized
 * into {@code tools/list}, keeping the SHAPE a call is built from — names, types,
 * {@code required}, {@code enum}, {@code default} — and keeping the handful of
 * descriptions that were measured to be load-bearing.
 * <p>
 * <b>Why this reverses a documented decision.</b> {@link OutputSchemaCompactor} states
 * that {@code inputSchema} is deliberately NOT compacted, because "a parameter's
 * description is what the model uses to build a correct call, and removing it produces
 * malformed calls". That was true as measured for #395: on Haiku, stripping parameter
 * prose made the model invent a {@code metadata} key for the role-rights {@code object}
 * key, invent a {@code type} value shape and invent a designer-era module path.
 * <p>
 * Re-measured on Sonnet 5 over 500 requests ({@code tests/tool-choice/}), the result
 * inverts: with the prose stripped and the shape intact, calls got MORE correct, not
 * less — 7 calls missing a required parameter out of 905, against 22 out of 882 with the
 * full prose. What the enum and the default carry is the call; the paragraph around them
 * is what costs tokens. So the decision is reversed for the parameter surface, on the
 * model bar the plugin actually targets, and the tool {@code description} stays in the
 * Java sources as before.
 * <p>
 * <b>What survives, and why it is an allowlist rather than a rule.</b> A few parameter
 * descriptions state a fact the schema cannot express, and dropping them measurably
 * breaks a request: {@code get_markers.markerKind} is the only place that says a
 * {@code task} marker means TODO/FIXME/XXX/HACK, and without it "find every FIXME" stops
 * resolving to {@code get_markers} at all. Those are listed in {@link #KEEP}; every other
 * parameter description stops at this wire boundary and lives on in the sources and in
 * {@code get_tool_guide}. A tool added later is stripped by default, which is the
 * measured-correct default.
 * <p>
 * <b>The walk is structural, not textual</b> — see {@link OutputSchemaCompactor} for the
 * full reasoning: this plugin declares properties literally called {@code type} and
 * {@code items}, so property-map KEYS are never read as schema keywords.
 */
public final class InputSchemaCompactor
{
    /** JSON Schema {@code "description"} key. */
    private static final String KEY_DESCRIPTION = "description"; //$NON-NLS-1$

    /** JSON Schema {@code "properties"} key — the top-level parameter map. */
    private static final String KEY_PROPERTIES = "properties"; //$NON-NLS-1$

    /**
     * Keywords whose value is a MAP of name -> subschema. The map's KEYS are names
     * (never keywords) and only its VALUES are recursed into.
     */
    private static final List<String> SCHEMA_MAP_KEYWORDS = Arrays.asList(KEY_PROPERTIES,
        "patternProperties", "$defs", "definitions", "dependentSchemas", "dependencies"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * Keywords whose value is a single subschema, or (for the applicator keywords) an
     * ARRAY of subschemas. Both shapes are handled.
     */
    private static final List<String> SCHEMA_VALUE_KEYWORDS = Arrays.asList("items", //$NON-NLS-1$
        "additionalProperties", "contains", "not", "if", "then", "else", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "allOf", "anyOf", "oneOf", "prefixItems"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Top-level parameters whose description states a fact the schema cannot express, per
     * tool. Keep this list short and justified: each entry is prose paid for on every
     * request of every session.
     */
    private static final Map<String, Set<String>> KEEP = buildKeepList();

    /**
     * Parameter names whose description survives in EVERY tool that declares them, because
     * the description carries the VALUE SHAPE and the schema has no way to express it.
     * <p>
     * {@code modulePath} is the measured case: a plain {@code string} whose only statement
     * of the expected form is the example {@code 'CommonModules/MyModule/Module.bsl'} in its
     * description, repeated across the 12 tools that take it. The 500-request sweep did not
     * catch its removal — there the model planned a {@code list_modules} lookup first, which
     * grades as correct planning — but a live run against a real server did: the agent
     * reported it could not tell whether the value was a file path or a {@code Type.Name}
     * token, and had to spend a discovery call to find out.
     */
    private static final Set<String> KEEP_IN_EVERY_TOOL = asSet("modulePath"); //$NON-NLS-1$

    private InputSchemaCompactor()
    {
        // Utility class
    }

    private static Map<String, Set<String>> buildKeepList()
    {
        Map<String, Set<String>> keep = new HashMap<>();
        // The only statement anywhere that a 'task' marker means TODO/FIXME/XXX/HACK.
        keep.put("get_markers", asSet("markerKind")); //$NON-NLS-1$ //$NON-NLS-2$
        // Two filters that are mutually exclusive and differ in matching semantics.
        keep.put("get_project_errors", asSet("objects", "objectFqns")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // 'callers' vs 'callees' - the enum values alone do not say which way they point.
        keep.put("get_method_call_hierarchy", asSet("direction")); //$NON-NLS-1$ //$NON-NLS-2$
        // A scope limit that makes the tool inapplicable: FILE infobases only.
        keep.put("create_infobase", asSet("infobaseFile")); //$NON-NLS-1$ //$NON-NLS-2$
        // debug=true changes the return contract and requires a wait_for_break follow-up.
        keep.put("run_yaxunit_tests", asSet("debug")); //$NON-NLS-1$ //$NON-NLS-2$
        // The lost-update guard: what the hash is and where it comes from.
        keep.put("write_module_source", asSet("expectedHash")); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(keep);
    }

    private static Set<String> asSet(String... names)
    {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }

    /**
     * Returns a copy of {@code inputSchema} with parameter descriptions removed, except
     * the top-level parameters allowlisted for {@code toolName}.
     *
     * @param toolName the tool the schema belongs to, used to look up the allowlist; may
     *            be {@code null}, in which case nothing is kept
     * @param inputSchema the schema to compact; may be {@code null}
     * @return the compacted schema, or {@code null} when {@code inputSchema} is
     *         {@code null}
     */
    public static JsonElement compact(String toolName, JsonElement inputSchema)
    {
        if (inputSchema == null)
        {
            return null;
        }
        Set<String> keep = new HashSet<>(KEEP.getOrDefault(toolName, Collections.emptySet()));
        keep.addAll(KEEP_IN_EVERY_TOOL);
        JsonElement copy = inputSchema.deepCopy();
        stripSchema(copy, keep);
        return copy;
    }

    /**
     * Removes the {@code description} keyword from {@code element} (treated as a schema)
     * and from every nested subschema, in place. The allowlist applies to the TOP-LEVEL
     * parameter map only: a nested field never keeps its prose, because the measurement
     * covers the parameters a call is built from, not the shapes inside them.
     *
     * @param element the schema node to strip; never {@code null}
     * @param keepTopLevel names of top-level parameters whose description survives
     */
    private static void stripSchema(JsonElement element, Set<String> keepTopLevel)
    {
        if (element.isJsonArray())
        {
            for (JsonElement item : element.getAsJsonArray())
            {
                stripSchema(item, Collections.emptySet());
            }
            return;
        }
        if (!element.isJsonObject())
        {
            return;
        }

        JsonObject schema = element.getAsJsonObject();
        // The schema's own top-level description (the object's, not a parameter's) is
        // not part of the parameter surface and goes with the rest.
        schema.remove(KEY_DESCRIPTION);

        for (String keyword : SCHEMA_MAP_KEYWORDS)
        {
            JsonElement map = schema.get(keyword);
            if (map == null || !map.isJsonObject())
            {
                continue;
            }
            // Recurse into the VALUES only: a key here is a property name, which may
            // legitimately be "description", "type" or "items".
            JsonObject entries = map.getAsJsonObject();
            for (String name : entries.keySet())
            {
                JsonElement child = entries.get(name);
                if (KEY_PROPERTIES.equals(keyword) && keepTopLevel.contains(name)
                    && child.isJsonObject())
                {
                    // Allowlisted parameter: keep its own description, strip anything
                    // nested below it.
                    JsonObject kept = child.getAsJsonObject();
                    JsonElement own = kept.get(KEY_DESCRIPTION);
                    stripSchema(kept, Collections.emptySet());
                    if (own != null)
                    {
                        kept.add(KEY_DESCRIPTION, own);
                    }
                }
                else
                {
                    stripSchema(child, Collections.emptySet());
                }
            }
        }

        for (String keyword : SCHEMA_VALUE_KEYWORDS)
        {
            JsonElement value = schema.get(keyword);
            if (value != null && (value.isJsonObject() || value.isJsonArray()))
            {
                stripSchema(value, Collections.emptySet());
            }
        }
    }
}
