/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;

/**
 * Unit tests for {@link ComparisonView}, and deliberately for ONE of its accessors.
 * <p>
 * The class is a wall rather than a computation: twenty one-line delegations whose whole job is to
 * expose the reading half of {@code IComparisonSession} and nothing else. Pinning each of them
 * would pin the wall to itself. {@link ComparisonView#isGlobalScope()} is different, because
 * something downstream is DECIDED by it: {@code get_comparison_node} turns its answer into
 * {@link ComparisonNodeRenderer.ContentCoverage}, which governs whether the document tells the
 * caller that content was excluded somewhere. The renderer's own tests drive the classification
 * from a boolean; this one pins where that boolean comes from, so the two halves together cover
 * the claim end to end.
 * <p>
 * It matters WHICH answer it is. The session computes {@code isGlobalScope} once, in its
 * constructor, and keeps it - the same value that decided {@code mergeObjectsContent} at launch -
 * while the scope OBJECT grows during the run as the engine pulls dependencies in. Reading the
 * saved answer is therefore the difference between describing the run the caller started and
 * describing the scope it ended up with.
 */
public class ComparisonViewTest
{
    /**
     * The handle is not touched by the accessor under test, and passing {@code null} says so: a
     * fake handle would suggest the answer might come from it, which is exactly the confusion this
     * test exists to rule out.
     *
     * @param globalScope what the session answers
     * @return a view over a session scripted to answer that
     */
    private static ComparisonView viewOverSessionSaying(boolean globalScope)
    {
        IComparisonSession session = mock(IComparisonSession.class);
        when(session.isGlobalScope()).thenReturn(globalScope);
        return new ComparisonView(null, session);
    }

    @Test
    public void isGlobalScopeReportsTheSessionsOwnAnswerForAWholeConfigurationRun()
    {
        assertTrue("a whole-configuration run must be reported as one", //$NON-NLS-1$
            viewOverSessionSaying(true).isGlobalScope());
    }

    @Test
    public void isGlobalScopeReportsTheSessionsOwnAnswerForAScopedRun()
    {
        // The direction that costs something when it is wrong: answering true here would make
        // every scoped document claim that content was compared everywhere.
        assertFalse("a scoped run must not be reported as a whole-configuration one", //$NON-NLS-1$
            viewOverSessionSaying(false).isGlobalScope());
    }
}
