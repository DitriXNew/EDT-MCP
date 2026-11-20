/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Issue #997: while a consent dialog is open, the notice every other tool response carries must
 * say that only the user can answer it - the agent cannot press it with {@code edt_dialog}, so the
 * useful next step is to ask the user. SWT widgets are mocked, as in {@code EdtDialogToolTest}.
 */
public class EdtDialogsConsentNoticeTest
{
    private Display display;
    private Shell shell;
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
        shell = mock(Shell.class);
        when(shell.isVisible()).thenReturn(true);
        when(shell.getStyle()).thenReturn(SWT.APPLICATION_MODAL);
        when(shell.getText()).thenReturn("Confirm destructive operation"); //$NON-NLS-1$
        when(display.getShells()).thenReturn(new Shell[] {shell});
        Map<String, Object> data = new HashMap<>();
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(shell).setData(anyString(), any());
        when(shell.getData(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        Label tool = mock(Label.class);
        when(tool.getText()).thenReturn("Requested by MCP tool: delete_project"); //$NON-NLS-1$
        Button allow = mock(Button.class);
        when(allow.getStyle()).thenReturn(SWT.PUSH);
        when(allow.getText()).thenReturn("Allow"); //$NON-NLS-1$
        when(allow.isEnabled()).thenReturn(true);
        when(allow.isVisible()).thenReturn(true);
        when(shell.getChildren()).thenReturn(new Control[] {tool, allow});
        dialogs = new EdtDialogs(() -> display);
    }

    private JsonObject listed()
    {
        JsonObject response = JsonParser.parseString(dialogs.list()).getAsJsonObject();
        assertTrue(response.toString(), response.get("success").getAsBoolean()); //$NON-NLS-1$
        return response.getAsJsonArray("dialogs").get(0).getAsJsonObject(); //$NON-NLS-1$
    }

    @Test
    public void theNoticeOfAHumanOnlyDialogSaysOnlyTheUserCanAnswerIt()
    {
        EdtDialogs.protect(shell);
        listed();
        String notice = dialogs.notice();
        assertEquals("EDT dialog open: \"Confirm destructive operation\" - only the user can answer it in EDT; " //$NON-NLS-1$
            + "ask them. Details: edt_dialog action=list.", notice); //$NON-NLS-1$
    }

    @Test
    public void theConsentDialogIsListedAsHumanOnlyAndNamesItsTool()
    {
        EdtDialogs.protect(shell);
        JsonObject dialog = listed();
        assertFalse(dialog.toString(), dialog.get("answerable").getAsBoolean()); //$NON-NLS-1$
        assertEquals("ask the user to answer it in EDT", dialog.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dialog.toString(),
            dialog.get("message").getAsString().contains("Requested by MCP tool: delete_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
