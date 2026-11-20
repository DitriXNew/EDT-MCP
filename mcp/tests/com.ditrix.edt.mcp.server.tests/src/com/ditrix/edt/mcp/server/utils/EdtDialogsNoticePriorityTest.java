/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Issue #997: with an answerable dialog and a human-only one (a consent prompt) open at once, the
 * notice names the human-only one - the agent can press the other itself after a list, but only the
 * user can answer the consent prompt. The listing order is by random dialog id, so both orders.
 */
public class EdtDialogsNoticePriorityTest
{
    private static JsonObject dialog(String title, boolean answerable)
    {
        JsonObject dialog = new JsonObject();
        dialog.addProperty("title", title); //$NON-NLS-1$
        dialog.addProperty("answerable", answerable); //$NON-NLS-1$
        return dialog;
    }

    @Test
    public void aHumanOnlyDialogIsNamedWhicheverOrderTheyAreListedIn()
    {
        JsonObject update = dialog("Application update", true); //$NON-NLS-1$
        JsonObject consent = dialog("Confirm destructive operation", false); //$NON-NLS-1$
        String expected = "EDT dialog open: \"Confirm destructive operation\" - only the user can answer it in EDT; " //$NON-NLS-1$
            + "ask them. Details: edt_dialog action=list."; //$NON-NLS-1$
        assertEquals(expected, EdtDialogs.noticeFor(List.of(update, consent)));
        assertEquals(expected, EdtDialogs.noticeFor(List.of(consent, update)));
    }

    @Test
    public void withOnlyAnswerableDialogsTheFirstIsNamedAsBefore()
    {
        assertEquals("EDT dialog open: \"Application update\". Call edt_dialog with action=list.", //$NON-NLS-1$
            EdtDialogs.noticeFor(List.of(dialog("Application update", true), dialog("Other", true)))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
