package com.ditrix.edt.mcp.server.groups.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import com.ditrix.edt.mcp.server.groups.ui.GroupVisibilityManager;

/**
 * Handler for toggling Navigator groups visibility.
 */
public class HideGroupsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        GroupVisibilityManager.getInstance().toggleGroupsHidden();
        return null;
    }
}