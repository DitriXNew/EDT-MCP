/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
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
