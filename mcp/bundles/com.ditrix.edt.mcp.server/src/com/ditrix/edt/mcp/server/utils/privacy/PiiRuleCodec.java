/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;

/**
 * JSON codec for a {@link PiiRuleSet}, plus the loader for the bundled default rule
 * table ({@code pii-defaults.json}, packaged next to this class). It uses the shared
 * {@link GsonProvider} (HTML escaping disabled) so regex metacharacters survive
 * round-trips readable and byte-for-byte lossless (commas, pipes, backslashes).
 * <p>
 * Decoding is deliberately tolerant: a malformed document yields {@link PiiRuleSet#EMPTY}
 * rather than throwing, a rule with no usable {@code regex} is skipped, and every other
 * omitted field falls back to a sensible, fail-closed default (a rule is enabled, scoped
 * to {@link PiiRuleScope#BOTH}, emitted as a flat {@code [redacted]} mask). The wire
 * shape is an object {@code {"version":1,"rules":[...]}}; a bare top-level array is also
 * accepted for hand-authored files.
 */
public final class PiiRuleCodec
{
    /** Current schema version stamped into encoded documents. */
    public static final int SCHEMA_VERSION = 1;

    /** The bundled default rule table, resolved relative to this class's package. */
    static final String DEFAULTS_RESOURCE = "pii-defaults.json"; //$NON-NLS-1$

    private static final String KEY_VERSION = "version"; //$NON-NLS-1$
    private static final String KEY_RULES = "rules"; //$NON-NLS-1$
    private static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$
    private static final String KEY_REGEX = "regex"; //$NON-NLS-1$
    private static final String KEY_SCOPE = "scope"; //$NON-NLS-1$
    private static final String KEY_REPRESENTATION = "representation"; //$NON-NLS-1$
    private static final String KEY_COUNTABLE = "countable"; //$NON-NLS-1$

    /** Default when {@code enabled} is omitted: a rule that is present is presumed active. */
    private static final boolean DEFAULT_ENABLED = true;

    /** Default when {@code scope} is omitted: apply everywhere (fail-closed). */
    private static final PiiRuleScope DEFAULT_SCOPE = PiiRuleScope.BOTH;

    /** Default when {@code representation} is omitted: a flat, non-linkable mask. */
    private static final String DEFAULT_REPRESENTATION = Pseudonymizer.MASK;

    /** Default when {@code countable} is omitted: emit the representation verbatim (flat mask). */
    private static final boolean DEFAULT_COUNTABLE = false;

    private PiiRuleCodec()
    {
        // Utility class
    }

    /**
     * Loads the bundled default rule table. Never {@code null}; returns
     * {@link PiiRuleSet#EMPTY} only if the resource is absent (a packaging regression,
     * which the codec test asserts against) so production degrades gracefully rather
     * than crashing the wire path.
     *
     * @return the default rules, or {@link PiiRuleSet#EMPTY} when the resource is missing
     */
    public static PiiRuleSet loadBundledDefaults()
    {
        try (InputStream in = PiiRuleCodec.class.getResourceAsStream(DEFAULTS_RESOURCE))
        {
            if (in == null)
            {
                return PiiRuleSet.EMPTY;
            }
            return decode(in);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("failed to read " + DEFAULTS_RESOURCE, e); //$NON-NLS-1$
        }
    }

    /**
     * Decodes a rule set from a UTF-8 JSON stream. The stream is fully read and closed.
     *
     * @param in the JSON stream (never {@code null})
     * @return the decoded rules (never {@code null})
     */
    public static PiiRuleSet decode(InputStream in)
    {
        return decode(readUtf8(in));
    }

    /**
     * Decodes a rule set from a JSON string. Tolerant: unparseable input yields
     * {@link PiiRuleSet#EMPTY}, and individual malformed rules are skipped.
     *
     * @param json the JSON document (may be {@code null}/empty)
     * @return the decoded rules (never {@code null})
     */
    public static PiiRuleSet decode(String json)
    {
        if (json == null || json.isEmpty())
        {
            return PiiRuleSet.EMPTY;
        }
        JsonElement root;
        try
        {
            root = JsonParser.parseString(json);
        }
        catch (RuntimeException e)
        {
            return PiiRuleSet.EMPTY;
        }
        JsonArray array = rulesArray(root);
        if (array == null)
        {
            return PiiRuleSet.EMPTY;
        }
        List<PiiRule> rules = new ArrayList<>(array.size());
        for (JsonElement element : array)
        {
            if (element != null && element.isJsonObject())
            {
                PiiRule rule = toRule(element.getAsJsonObject());
                if (rule != null)
                {
                    rules.add(rule);
                }
            }
        }
        return new PiiRuleSet(rules);
    }

    /**
     * Encodes a rule set to a JSON string in the canonical
     * {@code {"version":1,"rules":[...]}} shape. Round-trips losslessly through
     * {@link #decode(String)}.
     *
     * @param set the rules to encode (may be {@code null} - treated as empty)
     * @return the JSON document
     */
    public static String encode(PiiRuleSet set)
    {
        JsonObject root = new JsonObject();
        root.addProperty(KEY_VERSION, Integer.valueOf(SCHEMA_VERSION));
        JsonArray array = new JsonArray();
        if (set != null)
        {
            for (PiiRule rule : set.getRules())
            {
                array.add(toJson(rule));
            }
        }
        root.add(KEY_RULES, array);
        return GsonProvider.toJson(root);
    }

    private static JsonObject toJson(PiiRule rule)
    {
        JsonObject obj = new JsonObject();
        obj.addProperty(KEY_ENABLED, Boolean.valueOf(rule.isEnabled()));
        obj.addProperty(KEY_REGEX, rule.getRegex());
        obj.addProperty(KEY_SCOPE, rule.getScope().name());
        obj.addProperty(KEY_REPRESENTATION, rule.getRepresentation());
        obj.addProperty(KEY_COUNTABLE, Boolean.valueOf(rule.isCountable()));
        return obj;
    }

    /**
     * Extracts the rules array from either a bare top-level array or an object with a
     * {@code rules} array. Returns {@code null} when neither shape is present.
     */
    private static JsonArray rulesArray(JsonElement root)
    {
        if (root.isJsonArray())
        {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject())
        {
            JsonElement rules = root.getAsJsonObject().get(KEY_RULES);
            if (rules != null && rules.isJsonArray())
            {
                return rules.getAsJsonArray();
            }
        }
        return null;
    }

    /**
     * Builds a rule from a JSON object, applying defaults for omitted fields. Returns
     * {@code null} when there is no usable {@code regex} (a rule with no pattern is
     * meaningless and is skipped).
     */
    private static PiiRule toRule(JsonObject obj)
    {
        String regex = optString(obj, KEY_REGEX, null);
        if (regex == null || regex.trim().isEmpty())
        {
            return null;
        }
        boolean enabled = optBoolean(obj, KEY_ENABLED, DEFAULT_ENABLED);
        PiiRuleScope scope = optScope(obj, DEFAULT_SCOPE);
        String representation = optString(obj, KEY_REPRESENTATION, DEFAULT_REPRESENTATION);
        boolean countable = optBoolean(obj, KEY_COUNTABLE, DEFAULT_COUNTABLE);
        return new PiiRule(enabled, regex, scope, representation, countable);
    }

    private static String optString(JsonObject obj, String key, String fallback)
    {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
        {
            return el.getAsString();
        }
        return fallback;
    }

    private static boolean optBoolean(JsonObject obj, String key, boolean fallback)
    {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean())
        {
            return el.getAsBoolean();
        }
        return fallback;
    }

    private static PiiRuleScope optScope(JsonObject obj, PiiRuleScope fallback)
    {
        String raw = optString(obj, KEY_SCOPE, null);
        if (raw == null)
        {
            return fallback;
        }
        try
        {
            return PiiRuleScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return fallback;
        }
    }

    private static String readUtf8(InputStream in)
    {
        if (in == null)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            int n;
            while ((n = reader.read(buf)) != -1)
            {
                sb.append(buf, 0, n);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("failed to read PII rule JSON", e); //$NON-NLS-1$
        }
        return sb.toString();
    }
}
