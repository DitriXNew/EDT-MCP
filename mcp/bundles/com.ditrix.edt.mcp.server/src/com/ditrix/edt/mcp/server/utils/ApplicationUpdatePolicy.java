/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;

/**
 * Single canonical interpretation of an {@link ApplicationUpdateState} returned
 * by {@code IApplicationManager.update(...)}. Both the explicit
 * {@code update_database} tool and the pre-launch auto-chain
 * ({@code LaunchLifecycleUtils.updateApplicationIfNeeded}) classify through this
 * one place so the "is this success?" decision never diverges.
 *
 * <h2>Why {@code INCREMENTAL_UPDATE_REQUIRED} after an incremental update is a
 * SUCCESS</h2>
 * For a configuration that has a dependent extension (the classic case is a
 * YAXUnit <em>test extension</em>), {@code InfobaseApplicationProvisionDelegate.getUpdateState}
 * propagates the extension's {@code INCREMENTAL_UPDATE_REQUIRED} to the whole
 * application — and a plain {@code IApplicationManager.update} publishes the
 * configuration but does <b>not</b> durably clear that state; it reverts
 * immediately. So observing {@code INCREMENTAL_UPDATE_REQUIRED} right after an
 * incremental update does NOT mean the update failed — the changes ARE in the
 * infobase. Treating it as a failure is what historically pushed callers into a
 * needless (and often failing) FULL update. See
 * {@code LaunchUpdateDialogAutoConfirmer} for the matching launch-time handling.
 *
 * <p>A genuine "incremental is not enough" signal is the distinct
 * {@link ApplicationUpdateState#FULL_UPDATE_REQUIRED} value — only that one maps
 * to {@link Outcome#NEEDS_FULL_UPDATE}.
 */
public final class ApplicationUpdatePolicy
{
    /** Classified outcome of a database update. */
    public enum Outcome
    {
        /** The configuration is published; the caller may proceed. */
        SUCCESS,
        /** The update is still running (BEING_UPDATED); poll until it settles. */
        IN_PROGRESS,
        /** Incremental is genuinely insufficient — a FULL update is required. */
        NEEDS_FULL_UPDATE,
        /** Unexpected terminal state — surface it to the user. */
        FAILED
    }

    /** Immutable {outcome + actionable message} pair. */
    public static final class Result
    {
        private final Outcome outcome;
        private final String message;

        private Result(Outcome outcome, String message)
        {
            this.outcome = outcome;
            this.message = message;
        }

        public Outcome outcome()
        {
            return outcome;
        }

        public String message()
        {
            return message;
        }

        public boolean isSuccess()
        {
            return outcome == Outcome.SUCCESS;
        }
    }

    private ApplicationUpdatePolicy()
    {
        // Utility class
    }

    /**
     * Classifies the state for the <b>pre-launch</b> path (the auto-chain only
     * ever runs an INCREMENTAL update). {@code BEING_UPDATED} is expected to be
     * polled to a settled state by the caller before classification, but it is
     * handled here too for safety.
     */
    public static Result classifyPostUpdate(ApplicationUpdateState after)
    {
        return classifyExplicitUpdate(ApplicationUpdateType.INCREMENTAL, after);
    }

    /**
     * Classifies the state for the <b>explicit</b> {@code update_database} path,
     * where the caller may have requested a FULL or an INCREMENTAL update.
     *
     * @param requestedType the update type that was actually run
     * @param after         the state returned by {@code update()} (or polled
     *                      after a {@code BEING_UPDATED}); may be {@code null}
     */
    public static Result classifyExplicitUpdate(ApplicationUpdateType requestedType,
            ApplicationUpdateState after)
    {
        if (after == null)
        {
            return new Result(Outcome.FAILED,
                "Database update returned no state. Inspect the EDT '.log' / Problems view."); //$NON-NLS-1$
        }
        switch (after)
        {
            case UPDATED:
                return new Result(Outcome.SUCCESS, "Database updated successfully."); //$NON-NLS-1$

            case INCREMENTAL_UPDATE_REQUIRED:
                if (requestedType == ApplicationUpdateType.FULL)
                {
                    // A FULL update should leave the IB UPDATED; still asking for an
                    // incremental afterward means the full update did not settle.
                    return new Result(Outcome.FAILED,
                        "Full update finished but the database still reports " //$NON-NLS-1$
                            + "INCREMENTAL_UPDATE_REQUIRED. Inspect the EDT '.log' / Problems " //$NON-NLS-1$
                            + "view; the infobase may need manual restructurization."); //$NON-NLS-1$
                }
                // The expected cosmetic case for an extension-bearing configuration:
                // the changes ARE published; do NOT escalate to a full update. The
                // message keeps an honest escape hatch in case the rare genuine
                // "needs full" is misreported as this state.
                return new Result(Outcome.SUCCESS,
                    "Database updated successfully (incremental). The application still reports " //$NON-NLS-1$
                        + "INCREMENTAL_UPDATE_REQUIRED — normally the cosmetic state of a " //$NON-NLS-1$
                        + "configuration with a dependent extension, so a full update is usually " //$NON-NLS-1$
                        + "NOT needed. If a freshly launched client still runs stale code, re-call " //$NON-NLS-1$
                        + "update_database with fullUpdate=true."); //$NON-NLS-1$

            case FULL_UPDATE_REQUIRED:
                if (requestedType == ApplicationUpdateType.FULL)
                {
                    return new Result(Outcome.FAILED,
                        "Full update finished but the database still reports " //$NON-NLS-1$
                            + "FULL_UPDATE_REQUIRED. Inspect the EDT '.log' / Problems view."); //$NON-NLS-1$
                }
                return new Result(Outcome.NEEDS_FULL_UPDATE,
                    "Incremental update finished but the database genuinely requires a FULL " //$NON-NLS-1$
                        + "update (state: FULL_UPDATE_REQUIRED) — the structural changes cannot be " //$NON-NLS-1$
                        + "applied incrementally. Call update_database with fullUpdate=true to apply " //$NON-NLS-1$
                        + "them, then retry."); //$NON-NLS-1$

            case BEING_UPDATED:
                return new Result(Outcome.IN_PROGRESS,
                    "The update is still running in the background (BEING_UPDATED). Poll " //$NON-NLS-1$
                        + "get_applications until the state becomes UPDATED before launching a client."); //$NON-NLS-1$

            default:
                // UNKNOWN or any future enum value.
                return new Result(Outcome.FAILED,
                    "Database update finished with an unexpected state: " + after.name() //$NON-NLS-1$
                        + ". Inspect the EDT '.log' / Problems view."); //$NON-NLS-1$
        }
    }
}
