/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless ratchet for {@link PiiRuleCodec}: it exercises the JSON round-trip (including
 * regex metacharacters that must survive byte-for-byte), the missing-field back-compat
 * defaults, and that the bundled {@code pii-defaults.json} loads via
 * {@code getResourceAsStream} into a non-empty rule set with the expected
 * NORMAL / mask / value entries. Cyrillic sample data is taken from
 * {@link Pseudonymizer#PREFIX} / {@link Pseudonymizer#MASK} instead of raw literals
 * (project rule 7).
 */
public class PiiRuleCodecTest
{
    /** The "natural person" pseudonym stem, reused from Pseudonymizer to avoid a raw Cyrillic literal. */
    private static final String NORMAL_REP = Pseudonymizer.PREFIX;

    /** The flat mask ("[redacted]"). */
    private static final String MASK_REP = Pseudonymizer.MASK;

    // ---- round-trip ----

    @Test
    public void roundTripPreservesTrickyRegexChars()
    {
        // Commas, pipes, backslashes, parentheses, braces - the JSON-escaping-sensitive set.
        String innRegex = "\\b\\d{10}\\b|\\b\\d{12}\\b";
        String phoneRegex = "(?:\\+7|8)[\\s\\-()]*\\d{3},\\d{2}";
        String backslashHeavy = "a\\\\b\\.c|d,e";

        PiiRuleSet original = PiiRuleSet.of(
            new PiiRule(true, innRegex, PiiRuleScope.VALUE, NORMAL_REP, true),
            new PiiRule(true, phoneRegex, PiiRuleScope.VALUE, NORMAL_REP, true),
            new PiiRule(false, backslashHeavy, PiiRuleScope.BOTH, MASK_REP, false));

        PiiRuleSet decoded = PiiRuleCodec.decode(PiiRuleCodec.encode(original));

        assertEquals(original, decoded);
        // Explicitly assert the exact regex strings survived (equals() already covers it,
        // but pin the intent so a codec escaping regression is unmistakable).
        assertEquals(innRegex, decoded.getRules().get(0).getRegex());
        assertEquals(phoneRegex, decoded.getRules().get(1).getRegex());
        assertEquals(backslashHeavy, decoded.getRules().get(2).getRegex());
    }

    @Test
    public void roundTripPreservesEveryField()
    {
        PiiRuleSet original = PiiRuleSet.of(
            new PiiRule(true, "name", PiiRuleScope.NAME, NORMAL_REP, true),
            new PiiRule(false, "value", PiiRuleScope.VALUE, MASK_REP, false),
            new PiiRule(true, "both", PiiRuleScope.BOTH, "custom", true));

        PiiRuleSet decoded = PiiRuleCodec.decode(PiiRuleCodec.encode(original));

        assertEquals(original, decoded);
        PiiRule first = decoded.getRules().get(0);
        assertTrue(first.isEnabled());
        assertEquals(PiiRuleScope.NAME, first.getScope());
        assertEquals(NORMAL_REP, first.getRepresentation());
        assertTrue(first.isCountable());
        assertFalse(decoded.getRules().get(1).isEnabled());
        assertEquals(PiiRuleScope.VALUE, decoded.getRules().get(1).getScope());
    }

    @Test
    public void encodeProducesVersionedRulesShape()
    {
        String json = PiiRuleCodec.encode(PiiRuleSet.of(new PiiRule(true, "x", PiiRuleScope.NAME, MASK_REP, false)));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(PiiRuleCodec.SCHEMA_VERSION, root.get("version").getAsInt());
        assertTrue(root.get("rules").isJsonArray());
        assertEquals(1, root.getAsJsonArray("rules").size());
    }

    // ---- tolerance / back-compat ----

    @Test
    public void missingFieldsGetSensibleDefaults()
    {
        // Only the mandatory regex is present; everything else must default.
        PiiRuleSet set = PiiRuleCodec.decode("{\"rules\":[{\"regex\":\"email\"}]}");
        assertEquals(1, set.size());
        PiiRule rule = set.getRules().get(0);
        assertEquals("email", rule.getRegex());
        assertTrue("enabled defaults to true", rule.isEnabled());
        assertEquals("scope defaults to BOTH", PiiRuleScope.BOTH, rule.getScope());
        assertEquals("representation defaults to the flat mask", MASK_REP, rule.getRepresentation());
        assertFalse("countable defaults to false", rule.isCountable());
    }

    @Test
    public void bareTopLevelArrayIsAccepted()
    {
        PiiRuleSet set = PiiRuleCodec.decode("[{\"regex\":\"x\",\"scope\":\"value\"}]");
        assertEquals(1, set.size());
        // scope parsing is case-insensitive.
        assertEquals(PiiRuleScope.VALUE, set.getRules().get(0).getScope());
    }

    @Test
    public void unknownScopeFallsBackToDefault()
    {
        PiiRuleSet set = PiiRuleCodec.decode("{\"rules\":[{\"regex\":\"x\",\"scope\":\"nonsense\"}]}");
        assertEquals(PiiRuleScope.BOTH, set.getRules().get(0).getScope());
    }

    @Test
    public void ruleWithoutRegexIsSkipped()
    {
        PiiRuleSet set = PiiRuleCodec.decode(
            "{\"rules\":[{\"scope\":\"NAME\"},{\"regex\":\"  \"},{\"regex\":\"keep\"}]}");
        assertEquals(1, set.size());
        assertEquals("keep", set.getRules().get(0).getRegex());
    }

    @Test
    public void unparseableJsonYieldsEmpty()
    {
        assertSame(PiiRuleSet.EMPTY, PiiRuleCodec.decode("{not valid json"));
    }

    @Test
    public void nullAndEmptyYieldEmpty()
    {
        assertSame(PiiRuleSet.EMPTY, PiiRuleCodec.decode((String)null));
        assertSame(PiiRuleSet.EMPTY, PiiRuleCodec.decode(""));
        assertTrue(PiiRuleCodec.decode("{}").isEmpty());
        assertTrue(PiiRuleCodec.decode("[]").isEmpty());
    }

    // ---- bundled defaults ----

    @Test
    public void bundledDefaultsLoadNonEmptyWithExpectedEntries()
    {
        PiiRuleSet defaults = PiiRuleCodec.loadBundledDefaults();
        assertNotNull(defaults);
        assertFalse("pii-defaults.json must package and parse into a non-empty set", defaults.isEmpty());
        List<PiiRule> rules = defaults.getRules();

        // A NORMAL migration: NAME-scope, countable, natural-person pseudonym stem.
        assertTrue("expected NAME-scope countable NORMAL rules with the pseudonym representation",
            rules.stream().anyMatch(r -> r.getScope() == PiiRuleScope.NAME && r.isCountable()
                && NORMAL_REP.equals(r.getRepresentation())));

        // A SPECIAL/BIOMETRIC migration: NAME-scope, non-countable, flat mask.
        assertTrue("expected NAME-scope mask (non-countable) rules with the flat-mask representation",
            rules.stream().anyMatch(r -> r.getScope() == PiiRuleScope.NAME && !r.isCountable()
                && MASK_REP.equals(r.getRepresentation())));

        // A ContentPatterns migration: VALUE-scope, countable.
        assertTrue("expected VALUE-scope countable content-pattern rules",
            rules.stream().anyMatch(r -> r.getScope() == PiiRuleScope.VALUE && r.isCountable()
                && NORMAL_REP.equals(r.getRepresentation())));

        // The tricky INN regex (with a pipe) proves the resource's escaped regexes parsed intact.
        assertTrue("expected the INN content pattern to survive resource decoding",
            rules.stream().anyMatch(r -> r.getScope() == PiiRuleScope.VALUE
                && "\\b\\d{10}\\b|\\b\\d{12}\\b".equals(r.getRegex())));

        // Every default rule is enabled and re-encodes losslessly.
        assertTrue(rules.stream().allMatch(PiiRule::isEnabled));
        assertEquals(defaults, PiiRuleCodec.decode(PiiRuleCodec.encode(defaults)));
    }

    @Test
    public void bundledDefaultsSerializeWithSharedGson()
    {
        // Encoding goes through GsonProvider (HTML escaping disabled), so regex operators
        // stay readable rather than turning into \\uXXXX escapes.
        String json = PiiRuleCodec.encode(PiiRuleCodec.loadBundledDefaults());
        assertTrue(json.contains("\"version\""));
        // Sanity: it is valid JSON parseable by the shared Gson.
        assertNotNull(GsonProvider.get().fromJson(json, JsonObject.class));
    }
}
