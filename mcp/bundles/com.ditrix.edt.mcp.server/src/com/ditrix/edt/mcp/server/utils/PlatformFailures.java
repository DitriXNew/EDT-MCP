/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;

import com.e1c.g5.dt.applications.ApplicationException;

/**
 * Turns a platform failure into a message a caller can actually read.
 *
 * <h2>Why</h2>
 * EDT reports failures as {@link IStatus} objects and only then wraps them in exceptions, so
 * {@code getMessage()} — what tools naturally concatenate into an error — is frequently the
 * LEAST informative part of the failure:
 * <ul>
 *   <li>{@code new ApplicationException(status)} takes {@code status.getMessage()}, which is the
 *       EMPTY STRING for a cancellation ({@code Status.CANCEL_STATUS}); the tool then emits a
 *       sentence that stops at the colon;</li>
 *   <li>a wrapper status often carries a generic message while the real reason sits in a CHILD
 *       status (EDT builds {@code MultiStatus} results) or in the status's own exception;</li>
 *   <li>an exception whose message is {@code null} renders as the literal "null".</li>
 * </ul>
 * All three read as "the tool broke" rather than "the platform refused, and here is why".
 *
 * <h2>What it does</h2>
 * {@link #describe(Throwable)} walks the failure — the exception chain, and for each hop the
 * {@link IStatus} it carries (message, then children, then the status's own exception) — and
 * returns the first non-blank message it finds. When the whole failure genuinely carries no text
 * it says so, naming the exception type and the status severity, because "CANCEL, no message" is
 * itself the diagnosis: something aborted the operation rather than failing it.
 *
 * <p>Pure and side-effect-free (logging stays with the caller, which knows the context), so the
 * whole decision is unit-testable without an EDT runtime.
 */
public final class PlatformFailures
{
    /** Cap on how many {@code getCause()} hops are walked, guarding against a cyclical chain. */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 10;

    /** Cap on how deep a {@link IStatus} child tree is walked. */
    private static final int MAX_STATUS_DEPTH = 4;

    private PlatformFailures()
    {
        // Utility class
    }

    /**
     * The most informative message the failure carries.
     *
     * @param failure the exception to describe (may be {@code null})
     * @return a non-blank description, never {@code null}
     */
    public static String describe(Throwable failure)
    {
        if (failure == null)
        {
            return "no failure was reported"; //$NON-NLS-1$
        }
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_CHAIN_DEPTH; depth++)
        {
            // The status tree is consulted BEFORE the throwable's own message whenever that status
            // HAS children: ApplicationException(IStatus) copies the root message into
            // getMessage(), so trusting the exception first would again return the headline and
            // never the child that names the cause.
            IStatus status = statusOf(current);
            if (hasChildren(status))
            {
                String fromChildren = statusMessage(status, 0);
                if (fromChildren != null)
                {
                    return fromChildren;
                }
            }
            String own = trimToNull(current.getMessage());
            if (own != null)
            {
                return own;
            }
            String fromStatus = statusMessage(status, 0);
            if (fromStatus != null)
            {
                return fromStatus;
            }
            current = current.getCause();
        }
        return describeTextless(failure);
    }

    /**
     * The fallback used when neither the exception chain nor its statuses carry any text: name
     * what failed and, when a status is available, its SEVERITY — a message-less {@code CANCEL}
     * is a different (and far more actionable) event than a message-less error.
     *
     * @param failure the exception (never {@code null})
     * @return the description
     */
    private static String describeTextless(Throwable failure)
    {
        IStatus status = statusOf(failure);
        String severity = status == null ? null : severityToken(status);
        return failure.getClass().getSimpleName() + " (the platform reported no message" //$NON-NLS-1$
            + (severity == null ? "" : "; status severity: " + severity) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Whether the status carries child statuses worth searching before its own headline. */
    private static boolean hasChildren(IStatus status)
    {
        return status != null && status.getChildren() != null && status.getChildren().length > 0;
    }

    /**
     * First non-blank message in a status tree: its children (depth-first, bounded), then the
     * status's own message
     * (depth-first, bounded), then the message of the exception the status carries.
     *
     * @param status the status to search (may be {@code null})
     * @param depth current recursion depth
     * @return the message, or {@code null} when the tree carries none
     */
    static String statusMessage(IStatus status, int depth)
    {
        if (status == null || depth > MAX_STATUS_DEPTH)
        {
            return null;
        }
        // CHILDREN FIRST when there are any. EDT wraps its results in a MultiStatus whose own
        // message is the generic headline ("Database update failed") while the reason — the busy
        // port, the rejected object — sits in a child. Returning the root first made this helper
        // hand back exactly the uninformative text it exists to replace.
        IStatus[] children = status.getChildren();
        if (children != null)
        {
            for (IStatus child : children)
            {
                String fromChild = statusMessage(child, depth + 1);
                if (fromChild != null)
                {
                    return fromChild;
                }
            }
        }
        String own = trimToNull(status.getMessage());
        if (own != null)
        {
            return own;
        }
        Throwable carried = status.getException();
        // Only the carried exception's OWN message: recursing into describe() here could bounce
        // between a status and the exception that carries it.
        return carried == null ? null : trimToNull(carried.getMessage());
    }

    /**
     * The {@link IStatus} a throwable carries, for the two platform exception shapes this plugin
     * meets. Deliberately an explicit type test rather than a reflective {@code getStatus()}
     * probe: an accessor found by name on an unrelated type would be read as a platform status
     * it is not.
     *
     * @param failure the exception (may be {@code null})
     * @return the status, or {@code null} when the exception carries none
     */
    static IStatus statusOf(Throwable failure)
    {
        if (failure instanceof CoreException)
        {
            return ((CoreException)failure).getStatus();
        }
        if (failure instanceof ApplicationException)
        {
            return ((ApplicationException)failure).getStatus();
        }
        return null;
    }

    /** The severity of a status as a stable token (never localized). */
    static String severityToken(IStatus status)
    {
        switch (status.getSeverity())
        {
        case IStatus.CANCEL:
            return "CANCEL"; //$NON-NLS-1$
        case IStatus.ERROR:
            return "ERROR"; //$NON-NLS-1$
        case IStatus.WARNING:
            return "WARNING"; //$NON-NLS-1$
        case IStatus.INFO:
            return "INFO"; //$NON-NLS-1$
        default:
            return "OK"; //$NON-NLS-1$
        }
    }

    /** {@code null} for a {@code null}/blank string, the trimmed value otherwise. */
    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
