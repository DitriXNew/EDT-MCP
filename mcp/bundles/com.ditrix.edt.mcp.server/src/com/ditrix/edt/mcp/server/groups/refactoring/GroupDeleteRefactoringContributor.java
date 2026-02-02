/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.refactoring;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.refactoring.core.IBmRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IDeleteRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
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
        
        GroupService groupService = GroupService.getInstance();
        
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
     * Operation that removes an object from a group during refactoring.
     */
    private static class GroupObjectRemoveOperation implements IBmRefactoringOperation {
        
        private final IProject project;
        private final String fqn;
        private final String groupPath;
        
        @SuppressWarnings("unused")
        private IBmTransaction transaction;
        
        public GroupObjectRemoveOperation(IProject project, String fqn, String groupPath) {
            this.project = project;
            this.fqn = fqn;
            this.groupPath = groupPath;
        }
        
        @Override
        public void perform() {
            GroupService groupService = GroupService.getInstance();
            
            // Remove the object from the group
            boolean removed = groupService.removeObjectFromGroup(project, fqn);
            
            if (removed) {
                Activator.logInfo("Removed deleted object from group " + groupPath + ": " + fqn);
            }
        }
        
        @Override
        public void setActiveTransaction(IBmTransaction transaction) {
            this.transaction = transaction;
        }
    }
}
