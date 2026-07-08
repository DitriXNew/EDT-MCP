/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for {@link PiiRuleSettings} — the headless-safe resolution of the
 * user-configurable PII rule table and the pseudonymiser salt.
 * <p>
 * Exercises the pure package-visible resolvers ({@link PiiRuleSettings#ruleSetFrom}
 * and {@link PiiRuleSettings#saltFrom}) plus the public {@code current*} entry points
 * headlessly (no Activator / preference store), mirroring how
 * {@code PiiRedactionSettingsTest} exercises the pure {@code PiiRedactionGate}. The
 * store-backed persistence is proven by feeding the resolvers the raw stored value the
 * store would return.
 * <p>
 * Rule sets are held as {@link Object} so the test depends only on {@link PiiRuleSettings}
 * (and not on the {@code PiiRuleSet} model owned by a sibling slice): the assertions are
 * about identity (cache hit / eviction) and non-nullness, not the parsed structure.
 * The two custom sources differ from the bundled default only by trailing whitespace, so
 * they stay structurally valid (any JSON parser ignores it) while being distinct cache
 * keys.
 */
public class PiiRuleSettingsTest
{
    /** A valid custom rule-table source, textually distinct from the bundled default. */
    private static final String CUSTOM_A = PreferenceConstants.DEFAULT_PII_RULES_JSON + "\n"; //$NON-NLS-1$

    /** A second valid custom source, distinct from {@link #CUSTOM_A}. */
    private static final String CUSTOM_B = PreferenceConstants.DEFAULT_PII_RULES_JSON + "\n\n"; //$NON-NLS-1$

    // === Bundled defaults ===

    @Test
    public void testBundledDefaultsArePresent()
    {
        assertNotNull("A bundled default rule table JSON must exist", //$NON-NLS-1$
            PreferenceConstants.DEFAULT_PII_RULES_JSON);
        assertFalse("The bundled default rule table must not be blank", //$NON-NLS-1$
            PreferenceConstants.DEFAULT_PII_RULES_JSON.isBlank());
        assertEquals("The default salt must be empty (per-run random key)", //$NON-NLS-1$
            "", PreferenceConstants.DEFAULT_PII_SALT); //$NON-NLS-1$
        assertFalse("The master PII toggle must stay OFF by default", //$NON-NLS-1$
            PreferenceConstants.DEFAULT_PII_REDACTION_ENABLED);
    }

    // === Headless fallback (no Activator / store) ===

    @Test
    public void testHeadlessCurrentRuleSetIsBundledDefault()
    {
        // With no Activator (unit test), the store read yields null and the resolver
        // falls back to the bundled default — the same instance ruleSetFrom(null) gives.
        Object headless = PiiRuleSettings.currentRuleSet();
        assertNotNull("Headless rule set must never be null", headless); //$NON-NLS-1$
        assertSame("Headless must resolve to the bundled default", //$NON-NLS-1$
            PiiRuleSettings.ruleSetFrom(null), headless);
        assertSame("The bundled default is memoised (stable instance)", //$NON-NLS-1$
            headless, PiiRuleSettings.currentRuleSet());
    }

    @Test
    public void testHeadlessCurrentSaltIsBlank()
    {
        assertEquals("Headless salt must be the empty default", //$NON-NLS-1$
            "", PiiRuleSettings.currentSalt()); //$NON-NLS-1$
        assertEquals(PreferenceConstants.DEFAULT_PII_SALT, PiiRuleSettings.currentSalt());
    }

    // === Rule set resolution / persistence ===

    @Test
    public void testBlankAndDefaultResolveToBundledDefault()
    {
        Object bundled = PiiRuleSettings.ruleSetFrom(null);
        assertNotNull(bundled);
        assertSame("A blank stored value uses the bundled default", //$NON-NLS-1$
            bundled, PiiRuleSettings.ruleSetFrom("   ")); //$NON-NLS-1$
        assertSame("The stored default JSON resolves to the bundled default", //$NON-NLS-1$
            bundled, PiiRuleSettings.ruleSetFrom(PreferenceConstants.DEFAULT_PII_RULES_JSON));
    }

    @Test
    public void testStoreRoundTripParsesCustomRules()
    {
        // A non-default stored value is parsed into its own rule set (round-trip: the
        // value the store would return is turned into the effective rule set).
        Object custom = PiiRuleSettings.ruleSetFrom(CUSTOM_A);
        assertNotNull(custom);
        assertNotSame("A custom rule table must not collapse to the bundled default", //$NON-NLS-1$
            PiiRuleSettings.ruleSetFrom(null), custom);
    }

    @Test
    public void testParsedRuleSetIsCached()
    {
        // The same source is parsed once and cached: two reads return the SAME instance.
        assertSame("An unchanged rule table must be served from cache", //$NON-NLS-1$
            PiiRuleSettings.ruleSetFrom(CUSTOM_A), PiiRuleSettings.ruleSetFrom(CUSTOM_A));
    }

    @Test
    public void testCacheInvalidatedWhenSourceChanges()
    {
        Object a = PiiRuleSettings.ruleSetFrom(CUSTOM_A);
        Object b = PiiRuleSettings.ruleSetFrom(CUSTOM_B);
        assertNotSame("A changed rule table must be re-parsed, not the cached one", a, b); //$NON-NLS-1$

        // CUSTOM_A was evicted by CUSTOM_B (single-entry cache); reading it again
        // re-parses it into a fresh instance — the change is picked up without a listener.
        Object aAgain = PiiRuleSettings.ruleSetFrom(CUSTOM_A);
        assertNotSame("The evicted rule table must be re-parsed on the next read", a, aAgain); //$NON-NLS-1$
    }

    @Test
    public void testMalformedRulesFallBackToDefault()
    {
        // A malformed stored value fails closed to the bundled default (no exception),
        // so a bad edit never breaks the wire path.
        Object bundled = PiiRuleSettings.ruleSetFrom(null);
        assertSame("Malformed rules must fail closed to the bundled default", //$NON-NLS-1$
            bundled, PiiRuleSettings.ruleSetFrom("{ this is not valid json")); //$NON-NLS-1$
    }

    // === Salt resolution / persistence ===

    @Test
    public void testSaltRoundTripAndBlankVersusSet()
    {
        assertEquals("A null stored salt is the empty default", //$NON-NLS-1$
            "", PiiRuleSettings.saltFrom(null)); //$NON-NLS-1$
        assertEquals("An empty stored salt stays empty", //$NON-NLS-1$
            "", PiiRuleSettings.saltFrom("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(PreferenceConstants.DEFAULT_PII_SALT, PiiRuleSettings.saltFrom(null));
        assertEquals("A set salt is returned verbatim (round-trip)", //$NON-NLS-1$
            "s3cr3t-pepper", PiiRuleSettings.saltFrom("s3cr3t-pepper")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
