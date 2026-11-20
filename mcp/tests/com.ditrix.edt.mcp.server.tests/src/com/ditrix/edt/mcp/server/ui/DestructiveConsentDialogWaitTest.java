/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.widgets.Shell;
import org.junit.Test;
import org.mockito.InOrder;

/** Issue #997: the consent dialog tells the human that it closes by itself, and after how long. */
public class DestructiveConsentDialogWaitTest
{
    @Test
    public void theDialogNamesItsOwnWait()
    {
        assertEquals("Closes by itself after 45 s without an answer; the tool call is then refused.", //$NON-NLS-1$
            DestructiveConsentDialog.waitText(45));
    }

    /** PR #1002 review: the gate hears of a press at the press, before the dialog tears down. */
    @Test
    public void aPressIsReportedBeforeTheDialogCloses()
    {
        AtomicLong heard = new AtomicLong(-1L);
        AtomicLong heardAt = new AtomicLong();
        AtomicLong heardWhenClosing = new AtomicLong(-1L);
        DestructiveConsentDialog dialog = new DestructiveConsentDialog(null, "delete_project", null, 45) //$NON-NLS-1$
        {
            @Override
            public boolean close()
            {
                heardWhenClosing.set(heard.get());
                return super.close();
            }
        };
        dialog.setAnswerListener((buttonId, atNanos) -> { heard.set(buttonId); heardAt.set(atNanos); });
        long before = System.nanoTime();
        dialog.buttonPressed(IDialogConstants.OK_ID);
        assertEquals("reported before teardown began", IDialogConstants.OK_ID, heardWhenClosing.get()); //$NON-NLS-1$
        assertTrue(heardAt.get() - before >= 0);
        assertEquals(heardAt.get(), dialog.answeredAtNanos().getAsLong());
    }

    /**
     * The answered dialog leaves the open-dialog list (by hiding) BEFORE the gate releases the caller,
     * so the caller's response does not tell it to ask the user about a prompt already answered.
     */
    @Test
    public void theAnsweredDialogIsHiddenBeforeTheGateIsTold()
    {
        Shell shell = mock(Shell.class);
        Runnable told = mock(Runnable.class);
        DestructiveConsentDialog dialog = new DestructiveConsentDialog(null, "delete_project", null, 45) //$NON-NLS-1$
        {
            @Override
            public Shell getShell()
            {
                return shell;
            }

            @Override
            public boolean close()
            {
                return true;
            }
        };
        dialog.setAnswerListener((buttonId, atNanos) -> told.run());
        dialog.buttonPressed(IDialogConstants.CANCEL_ID);
        InOrder order = inOrder(shell, told);
        order.verify(shell).setVisible(false);
        order.verify(told).run();
    }

    /** The close box and Esc reject; that answer is reported at the event too. */
    @Test
    public void closingTheWindowIsReportedAsARejectBeforeTeardown()
    {
        AtomicLong heard = new AtomicLong(-1L);
        AtomicLong heardWhenClosing = new AtomicLong(-1L);
        DestructiveConsentDialog dialog = new DestructiveConsentDialog(null, "delete_project", null, 45) //$NON-NLS-1$
        {
            @Override
            public boolean close()
            {
                heardWhenClosing.set(heard.get());
                return super.close();
            }
        };
        dialog.setAnswerListener((buttonId, atNanos) -> heard.set(buttonId));
        dialog.handleShellCloseEvent();
        assertEquals(IDialogConstants.CANCEL_ID, heardWhenClosing.get());
        assertTrue(dialog.answeredAtNanos().isPresent());
    }
}
