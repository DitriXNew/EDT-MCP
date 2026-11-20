/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/**
 * PR #1002 review: from a consent prompt's cutoff on its verdict is TIMEOUT, but its shell stays on
 * screen until the UI thread runs the close - queued by the worker, or delayed by a busy UI thread on
 * the UI-thread path - while this call's response and every concurrent one are assembled at once.
 * Past the cutoff the dialog must be out of the open-dialog list, the notice and {@code edt_dialog},
 * so no response asks the agent to have the user answer a prompt whose answer no longer counts.
 */
public class EdtDialogsDecidedPromptTest
{
    private Display display;
    private final AtomicLong now = new AtomicLong(1_000L);
    private EdtDialogs dialogs;

    @Before
    public void setUp()
    {
        display = mock(Display.class);
        when(display.getThread()).thenReturn(new Thread());
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; })
            .when(display).asyncExec(any(Runnable.class));
        Map<Integer, Listener> filters = new HashMap<>();
        doAnswer(call -> { filters.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(display).addFilter(anyInt(), any(Listener.class));
        dialogs = new EdtDialogs(() -> display, now::get);
    }

    static Shell modal(String title)
    {
        Shell shell = mock(Shell.class);
        when(shell.isVisible()).thenReturn(true);
        when(shell.getStyle()).thenReturn(SWT.APPLICATION_MODAL);
        when(shell.getText()).thenReturn(title);
        Map<String, Object> data = new HashMap<>();
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(shell).setData(anyString(), any());
        when(shell.getData(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        Label label = mock(Label.class);
        when(label.getText()).thenReturn("Requested by MCP tool: delete_project"); //$NON-NLS-1$
        Button allow = mock(Button.class);
        when(allow.getStyle()).thenReturn(SWT.PUSH);
        when(allow.getText()).thenReturn("Allow"); //$NON-NLS-1$
        when(allow.isEnabled()).thenReturn(true);
        when(allow.isVisible()).thenReturn(true);
        when(shell.getChildren()).thenReturn(new Control[] {label, allow});
        EdtDialogs.protect(shell);
        return shell;
    }

    private Shell consentUntil(long cutoff)
    {
        Shell shell = modal("Confirm destructive operation"); //$NON-NLS-1$
        EdtDialogs.answerableUntil(shell, cutoff);
        return shell;
    }

    private JsonArray listed()
    {
        return JsonParser.parseString(dialogs.list()).getAsJsonObject().getAsJsonArray("dialogs"); //$NON-NLS-1$
    }

    @Test
    public void aPromptPastItsCutoffLeavesTheNoticeWhileItsShellIsStillOpen()
    {
        Shell consent = consentUntil(5_000L);
        when(display.getShells()).thenReturn(new Shell[] {consent});
        assertEquals(1, listed().size());
        assertTrue(dialogs.notice().contains("ask them")); //$NON-NLS-1$

        now.set(5_000L); // the cutoff; no UI event has run, the shell is still visible

        assertNull("a timed-out prompt must not be offered to the user any more", dialogs.notice()); //$NON-NLS-1$
        assertEquals(0, dialogs.cachedDialogs().size());
    }

    @Test
    public void aPromptBeforeItsCutoffIsStillOfferedToTheUser()
    {
        Shell consent = consentUntil(5_000L);
        when(display.getShells()).thenReturn(new Shell[] {consent});
        listed();
        now.set(4_999L);
        assertTrue(dialogs.notice(), dialogs.notice().contains("only the user can answer it")); //$NON-NLS-1$
    }

    @Test
    public void aSweepPastTheCutoffDoesNotListItAgain()
    {
        Shell consent = consentUntil(5_000L);
        when(display.getShells()).thenReturn(new Shell[] {consent});
        now.set(6_000L);
        assertEquals("edt_dialog list must not show it before the queued close", 0, listed().size()); //$NON-NLS-1$
        assertNull(dialogs.notice());
    }

    @Test
    public void aDialogWithoutACutoffIsNotAffectedByTime()
    {
        Shell other = modal("Another question"); //$NON-NLS-1$
        Shell consent = consentUntil(5_000L);
        when(display.getShells()).thenReturn(new Shell[] {consent, other});
        assertEquals(2, listed().size());
        now.set(Long.MAX_VALUE / 2);
        JsonArray left = dialogs.cachedDialogs();
        assertEquals(1, left.size());
        assertEquals("Another question", left.get(0).getAsJsonObject().get("title").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dialogs.notice(), dialogs.notice().startsWith("EDT dialog open: \"Another question\"")); //$NON-NLS-1$
    }

    @Test
    public void aPressOnAPromptPastItsCutoffIsToldItHasClosed()
    {
        Shell consent = consentUntil(5_000L);
        when(display.getShells()).thenReturn(new Shell[] {consent});
        String id = listed().get(0).getAsJsonObject().get("dialogId").getAsString(); //$NON-NLS-1$
        now.set(5_000L);
        String result = dialogs.press(id, "Allow"); //$NON-NLS-1$
        assertTrue(result, result.contains("has closed or been replaced")); //$NON-NLS-1$
        assertFalse("its verdict is final: nobody is to be asked to answer it", //$NON-NLS-1$
            result.contains("ask the user")); //$NON-NLS-1$
    }

    @Test
    public void theCutoffWrapsWithTheNanoClock()
    {
        // System.nanoTime() may be negative or wrap; the comparison is by difference, like the arbiter's.
        now.set(Long.MAX_VALUE - 10L);
        Shell consent = consentUntil(Long.MAX_VALUE - 5L);
        when(display.getShells()).thenReturn(new Shell[] {consent});
        assertEquals(1, listed().size());
        now.set(Long.MIN_VALUE + 3L); // 14 ns later, past the cutoff
        assertNull(dialogs.notice());
    }
}
