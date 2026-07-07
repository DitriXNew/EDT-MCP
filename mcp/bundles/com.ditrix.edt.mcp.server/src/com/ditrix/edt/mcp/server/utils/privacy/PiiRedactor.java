/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PiiRedactionSettings;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * The 152-FZ PII redactor invoked at the single wire-serialization choke point
 * ({@code McpProtocolHandler}) on every tool result. It is a strict no-op unless
 * redaction is enabled AND the tool is flagged {@link IMcpTool#returnsInfobaseData()};
 * in every skip case it returns the SAME {@link String} reference it was given, so an
 * OFF / not-flagged / error / no-PII result stays byte-identical (the golden and the
 * flagged tools' {@code assert_no_diff} e2e stay green when redaction is off).
 * <p>
 * Detection is two-layered and fully reflective over the JSON tree:
 * <ol>
 * <li>{@link AttributeNameDictionary} on a JSON KEY (and, for the canonical
 * {@code value} field of a variable/evaluate DTO, on its sibling {@code name} field)
 * classifies a value by its attribute/variable name;</li>
 * <li>{@link ContentPatterns} regexes on the value content catch structured data
 * (SNILS / INN / passport / e-mail / phone) regardless of key.</li>
 * </ol>
 * A {@link Sensitivity#NORMAL} hit is replaced with a stable pseudonym; special-
 * category / biometric data is fully masked. A project PII catalog (layer 0, from
 * the metadata model) is deferred to a follow-up.
 * <p>
 * The package is pure and headless: the enabled decision comes from
 * {@link PiiRedactionSettings} (itself headless-safe) and the audit is an injected
 * {@link AuditSink} (the production wiring logs meta only via {@link Activator}), so
 * the whole redaction is unit-testable through {@link #apply} without OSGi.
 */
public final class PiiRedactor
{
    /**
     * Sink for the redaction audit trail. Receives META ONLY (tool name, the most
     * severe sensitivity masked, and the count) - never a redacted value or a
     * token-to-value mapping. The default is {@link #NONE} (no-op) so the core stays
     * pure; production wires a logger.
     */
    @FunctionalInterface
    public interface AuditSink
    {
        /**
         * Records that a redaction happened.
         *
         * @param toolName the flagged tool whose result was redacted
         * @param maxSensitivity the most severe sensitivity class masked
         * @param redactionCount the number of values masked (&gt; 0)
         */
        void record(String toolName, Sensitivity maxSensitivity, int redactionCount);

        /** A no-op sink (used by the pure core and in tests). */
        AuditSink NONE = (toolName, maxSensitivity, redactionCount) -> {
            // no audit
        };
    }

    /** Canonical ToolResult success/error flag. */
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    /** The DTO field holding a variable's / evaluation's serialized value. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** The DTO field holding a variable's name (sibling classifier for {@code value}). */
    private static final String KEY_NAME = "name"; //$NON-NLS-1$

    /** DTO field: value was truncated (describes the ORIGINAL value length). */
    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$

    /** DTO field: original length before truncation. */
    private static final String KEY_FULL_LENGTH = "fullLength"; //$NON-NLS-1$

    /** Fallback tool name for the audit line when a tool reports none. */
    private static final String UNKNOWN_TOOL = "<unknown>"; //$NON-NLS-1$

    /** Per-JVM-run pseudonymiser (stable within a run, no reverse table). */
    private static final Pseudonymizer PSEUDONYMIZER = new Pseudonymizer();

    /** Production audit: a meta-only INFO line via the plugin log. */
    private static final AuditSink DEFAULT_AUDIT = PiiRedactor::logAudit;

    private PiiRedactor()
    {
        // Utility class
    }

    /**
     * The choke-point entry: redacts {@code result} when redaction is enabled and
     * {@code tool} is flagged {@link IMcpTool#returnsInfobaseData()}, otherwise
     * returns the same reference. The enabled decision (preference plus the
     * {@code EDT_MCP_PII_REDACTION} env kill-switch) is resolved by
     * {@link PiiRedactionSettings#isEnabled()}.
     *
     * @param tool the tool that produced the result (may be {@code null})
     * @param params the tool call parameters (reserved for the deferred per-infobase
     *            PII flag; unused in v1)
     * @param result the tool's serialized result (may be {@code null})
     * @return the redacted JSON, or the original {@code result} reference when nothing
     *         is redacted
     */
    public static String redactIfEnabled(IMcpTool tool, Map<String, String> params, String result) // NOSONAR params is reserved for the deferred per-infobase PII flag; the wire contract passes it
    {
        return apply(PiiRedactionSettings.isEnabled(), tool, result, DEFAULT_AUDIT);
    }

    /**
     * The pure, headless-testable core. Applies the four guards (enabled, flagged,
     * error-payload, parseable JSON) and, if they pass, walks the JSON tree redacting
     * personal data. Returns the SAME {@code result} reference whenever nothing is
     * masked (never a no-op re-serialization); otherwise returns the re-serialized,
     * redacted tree and notifies {@code audit}.
     *
     * @param enabled whether redaction is active
     * @param tool the tool that produced the result (may be {@code null})
     * @param result the tool's serialized result (may be {@code null})
     * @param audit the audit sink (may be {@code null} - treated as no-op)
     * @return the redacted JSON, or the original {@code result} reference
     */
    static String apply(boolean enabled, IMcpTool tool, String result, AuditSink audit)
    {
        if (result == null || !enabled)
        {
            return result;
        }
        if (tool == null || !tool.returnsInfobaseData())
        {
            return result;
        }
        if (isJsonErrorPayload(result))
        {
            return result;
        }
        JsonElement root;
        try
        {
            root = JsonParser.parseString(result);
        }
        catch (RuntimeException e)
        {
            return result; // not JSON: leave the tool's text untouched
        }
        if (root == null || !(root.isJsonObject() || root.isJsonArray()))
        {
            return result;
        }
        Context ctx = new Context();
        redactElement(root, null, ctx);
        if (ctx.count == 0)
        {
            // Nothing masked: return the ORIGINAL string so a zero-PII result stays
            // byte-identical (a Gson round-trip would reformat whitespace).
            return result;
        }
        String redacted = GsonProvider.toJson(root);
        AuditSink sink = audit != null ? audit : AuditSink.NONE;
        sink.record(toolName(tool), ctx.maxSensitivity != null ? ctx.maxSensitivity : Sensitivity.NORMAL, ctx.count);
        return redacted;
    }

    /**
     * Whether {@code result} is a canonical ToolResult error payload
     * ({@code {"success":false,...}}). Mirrors the protocol handler's own detection:
     * only an explicit boolean {@code success==false} counts, so a successful result
     * that merely carries an {@code error} field is still redacted. Error payloads are
     * skipped because our errors are English tool text, not infobase data.
     *
     * @param result the serialized result (may be {@code null})
     * @return {@code true} if it is an error payload
     */
    static boolean isJsonErrorPayload(String result)
    {
        if (result == null)
        {
            return false;
        }
        try
        {
            JsonElement el = JsonParser.parseString(result);
            if (!el.isJsonObject())
            {
                return false;
            }
            JsonElement success = el.getAsJsonObject().get(KEY_SUCCESS);
            return success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                && !success.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Recursively redacts string leaves of {@code node}, mutating the tree in place.
     * For an object, each string field is classified by its key (and the canonical
     * {@code value} field additionally by the sibling {@code name}); when a
     * {@code value} field is replaced its now-stale {@code truncated}/{@code fullLength}
     * siblings are dropped. Array string elements are classified by the array's
     * enclosing key.
     */
    private static void redactElement(JsonElement node, String enclosingKey, Context ctx)
    {
        if (node.isJsonObject())
        {
            JsonObject obj = node.getAsJsonObject();
            String siblingName = stringField(obj, KEY_NAME);
            List<String> keys = new ArrayList<>(obj.keySet());
            for (String key : keys)
            {
                JsonElement child = obj.get(key);
                if (isJsonString(child))
                {
                    String original = child.getAsString();
                    String redacted = redactString(key, original, siblingName, ctx);
                    if (!redacted.equals(original))
                    {
                        obj.addProperty(key, redacted);
                        if (KEY_VALUE.equals(key))
                        {
                            // truncated/fullLength describe the ORIGINAL value length;
                            // a pseudonym/mask changes it, so drop them or they lie.
                            obj.remove(KEY_TRUNCATED);
                            obj.remove(KEY_FULL_LENGTH);
                        }
                    }
                }
                else if (isContainer(child))
                {
                    redactElement(child, key, ctx);
                }
            }
        }
        else if (node.isJsonArray())
        {
            JsonArray arr = node.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++)
            {
                JsonElement el = arr.get(i);
                if (isJsonString(el))
                {
                    String original = el.getAsString();
                    String redacted = redactString(enclosingKey, original, null, ctx);
                    if (!redacted.equals(original))
                    {
                        arr.set(i, new JsonPrimitive(redacted));
                    }
                }
                else if (isContainer(el))
                {
                    redactElement(el, enclosingKey, ctx);
                }
            }
        }
    }

    /**
     * Redacts a single string value: name-classification (key, plus sibling name for
     * the {@code value} field) first, then content-regex. Returns the same
     * {@code value} reference when nothing matches.
     */
    private static String redactString(String key, String value, String siblingName, Context ctx)
    {
        if (value == null || value.isEmpty())
        {
            return value;
        }
        Sensitivity byKey = AttributeNameDictionary.classify(key);
        Sensitivity byName = KEY_VALUE.equals(key) ? AttributeNameDictionary.classify(siblingName) : null;
        Sensitivity nameSens = mostSensitive(byKey, byName);
        if (nameSens != null)
        {
            ctx.record(nameSens, 1);
            return PSEUDONYMIZER.token(nameSens, value);
        }
        ContentPatterns.RedactionResult r = ContentPatterns.redact(value, PSEUDONYMIZER);
        if (r.count > 0)
        {
            ctx.record(Sensitivity.NORMAL, r.count);
            return r.text;
        }
        return value;
    }

    private static Sensitivity mostSensitive(Sensitivity a, Sensitivity b)
    {
        if (a == null)
        {
            return b;
        }
        if (b == null)
        {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static String stringField(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        return isJsonString(el) ? el.getAsString() : null;
    }

    private static boolean isJsonString(JsonElement el)
    {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
    }

    private static boolean isContainer(JsonElement el)
    {
        return el != null && (el.isJsonObject() || el.isJsonArray());
    }

    private static String toolName(IMcpTool tool)
    {
        try
        {
            String name = tool.getName();
            return name != null ? name : UNKNOWN_TOOL;
        }
        catch (RuntimeException e)
        {
            return UNKNOWN_TOOL;
        }
    }

    private static void logAudit(String toolName, Sensitivity maxSensitivity, int redactionCount)
    {
        Activator.logInfo("PII redaction applied on tool '" + toolName + "': " + redactionCount //$NON-NLS-1$ //$NON-NLS-2$
            + " value(s) masked, max sensitivity " + maxSensitivity + "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Mutable accumulator for one redaction pass: the count and the most severe class seen. */
    private static final class Context
    {
        private int count;
        private Sensitivity maxSensitivity;

        void record(Sensitivity sensitivity, int delta)
        {
            count += delta;
            if (sensitivity != null
                && (maxSensitivity == null || sensitivity.ordinal() > maxSensitivity.ordinal()))
            {
                maxSensitivity = sensitivity;
            }
        }
    }
}
