/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.refactoring;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.refactoring.core.IDeleteRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringPostProcessor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Refactoring contributor that removes objects from groups when they are deleted.
 * Listens to EDT's refactoring framework and removes FQNs from YAML storage accordingly.
 */
public class GroupDeleteRefactoringContributor implements IDeleteRefactoringContributor {
    
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object, 
            RefactoringSettings settings, RefactoringStatus status) {
        
        if (object == null || !(object instanceof IBmObject bmObject)) {
            return null;
        }
        
        String fqn = TagUtils.extractFqn(bmObject);
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }
        
        // Get the project for this object
        IProject project = TagUtils.extractProject(object);
        if (project == null) {
            return null;
        }
        
        IGroupService groupService = Activator.getGroupServiceStatic();
        
        // Check if this object is in any group
        Group group = groupService.findGroupForObject(project, fqn);
        
        if (group == null) {
            // Object is not in any group, nothing to do
            return null;
        }
        
        // Create an operation to remove the object from the group
        return new RefactoringOperationDescriptor(
            new GroupObjectRemoveOperation(project, fqn, group.getFullPath()));
    }
    
    @Override
    public RefactoringOperationDescriptor createCleanReferenceOperation(IBmObject targetObject, 
            IBmObject referencingObject, EStructuralFeature feature, 
            RefactoringSettings settings, RefactoringStatus status) {
        // We don't need to clean references
        return null;
    }
    
    /**
     * Operation that removes an object from a group after it is deleted.
     * <p>
     * Mirrors {@code TagDeleteOperation}: the YAML group-storage mutation must run in
     * {@link #postProcess()} (after the BM transaction commits), NOT in
     * {@link #perform()} inside the transaction. Doing it inside the transaction means
     * a rolled-back delete would still strip the object from the group YAML, diverging
     * the group storage from the model. (audit A11)
     */
    private static class GroupObjectRemoveOperation implements IRefactoringOperation, IRefactoringPostProcessor {

        private final IProject project;
        private final String fqn;
        private final String groupPath;

        public GroupObjectRemoveOperation(IProject project, String fqn, String groupPath) {
            this.project = project;
            this.fqn = fqn;
            this.groupPath = groupPath;
        }

        @Override
        public void perform() {
            // Do nothing during the BM transaction - YAML changes go in postProcess().
        }

        @Override
        public void postProcess() {
            IGroupService groupService = Activator.getGroupServiceStatic();

            // Remove the object from the group
            boolean removed = groupService.removeObjectFromGroup(project, fqn);

            if (removed) {
                Activator.logInfo("Removed deleted object from group " + groupPath + ": " + fqn);
            }
        }
    }
}
