/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;

/**
 * How a configuration-to-infobase update must treat infobase configuration changes made
 * OUTSIDE EDT (Designer, {@code ibcmd}, {@code 1cv8 DESIGNER /LoadConfigFromFiles}, a CLI
 * pipeline).
 *
 * <p>EDT detects such changes when it updates the infobase and — in an interactive session —
 * raises its blocking <em>"Infobase configuration changes"</em> modal offering
 * <b>Import</b> / <b>Override</b> / <b>Cancel</b>. Nothing presses that modal in an
 * unattended MCP run, so the update call blocks on the UI thread until the tool times out:
 * no launch, no report, no client. This policy is the unattended answer to that question,
 * selected per call by the {@code externalInfobaseChanges} tool parameter.
 *
 * <p>The three choices are NOT interchangeable — each writes something different:
 * <ul>
 *   <li>{@link #OVERRIDE} writes the INFOBASE (the project wins) — the literal meaning of
 *       "update the infobase from the project", and the default;</li>
 *   <li>{@link #IMPORT} writes the PROJECT sources (the infobase wins) — never the default,
 *       because it mutates the caller's working tree behind their back;</li>
 *   <li>{@link #CANCEL} writes nothing and fails the call with an actionable error.</li>
 * </ul>
 */
public enum ExternalInfobaseChangesPolicy
{
    /**
     * Keep the project configuration and overwrite the infobase with it, discarding the
     * externally-made infobase changes. The default: it is what {@code updateBeforeLaunch}
     * asks for ("apply the project configuration to the infobase before launching").
     */
    OVERRIDE("override"), //$NON-NLS-1$

    /**
     * Import the external infobase changes INTO the project (the project sources are
     * rewritten), then continue the update. Opt-in only — this mutates the caller's
     * working tree.
     */
    IMPORT("import"), //$NON-NLS-1$

    /**
     * Do not resolve: abort the update and report the conflict as an error. Writes
     * nothing on either side.
     */
    CANCEL("cancel"); //$NON-NLS-1$

    /** The default policy applied when a caller passes no {@code externalInfobaseChanges}. */
    public static final ExternalInfobaseChangesPolicy DEFAULT = OVERRIDE;

    private final String wireValue;

    ExternalInfobaseChangesPolicy(String wireValue)
    {
        this.wireValue = wireValue;
    }

    /**
     * Returns the lowercase wire token of this policy — the value tools accept in the
     * {@code externalInfobaseChanges} parameter.
     *
     * @return the wire token, never {@code null}
     */
    public String wireValue()
    {
        return wireValue;
    }

    /**
     * Parses the {@code externalInfobaseChanges} parameter value. Blank/{@code null}
     * yields {@link #DEFAULT}; the comparison is case-insensitive and trimmed so
     * {@code "Override"} and {@code " override "} both resolve.
     *
     * @param value the raw parameter value (may be {@code null}/blank)
     * @return the parsed policy, or {@code null} when the value is a non-blank token that
     *         matches no policy (the caller reports it as an actionable error)
     */
    public static ExternalInfobaseChangesPolicy parse(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ExternalInfobaseChangesPolicy policy : values())
        {
            if (policy.wireValue.equals(normalized))
            {
                return policy;
            }
        }
        return null;
    }

    /**
     * Builds the actionable error for an update that stopped because the infobase configuration
     * had been changed OUTSIDE EDT and the conflict modal was auto-CANCELLED — so nothing was
     * written on either side and the infobase is still out of sync. The caller cannot see the
     * dialog, so the message names the cause, the parameter and the way forward, which depends
     * on WHY the modal was cancelled.
     *
     * @param policy the policy the call ran with (may be {@code null})
     * @param reason a {@code LaunchUpdateDialogAutoConfirmer.CANCEL_REASON_*} token (may be
     *            {@code null})
     * @return the error message
     */
    public static String declinedUpdateError(ExternalInfobaseChangesPolicy policy, String reason)
    {
        StringBuilder sb = new StringBuilder(
            "The infobase configuration was changed outside EDT (Designer, ibcmd or another CLI) since " //$NON-NLS-1$
                + "the last EDT interaction. The dialog that offers to resolve it was cancelled while this " //$NON-NLS-1$
                + "update ran, so nothing was written and the infobase is still out of sync. "); //$NON-NLS-1$
        if (LaunchUpdateDialogAutoConfirmer.CANCEL_REASON_BUTTON_NOT_FOUND.equals(reason))
        {
            sb.append("Cause: the '") //$NON-NLS-1$
                .append(policy == null ? "" : policy.wireValue()) //$NON-NLS-1$
                .append("' button was not found in that dialog (an EDT build or locale this plugin does not " //$NON-NLS-1$
                    + "know), so it was cancelled rather than answered blind. Resolve the divergence in the " //$NON-NLS-1$
                    + "EDT UI once, then re-run."); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append("Cause: externalInfobaseChanges=") //$NON-NLS-1$
            .append(policy == null ? "<none>" : policy.wireValue()) //$NON-NLS-1$
            .append(" (or a concurrent call on the same EDT asked for a different policy, which is " //$NON-NLS-1$
                + "resolved by cancelling). Re-run with externalInfobaseChanges='override' to keep the " //$NON-NLS-1$
                + "project configuration and overwrite the infobase, or 'import' to pull those external " //$NON-NLS-1$
                + "changes into the project first."); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Returns the accepted wire tokens as a comma-separated list, for error texts that
     * name the valid values.
     *
     * @return e.g. {@code "override, import, cancel"}
     */
    public static String acceptedValues()
    {
        StringBuilder sb = new StringBuilder();
        for (ExternalInfobaseChangesPolicy policy : values())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(policy.wireValue);
        }
        return sb.toString();
    }
}
