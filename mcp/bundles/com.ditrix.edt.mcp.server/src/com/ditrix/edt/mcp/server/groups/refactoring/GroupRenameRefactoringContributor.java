/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.refactoring;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.refactoring.AbstractObjectRenameRefactoringContributor;

/**
 * Refactoring contributor that updates group assignments when metadata objects are renamed.
 * Listens to EDT's refactoring framework and updates FQNs in YAML storage accordingly.
 *
 * <p>Thin subclass of {@link AbstractObjectRenameRefactoringContributor}: the shared base
 * supplies the LTK participant wiring and the post-refactoring FQN-rewrite change; this
 * class only binds the group-storage specifics.</p>
 */
public class GroupRenameRefactoringContributor extends AbstractObjectRenameRefactoringContributor {

    @Override
    protected boolean hasAssociation(IProject project, String fqn) {
        IGroupService groupService = Activator.getGroupServiceStatic();

        // The optional group service is absent in headless mode and during shutdown.
        if (groupService == null) {
            return false;
        }

        // Check if this object is in any group
        Group group = groupService.findGroupForObject(project, fqn);
        return group != null;
    }

    @Override
    protected boolean performRename(IProject project, String oldFqn, String newFqn) {
        IGroupService groupService = Activator.getGroupServiceStatic();
        return groupService != null && groupService.renameObject(project, oldFqn, newFqn);
    }

    @Override
    protected String describeRename(String oldFqn, String newFqn) {
        return "Update group assignment: " + oldFqn + " -> " + newFqn;
    }

    @Override
    protected String renameLogMessage(String oldFqn, String newFqn) {
        return "Group assignment updated: " + oldFqn + " -> " + newFqn;
    }
}
