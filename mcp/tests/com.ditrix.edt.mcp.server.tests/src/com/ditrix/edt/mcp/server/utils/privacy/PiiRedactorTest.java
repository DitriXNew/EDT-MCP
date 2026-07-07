/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PiiRedactionSettings;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless ratchet for {@link PiiRedactor}: the pure {@link PiiRedactor#apply} core
 * is driven with an explicit enabled flag and an injected audit sink, so every guard
 * and both detection layers are exercised without OSGi or a live preference store.
 * Cyrillic sample data is built with unicode escapes (project rule 7).
 */
public class PiiRedactorTest
{
    private static final String SNILS_VALUE = "112-233-445 95";
    private static final String FIO = "\u0424\u0418\u041e";
    private static final String SNILS_KEY = "\u0421\u041d\u0418\u041b\u0421";
    private static final String DIAGNOSIS_KEY = "\u0414\u0438\u0430\u0433\u043d\u043e\u0437";
    private static final String FULL_NAME = "\u0418\u0432\u0430\u043d\u043e\u0432 \u0418\u0432\u0430\u043d \u0418\u0432\u0430\u043d\u043e\u0432\u0438\u0447";
    private static final String DIAGNOSIS_VALUE = "\u0413\u0430\u0441\u0442\u0440\u0438\u0442";
    private static final String TYPE_STRING = "\u0421\u0442\u0440\u043e\u043a\u0430";
    private static final String TOOL = "get_variables";

    // ---- OFF / skip paths: the SAME reference must come back (byte-identical) ----

    @Test
    public void disabledReturnsSameReference()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        RecordingSink sink = new RecordingSink();
        String out = PiiRedactor.apply(false, flagged(TOOL), json, sink);
        assertSame(json, out);
        assertEquals(0, sink.calls);
    }

    @Test
    public void notFlaggedReturnsSameReference()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        String out = PiiRedactor.apply(true, plain("list_projects"), json, new RecordingSink());
        assertSame(json, out);
    }

    @Test
    public void errorPayloadIsSkipped()
    {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", "boom");
        String errJson = GsonProvider.toJson(err);
        assertTrue(PiiRedactor.isJsonErrorPayload(errJson));
        String out = PiiRedactor.apply(true, flagged(TOOL), errJson, new RecordingSink());
        assertSame(errJson, out);
    }

    @Test
    public void noPiiReturnsSameReference()
    {
        String json = variablesJson(var("Counter", TYPE_STRING, "42"));
        RecordingSink sink = new RecordingSink();
        String out = PiiRedactor.apply(true, flagged(TOOL), json, sink);
        assertSame(json, out);
        assertEquals(0, sink.calls);
    }

    @Test
    public void nullResultReturnsNull()
    {
        assertNull(PiiRedactor.apply(true, flagged(TOOL), null, new RecordingSink()));
    }

    // ---- ON paths: detection + pseudonymise / mask ----

    @Test
    public void contentRegexValueIsPseudonymized()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        RecordingSink sink = new RecordingSink();
        String out = PiiRedactor.apply(true, flagged(TOOL), json, sink);
        assertNotSame(json, out);
        assertFalse(out.contains(SNILS_VALUE));
        assertTrue(out.contains(Pseudonymizer.PREFIX + "#"));
        assertEquals(1, sink.calls);
        assertEquals(Sensitivity.NORMAL, sink.sensitivity);
        assertEquals(TOOL, sink.toolName);
        assertTrue(sink.count >= 1);
    }

    @Test
    public void dictionaryKeyValueIsPseudonymized()
    {
        // 11-digit value: no content-regex hit (INN is 10 or 12), so only the
        // dictionary key match can redact it.
        JsonObject data = new JsonObject();
        data.addProperty(SNILS_KEY, "12345678901");
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.add("data", data);
        String json = GsonProvider.toJson(root);
        String out = PiiRedactor.apply(true, flagged("evaluate_expression"), json, new RecordingSink());
        assertFalse(out.contains("12345678901"));
        assertTrue(out.contains(Pseudonymizer.PREFIX + "#"));
    }

    @Test
    public void siblingNameClassifiesFreeFormValue()
    {
        // A full name matches no content pattern; the sibling "name" = FIO drives it.
        String json = variablesJson(var(FIO, TYPE_STRING, FULL_NAME));
        RecordingSink sink = new RecordingSink();
        String out = PiiRedactor.apply(true, flagged(TOOL), json, sink);
        assertFalse(out.contains(FULL_NAME));
        assertTrue(out.contains(Pseudonymizer.PREFIX + "#"));
        assertEquals(Sensitivity.NORMAL, sink.sensitivity);
    }

    @Test
    public void specialCategoryValueIsMaskedNotPseudonymized()
    {
        String json = variablesJson(var(DIAGNOSIS_KEY, TYPE_STRING, DIAGNOSIS_VALUE));
        RecordingSink sink = new RecordingSink();
        String out = PiiRedactor.apply(true, flagged(TOOL), json, sink);
        assertFalse(out.contains(DIAGNOSIS_VALUE));
        assertTrue(out.contains(Pseudonymizer.MASK));
        assertFalse(out.contains(Pseudonymizer.PREFIX + "#"));
        assertEquals(Sensitivity.SPECIAL, sink.sensitivity);
    }

    @Test
    public void tokenIsStableWithinRun()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        String out1 = PiiRedactor.apply(true, flagged(TOOL), json, PiiRedactor.AuditSink.NONE);
        String out2 = PiiRedactor.apply(true, flagged(TOOL), json, PiiRedactor.AuditSink.NONE);
        assertEquals(out1, out2);

        String other = variablesJson(var("V", TYPE_STRING, "111-111-111 11"));
        String outOther = PiiRedactor.apply(true, flagged(TOOL), other, PiiRedactor.AuditSink.NONE);
        assertNotEquals(out1, outOther);
    }

    @Test
    public void truncationSiblingsDroppedWhenValueReplaced()
    {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("type", TYPE_STRING);
        root.addProperty("value", "Contact ivan@example.com now");
        root.addProperty("truncated", true);
        root.addProperty("fullLength", 812);
        String json = GsonProvider.toJson(root);
        String out = PiiRedactor.apply(true, flagged("evaluate_expression"), json, new RecordingSink());
        JsonObject outObj = JsonParser.parseString(out).getAsJsonObject();
        assertFalse(outObj.has("truncated"));
        assertFalse(outObj.has("fullLength"));
        assertFalse(out.contains("ivan@example.com"));
        assertTrue(out.contains(Pseudonymizer.PREFIX + "#"));
    }

    @Test
    public void auditReceivesMetaCountAcrossMultipleHits()
    {
        String json = variablesJson(
            var("V1", TYPE_STRING, SNILS_VALUE),
            var(FIO, TYPE_STRING, FULL_NAME));
        RecordingSink sink = new RecordingSink();
        PiiRedactor.apply(true, flagged(TOOL), json, sink);
        assertEquals(1, sink.calls);
        assertEquals(2, sink.count);
        assertEquals(TOOL, sink.toolName);
    }

    @Test
    public void redactIfEnabledIsNoOpWhenGateDisabled()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        Map<String, String> params = Collections.emptyMap();
        String out = PiiRedactor.redactIfEnabled(flagged(TOOL), params, json);
        // The headless gate defaults OFF (no Activator / no env override): same reference.
        if (!PiiRedactionSettings.isEnabled())
        {
            assertSame(json, out);
        }
        assertNotNull(out);
    }

    // ---- helpers ----

    private static String variablesJson(JsonObject... vars)
    {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        JsonArray arr = new JsonArray();
        for (JsonObject v : vars)
        {
            arr.add(v);
        }
        root.add("variables", arr);
        root.addProperty("count", vars.length);
        return GsonProvider.toJson(root);
    }

    private static JsonObject var(String name, String type, String value)
    {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type);
        o.addProperty("value", value);
        o.addProperty("hasChildren", false);
        return o;
    }

    private static IMcpTool flagged(String name)
    {
        return new StubTool(name, true);
    }

    private static IMcpTool plain(String name)
    {
        return new StubTool(name, false);
    }

    /** Minimal IMcpTool for the choke-point contract (name + returnsInfobaseData flag). */
    private static final class StubTool implements IMcpTool
    {
        private final String name;
        private final boolean flagged;

        StubTool(String name, boolean flagged)
        {
            this.name = name;
            this.flagged = flagged;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "";
        }

        @Override
        public String getInputSchema()
        {
            return "{}";
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "";
        }

        @Override
        public boolean returnsInfobaseData()
        {
            return flagged;
        }

        @Override
        public ResponseType getResponseType()
        {
            return ResponseType.JSON;
        }
    }

    /** Captures the last audit callback (meta only). */
    private static final class RecordingSink implements PiiRedactor.AuditSink
    {
        private String toolName;
        private Sensitivity sensitivity;
        private int count;
        private int calls;

        @Override
        public void record(String toolName, Sensitivity maxSensitivity, int redactionCount)
        {
            this.toolName = toolName;
            this.sensitivity = maxSensitivity;
            this.count = redactionCount;
            this.calls++;
        }
    }
}
