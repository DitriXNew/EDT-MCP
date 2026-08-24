/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Tests for the statement a write call makes about where it wrote (issue #408).
 * <p>
 * The three answers a call can give - "here", "nowhere", "I cannot tell" - and the fourth it gives
 * by saying nothing at all are what the barrier branches on, so what is pinned here is that they
 * stay four distinct things. Collapsing any two is the defect this type exists to prevent: it is how
 * "the tool queued nothing" and "the tool never said" ended up sharing one empty collection, and how
 * a scanned-but-untouched extension would end up refusing a healthy delete.
 */
public class WriteScopeTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String EXTENSION = "TestConfiguration.tests"; //$NON-NLS-1$

    @Test
    public void testAnActualWriteBeatsAStatementThatNothingWasQueuedWhicheverCameFirst()
    {
        // Order-independence is what makes a tool safe to write in the obvious way: resync_to_disk
        // states "already in sync" and only THEN has its dangling cleanup re-export
        // Configuration.mdo. If the earlier statement won, that export would never be waited for.
        WriteScope nothingFirst = new WriteScope();
        nothingFirst.queuedNothing();
        nothingFirst.wrote(PROJECT);

        WriteScope writeFirst = new WriteScope();
        writeFirst.wrote(PROJECT);
        writeFirst.queuedNothing();

        assertEquals(Collections.singletonList(PROJECT), nothingFirst.writtenProjects());
        assertEquals(Collections.singletonList(PROJECT), writeFirst.writtenProjects());
        assertEquals(nothingFirst.verdict(Collections.<String> emptyList()).written(),
            writeFirst.verdict(Collections.<String> emptyList()).written());
    }

    @Test
    public void testSayingNothingIsNotADeclarationAndFallsBackToTheDefault()
    {
        WriteScope scope = new WriteScope();

        WriteScope.Verdict verdict = scope.verdict(Collections.singletonList(PROJECT));

        assertEquals("a silent call keeps the pre-#408 wait", Collections.singletonList(PROJECT), //$NON-NLS-1$
            verdict.written());
        assertFalse("but it is not a declaration, so nothing may be published", verdict.declared()); //$NON-NLS-1$
        assertNull(verdict.publishable());
    }

    @Test
    public void testStatingThatNothingWasQueuedIsADeclarationOfTheEmptySet()
    {
        // The pair that must never merge: this call KNOWS it queued nothing, so it waits for
        // nothing AND says so, whereas the silent call above waits for the default and says nothing.
        WriteScope scope = new WriteScope();
        scope.queuedNothing();

        WriteScope.Verdict verdict = scope.verdict(Collections.singletonList(PROJECT));

        assertTrue("a stated no-op must not inherit the default wait", verdict.written().isEmpty()); //$NON-NLS-1$
        assertTrue(verdict.declared());
        assertEquals(Collections.emptyList(), new java.util.ArrayList<>(verdict.publishable()));
    }

    @Test
    public void testAnUndeterminableScopeWaitsItsFallbackWithoutPublishingAnything()
    {
        WriteScope scope = new WriteScope();
        scope.undeterminable("the platform does not report it", Collections.singletonList(PROJECT)); //$NON-NLS-1$

        WriteScope.Verdict verdict = scope.verdict(Collections.singletonList(EXTENSION));

        assertEquals("the stated fallback wins over the default", Collections.singletonList(PROJECT), //$NON-NLS-1$
            verdict.written());
        assertNull("'I could not tell' is not 'I wrote nowhere'", verdict.publishable()); //$NON-NLS-1$
        assertEquals("the platform does not report it", scope.undeterminableReason()); //$NON-NLS-1$
    }

    @Test
    public void testARecordOverridesAnUndeterminableStatement()
    {
        // If a tool that could not classify its write turns out to have submitted an export after
        // all, the export is the harder fact and must be waited for.
        WriteScope scope = new WriteScope();
        scope.undeterminable("opaque", Collections.<String> emptyList()); //$NON-NLS-1$
        scope.wrote(PROJECT);

        WriteScope.Verdict verdict = scope.verdict(Collections.<String> emptyList());

        assertEquals(Collections.singletonList(PROJECT), verdict.written());
        assertEquals(Collections.singletonList(PROJECT),
            new java.util.ArrayList<>(verdict.publishable()));
    }

    @Test
    public void testACallThatSaysBothThingsIsReadAsTheOneThatWaitsMoreAndClaimsLess()
    {
        // "I queued nothing" and "I cannot tell where I wrote" contradict each other, and the two
        // owe opposite answers: one skips the wait and publishes a finding, the other waits a
        // named fallback and publishes nothing. Reading such a call as the confident one would
        // drop the wait AND assert a finding the same call has just said it cannot make.
        WriteScope scope = new WriteScope();
        scope.queuedNothing();
        scope.undeterminable("opaque", Collections.singletonList(PROJECT)); //$NON-NLS-1$

        WriteScope.Verdict verdict = scope.verdict(Collections.<String> emptyList());

        assertEquals(Collections.singletonList(PROJECT), verdict.written());
        assertNull(verdict.publishable());
    }

    @Test
    public void testACascadeParticipantIsDeclaredButNeverPublishedAsWritten()
    {
        WriteScope scope = new WriteScope();
        scope.cascadedInto(EXTENSION);

        WriteScope.Verdict verdict = scope.verdict(Collections.singletonList(PROJECT));

        assertEquals(Collections.singletonList(EXTENSION), verdict.cascaded());
        assertTrue("a cascade participant is not a project we wrote in", verdict.written().isEmpty()); //$NON-NLS-1$
        assertEquals("and it must not be published under a name that says 'wrote'", //$NON-NLS-1$
            Collections.emptyList(), new java.util.ArrayList<>(verdict.publishable()));
        assertTrue("declaring only participants is still a declaration", verdict.declared()); //$NON-NLS-1$
    }

    @Test
    public void testAProjectBothWrittenAndCascadedIntoKeepsOnlyTheStrongerGrade()
    {
        // Otherwise it would be waited for twice, and the second wait would be under the grade that
        // cannot refuse - quietly weakening the first.
        WriteScope scope = new WriteScope();
        scope.cascadedInto(PROJECT);
        scope.wrote(PROJECT);

        WriteScope.Verdict verdict = scope.verdict(Collections.<String> emptyList());

        assertEquals(Collections.singletonList(PROJECT), verdict.written());
        assertTrue(verdict.cascaded().isEmpty());
    }

    @Test
    public void testTheStaticFacesRecordIntoTheBoundScopeAndNowhereElse()
    {
        // The choke point in BmTransactions reaches the scope this way, from ~20 call sites and
        // from helpers shared with read tools - so recording outside a write call must be a no-op
        // rather than an error, and must not leak into the next call.
        WriteScope scope = new WriteScope();
        WriteScope.runWithScope(scope, () -> {
            WriteScope.recordWrite(PROJECT);
            WriteScope.recordCascade(EXTENSION);
        });

        assertEquals(Collections.singletonList(PROJECT), scope.writtenProjects());
        assertEquals(Collections.singletonList(EXTENSION), scope.cascadeProjects());

        // Outside any call: no scope, no crash, nothing recorded anywhere.
        WriteScope.recordWrite("SomeOtherProject"); //$NON-NLS-1$
        assertEquals(Collections.singletonList(PROJECT), scope.writtenProjects());
    }

    @Test
    public void testARecordedWriteMarksAnyPlainErrorAtTheReturnPoint()
    {
        WriteScope scope = new WriteScope();
        String refusal = com.ditrix.edt.mcp.server.protocol.ToolResult.error("later step failed").toJson(); //$NON-NLS-1$

        assertEquals("an error before the commit must stay an ordinary refusal", refusal, //$NON-NLS-1$
            scope.markErrorAfterRecordedWrite(refusal));

        scope.wrote(PROJECT);
        String marked = scope.markErrorAfterRecordedWrite(refusal);
        assertTrue(marked.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
        assertTrue("the original diagnostic must survive", marked.contains("later step failed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnOpaqueWriterMarksUnknownWithoutClaimingACommit()
    {
        WriteScope scope = new WriteScope();
        scope.undeterminable("extension point is opaque", Collections.singletonList(PROJECT)); //$NON-NLS-1$
        String refusal = com.ditrix.edt.mcp.server.protocol.ToolResult.error("fix threw").toJson(); //$NON-NLS-1$

        String marked = scope.markErrorAfterRecordedWrite(refusal);

        assertTrue(marked.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
        assertFalse(marked.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
    }

    @Test
    public void testANestedCallRecordsIntoItsOwnScopeAndGivesTheOuterOneBack()
    {
        // Reachable: the consent dialog and resync_to_disk both pump nested SWT event loops, in
        // which another request's UI runnable can run on the same thread. Clearing the binding
        // instead of restoring it would leave the outer call recording nothing for the rest of its
        // work - the failure mode being prevented is silent under-claiming, not a crash.
        WriteScope outer = new WriteScope();
        WriteScope inner = new WriteScope();

        WriteScope.runWithScope(outer, () -> {
            WriteScope.recordWrite(PROJECT);
            WriteScope.runWithScope(inner, () -> WriteScope.recordWrite(EXTENSION));
            WriteScope.recordCascade("AfterTheNestedCall"); //$NON-NLS-1$
        });

        assertEquals(Collections.singletonList(PROJECT), outer.writtenProjects());
        assertEquals(Collections.singletonList("AfterTheNestedCall"), outer.cascadeProjects()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(EXTENSION), inner.writtenProjects());
    }

    @Test
    public void testTheBindingIsGivenBackEvenWhenTheCallThrowsAnError()
    {
        // An Error escaping a tool is not caught by the base class's catch(Exception), so a binding
        // that is only cleared on the normal path would leave the UI thread bound to a finished
        // call - and every later export on that thread would be charged to it.
        WriteScope leaked = new WriteScope();
        try
        {
            WriteScope.runWithScope(leaked, () -> {
                throw new StackOverflowError("simulated"); //$NON-NLS-1$
            });
        }
        catch (StackOverflowError expected)
        {
            // The point is what the binding looks like afterwards.
        }

        WriteScope.recordWrite(PROJECT);
        assertTrue("the finished call must not still be collecting records", //$NON-NLS-1$
            leaked.writtenProjects().isEmpty());
    }

    @Test
    public void testThePublishedListIsSortedAndDeduplicated()
    {
        WriteScope scope = new WriteScope();
        scope.wrote(EXTENSION);
        scope.wrote(PROJECT);
        scope.wrote(EXTENSION);

        assertEquals(Arrays.asList(PROJECT, EXTENSION),
            new java.util.ArrayList<>(scope.verdict(Collections.<String> emptyList()).publishable()));
    }

    @Test
    public void testUnusableNamesAreIgnoredRatherThanRecordedAsProjects()
    {
        WriteScope scope = new WriteScope();
        scope.wrote((String)null);
        scope.wrote(""); //$NON-NLS-1$
        scope.cascadedInto((String)null);

        assertTrue(scope.writtenProjects().isEmpty());
        assertTrue(scope.cascadeProjects().isEmpty());
        assertFalse("and an ignored name must not count as a declaration", //$NON-NLS-1$
            scope.verdict(Collections.singletonList(PROJECT)).declared());
    }
}
