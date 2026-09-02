/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * {@link IStatus} it carries (failing children first, then other children, its message, and its
 * own exception) — and
 * returns the first non-blank message it finds. When the whole failure genuinely carries no text
 * it says so, naming the exception type and the status severity, because "CANCEL, no message" is
 * itself the diagnosis: something aborted the operation rather than failing it.
 *
 * <p>{@link #rootCause(Throwable)} answers a separate, complementary question: after
 * {@code describe} has selected the headline, what does the deepest distinct CAUSAL throwable hop
 * say? Throwable causes and exceptions attached to statuses are causal edges; child statuses are
 * aggregate detail and are interpreted only by {@code describe}'s rule at their owning throwable
 * hop. Callers that need both compose them explicitly; the root-cause helper does not change
 * {@code describe}'s established selection rule or invent an ordering among aggregate statuses.
 *
 * <p>{@link #withoutObjectIdentity(String)} answers a SEPARATE question and is meant to be
 * COMPOSED with {@code describe}, never substituted for it. {@code describe} selects the most
 * informative message the failure carries, and that selection is exactly why it cannot cure a
 * leaking one: for the platform's "Failed to persist reference value
 * com...RoleDescriptionImpl@3f2a1b" the most informative message IS the message that leaks an
 * implementation object's identity. Choosing a different message would throw the diagnosis
 * away; choosing that one keeps the heap address. Selecting the text and cleaning the text are
 * two questions, answered by two methods, in that order.
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

    /**
     * A Java object identity the way {@code Object.toString()} renders it: a possibly qualified
     * type name, an at-sign, and the identity hash in hex. Group 1 is the type name.
     *
     * <p><b>What makes this safe to run over arbitrary prose is the RIGHT-HAND BOUNDARY, not a
     * floor on the hash length.</b> A length floor is wrong in both directions at once. It is too
     * NARROW upwards: the hash is {@code Integer.toHexString(hashCode())}, which is one to eight
     * digits, so a floor of four silently lets a real
     * {@code RoleDescriptionImpl@abc} through. And it is too WIDE downwards, because length says
     * nothing about whether the hex run is a hash at all: in "john@face.book" the four letters
     * "face" are every one of them hex digits, and a floor-only pattern matches "john@face" and
     * hands the caller back the corrupted "john.book".
     *
     * <p>A real identity's hex run is TERMINAL - the object's {@code toString()} ends there, so
     * what follows is the end of the text or a separator, never the continuation of a name. That
     * is what the two lookaheads assert: the run may not be followed by another name character,
     * and may not be followed by a dot that itself continues into one (which is exactly the shape
     * of a domain, a package tail or a file extension). Both halves are needed - the first alone
     * still eats "john@face" out of "john@face.book"; the second alone still eats "user@e" out of
     * "user@example.com".
     *
     * <p>Because the boundary carries the safety, the hash may be as short as ONE digit. The
     * knowing cost of that is a bare "X@1" - a legal, if unusual, identity rendering - being
     * scrubbed to "X" wherever it is genuinely something else.
     */
    private static final Pattern OBJECT_IDENTITY = Pattern
        .compile("([A-Za-z_$][A-Za-z0-9_$.]*)@[0-9a-fA-F]+(?![0-9A-Za-z_$])(?!\\.[0-9A-Za-z_$])"); //$NON-NLS-1$

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
            FailureMessage atHop = failureMessageAt(current);
            if (atHop != null)
            {
                return atHop.message;
            }
            current = current.getCause();
        }
        return describeTextless(failure);
    }

    /**
     * The deepest diagnosis on a bounded causal path below the failure, when it adds information to
     * {@link #describe(Throwable)}'s headline.
     *
     * <p>The walk follows only {@link Throwable#getCause()} and {@link IStatus#getException()}
     * edges. At each throwable hop, failing status children come first, then the throwable's own
     * message and status fallback. Unlike {@code describe}, causation never falls back to a
     * non-failing aggregate child merely because it has text. A status tree therefore says what
     * ONE causal hop means; structural depth within that aggregate never competes with causal depth.
     *
     * <p>Throwable identities are de-duplicated. Each ordinary or status-attached cause chain is
     * capped at {@link #MAX_CAUSE_CHAIN_DEPTH}, and discovery of attached exceptions is capped at
     * {@link #MAX_STATUS_DEPTH}. A status exception identical to its current throwable's
     * {@code getCause()} is the same edge and cannot restart that cause-chain cap. Status aliases are
     * revisited only when reached at a shallower depth, where the cap can expose children that were
     * previously out of bounds. These rules terminate cyclic graphs without making the result depend
     * on a deeper alias being visited first.
     *
     * <p>Messages equal to the selected headline are excluded. The formatted result is compared
     * with the headline again, because adding a type prefix can recreate a headline that differed
     * from the raw candidate. When no distinct result remains, the empty string is returned. A
     * short generic message (40 characters or fewer and no more than four words) is prefixed only
     * when a terminal exception genuinely owns it. Text selected from a status carries no wrapper
     * provenance. Thus a status-carried
     * {@code RuntimeException("SSH error", new JSchException("Auth fail"))} still contributes
     * {@code com.jcraft.jsch.JSchException: Auth fail}.
     *
     * <p>This method returns only the diagnosis. A caller that displays both messages should compose
     * English prose such as {@code describe(failure) + " Caused by: " + rootCause(failure)}.
     *
     * @param failure the exception to inspect (may be {@code null})
     * @return the deepest distinct diagnosis, or the empty string when there is no additional text
     */
    public static String rootCause(Throwable failure)
    {
        if (failure == null)
        {
            return ""; //$NON-NLS-1$
        }
        String selected = describe(failure);
        FailureMessage deepest = null;
        ArrayDeque<ThrowableHop> pending = new ArrayDeque<ThrowableHop>();
        IdentityHashMap<Throwable, Boolean> visitedThrowables =
            new IdentityHashMap<Throwable, Boolean>();
        pending.add(new ThrowableHop(failure, 0, 0));
        visitedThrowables.put(failure, Boolean.TRUE);
        while (!pending.isEmpty())
        {
            ThrowableHop hop = pending.remove();
            if (hop.causalDepth > 0)
            {
                FailureMessage atHop = causalFailureMessageAt(hop.failure);
                if (atHop != null && !selected.equals(atHop.message)
                    && (deepest == null || hop.causalDepth > deepest.depth))
                {
                    deepest = new FailureMessage(atHop.message, atHop.source, hop.causalDepth);
                }
            }

            Throwable directCause = hop.failure.getCause();
            if (hop.causeDepth + 1 < MAX_CAUSE_CHAIN_DEPTH)
            {
                enqueue(directCause, hop.causalDepth + 1, hop.causeDepth + 1, pending,
                    visitedThrowables);
            }
            enqueueStatusExceptions(statusOf(hop.failure), 0, hop.causalDepth + 1, directCause,
                pending, visitedThrowables, new IdentityHashMap<IStatus, Integer>());
        }
        if (deepest == null)
        {
            return ""; //$NON-NLS-1$
        }
        String formatted = deepest.message;
        if (deepest.source != null && deepest.source.getCause() == null
            && deepest.message.equals(trimToNull(deepest.source.getMessage()))
            && isShortGenericMessage(deepest.message))
        {
            formatted = deepest.source.getClass().getName() + ": " + deepest.message; //$NON-NLS-1$
        }
        return selected.equals(formatted) ? "" : formatted; //$NON-NLS-1$
    }

    /** The exact per-hop display rule used by {@link #describe(Throwable)}. */
    private static FailureMessage failureMessageAt(Throwable failure)
    {
        return failureMessageAt(failure, true);
    }

    /** The per-hop causal rule: no unrelated non-failing child fallback. */
    private static FailureMessage causalFailureMessageAt(Throwable failure)
    {
        return failureMessageAt(failure, false);
    }

    /** Shared per-hop selection, with message provenance. */
    private static FailureMessage failureMessageAt(Throwable failure,
            boolean allowNonFailingChildren)
    {
        IStatus status = statusOf(failure);
        if (hasChildren(status))
        {
            String fromChildren = statusMessage(status, 0, allowNonFailingChildren);
            if (fromChildren != null)
            {
                return new FailureMessage(fromChildren, null, 0);
            }
        }
        String own = trimToNull(failure.getMessage());
        if (own != null)
        {
            boolean copiedFromStatus = status != null
                && own.equals(trimToNull(status.getMessage()));
            return new FailureMessage(own, copiedFromStatus ? null : failure, 0);
        }
        String fromStatus = statusMessage(status, 0, allowNonFailingChildren);
        return fromStatus == null ? null : new FailureMessage(fromStatus, null, 0);
    }

    /** Adds a throwable once; status-attached exceptions start a fresh bounded cause chain. */
    private static void enqueue(Throwable failure, int causalDepth, int causeDepth,
            ArrayDeque<ThrowableHop> pending,
            IdentityHashMap<Throwable, Boolean> visitedThrowables)
    {
        if (failure != null && visitedThrowables.put(failure, Boolean.TRUE) == null)
        {
            pending.add(new ThrowableHop(failure, causalDepth, causeDepth));
        }
    }

    /** Finds exception edges in a bounded status aggregate; status messages are not candidates. */
    private static void enqueueStatusExceptions(IStatus status, int statusDepth, int causalDepth,
            Throwable directCause, ArrayDeque<ThrowableHop> pending,
            IdentityHashMap<Throwable, Boolean> visitedThrowables,
            IdentityHashMap<IStatus, Integer> visitedStatuses)
    {
        if (status == null || statusDepth > MAX_STATUS_DEPTH)
        {
            return;
        }
        Integer previousDepth = visitedStatuses.get(status);
        if (previousDepth != null && previousDepth.intValue() <= statusDepth)
        {
            return;
        }
        visitedStatuses.put(status, Integer.valueOf(statusDepth));

        IStatus[] children = status.getChildren();
        if (children != null)
        {
            for (int pass = 0; pass < 2; pass++)
            {
                boolean failing = pass == 0;
                for (IStatus child : children)
                {
                    if (child == null
                        || child.matches(IStatus.ERROR | IStatus.CANCEL) != failing)
                    {
                        continue;
                    }
                    enqueueStatusExceptions(child, statusDepth + 1, causalDepth, directCause,
                        pending, visitedThrowables, visitedStatuses);
                }
            }
        }
        Throwable statusException = status.getException();
        // CoreException.getCause() is its status exception. It is the SAME cause-chain edge, even
        // when the ordinary enqueue is stopped by the cap, so admitting it here with depth zero
        // would make every tenth CoreException restart the documented bound. Genuinely different
        // status exceptions still begin their own bounded chains.
        if (statusException != directCause)
        {
            enqueue(statusException, causalDepth, 0, pending, visitedThrowables);
        }
    }

    /** Deterministic proxy for a terse generic message that benefits from its exception type. */
    private static boolean isShortGenericMessage(String message)
    {
        if (message.length() > 40)
        {
            return false;
        }
        int words = 0;
        boolean inWord = false;
        for (int index = 0; index < message.length(); index++)
        {
            boolean whitespace = Character.isWhitespace(message.charAt(index));
            if (!whitespace && !inWord)
            {
                words++;
            }
            inWord = !whitespace;
        }
        return words <= 4;
    }

    /** Message plus the source/depth needed for deterministic terminal formatting. */
    private static final class FailureMessage
    {
        final String message;
        final Throwable source;
        final int depth;

        FailureMessage(String message, Throwable source, int depth)
        {
            this.message = message;
            this.source = source;
            this.depth = depth;
        }
    }

    /** One causal throwable plus its result depth and position in the current getCause chain. */
    private static final class ThrowableHop
    {
        final Throwable failure;
        final int causalDepth;
        final int causeDepth;

        ThrowableHop(Throwable failure, int causalDepth, int causeDepth)
        {
            this.failure = failure;
            this.causalDepth = causalDepth;
            this.causeDepth = causeDepth;
        }
    }

    /**
     * The first message ANYWHERE in the failure — the exception chain and, at every hop, the
     * {@link IStatus} tree it carries — that satisfies {@code filter}.
     *
     * <p>Separate from {@link #describe(Throwable)} because the two questions are opposites.
     * {@code describe} answers "what do I show a human", and deliberately stops at the most
     * informative message it meets. Code that must RECOGNISE one specific platform refusal has
     * to look everywhere instead: EDT reports, for example, a refused standalone-server start as
     * a generic "An internal error occurred during …" status whose cause — three hops down —
     * carries the sentence that actually names the reason. Searching only the headline would
     * never see it.
     *
     * @param failure the exception to search (may be {@code null})
     * @param filter the predicate a message must satisfy (never {@code null})
     * @return the first matching message, or {@code null} when the failure carries none
     */
    public static String firstMessageMatching(Throwable failure, Predicate<String> filter)
    {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_CHAIN_DEPTH; depth++)
        {
            String own = trimToNull(current.getMessage());
            if (own != null && filter.test(own))
            {
                return own;
            }
            String fromStatus = matchInStatus(statusOf(current), filter, 0);
            if (fromStatus != null)
            {
                return fromStatus;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * The first matching message in a status tree: the status's own message, then its children,
     * then the message of the exception the status carries.
     *
     * @param status the status to search (may be {@code null})
     * @param filter the predicate a message must satisfy
     * @param depth current recursion depth
     * @return the matching message, or {@code null}
     */
    private static String matchInStatus(IStatus status, Predicate<String> filter, int depth)
    {
        if (status == null || depth > MAX_STATUS_DEPTH)
        {
            return null;
        }
        String own = trimToNull(status.getMessage());
        if (own != null && filter.test(own))
        {
            return own;
        }
        IStatus[] children = status.getChildren();
        if (children != null)
        {
            for (IStatus child : children)
            {
                String fromChild = matchInStatus(child, filter, depth + 1);
                if (fromChild != null)
                {
                    return fromChild;
                }
            }
        }
        Throwable carried = status.getException();
        // The carried exception's own message only: its cause chain is walked by the caller's
        // loop, and recursing into it here could bounce between a status and its exception.
        String carriedMessage = carried == null ? null : trimToNull(carried.getMessage());
        return carriedMessage != null && filter.test(carriedMessage) ? carriedMessage : null;
    }

    /**
     * The message with every embedded Java object identity reduced to the SIMPLE type name:
     * "Failed to persist reference value com...impl.RoleDescriptionImpl@3f2a1b" comes back as
     * "Failed to persist reference value RoleDescriptionImpl". WHICH kind of object the platform
     * refused is the diagnosis and survives; the heap address of one particular run says nothing
     * to a caller who cannot inspect that heap, and does not.
     *
     * <p>Compose it with {@link #describe(Throwable)} - the class comment says why it cannot
     * replace it.
     *
     * @param message the text to scrub (may be {@code null})
     * @return the scrubbed text; {@code null} for {@code null} input, and the very same string
     *     instance when the text carries no object identity at all
     */
    public static String withoutObjectIdentity(String message)
    {
        if (message == null)
        {
            return null;
        }
        Matcher matcher = OBJECT_IDENTITY.matcher(message);
        if (!matcher.find())
        {
            // Hand back the very same instance rather than a copy: this runs on EVERY failure of
            // the paths that use it, and a message needing no scrubbing must be unchanged by
            // construction, not merely equal.
            return message;
        }
        StringBuilder scrubbed = new StringBuilder(message.length());
        do
        {
            // quoteReplacement: an inner class name carries '$', which a replacement string reads
            // as a group reference - unquoted it would throw instead of scrubbing.
            matcher.appendReplacement(scrubbed,
                Matcher.quoteReplacement(simpleTypeName(matcher.group(1))));
        }
        while (matcher.find());
        matcher.appendTail(scrubbed);
        return scrubbed.toString();
    }

    /**
     * The simple name of a possibly qualified Java type name: everything after the last dot, or
     * the whole name when it has none (or ends with one).
     *
     * @param typeName the matched type name (never {@code null}, never empty)
     * @return the simple type name
     */
    private static String simpleTypeName(String typeName)
    {
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == typeName.length() - 1)
        {
            return typeName;
        }
        return typeName.substring(lastDot + 1);
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

    /**
     * First non-blank message among {@code children}, optionally restricted to those that FAILED
     * ({@code ERROR}/{@code CANCEL}). Two passes over the same array rather than sorting it — the
     * array belongs to the platform.
     *
     * @param children the child statuses (never {@code null})
     * @param depth the parent's recursion depth
     * @param failingOnly {@code true} to consider only failing children
     * @param allowNonFailingChildren whether nested aggregates may use their display fallback
     * @param visitedStatuses shallowest depth already visited for each status identity
     * @return the message, or {@code null} when none of the considered children carries one
     */
    private static String firstChildMessage(IStatus[] children, int depth, boolean failingOnly,
            boolean allowNonFailingChildren,
            IdentityHashMap<IStatus, Integer> visitedStatuses)
    {
        for (IStatus child : children)
        {
            if (child == null || (failingOnly && !child.matches(IStatus.ERROR | IStatus.CANCEL)))
            {
                continue;
            }
            String message = statusMessage(child, depth + 1, allowNonFailingChildren,
                visitedStatuses);
            if (message != null)
            {
                return message;
            }
        }
        return null;
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
        return statusMessage(status, depth, true);
    }

    /** Selects status text while optionally disabling display's non-failing child fallback. */
    private static String statusMessage(IStatus status, int depth, boolean allowNonFailingChildren)
    {
        return statusMessage(status, depth, allowNonFailingChildren,
            new IdentityHashMap<IStatus, Integer>());
    }

    /** Alias-aware implementation; a shallower path may expose children hidden by the depth cap. */
    private static String statusMessage(IStatus status, int depth, boolean allowNonFailingChildren,
            IdentityHashMap<IStatus, Integer> visitedStatuses)
    {
        if (status == null || depth > MAX_STATUS_DEPTH)
        {
            return null;
        }
        Integer previousDepth = visitedStatuses.get(status);
        if (previousDepth != null && previousDepth.intValue() <= depth)
        {
            return null;
        }
        visitedStatuses.put(status, Integer.valueOf(depth));
        // CHILDREN FIRST when there are any. EDT wraps its results in a MultiStatus whose own
        // message is the generic headline ("Database update failed") while the reason — the busy
        // port, the rejected object — sits in a child. Returning the root first made this helper
        // hand back exactly the uninformative text it exists to replace.
        IStatus[] children = status.getChildren();
        if (children != null)
        {
            // "Failing" deliberately matches statusMessage's established display pass exactly:
            // IStatus.matches(ERROR | CANCEL). Display and causation agree on that term; causation
            // alone stops after this pass so an INFO/OK sibling cannot be presented as a cause.
            String fromFailing = firstChildMessage(children, depth, true,
                allowNonFailingChildren, visitedStatuses);
            if (fromFailing != null)
            {
                return fromFailing;
            }
            if (allowNonFailingChildren)
            {
                String fromAny = firstChildMessage(children, depth, false,
                    allowNonFailingChildren, visitedStatuses);
                if (fromAny != null)
                {
                    return fromAny;
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
