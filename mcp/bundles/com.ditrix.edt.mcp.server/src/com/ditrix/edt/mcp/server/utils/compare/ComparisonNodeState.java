/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;

/**
 * What happened to ONE comparison node across the three sides, and the only place that decides it.
 *
 * <h2>Why one place</h2>
 * There are two views of the same tree - {@link ComparisonTreeReport}, which lists the top objects,
 * and {@link ComparisonNodeRenderer}, which expands one of them - and a caller reaches the second
 * FROM the first. Each used to decode the state itself, and the two decodings disagreed on the case
 * three-way comparison exists for: when main and other carry the SAME edit away from the common
 * ancestor, {@code hasChangedMainOther()} is false, and the expanded node fell through to "No
 * differences" while the report the caller had just read called it "changed on both sides". The
 * node view contradicted the document it was reached from, over a node that differs from the
 * ancestor on both sides.
 * <p>
 * So the decision lives here and the two views only RENDER it, through {@link #label()}. A third
 * view, or a new state, cannot reintroduce the divergence, because there is no second decision to
 * drift from.
 *
 * <h2>The order of the questions is the contract</h2>
 * <ol>
 * <li>An UNFINISHED node is answered first: the tree is lazy, so every later question would be
 * answered from flags the engine has not filled in yet.</li>
 * <li>No {@link ComparisonFlags} object at all is the ABSENCE of a verdict, not a verdict of
 * "equal". It is {@link #NOT_REPORTED}, and it {@link #differs()} - a caller filtering to the
 * changed objects must still see a node nobody judged.</li>
 * <li>A CONFLICT comes from {@code hasDoubleChanges()} - the platform's own verdict, not a count of
 * changed sides.</li>
 * <li>Then PRESENCE (the one-sided cases, which the ancestor turns into an addition or a
 * deletion), then CHANGE RELATIVE TO THE ANCESTOR, and only last the plain main-vs-other
 * difference.</li>
 * </ol>
 * <p>
 * <b>A presence answer is only ever given for a side that was identified.</b> A one-sided node
 * whose {@code getNodeSide()} is {@code null} names no side, so there is nothing to turn into an
 * addition or a deletion and the state is {@link #NOT_REPORTED} - see {@link #decodeOneSided}.
 * <p>
 * The ancestor-relative questions are asked with {@link ComparisonFlags#hasChanged} against
 * {@link ComparisonSide#COMMON_ANCESTOR}, and the last one with
 * {@link ComparisonFlags#hasDifferences} rather than {@code hasChanged}: the platform's own
 * {@code hasDifferences} is {@code hasChanged} OR either one-sided marker, so it also covers a node
 * whose flags record a presence difference the node itself does not report through
 * {@link ComparisonNode#isOneSideNode()}. Narrowing that to {@code hasChanged} would answer
 * {@link #IDENTICAL} for a node the engine flagged as present on one side only.
 * <p>
 * Every label is locale-free and computed here, never taken from the platform's node labeller,
 * which branches on {@code Locale.getDefault()}.
 */
public enum ComparisonNodeState
{
    /** The platform itself marked the node as changed on both sides. */
    CONFLICT("CONFLICT (changed on both sides)", true), //$NON-NLS-1$
    /** Present on the other side only, and absent from the common ancestor. */
    ADDED_ON_OTHER("added on other", true), //$NON-NLS-1$
    /** Present on the main side only, and absent from the common ancestor. */
    ADDED_ON_MAIN("added on main", true), //$NON-NLS-1$
    /** Present on the other side and the ancestor, so the main side dropped it. */
    DELETED_ON_MAIN("deleted on main", true), //$NON-NLS-1$
    /** Present on the main side and the ancestor, so the other side dropped it. */
    DELETED_ON_OTHER("deleted on other", true), //$NON-NLS-1$
    /** Present in the common ancestor only. */
    ONLY_IN_ANCESTOR("deleted on both sides", true), //$NON-NLS-1$
    /** Both sides moved away from the ancestor without the platform calling it a conflict. */
    CHANGED_ON_BOTH("changed on both sides", true), //$NON-NLS-1$
    /** Only the other side moved away from the ancestor. */
    CHANGED_ON_OTHER("changed on other", true), //$NON-NLS-1$
    /** Only the main side moved away from the ancestor. */
    CHANGED_ON_MAIN("changed on main", true), //$NON-NLS-1$
    /** The two sides differ, but not relative to the ancestor in a way the flags name. */
    DIFFERS("differs between main and other", true), //$NON-NLS-1$
    /** Compared, and equal on every side. */
    IDENTICAL("identical", false), //$NON-NLS-1$
    /**
     * The engine left the question unanswered - NOT a statement of equality, and not a statement
     * about presence either. Two ways in, and both are an absence of a verdict rather than a
     * verdict: no {@link ComparisonFlags} object at all, and a node that calls itself one-sided
     * without naming the side it exists on.
     */
    NOT_REPORTED("not reported by the engine", true), //$NON-NLS-1$
    /** Not compared yet - the tree is lazy, so this is NOT a statement about equality. */
    NOT_COMPARED("not compared yet", true); //$NON-NLS-1$

    private final String label;
    private final boolean differs;

    ComparisonNodeState(String label, boolean differs)
    {
        this.label = label;
        this.differs = differs;
    }

    /** @return the stable wire text for this state, rendered by BOTH views */
    public String label()
    {
        return label;
    }

    /**
     * @return {@code true} when this state must survive the {@code changedOnly} filter;
     *     {@link #NOT_COMPARED} and {@link #NOT_REPORTED} do, because dropping them would silently
     *     present a subtree nobody compared, or nobody judged, as an equal one
     */
    public boolean differs()
    {
        return differs;
    }

    /**
     * Decides what happened to one node whose own comparison status is known.
     *
     * @param node the platform node (never {@code null})
     * @param status the node's comparison status (may be {@code null}, which is not
     *     {@code FINISHED})
     * @return the decoded state, never {@code null}
     */
    public static ComparisonNodeState decode(ComparisonNode node, ComparisonNodeStatus status)
    {
        return decode(node, status == ComparisonNodeStatus.FINISHED);
    }

    /**
     * Decides what happened to one node.
     * <p>
     * {@code finished} is passed in rather than read off the node because only a
     * {@code TopComparisonNode} carries a status of its own: a child node inherits the finishedness
     * of the top node it was reached through, and answering "identical" for a child of an
     * unfinished subtree is the same lie as answering it for the subtree.
     *
     * @param node the platform node (never {@code null})
     * @param finished whether the node's subtree has been compared
     * @return the decoded state, never {@code null}
     */
    public static ComparisonNodeState decode(ComparisonNode node, boolean finished)
    {
        if (!finished)
        {
            return NOT_COMPARED;
        }
        ComparisonFlags flags = node.getComparisonFlags();
        if (flags == null)
        {
            // No flags object is the ABSENCE of a verdict, not a verdict of "equal". Substituting
            // ComparisonFlags.NO_FLAGS here - which is what the top report used to do - turns a
            // question the engine never answered into the answer "identical".
            return NOT_REPORTED;
        }
        if (flags.hasDoubleChanges())
        {
            return CONFLICT;
        }
        if (node.isOneSideNode())
        {
            return decodeOneSided(node.getNodeSide(), node.isAncestorObjectExists());
        }
        boolean changedOnMain = flags.hasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        boolean changedOnOther =
            flags.hasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);
        if (changedOnMain && changedOnOther)
        {
            // The case the whole enum exists for: both sides moved, so main and other may well
            // agree with each other, and a decision taken from MAIN-vs-OTHER alone reads a node
            // that differs from the ancestor on both sides as an unchanged one.
            return CHANGED_ON_BOTH;
        }
        if (changedOnOther)
        {
            return CHANGED_ON_OTHER;
        }
        if (changedOnMain)
        {
            return CHANGED_ON_MAIN;
        }
        if (flags.hasDifferences(ComparisonSide.MAIN, ComparisonSide.OTHER))
        {
            return DIFFERS;
        }
        return IDENTICAL;
    }

    /**
     * Names the one-sided case, which the ancestor turns into an addition or a deletion.
     * <p>
     * <b>Every answer here is read off the side the node names, and there is no fallback that
     * guesses one.</b> The parameter may be {@code null} - the platform's own accessor is nullable
     * and this method's contract says so - and a {@code null} side means the node reported that it
     * exists on ONE side without saying which. That is a question nobody answered, so it is
     * {@link #NOT_REPORTED}, the vocabulary this document already carries for exactly that: no
     * verdict was attached, and the state {@link #differs()}, so the node still survives the
     * {@code changedOnly} filter. It used to fall through to {@link #ONLY_IN_ANCESTOR}, which
     * renders "deleted on both sides" - a three-sided verdict about presence, reached without
     * having identified a single side, and printed into both the top-level table and the expanded
     * report as if the engine had said it.
     * <p>
     * {@link #ONLY_IN_ANCESTOR} is therefore reserved for {@link ComparisonSide#COMMON_ANCESTOR}
     * and nothing else: it is the one side whose presence really does mean both working sides
     * dropped the object. Any side this enum does not know - a constant added by a later platform
     * included - is unanswered rather than assumed, for the same reason.
     *
     * @param side the side the object exists on (may be {@code null})
     * @param ancestorExists whether the common ancestor still has the object
     * @return the decoded state
     */
    private static ComparisonNodeState decodeOneSided(ComparisonSide side, boolean ancestorExists)
    {
        if (side == ComparisonSide.OTHER)
        {
            return ancestorExists ? DELETED_ON_MAIN : ADDED_ON_OTHER;
        }
        if (side == ComparisonSide.MAIN)
        {
            return ancestorExists ? DELETED_ON_OTHER : ADDED_ON_MAIN;
        }
        if (side == ComparisonSide.COMMON_ANCESTOR)
        {
            return ONLY_IN_ANCESTOR;
        }
        return NOT_REPORTED;
    }
}
