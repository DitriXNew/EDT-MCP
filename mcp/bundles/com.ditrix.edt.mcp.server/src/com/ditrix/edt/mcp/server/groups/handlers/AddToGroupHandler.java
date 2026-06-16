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
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Handler for the "Add to Group..." command.
 * Shows a dialog to select target group and adds selected objects to it.
 * Only enabled for top-level metadata objects (with FQN like Type.Name).
 */
public class AddToGroupHandler extends AbstractHandler {

    private static final String ADD_TO_GROUP = "Add to Group";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Check if selection contains at least one top-level object
        Object selection = org.eclipse.ui.handlers.HandlerUtil.getVariable(evaluationContext, "selection");
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            setBaseEnabled(false);
            return;
        }
        
        // Check if any selected object is a top-level object
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                String fqn = TagUtils.extractFqn(eObject);
                if (fqn != null) {
                    String[] parts = fqn.split("\\.");
                    if (parts.length == 2) {
                        // Found at least one top-level object
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

        // Collect selected top-level objects and determine their type
        Selected selected = collectSelectedTopLevelObjects(structuredSelection);

        if (selected.objects.isEmpty() || selected.project == null || selected.objectType == null) {
            MessageDialog.openWarning(shell, ADD_TO_GROUP,
                "Please select one or more top-level metadata objects to add to a group.");
            return null;
        }

        // Get groups filtered by object type
        IGroupService service = Activator.getGroupServiceStatic();
        List<Group> matchingGroups = findMatchingGroups(service, selected.project, selected.objectType);

        if (matchingGroups.isEmpty()) {
            MessageDialog.openInformation(shell, ADD_TO_GROUP,
                "No groups exist for " + selected.objectType + ".\n" +
                "Create a group first using 'New Group...' on the " + selected.objectType + " folder.");
            return null;
        }

        Group targetGroup = chooseTargetGroup(shell, matchingGroups, selected.objectType);
        if (targetGroup == null) {
            return null;
        }

        // Add all selected objects to the group
        int successCount = 0;
        int failCount = 0;

        for (EObject eObject : selected.objects) {
            String fqn = TagUtils.extractFqn(eObject);
            if (fqn != null) {
                try {
                    boolean added = service.addObjectToGroup(selected.project, fqn, targetGroup.getFullPath());
                    if (added) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    Activator.logError("Failed to add " + fqn + " to group", e);
                    failCount++;
                }
            }
        }

        showResult(shell, targetGroup, successCount, failCount);

        return null;
    }

    /**
     * Parses the structured selection into the top-level metadata objects (FQN like
     * {@code Type.Name}), the owning project, and the common object type. Pure, no
     * side effects.
     */
    private static Selected collectSelectedTopLevelObjects(IStructuredSelection structuredSelection) {
        List<EObject> selectedObjects = new ArrayList<>();
        IProject project = null;
        String objectType = null; // e.g., "Catalog", "CommonModule"

        for (Object element : structuredSelection.toList()) {
            String type = topLevelTypeOf(element);
            if (type == null) {
                continue;
            }
            // Top-level object
            EObject eObject = (EObject) element;
            selectedObjects.add(eObject);
            if (project == null) {
                project = TagUtils.extractProject(eObject);
            }
            if (objectType == null) {
                objectType = type; // e.g., "Catalog"
            }
        }

        return new Selected(selectedObjects, project, objectType);
    }

    /**
     * Returns the top-level metadata type token (the {@code Type} part of a {@code Type.Name}
     * FQN, e.g. {@code "Catalog"}) for a selection element, or {@code null} when the element is
     * not an {@link EObject}, carries no FQN, or its FQN is not a two-part top-level name. Pure,
     * no side effects.
     */
    private static String topLevelTypeOf(Object element) {
        if (!(element instanceof EObject eObject)) {
            return null;
        }
        String fqn = TagUtils.extractFqn(eObject);
        if (fqn == null) {
            return null;
        }
        String[] parts = fqn.split("\\.");
        return parts.length == 2 ? parts[0] : null;
    }

    /** Read-only query: the groups whose path matches the given object type. */
    private static List<Group> findMatchingGroups(IGroupService service, IProject project, String objectType) {
        final String filterPath = objectType;
        return service.getAllGroups(project).stream()
            .filter(g -> filterPath.equals(g.getPath()))
            .toList();
    }

    /**
     * Shows the group-selection dialog and returns the chosen group, or {@code null}
     * if the dialog was cancelled or yielded no group. Does not mutate group state.
     */
    private static Group chooseTargetGroup(Shell shell, List<Group> matchingGroups, String objectType) {
        // Show group selection dialog with group names only
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Group group) {
                    return group.getName();
                }
                return super.getText(element);
            }
        });
        dialog.setTitle(ADD_TO_GROUP);
        dialog.setMessage("Select target group for " + objectType + ":");
        dialog.setElements(matchingGroups.toArray());
        dialog.setMultipleSelection(false);

        if (dialog.open() != Window.OK) {
            return null;
        }

        Object[] result = dialog.getResult();
        if (result == null || result.length == 0 || !(result[0] instanceof Group targetGroup)) {
            return null;
        }
        return targetGroup;
    }

    /** Reports the outcome of the add operation to the user via a message dialog. */
    private static void showResult(Shell shell, Group targetGroup, int successCount, int failCount) {
        if (successCount > 0) {
            MessageDialog.openInformation(shell, ADD_TO_GROUP,
                "Added " + successCount + " object(s) to group '" + targetGroup.getName() + "'.");
        } else if (failCount > 0) {
            MessageDialog.openWarning(shell, ADD_TO_GROUP,
                "Failed to add objects. They may already be in the group.");
        }
    }

    /** Holder for the parsed selection: top-level objects, owning project and common type. */
    private static final class Selected {
        final List<EObject> objects;
        final IProject project;
        final String objectType;

        Selected(List<EObject> objects, IProject project, String objectType) {
            this.objects = objects;
            this.project = project;
            this.objectType = objectType;
        }
    }
}
