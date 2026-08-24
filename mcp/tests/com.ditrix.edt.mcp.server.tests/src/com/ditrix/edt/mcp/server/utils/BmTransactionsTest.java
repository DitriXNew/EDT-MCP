/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;

/**
 * Tests for {@link BmTransactions}.
 * <p>
 * The whole point of the helper is to route a read through
 * {@link IBmModel#executeReadonlyTask} and a write through
 * {@link IBmModel#execute} - so a read can never accidentally run in a
 * write-capable transaction (the {@code 25d7851} class of bug). These tests pin
 * that routing with a mocked {@link IBmModel}: the stub invokes the submitted
 * task's body with a stand-in transaction so we also confirm the operation
 * receives the active transaction and its return value is propagated.
 */
public class BmTransactionsTest
{
    @Test
    public void testReadRunsInReadonlyTaskAndReturnsResult()
    {
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });

        boolean[] ran = {false};
        String result = BmTransactions.read(model, "t", (t, pm) -> { //$NON-NLS-1$
            ran[0] = true;
            assertSame("read op must receive the active transaction", tx, t); //$NON-NLS-1$
            return "R"; //$NON-NLS-1$
        });

        assertTrue("read op must run", ran[0]); //$NON-NLS-1$
        assertEquals("R", result); //$NON-NLS-1$
        // A read must go through the read-only path, never the writable one.
        verify(model).executeReadonlyTask(any());
        verify(model, never()).execute(any());
    }

    @Test
    public void testWriteRunsInWriteTaskAndReturnsResult()
    {
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(model.execute(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });

        boolean[] ran = {false};
        String result = BmTransactions.write(model, "t", (t, pm) -> { //$NON-NLS-1$
            ran[0] = true;
            assertSame("write op must receive the active transaction", tx, t); //$NON-NLS-1$
            return "W"; //$NON-NLS-1$
        });

        assertTrue("write op must run", ran[0]); //$NON-NLS-1$
        assertEquals("W", result); //$NON-NLS-1$
        // A write must go through the writable path, never the read-only one.
        verify(model).execute(any());
        verify(model, never()).executeReadonlyTask(any());
    }

    @Test
    public void testWriteReturnRecordsCommitBeforeCallerSideWorkCanFail()
    {
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(model.execute(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });

        WriteScope scope = new WriteScope();
        WriteScope.runWithScope(scope, () ->
            BmTransactions.write(model, "commit", (t, pm) -> "done")); //$NON-NLS-1$ //$NON-NLS-2$

        String failure = scope.markErrorAfterRecordedWrite(
            ToolResult.error("response rendering failed").toJson()); //$NON-NLS-1$
        assertTrue("a BM write that returned must mark every later error as post-commit: " + failure, //$NON-NLS-1$
            failure.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
        assertTrue("commit-only recording must not invent an export project", //$NON-NLS-1$
            scope.writtenProjects().isEmpty());
    }

    @Test
    public void testForceExportToDiskRecordsTheProjectIntoTheCallsWriteScope()
    {
        // The #408 mechanism, pinned at the point that makes it a mechanism rather than a
        // convention: this is the ONE place the plugin hands save tasks to the platform, reached
        // from ~20 call sites across three tools and from the shared form/rights writers. Because
        // the record is taken here, a tool declares where it wrote by DOING the write - a new tool
        // cannot forget a step it never has to take.
        org.eclipse.core.resources.IProject project =
            mock(org.eclipse.core.resources.IProject.class);
        when(project.getName()).thenReturn("TestConfiguration"); //$NON-NLS-1$

        com.ditrix.edt.mcp.server.tools.base.WriteScope scope =
            new com.ditrix.edt.mcp.server.tools.base.WriteScope();
        com.ditrix.edt.mcp.server.tools.base.WriteScope.runWithScope(scope,
            () -> BmTransactions.forceExportToDisk(project, "Catalog.Products")); //$NON-NLS-1$

        // Headless there are no EDT services, so the submission itself cannot succeed - and that is
        // the case worth pinning: the record is taken for the ATTEMPT. A refused submission is not
        // evidence that the call did not write (the model change stands, and a list submission that
        // threw part way through is not even evidence that nothing was queued), so dropping the
        // project there is how the barrier used to end up waiting for the wrong one.
        assertEquals(java.util.Collections.singletonList("TestConfiguration"), //$NON-NLS-1$
            scope.writtenProjects());
    }

    @Test
    public void testForceExportToDiskOutsideAWriteCallRecordsNothingAndDoesNotThrow()
    {
        // The same helper is reachable from paths that are not a write tool's call at all
        // (build_external_objects runs in a Job). Recording must simply not happen there.
        org.eclipse.core.resources.IProject project =
            mock(org.eclipse.core.resources.IProject.class);
        when(project.getName()).thenReturn("TestConfiguration"); //$NON-NLS-1$

        assertTrue("no services headless, so no submission - and no exception either", //$NON-NLS-1$
            !BmTransactions.forceExportToDisk(project, "Catalog.Products")); //$NON-NLS-1$
    }
}
