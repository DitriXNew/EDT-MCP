/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

/**
 * One reading of EDT's comparison service: either an ANSWER the platform gave, or the fact that
 * the question could not be ASKED at all.
 *
 * <h2>Why this type exists</h2>
 * It is the reading-side counterpart of {@link ComparisonEngine.ServiceUnavailableException}. The
 * lifetime calls (start/cancel/stop) throw when the service is not registered, because returning
 * quietly from one let a caller publish work that never reached the platform. The reading calls
 * cannot throw - a poll loop that died on one unlucky tick would end a healthy comparison - so
 * they used to answer {@code null} or an EMPTY LIST instead, and that is the same defect in the
 * other direction: <b>"we could not ask" was byte-for-byte identical to "we asked and there is
 * nothing there"</b>. Consumers then turned the second reading into a VERDICT. The measured
 * consequence: {@code ComparisonSessionRegistry} read an empty handle list produced by an
 * unregistered service as proof that EDT had forgotten the comparison, dropped the session
 * without stopping it, and left the comparison holding EDT's single slot addressable by nobody.
 *
 * <h2>Why not {@code Optional}</h2>
 * {@code Optional} states the same two cases, and that is exactly the problem: its vocabulary IS
 * the vocabulary of the confusion being removed. "Empty" reads as "nothing there" - the very
 * reading that caused the defect - and the one-liner a reader reaches for,
 * {@code answer.orElse(Collections.emptyList())}, silently restores it while looking like
 * housekeeping. This type has no such default: {@link #orElse(Object)} takes the fallback as an
 * argument, so choosing one is a visible decision at the call site rather than the shape of the
 * type. {@code Optional<Boolean>} would additionally give three states for a two-state question.
 *
 * <h2>What "answered" means</h2>
 * That the platform was reached and said something - INCLUDING saying "nothing". An answered
 * {@code null} status and an answered empty list are real facts about the comparison; an
 * unavailable answer is a fact about this server's reach, and no caller may quote it as a fact
 * about the comparison.
 *
 * @param <T> what the platform answers with
 */
public final class PlatformAnswer<T>
{
    private static final PlatformAnswer<?> UNAVAILABLE = new PlatformAnswer<>(null, false);

    private final T value;
    private final boolean answered;

    private PlatformAnswer(T value, boolean answered)
    {
        this.value = value;
        this.answered = answered;
    }

    /**
     * The platform was reached and answered this - {@code null} and empty collections included,
     * because those are answers too.
     *
     * @param <T> what the platform answers with
     * @param value what it said
     * @return the answer
     */
    public static <T> PlatformAnswer<T> of(T value)
    {
        return new PlatformAnswer<>(value, true);
    }

    /**
     * The platform could not be asked, so there is no answer to quote.
     *
     * @param <T> what the platform would have answered with
     * @return the absence
     */
    @SuppressWarnings("unchecked")
    public static <T> PlatformAnswer<T> unavailable()
    {
        return (PlatformAnswer<T>)UNAVAILABLE;
    }

    /**
     * @return {@code true} when the platform answered, whatever it said
     */
    public boolean isAnswered()
    {
        return answered;
    }

    /**
     * @return {@code true} when the question could not be asked at all
     */
    public boolean isUnavailable()
    {
        return !answered;
    }

    /**
     * The answer, or a fallback the CALLER names.
     * <p>
     * There is deliberately no zero-argument form. Every collapse of "could not ask" into a value
     * is a judgement about what to do when this server cannot reach EDT, and it belongs at the
     * call site - written down, next to the comment saying why that judgement is safe there.
     *
     * @param fallback what to use when the platform could not be asked
     * @return the platform's answer when there is one, otherwise {@code fallback}
     */
    public T orElse(T fallback)
    {
        return answered ? value : fallback;
    }

    @Override
    public String toString()
    {
        return answered ? "answered(" + value + ')' : "unavailable"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
