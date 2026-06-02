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
import com.ditrix.edt.mcp.server.refactoring.AbstractObjectDeleteRefactoringContributor;

/**
 * Refactoring contributor that removes objects from groups when they are deleted.
 * Listens to EDT's refactoring framework and removes FQNs from YAML storage accordingly.
 *
 * <p>Thin subclass of {@link AbstractObjectDeleteRefactoringContributor}: the shared base
 * supplies the LTK participant wiring and the {@code perform()}/{@code postProcess()}
 * transaction split. The YAML group-storage mutation runs in {@code postProcess()} (after
 * the BM transaction commits), NOT in {@code perform()} inside the transaction; otherwise a
 * rolled-back delete would still strip the object from the group YAML, diverging the group
 * storage from the model (commit {@code 7ddc2da}, audit A11). This class only binds the
 * group-storage specifics.</p>
 */
public class GroupDeleteRefactoringContributor extends AbstractObjectDeleteRefactoringContributor {

    @Override
    protected boolean hasAssociation(IProject project, String fqn) {
        IGroupService groupService = Activator.getGroupServiceStatic();

        // Check if this object is in any group
        Group group = groupService.findGroupForObject(project, fqn);
        return group != null;
    }

    @Override
    protected boolean performRemove(IProject project, String fqn) {
        return Activator.getGroupServiceStatic().removeObjectFromGroup(project, fqn);
    }

    @Override
    protected String removeLogMessage(IProject project, String fqn) {
        // Resolve the group path while the membership still exists (this runs at
        // descriptor-creation time, before the removal in postProcess()).
        IGroupService groupService = Activator.getGroupServiceStatic();
        Group group = groupService.findGroupForObject(project, fqn);
        String groupPath = group != null ? group.getFullPath() : null;
        return "Removed deleted object from group " + groupPath + ": " + fqn;
    }
}
