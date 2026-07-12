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
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PiiRedactionSettings;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless ratchet for the rule-driven {@link PiiRedactor}: the pure
 * {@link PiiRedactor#apply(String, PiiRuleSet, String)} core is driven with an explicit
 * {@link PiiRuleSet} and salt, so every path (name-scope whole-value replacement,
 * value-scope span replacement, flat mask, non-countable literal, salt stability,
 * per-run blank salt, invalid-regex isolation, and the same-reference short-circuit) is
 * exercised without OSGi or a live preference store. Cyrillic sample data is built with
 * unicode escapes (project rule 7).
 */
public class PiiRedactorTest
{
    /** The "natural person" pseudonym stem and the flat mask, reused to avoid raw literals. */
    private static final String PREFIX = Pseudonymizer.PREFIX;
    private static final String MASK = Pseudonymizer.MASK;

    private static final String SALT = "unit-test-salt";
    private static final String OTHER_SALT = "another-salt";

    private static final String SNILS_VALUE = "112-233-445 95";
    private static final String EMAIL_TEXT = "Contact ivan@example.com now";
    private static final String EMAIL_LITERAL = "ivan@example.com";
    private static final String NUMERIC_ID = "12345678901";

    private static final String FULL_NAME = "\u0418\u0432\u0430\u043d\u043e\u0432 \u0418\u0432\u0430\u043d \u0418\u0432\u0430\u043d\u043e\u0432\u0438\u0447";
    private static final String DIAGNOSIS_VALUE = "\u0413\u0430\u0441\u0442\u0440\u0438\u0442";
    private static final String TYPE_STRING = "\u0421\u0442\u0440\u043e\u043a\u0430";
    private static final String SNILS_KEY = "\u0421\u041d\u0418\u041b\u0421";
    private static final String DIAGNOSIS_KEY = "\u0414\u0438\u0430\u0433\u043d\u043e\u0437";
    private static final String FIO = "\u0424\u0418\u041e";

    /** NAME-scope regexes (attribute-name stems), matched case-insensitively against a key. */
    private static final String NAME_SNILS = "\u0441\u043d\u0438\u043b\u0441";
    private static final String NAME_DIAGNOSIS = "\u0434\u0438\u0430\u0433\u043d\u043e\u0437";
    private static final String NAME_FIO = "\u0444\u0438\u043e";

    /** VALUE-scope content regexes. */
    private static final String EMAIL_REGEX = "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}";
    private static final String SNILS_REGEX = "\\b\\d{3}-\\d{3}-\\d{3}\\s?\\d{2}\\b";

    /** A rendered countable pseudonym: PREFIX + '#' + 8 hex digits. */
    private static final Pattern PSEUDONYM = Pattern.compile(Pattern.quote(PREFIX) + "#[0-9a-f]{8}");

    private static final String TOOL = "get_variables";

    // ---- OFF / short-circuit paths: the SAME reference must come back (byte-identical) ----

    @Test
    public void emptyRuleSetReturnsSameReference()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        assertSame(json, PiiRedactor.apply(json, PiiRuleSet.EMPTY, SALT));
        assertSame(json, PiiRedactor.apply(json, PiiRuleSet.of(), SALT));
    }

    @Test
    public void nullRuleSetReturnsSameReference()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        assertSame(json, PiiRedactor.apply(json, null, SALT));
    }

    @Test
    public void noMatchReturnsSameReference()
    {
        // A number that no rule targets: the walk masks nothing, so the ORIGINAL string
        // reference is returned (never a no-op re-serialization).
        String json = variablesJson(var("Counter", TYPE_STRING, "42"));
        PiiRuleSet rules = PiiRuleSet.of(valueRule(EMAIL_REGEX, PREFIX, true));
        assertSame(json, PiiRedactor.apply(json, rules, SALT));
    }

    @Test
    public void nullResultReturnsNull()
    {
        assertNull(PiiRedactor.apply(null, PiiRuleSet.of(valueRule(EMAIL_REGEX, PREFIX, true)), SALT));
    }

    @Test
    public void nonJsonBodyReturnsSameReference()
    {
        String text = "just plain tool text, not JSON at all";
        assertSame(text, PiiRedactor.apply(text, PiiRuleSet.of(valueRule(EMAIL_REGEX, PREFIX, true)), SALT));
    }

    // ---- NAME scope: match the key, replace the WHOLE value ----

    @Test
    public void nameScopeReplacesWholeValueWithPseudonym()
    {
        String json = dataJson(SNILS_KEY, NUMERIC_ID);
        PiiRuleSet rules = PiiRuleSet.of(nameRule(NAME_SNILS, PREFIX, true));
        String out = PiiRedactor.apply(json, rules, SALT);
        assertNotSame(json, out);
        assertFalse(out.contains(NUMERIC_ID));
        String value = dataValue(out, SNILS_KEY);
        assertTrue("whole value must be a single pseudonym token", PSEUDONYM.matcher(value).matches());
    }

    @Test
    public void siblingNameClassifiesFreeFormValue()
    {
        // A full name matches no content pattern; the sibling "name" = FIO drives the hit.
        String json = variablesJson(var(FIO, TYPE_STRING, FULL_NAME));
        PiiRuleSet rules = PiiRuleSet.of(nameRule(NAME_FIO, PREFIX, true));
        String out = PiiRedactor.apply(json, rules, SALT);
        assertFalse(out.contains(FULL_NAME));
        assertTrue(out.contains(PREFIX + "#"));
    }

    @Test
    public void specialCategoryNameRuleFullMasks()
    {
        String json = dataJson(DIAGNOSIS_KEY, DIAGNOSIS_VALUE);
        PiiRuleSet rules = PiiRuleSet.of(nameRule(NAME_DIAGNOSIS, MASK, false));
        String out = PiiRedactor.apply(json, rules, SALT);
        assertFalse(out.contains(DIAGNOSIS_VALUE));
        assertEquals(MASK, dataValue(out, DIAGNOSIS_KEY));
        assertFalse(out.contains(PREFIX + "#"));
    }

    @Test
    public void nonCountableRuleEmitsRepresentationVerbatim()
    {
        String json = dataJson(DIAGNOSIS_KEY, DIAGNOSIS_VALUE);
        // countable == false => the representation is the whole token, no #hmac suffix.
        PiiRuleSet rules = PiiRuleSet.of(nameRule(NAME_DIAGNOSIS, "REDACT", false));
        String out = PiiRedactor.apply(json, rules, SALT);
        assertEquals("REDACT", dataValue(out, DIAGNOSIS_KEY));
        assertFalse(out.contains("#"));
    }

    // ---- VALUE scope: regex-replace spans in place ----

    @Test
    public void valueScopeReplacesOnlyTheMatchedSpan()
    {
        String json = variablesJson(var("V", TYPE_STRING, EMAIL_TEXT));
        PiiRuleSet rules = PiiRuleSet.of(valueRule(EMAIL_REGEX, PREFIX, true));
        String out = PiiRedactor.apply(json, rules, SALT);
        String value = variableValue(out);
        assertFalse(value.contains(EMAIL_LITERAL));
        assertTrue(value.startsWith("Contact "));
        assertTrue(value.endsWith(" now"));
        assertTrue(value.contains(PREFIX + "#"));
    }

    @Test
    public void truncationSiblingsDroppedWhenValueReplaced()
    {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("type", TYPE_STRING);
        root.addProperty("value", EMAIL_TEXT);
        root.addProperty("truncated", true);
        root.addProperty("fullLength", 812);
        String json = GsonProvider.toJson(root);
        PiiRuleSet rules = PiiRuleSet.of(valueRule(EMAIL_REGEX, PREFIX, true));
        String out = PiiRedactor.apply(json, rules, SALT);
        JsonObject outObj = JsonParser.parseString(out).getAsJsonObject();
        assertFalse(outObj.has("truncated"));
        assertFalse(outObj.has("fullLength"));
        assertFalse(out.contains(EMAIL_LITERAL));
        assertTrue(out.contains(PREFIX + "#"));
    }

    // ---- salt semantics ----

    @Test
    public void sameSaltIsStableAcrossEngines()
    {
        String json = variablesJson(var("V", TYPE_STRING, SNILS_VALUE));
        PiiRuleSet rules = PiiRuleSet.of(valueRule(SNILS_REGEX, PREFIX, true));
        // Two independent passes each build their own Pseudonymizer(SALT): identical output.
        assertEquals(PiiRedactor.apply(json, rules, SALT), PiiRedactor.apply(json, rules, SALT));
        // A different salt derives a different key => a different pseudonym.
        assertNotEquals(PiiRedactor.apply(json, rules, SALT), PiiRedactor.apply(json, rules, OTHER_SALT));
    }

    @Test
    public void blankSaltIsRandomPerPass()
    {
        String json = dataJson(SNILS_KEY, NUMERIC_ID);
        PiiRuleSet rules = PiiRuleSet.of(nameRule(NAME_SNILS, PREFIX, true));
        String out1 = PiiRedactor.apply(json, rules, "");
        String out2 = PiiRedactor.apply(json, rules, "");
        // A blank salt falls back to a fresh random key per pass: the pseudonyms differ.
        assertNotEquals(out1, out2);
        // ... but a null salt behaves the same way (still masks, still a pseudonym token).
        String outNull = PiiRedactor.apply(json, rules, null);
        assertTrue(PSEUDONYM.matcher(dataValue(outNull, SNILS_KEY)).matches());
    }

    // ---- robustness ----

    @Test
    public void invalidRegexRowIsIsolatedNotThrown()
    {
        // A syntactically broken NAME rule and a broken VALUE rule must be skipped, and
        // the good rules alongside them must still redact.
        PiiRuleSet rules = PiiRuleSet.of(
            nameRule("[", MASK, false),          // bad NAME regex
            valueRule("(", PREFIX, true),        // bad VALUE regex
            valueRule(EMAIL_REGEX, PREFIX, true), // good VALUE regex
            nameRule(NAME_SNILS, PREFIX, true)); // good NAME regex
        String json = variablesJson(var("V", TYPE_STRING, EMAIL_TEXT));
        String out = PiiRedactor.apply(json, rules, SALT); // must not throw
        assertFalse(out.contains(EMAIL_LITERAL));
        assertTrue(out.contains(PREFIX + "#"));
    }

    @Test
    public void redactCountsEveryMaskedValue()
    {
        String json = variablesJson(
            var("V1", TYPE_STRING, SNILS_VALUE),
            var(FIO, TYPE_STRING, FULL_NAME));
        PiiRuleSet rules = PiiRuleSet.of(
            valueRule(SNILS_REGEX, PREFIX, true),
            nameRule(NAME_FIO, PREFIX, true));
        PiiRedactor.Outcome outcome = PiiRedactor.redact(json, rules, SALT);
        assertEquals(2, outcome.count);
        assertNotEquals(json, outcome.text);
    }

    // ---- error-payload guard + production gate ----

    @Test
    public void errorPayloadIsDetected()
    {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", "boom");
        assertTrue(PiiRedactor.isJsonErrorPayload(GsonProvider.toJson(err)));

        JsonObject ok = new JsonObject();
        ok.addProperty("success", true);
        assertFalse(PiiRedactor.isJsonErrorPayload(GsonProvider.toJson(ok)));
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

    private static PiiRule nameRule(String regex, String representation, boolean countable)
    {
        return new PiiRule(true, regex, PiiRuleScope.NAME, representation, countable);
    }

    private static PiiRule valueRule(String regex, String representation, boolean countable)
    {
        return new PiiRule(true, regex, PiiRuleScope.VALUE, representation, countable);
    }

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

    private static String dataJson(String key, String value)
    {
        JsonObject data = new JsonObject();
        data.addProperty(key, value);
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.add("data", data);
        return GsonProvider.toJson(root);
    }

    private static String dataValue(String json, String key)
    {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data").get(key).getAsString();
    }

    private static String variableValue(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("variables").get(0).getAsJsonObject()
            .get("value").getAsString();
    }

    private static IMcpTool flagged(String name)
    {
        return new StubTool(name, true);
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
}
