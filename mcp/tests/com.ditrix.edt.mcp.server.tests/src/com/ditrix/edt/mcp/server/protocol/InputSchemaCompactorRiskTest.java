/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

import org.junit.After;
import org.junit.Before;

import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Ratchet: a parameter whose description WARNS about an effect — it discards, overwrites,
 * deletes, terminates, or hands back unredacted data — must not lose that warning at the
 * {@code tools/list} boundary.
 * <p>
 * <b>Why a test and not a rule.</b> {@link InputSchemaCompactor} keeps such descriptions
 * through an allowlist, because no structural signal separates "this default writes" from
 * "this default is 100 rows" — the tools emit no JSON Schema {@code default} at all, and a
 * purely textual rule that keeps every mention of a default would retain 150 parameters
 * and ~32K characters, which is most of what the compaction saves. So the list stays
 * curated. What was NOT acceptable is how the list grew: review found these one at a time,
 * over four rounds — a false two-phase clause, then opaque payload shapes, then mutating
 * boolean defaults, then the external-changes conflict policy. Each was found by a reader,
 * not by the build.
 * <p>
 * This test closes that: a parameter that talks like it changes state or exposes data must
 * either survive compaction, or be listed in {@link #ACKNOWLEDGED} with a reason. The next
 * one cannot ship silently.
 */
public class InputSchemaCompactorRiskTest
{
    @Before
    public void setUp()
    {
        McpToolRegistry.getInstance().clear();
        BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
    }

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    /**
     * Words that mark a description as carrying an EFFECT rather than a shape. Deliberately
     * narrow — this is a tripwire, not a classifier.
     */
    private static final List<String> RISK_WORDS = Arrays.asList("discard", "overwrit", //$NON-NLS-1$ //$NON-NLS-2$
        "irrevers", "cannot be undone", "personal data", "not redacted", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "dangling", "wipes", "destroys"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Parameters that trip the tripwire but are deliberately NOT kept on the wire, each
     * with the reason. An entry here is a decision on the record, not an exemption to be
     * added lightly: the word appears, but the sentence does not warn the CALLER about an
     * effect of THIS parameter.
     */
    private static final Map<String, String> ACKNOWLEDGED = buildAcknowledged();

    private static Map<String, String> buildAcknowledged()
    {
        Map<String, String> ack = new java.util.HashMap<>();
        // 'dangling' here describes what the tool REPORTS, and resync_to_disk's own
        // description already carries the destructive-mode warning in full.
        ack.put("resync_to_disk.cleanDanglingReferences", //$NON-NLS-1$
            "the tool description states the destructive modes"); //$NON-NLS-1$
        // force= is named and explained in delete_metadata's own description, which is
        // kept in full ("force=true overrides that block and can leave dangling references").
        ack.put("delete_metadata.force", "covered by the tool description"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(ack);
    }

    @Test
    public void everyRiskBearingParameterSurvivesCompaction()
    {
        List<String> lost = new ArrayList<>();
        for (IMcpTool tool : McpToolRegistry.getInstance().getAllTools())
        {
            String raw = tool.getInputSchema();
            if (raw == null || raw.isEmpty())
            {
                continue;
            }
            JsonObject rawProps = properties(JsonParser.parseString(raw));
            JsonObject wireProps =
                properties(InputSchemaCompactor.compact(tool.getName(), JsonParser.parseString(raw)));
            if (rawProps == null)
            {
                continue;
            }
            for (String name : new HashSet<>(rawProps.keySet()))
            {
                String description = description(rawProps.get(name));
                if (description == null || !carriesRisk(description))
                {
                    continue;
                }
                String key = tool.getName() + "." + name; //$NON-NLS-1$
                boolean kept = wireProps != null && description(wireProps.get(name)) != null;
                if (!kept && !ACKNOWLEDGED.containsKey(key))
                {
                    lost.add(key);
                }
            }
        }
        assertTrue("These parameters warn about an effect (discard / overwrite / delete / " //$NON-NLS-1$
            + "unredacted data) and the warning does NOT reach tools/list. Either add them to " //$NON-NLS-1$
            + "InputSchemaCompactor.KEEP, or record the decision in ACKNOWLEDGED with a " //$NON-NLS-1$
            + "reason: " + lost, lost.isEmpty()); //$NON-NLS-1$
    }

    private static boolean carriesRisk(String description)
    {
        String lower = description.toLowerCase(Locale.ROOT);
        for (String word : RISK_WORDS)
        {
            if (lower.contains(word))
            {
                return true;
            }
        }
        return false;
    }

    private static JsonObject properties(JsonElement schema)
    {
        if (schema == null || !schema.isJsonObject())
        {
            return null;
        }
        JsonElement props = schema.getAsJsonObject().get("properties"); //$NON-NLS-1$
        return props != null && props.isJsonObject() ? props.getAsJsonObject() : null;
    }

    private static String description(JsonElement property)
    {
        if (property == null || !property.isJsonObject())
        {
            return null;
        }
        JsonElement description = property.getAsJsonObject().get("description"); //$NON-NLS-1$
        return description != null && description.isJsonPrimitive() ? description.getAsString() : null;
    }

    /** Guards the tripwire itself: a vocabulary that matches nothing would pass vacuously. */
    @Test
    public void theTripwireActuallyMatchesSomething()
    {
        int matched = 0;
        for (IMcpTool tool : McpToolRegistry.getInstance().getAllTools())
        {
            String raw = tool.getInputSchema();
            JsonObject props = raw == null ? null : properties(JsonParser.parseString(raw));
            if (props == null)
            {
                continue;
            }
            for (String name : props.keySet())
            {
                String description = description(props.get(name));
                if (description != null && carriesRisk(description))
                {
                    matched++;
                }
            }
        }
        assertTrue("the risk vocabulary matched no parameter at all - it has drifted away " //$NON-NLS-1$
            + "from the descriptions and the ratchet is inert", matched > 0); //$NON-NLS-1$
    }
}
