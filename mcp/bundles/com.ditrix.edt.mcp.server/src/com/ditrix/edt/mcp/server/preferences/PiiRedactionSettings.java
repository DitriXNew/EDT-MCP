/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Locale;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Resolves whether the 152-FZ PII redactor is active for the current call. The
 * master toggle lives in the plugin preferences
 * ({@link PreferenceConstants#PREF_PII_REDACTION_ENABLED}, default OFF), but an
 * environment kill-switch WINS over the stored value so an automated / CI stand
 * can force redaction on or off at launch without touching the preference store.
 *
 * <p>The env override is read from {@code EDT_MCP_PII_REDACTION}: a value like
 * {@code on}/{@code true} forces redaction ON, {@code off}/{@code false} forces
 * it OFF, and any other value (blank / unknown / unset) defers to the preference.
 * This mirrors {@code DestructiveConsentGate}'s env bypass — the pure classifiers
 * live on the nested {@link PiiRedactionGate} so the whole decision is unit-
 * testable headlessly, without a live preference store or process environment.
 *
 * @see PreferenceConstants#PREF_PII_REDACTION_ENABLED
 */
public final class PiiRedactionSettings
{
    private PiiRedactionSettings()
    {
        // Utility class
    }

    /**
     * Whether PII redaction is currently active: the {@code EDT_MCP_PII_REDACTION}
     * env override (on/off) wins; absent an override, the preference toggle
     * decides. Defaults to {@code false} in a headless/test context with no
     * Activator or preference store.
     *
     * @return {@code true} if the redactor should run on infobase-data tool results
     */
    public static boolean isEnabled()
    {
        return PiiRedactionGate.resolve(prefEnabled());
    }

    /**
     * Reads the stored master toggle, falling back to
     * {@link PreferenceConstants#DEFAULT_PII_REDACTION_ENABLED} when there is no
     * Activator or preference store (headless / unit test).
     */
    private static boolean prefEnabled()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return PreferenceConstants.DEFAULT_PII_REDACTION_ENABLED;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        return store != null
            ? store.getBoolean(PreferenceConstants.PREF_PII_REDACTION_ENABLED)
            : PreferenceConstants.DEFAULT_PII_REDACTION_ENABLED;
    }

    /**
     * The env kill-switch: pure classifiers for {@code EDT_MCP_PII_REDACTION} plus
     * the env-reading resolution, kept together (and package-visible) so the
     * env-wins-over-preference decision is unit-testable without the process
     * environment. Mirrors {@code DestructiveConsentGate.envForcesAllow}.
     */
    static final class PiiRedactionGate
    {
        /**
         * Environment variable that overrides the PII-redaction preference. An
         * {@code on}-family value forces redaction ON, an {@code off}-family value
         * forces it OFF; any other value defers to the stored toggle. Set on the
         * EDT/CI launch, exactly like {@code EDT_MCP_DESTRUCTIVE_CONSENT}.
         */
        static final String ENV_PII_REDACTION = "EDT_MCP_PII_REDACTION"; //$NON-NLS-1$

        /** Env values (case-insensitive, trimmed) that force redaction ON. */
        private static final Set<String> ON_VALUES =
            Set.of("on", "true", "1", "yes", "enabled"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        /** Env values (case-insensitive, trimmed) that force redaction OFF. */
        private static final Set<String> OFF_VALUES =
            Set.of("off", "false", "0", "no", "disabled"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        private PiiRedactionGate()
        {
            // Utility holder
        }

        /**
         * Resolves the effective enabled flag by reading {@link #ENV_PII_REDACTION}
         * from the process environment and applying {@link #resolve(String, boolean)}.
         *
         * @param prefEnabled the stored preference value
         * @return the effective enabled flag (env override wins)
         */
        static boolean resolve(boolean prefEnabled)
        {
            return resolve(System.getenv(ENV_PII_REDACTION), prefEnabled);
        }

        /**
         * Pure decision: an env value that forces ON/OFF wins over the preference;
         * a null/blank/unknown env value defers to {@code prefEnabled}.
         *
         * @param rawEnvValue the raw env value (may be {@code null})
         * @param prefEnabled the stored preference value
         * @return the effective enabled flag
         */
        static boolean resolve(String rawEnvValue, boolean prefEnabled)
        {
            if (envForcesOn(rawEnvValue))
            {
                return true;
            }
            if (envForcesOff(rawEnvValue))
            {
                return false;
            }
            return prefEnabled;
        }

        /**
         * Whether the env value forces redaction ON (case-insensitive, trimmed).
         *
         * @param rawEnvValue the raw env value (may be {@code null})
         * @return {@code true} iff the value is in the ON-family
         */
        static boolean envForcesOn(String rawEnvValue)
        {
            return rawEnvValue != null && ON_VALUES.contains(rawEnvValue.trim().toLowerCase(Locale.ROOT));
        }

        /**
         * Whether the env value forces redaction OFF (case-insensitive, trimmed).
         *
         * @param rawEnvValue the raw env value (may be {@code null})
         * @return {@code true} iff the value is in the OFF-family
         */
        static boolean envForcesOff(String rawEnvValue)
        {
            return rawEnvValue != null && OFF_VALUES.contains(rawEnvValue.trim().toLowerCase(Locale.ROOT));
        }
    }
}
