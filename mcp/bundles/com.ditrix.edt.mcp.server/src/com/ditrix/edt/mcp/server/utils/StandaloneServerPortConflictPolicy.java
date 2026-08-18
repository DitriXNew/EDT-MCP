/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;

/**
 * How an operation that STARTS a 1C standalone server must answer EDT's blocking
 * <em>"Standalone server port conflict"</em> modal.
 *
 * <p>EDT verifies the server's ports before starting it
 * ({@code StandaloneServerBehaviourDelegate.verifyServerPorts}); when one of them (HTTP gate /
 * debug server / SSH gate) is already bound it raises an application-modal dialog offering
 * <b>Find free port</b> / <b>Cancel</b>. Nothing presses it in an unattended MCP run, so the
 * call blocks forever — and, because the dialog is application-modal, so does every call behind
 * it. This policy is the unattended answer, selected per call by the
 * {@code standaloneServerPortConflict} tool parameter.
 *
 * <p>The two choices are not interchangeable:
 * <ul>
 *   <li>{@link #CANCEL} (the default) writes nothing: the server does not start and the call
 *       fails with the busy ports named. Safe, because the alternative is not a data question
 *       — it changes the STAND;</li>
 *   <li>{@link #REASSIGN} presses EDT's own "Find free port": EDT picks free ports and
 *       <b>rewrites the server's configuration</b>. Every client, published URL and bookmark
 *       pointing at the old ports then points at nothing, which is why it is opt-in and never
 *       the default.</li>
 * </ul>
 */
public enum StandaloneServerPortConflictPolicy
{
    /**
     * Cancel the dialog: the standalone server is not started and the operation fails with an
     * actionable error naming the busy ports. Writes nothing.
     */
    CANCEL("cancel"), //$NON-NLS-1$

    /**
     * Let EDT move the server to free ports — it rewrites the server configuration, so the
     * address its clients connect to changes. Opt-in only.
     */
    REASSIGN("reassign"); //$NON-NLS-1$

    /**
     * The default applied when a caller passes no {@code standaloneServerPortConflict}: refuse
     * rather than re-address someone's server as a side effect of an unrelated request.
     */
    public static final StandaloneServerPortConflictPolicy DEFAULT = CANCEL;

    /**
     * The {@code standaloneServerPortConflict} parameter description, shared verbatim by every
     * tool that can start a standalone server ({@code update_database}, {@code debug_launch},
     * {@code run_yaxunit_tests}).
     *
     * <p>It lives on the policy rather than on one of those tools so the three stay in sync by
     * construction: the wire tokens and the sentence that explains them are defined together,
     * and a new token cannot be added without the description moving with it.
     */
    public static final String PARAMETER_DESCRIPTION =
        "Answer to EDT's standalone-server port-conflict prompt: cancel (default) = fail and " //$NON-NLS-1$
            + "name the busy ports; reassign = let EDT move the server to free ports (rewrites " //$NON-NLS-1$
            + "its configuration)."; //$NON-NLS-1$

    private final String wireValue;

    StandaloneServerPortConflictPolicy(String wireValue)
    {
        this.wireValue = wireValue;
    }

    /**
     * Returns the lowercase wire token of this policy — the value tools accept in the
     * {@code standaloneServerPortConflict} parameter.
     *
     * @return the wire token, never {@code null}
     */
    public String wireValue()
    {
        return wireValue;
    }

    /**
     * Parses the {@code standaloneServerPortConflict} parameter value. Blank/{@code null}
     * yields {@link #DEFAULT}; the comparison is case-insensitive and trimmed.
     *
     * @param value the raw parameter value (may be {@code null}/blank)
     * @return the parsed policy, or {@code null} when the value is a non-blank token that
     *         matches no policy (the caller reports it as an actionable error)
     */
    public static StandaloneServerPortConflictPolicy parse(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return DEFAULT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (StandaloneServerPortConflictPolicy policy : values())
        {
            if (policy.wireValue.equals(normalized))
            {
                return policy;
            }
        }
        return null;
    }

    /**
     * Returns the accepted wire tokens as a comma-separated list, for error texts that name the
     * valid values.
     *
     * @return {@code "cancel, reassign"}
     */
    public static String acceptedValues()
    {
        StringBuilder sb = new StringBuilder();
        for (StandaloneServerPortConflictPolicy policy : values())
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
