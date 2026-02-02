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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;

/**
 * Base class for group-related handlers.
 * Provides common functionality for extracting group and project from selection.
 */
public abstract class AbstractGroupHandler extends AbstractHandler {
    
    /**
     * Gets the group service.
     * 
     * @return the group service
     */
    protected IGroupService getGroupService() {
        return Activator.getGroupServiceStatic();
    }
    
    /**
     * Extracts a GroupNavigatorAdapter from the current selection.
     * 
     * @param evaluationContext the evaluation context
     * @return the adapter or null
     */
    protected GroupNavigatorAdapter getGroupAdapterFromContext(Object evaluationContext) {
        Object selection = HandlerUtil.getVariable(evaluationContext, "selection");
        return extractGroupAdapter(selection);
    }
    
    /**
     * Extracts a GroupNavigatorAdapter from a selection.
     * 
     * @param selection the selection
     * @return the adapter or null
     */
    protected GroupNavigatorAdapter extractGroupAdapter(Object selection) {
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            return null;
        }
        
        Object first = structuredSelection.getFirstElement();
        if (first instanceof GroupNavigatorAdapter adapter) {
            return adapter;
        }
        
        return null;
    }
    
    /**
     * Extracts the Group from a selection.
     * 
     * @param selection the selection
     * @return the group or null
     */
    protected Group extractGroup(ISelection selection) {
        GroupNavigatorAdapter adapter = extractGroupAdapter(selection);
        return adapter != null ? adapter.getGroup() : null;
    }
    
    /**
     * Extracts the project from a selection.
     * 
     * @param selection the selection
     * @return the project or null
     */
    protected IProject extractProject(ISelection selection) {
        GroupNavigatorAdapter adapter = extractGroupAdapter(selection);
        return adapter != null ? adapter.getProject() : null;
    }
    
    /**
     * Helper class holding extracted selection data.
     */
    protected static class GroupSelection {
        public final GroupNavigatorAdapter adapter;
        public final Group group;
        public final IProject project;
        public final Shell shell;
        
        public GroupSelection(GroupNavigatorAdapter adapter, Shell shell) {
            this.adapter = adapter;
            this.group = adapter.getGroup();
            this.project = adapter.getProject();
            this.shell = shell;
        }
        
        public boolean isValid() {
            return adapter != null && group != null && project != null;
        }
    }
    
    /**
     * Extracts complete selection data from an execution event.
     * 
     * @param event the execution event
     * @return the selection data or null
     */
    protected GroupSelection extractSelection(org.eclipse.core.commands.ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        GroupNavigatorAdapter adapter = extractGroupAdapter(selection);
        
        if (adapter == null) {
            return null;
        }
        
        return new GroupSelection(adapter, shell);
    }
}
