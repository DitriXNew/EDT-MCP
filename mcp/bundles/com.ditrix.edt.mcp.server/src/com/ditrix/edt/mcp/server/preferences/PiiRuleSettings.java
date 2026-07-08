/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleCodec;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleSet;

/**
 * Resolves the user-configurable PII rule table and the pseudonymiser salt for the
 * 152-FZ redactor. The rule table replaces the previous hard-coded detection: it is
 * stored as JSON in {@link PreferenceConstants#PREF_PII_RULES_JSON} (default
 * {@link PreferenceConstants#DEFAULT_PII_RULES_JSON}) and the salt in
 * {@link PreferenceConstants#PREF_PII_SALT} (default empty).
 * <p>
 * Like {@code PiiRedactionSettings.prefEnabled}, this class is headless-safe: when
 * there is no {@link Activator} or preference store (a unit test or the wire path
 * running without OSGi) it falls back to the <em>bundled default</em> rule set and a
 * <em>blank</em> salt, so {@code PiiRedactor} and its tests keep working without a
 * live store. Reading the store, and the pure ruleset/salt resolution, are split
 * exactly as {@code PiiRedactionSettings} splits its store read from the pure
 * {@code PiiRedactionGate}, so the parse + cache + fallback logic is unit-testable
 * without a preference store or the process environment.
 * <p>
 * The parsed rule set is cached and re-parsed only when the stored JSON changes:
 * every call re-reads the (cheap) store string and re-parses only on a mismatch, so
 * editing the rules in the preferences panel is picked up on the next tool result
 * without an explicit listener. A malformed stored value fails closed to the bundled
 * default rather than throwing, so a bad edit never breaks the wire path. The
 * {@code EDT_MCP_PII_REDACTION} env kill-switch is unrelated to the rule table and is
 * left entirely to {@code PiiRedactionSettings}.
 *
 * @see PiiRedactionSettings
 * @see PiiRuleSet
 */
public final class PiiRuleSettings
{
    /** Guards the two caches ({@link #defaultRuleSet} and the single custom entry). */
    private static final Object LOCK = new Object();

    /**
     * The bundled default rule set, loaded once via
     * {@link PiiRuleCodec#loadBundledDefaults()}. This is the headless / blank /
     * malformed / explicitly-default fallback; loading it once keeps that common path
     * allocation-free after the first call.
     */
    private static PiiRuleSet defaultRuleSet;

    /** Source JSON of the last parsed CUSTOM rule set (the cache key). */
    private static String cachedSource;

    /** Parsed rule set matching {@link #cachedSource}; the cached value. */
    private static PiiRuleSet cachedRuleSet;

    private PiiRuleSettings()
    {
        // Utility class
    }

    /**
     * The rule set to apply to the current call: the stored JSON rule table parsed and
     * cached, or the bundled default when the store is unset / blank / unavailable
     * (headless) / malformed.
     *
     * @return the effective rule set (never {@code null})
     */
    public static PiiRuleSet currentRuleSet()
    {
        return ruleSetFrom(readStore(PreferenceConstants.PREF_PII_RULES_JSON));
    }

    /**
     * The pseudonymiser salt for the current call: the stored salt, or the empty
     * default ({@link PreferenceConstants#DEFAULT_PII_SALT}) when the store is unset or
     * unavailable (headless).
     *
     * @return the salt (never {@code null}; empty means a per-run random key)
     */
    public static String currentSalt()
    {
        return saltFrom(readStore(PreferenceConstants.PREF_PII_SALT));
    }

    /**
     * Pure rule-set resolution from a raw stored value, split out (package-visible) so
     * the parse + cache + fallback is unit-testable without a preference store. A
     * {@code null} / blank value, or the exact bundled-default JSON, resolves to the
     * shared {@link #defaultRuleSet()}; any other value is parsed and cached, and a
     * malformed value fails closed to the default.
     *
     * @param rawJson the raw stored rule-table JSON (may be {@code null}/blank)
     * @return the resolved rule set (never {@code null})
     */
    static PiiRuleSet ruleSetFrom(String rawJson)
    {
        if (rawJson == null || rawJson.isBlank() || rawJson.equals(PreferenceConstants.DEFAULT_PII_RULES_JSON))
        {
            return defaultRuleSet();
        }
        synchronized (LOCK)
        {
            if (rawJson.equals(cachedSource) && cachedRuleSet != null)
            {
                return cachedRuleSet;
            }
            PiiRuleSet resolved;
            try
            {
                resolved = PiiRuleCodec.decode(rawJson);
            }
            catch (RuntimeException e)
            {
                // Defensive: PiiRuleCodec.decode is tolerant and should not throw, but a
                // pathological input must never propagate to the wire path.
                resolved = PiiRuleSet.EMPTY;
            }
            if (resolved.isEmpty())
            {
                // A non-blank, non-default stored value that decodes to no usable rules is a
                // malformed / corrupted rule table (PiiRuleCodec.decode returns EMPTY rather
                // than throwing on unparseable JSON). Fail closed to the bundled default so a
                // bad edit never disables redaction on the wire path (the panel validates
                // before save).
                resolved = defaultRuleSet();
            }
            cachedSource = rawJson;
            cachedRuleSet = resolved;
            return resolved;
        }
    }

    /**
     * Pure salt resolution from a raw stored value: the value verbatim, or the empty
     * default when {@code null}. Kept package-visible to mirror {@link #ruleSetFrom}.
     *
     * @param rawSalt the raw stored salt (may be {@code null})
     * @return the salt (never {@code null})
     */
    static String saltFrom(String rawSalt)
    {
        return rawSalt != null ? rawSalt : PreferenceConstants.DEFAULT_PII_SALT;
    }

    /**
     * The bundled default rule set, loaded once and memoised. The single source of truth
     * is the packaged {@code pii-defaults.json} via {@link PiiRuleCodec#loadBundledDefaults()}
     * (the same set {@code PreferenceConstants.DEFAULT_PII_RULES_JSON} is the serialized
     * form of and the Privacy panel's "Load defaults" button stages), so the redactor's
     * default can never drift from the UI's.
     *
     * @return the shared default rule set
     */
    private static PiiRuleSet defaultRuleSet()
    {
        synchronized (LOCK)
        {
            if (defaultRuleSet == null)
            {
                defaultRuleSet = PiiRuleCodec.loadBundledDefaults();
            }
            return defaultRuleSet;
        }
    }

    /**
     * Reads a string preference through the Activator-null-safe store, mirroring
     * {@code PiiRedactionSettings.prefEnabled}: no Activator or no store (headless /
     * unit test) yields {@code null}, and the pure resolvers then fall back to the
     * bundled default / blank salt.
     *
     * @param key the preference key
     * @return the stored string, or {@code null} in a headless context
     */
    private static String readStore(String key)
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        return store != null ? store.getString(key) : null;
    }
}
