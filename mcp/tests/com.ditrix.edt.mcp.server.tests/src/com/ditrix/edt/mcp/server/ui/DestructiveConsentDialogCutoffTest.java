/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

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
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.EdtDialogs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * PR #1002 review: the consent dialog's shell carries its prompt's cutoff, so the open-dialog list
 * drops it from the cutoff on, on both of the gate's paths, before EDT gets to close it.
 */
public class DestructiveConsentDialogCutoffTest
{
    private static Shell shell()
    {
        Shell shell = mock(Shell.class);
        when(shell.isVisible()).thenReturn(true);
        when(shell.getStyle()).thenReturn(SWT.APPLICATION_MODAL);
        when(shell.getText()).thenReturn("Confirm destructive operation"); //$NON-NLS-1$
        Map<String, Object> data = new HashMap<>();
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(shell).setData(anyString(), any());
        when(shell.getData(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        Label label = mock(Label.class);
        when(label.getText()).thenReturn("Requested by MCP tool: delete_project"); //$NON-NLS-1$
        when(shell.getChildren()).thenReturn(new Control[] {label});
        return shell;
    }

    private static int listedAfterMark(OptionalLong cutoff)
    {
        Shell shell = shell();
        DestructiveConsentDialog.mark(shell, cutoff);
        Display display = mock(Display.class);
        when(display.getThread()).thenReturn(new Thread());
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; })
            .when(display).asyncExec(any(Runnable.class));
        doAnswer(call -> null).when(display).addFilter(anyInt(), any(Listener.class));
        when(display.getShells()).thenReturn(new Shell[] {shell});
        JsonObject response = JsonParser.parseString(new EdtDialogs(() -> display).list()).getAsJsonObject();
        return response.getAsJsonArray("dialogs").size(); //$NON-NLS-1$
    }

    @Test
    public void aMarkedPromptPastItsCutoffIsNotListed()
    {
        assertEquals(0, listedAfterMark(OptionalLong.of(System.nanoTime() - 1L)));
    }

    @Test
    public void aMarkedPromptBeforeItsCutoffIsListedAsHumanOnly()
    {
        assertEquals(1, listedAfterMark(OptionalLong.of(System.nanoTime() + TimeUnit.HOURS.toNanos(1))));
    }

    @Test
    public void theMarkAlsoMakesItHumanOnly()
    {
        Shell shell = shell();
        DestructiveConsentDialog.mark(shell, OptionalLong.empty());
        assertTrue(shell.getData(EdtDialogs.class.getName() + ".humanOnly") != null); //$NON-NLS-1$
        assertFalse("no cutoff given, none recorded", //$NON-NLS-1$
            shell.getData(EdtDialogs.class.getName() + ".cutoffNanos") != null); //$NON-NLS-1$
    }
}
