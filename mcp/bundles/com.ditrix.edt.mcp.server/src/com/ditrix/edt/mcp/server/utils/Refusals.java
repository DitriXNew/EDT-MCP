/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * REFUSALS: the exceptions our own validation raises to reject bad input, as opposed to the
 * failures the platform raises when something is actually broken. A refusal is the tool working
 * correctly, so it is logged at {@link IStatus#INFO} WITHOUT a stack.
 * <p>
 * INFO rather than WARNING because the actionable text is ALREADY in the log at WARNING: every
 * tools/call that ends in an error logs {@code "Failed tools/call: <tool> - <message>"} once
 * (see {@code McpProtocolHandler}). A second WARNING per refusal would only move the noise one
 * severity down and make the WARNING band the next thing a triage has to hand-sort. What this
 * entry adds is the internal code path, which is a debugging breadcrumb.
 * <p>
 * The discriminator is an explicit MARKER set where the refusal is raised ({@link #state} /
 * {@link #argument}), never the exception class: {@code IllegalStateException} is raised both by
 * our validation and by the platform, so demoting by class would hide a genuine platform defect
 * at exactly the moment it starts happening. Anything UNMARKED therefore keeps
 * {@link IStatus#ERROR} and its stack - see {@link #statusFor}.
 * <p>
 * The marked exceptions stay subtypes of {@code IllegalStateException} /
 * {@code IllegalArgumentException} and carry the message verbatim, so every existing
 * {@code catch} clause and every client-visible message is unaffected: only the log level changes.
 * <p>
 * The ready-JSON refusal {@link FormValidationException} is a {@link Marker} too, so a path whose
 * catch block does not short-circuit on {@link FormValidationException#jsonOf} still logs it as
 * the refusal it is.
 */
public final class Refusals
{
    /**
     * Marker carried by an exception that our OWN validation raised to refuse the caller's input.
     * Implemented only by exceptions built at a validation site that knows, by construction, that
     * the message came from our own checks - never by a site that merely relays a message some
     * writer handed back, because such a string may describe a PLATFORM failure instead.
     */
    public interface Marker
    {
        /**
         * The text to log for this refusal, when {@code getMessage()} is not the actionable one.
         *
         * @return the log text, or {@code null} to use {@code getMessage()}
         */
        default String logDetail()
        {
            return null;
        }
    }

    /** A refused precondition, raised where an {@code IllegalStateException} is the natural type. */
    private static final class RefusedState
        extends IllegalStateException
        implements Marker
    {
        private static final long serialVersionUID = 1L;

        RefusedState(String message)
        {
            super(message);
        }
    }

    /** A refused argument, raised where an {@code IllegalArgumentException} is the natural type. */
    private static final class RefusedArgument
        extends IllegalArgumentException
        implements Marker
    {
        private static final long serialVersionUID = 1L;

        RefusedArgument(String message)
        {
            super(message);
        }
    }

    private Refusals()
    {
        // Utility class
    }

    /**
     * A refusal to be thrown where the code would otherwise throw {@code IllegalStateException}.
     * The returned exception IS an {@code IllegalStateException} and carries {@code message}
     * verbatim, so nothing about the caller's error text or the surrounding catch clauses changes.
     *
     * @param message the actionable refusal text the caller will be shown
     * @return the marked exception to throw
     */
    public static IllegalStateException state(String message)
    {
        return new RefusedState(message);
    }

    /**
     * A refusal to be thrown where the code would otherwise throw {@code IllegalArgumentException}.
     * The returned exception IS an {@code IllegalArgumentException} and carries {@code message}
     * verbatim.
     *
     * @param message the actionable refusal text the caller will be shown
     * @return the marked exception to throw
     */
    public static IllegalArgumentException argument(String message)
    {
        return new RefusedArgument(message);
    }

    /**
     * The refusal message when {@code t} ITSELF is marked, or {@code null} otherwise - which is the
     * "this is a real failure, stay loud" answer.
     * <p>
     * Deliberately the OUTERMOST throwable only, not a cause-chain scan. A scan would demote a
     * genuine failure that happens to wrap a refusal, discarding the outer exception's message and
     * stack - the failure mode this whole change exists to prevent. Measured before choosing: in
     * all 48 entries of the run this issue cites the marked exception IS the outermost throwable
     * (no {@code Caused by} anywhere), and {@code BmTransactions} rethrows the write lambda's
     * exception unwrapped. So the strict rule costs nothing today, and if a wrapping site ever
     * appears its refusals simply go back to ERROR - noisy, visible in the log ratchet, and safe.
     *
     * @param t the caught exception, may be {@code null}
     * @return the refusal's log text, or {@code null} when {@code t} is not marked
     */
    public static String messageOf(Throwable t)
    {
        if (!(t instanceof Marker))
        {
            return null;
        }
        String detail = ((Marker)t).logDetail();
        return detail != null ? detail : t.getMessage();
    }

    /**
     * The status to log for a failed operation: a stack-free {@link IStatus#INFO} naming the
     * refusal when the cause chain is marked, otherwise an {@link IStatus#ERROR} carrying
     * {@code e} (and therefore its stack) exactly as before.
     *
     * @param context the log context the call site used, e.g. {@code "Error creating form member"}
     * @param e the caught exception, may be {@code null}
     * @return the status to emit
     */
    public static IStatus statusFor(String context, Throwable e)
    {
        String refusal = messageOf(e);
        if (refusal == null)
        {
            return new Status(IStatus.ERROR, Log.pluginId(), context, e);
        }
        return new Status(IStatus.INFO, Log.pluginId(), context + ": " + refusal, null); //$NON-NLS-1$
    }

    /**
     * Where {@link #log} sends its status; {@code null} (the production default) means the
     * platform log. A test sets it to observe that {@code log} really emits what
     * {@link #statusFor} decided: without that, a {@code log} rewritten to bypass
     * {@code statusFor} would disable this fix while every test stayed green.
     */
    private static final AtomicReference<Consumer<IStatus>> sink = new AtomicReference<>();

    /**
     * Test seam: redirects {@link #log}. Pass {@code null} to restore the platform log.
     *
     * @param newSink the sink to receive emitted statuses, or {@code null} for the default
     */
    static void setSink(Consumer<IStatus> newSink)
    {
        sink.set(newSink);
    }

    /**
     * Logs a failed operation through {@link #statusFor}: a refusal lands at INFO with no stack,
     * anything else keeps its ERROR and its stack.
     *
     * @param context the log context, e.g. {@code "Error creating form member"}
     * @param e the caught exception, may be {@code null}
     */
    public static void log(String context, Throwable e)
    {
        IStatus status = statusFor(context, e);
        Consumer<IStatus> current = sink.get();
        if (current != null)
        {
            current.accept(status);
            return;
        }
        Log.log(status);
    }
}
