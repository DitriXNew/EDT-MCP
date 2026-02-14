/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;

/**
 * Handler for copying group name to clipboard.
 * Handles Ctrl+C when a group is selected in the Navigator.
 */
public class CopyGroupHandler extends AbstractGroupHandler {
    
    @Override
    public void setEnabled(Object evaluationContext) {
        GroupNavigatorAdapter adapter = getGroupAdapterFromContext(evaluationContext);
        setBaseEnabled(adapter != null);
    }
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        GroupSelection sel = extractSelection(event);
        if (sel == null) {
            return null;
        }
        
        Group group = sel.group;
        String textToCopy = group.getName();
        
        // Copy to clipboard
        Display display = Display.getCurrent();
        if (display == null) {
            display = Display.getDefault();
        }
        
        Clipboard clipboard = new Clipboard(display);
        try {
            TextTransfer textTransfer = TextTransfer.getInstance();
            clipboard.setContents(
                new Object[] { textToCopy },
                new Transfer[] { textTransfer }
            );
        } finally {
            clipboard.dispose();
        }
        
        return null;
    }
}
