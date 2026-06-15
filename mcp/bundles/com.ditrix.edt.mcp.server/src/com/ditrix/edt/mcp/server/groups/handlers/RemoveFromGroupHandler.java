/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Handler for the "Remove from Group" command.
 * Removes selected objects from their groups, returning them to the original location.
 * Works on EObjects that are currently in a group.
 */
public class RemoveFromGroupHandler extends AbstractHandler {

    private static final String REMOVE_FROM_GROUP = "Remove from Group";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Check if selection contains objects that are in groups
        Object selection = HandlerUtil.getVariable(evaluationContext, "selection");
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            setBaseEnabled(false);
            return;
        }
        
        IGroupService service = Activator.getGroupServiceStatic();
        
        // Enable if any selected element is an EObject in a group
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                IProject project = TagUtils.extractProject(eObject);
                String fqn = TagUtils.extractFqn(eObject);
                if (project != null && fqn != null) {
                    Group group = service.findGroupForObject(project, fqn);
                    if (group != null) {
                        setBaseEnabled(true);
                        return;
                    }
                }
            }
        }
        
        setBaseEnabled(false);
    }
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            return null;
        }
        
        IGroupService service = Activator.getGroupServiceStatic();

        // Collect selected objects that are in groups
        List<ObjectInGroup> objectsToRemove = new ArrayList<>();
        IProject project = collectObjectsInGroups(structuredSelection, service, objectsToRemove);

        if (objectsToRemove.isEmpty() || project == null) {
            return null;
        }
        
        // Confirm removal
        String message = objectsToRemove.size() == 1
            ? "Remove this object from its group?"
            : "Remove " + objectsToRemove.size() + " objects from their groups?";
        
        if (!MessageDialog.openConfirm(shell, REMOVE_FROM_GROUP, message)) {
            return null;
        }
        
        // Remove objects from their groups
        int successCount = 0;
        int failCount = 0;
        
        for (ObjectInGroup obj : objectsToRemove) {
            try {
                boolean removed = service.removeObjectFromGroup(obj.project, obj.fqn);
                if (removed) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                Activator.logError("Failed to remove " + obj.fqn + " from group", e);
                failCount++;
            }
        }
        
        showRemovalResult(shell, successCount, failCount);

        return null;
    }

    /**
     * Collects, from the current selection, every {@link EObject} that resolves to a project +
     * FQN and is currently in a group; each match is appended to {@code objectsToRemove}. Returns
     * the project of the first such match (the original location to report against), or
     * {@code null} when no selected object is in a group.
     */
    private IProject collectObjectsInGroups(IStructuredSelection structuredSelection,
            IGroupService service, List<ObjectInGroup> objectsToRemove) {
        IProject project = null;
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                IProject objProject = TagUtils.extractProject(eObject);
                String fqn = TagUtils.extractFqn(eObject);
                if (objProject != null && fqn != null) {
                    Group group = service.findGroupForObject(objProject, fqn);
                    if (group != null) {
                        objectsToRemove.add(new ObjectInGroup(objProject, fqn));
                        if (project == null) {
                            project = objProject;
                        }
                    }
                }
            }
        }
        return project;
    }

    /**
     * Shows the post-removal feedback dialog: an information dialog on a fully successful run, a
     * warning dialog when at least one removal failed, and nothing when nothing succeeded and
     * nothing failed (mirrors the original inline behaviour exactly).
     */
    private void showRemovalResult(Shell shell, int successCount, int failCount) {
        if (successCount > 0 && failCount == 0) {
            MessageDialog.openInformation(shell, REMOVE_FROM_GROUP,
                "Removed " + successCount + " object(s) from their groups.");
        } else if (failCount > 0) {
            MessageDialog.openWarning(shell, REMOVE_FROM_GROUP,
                "Removed " + successCount + " object(s), failed to remove " + failCount + " object(s).");
        }
    }
    
    /**
     * Helper record to hold project and FQN for removal.
     */
    private record ObjectInGroup(IProject project, String fqn) {}
}
