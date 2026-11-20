/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Issue #997: the consent dialog tells the human that it closes by itself, and after how long. */
public class DestructiveConsentDialogWaitTest
{
    @Test
    public void theDialogNamesItsOwnWait()
    {
        assertEquals("Closes by itself after 45 s without an answer; the tool call is then refused.", //$NON-NLS-1$
            DestructiveConsentDialog.waitText(45));
    }
}
