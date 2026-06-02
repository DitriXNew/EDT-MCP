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
}
