/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.refactoring;

import java.util.Collection;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ltk.core.refactoring.Change;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.refactoring.core.IRenameRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com._1c.g5.wiring.ServiceAccess;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * Refactoring contributor that updates tag assignments when metadata objects are renamed.
 * Listens to EDT's refactoring framework and updates FQNs in YAML storage accordingly.
 */
public class TagRenameRefactoringContributor implements IRenameRefactoringContributor {
    
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object, 
            RefactoringSettings settings, RefactoringStatus status) {
        // We don't need to participate in the BM transaction itself
        return null;
    }
    
    @Override
    public RefactoringOperationDescriptor createPreReferenceUpdateParticipatingOperation(
            IBmObject object, RefactoringSettings settings, RefactoringStatus status) {
        return null;
    }
    
    @Override
    public Collection<Change> createNativePreChanges(EObject object, String newName, 
            RefactoringSettings settings, RefactoringStatus status) {
        // We don't need pre-changes
        return null;
    }
    
    @Override
    public Collection<Change> createNativePostChanges(EObject object, String newName, 
            RefactoringSettings settings, RefactoringStatus status) {
        // Check if this object has any tags assigned
        if (object == null || !(object instanceof IBmObject)) {
            return null;
        }
        
        IBmObject bmObject = (IBmObject) object;
        String oldFqn = extractFqn(bmObject);
        
        if (oldFqn == null || oldFqn.isEmpty()) {
            return null;
        }
        
        // Get the project for this object
        IProject project = getProject(object);
        if (project == null) {
            return null;
        }
        
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);
        
        // Check if this object has any tags assigned
        Set<String> tags = storage.getTagNames(oldFqn);
        
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        
        // Build the new FQN based on the new name
        String newFqn = buildNewFqn(oldFqn, newName);
        
        if (newFqn == null || newFqn.equals(oldFqn)) {
            return null;
        }
        
        // Create a change to update the FQN in YAML after refactoring
        return java.util.Collections.singletonList(
            new TagFqnRenameChange(project, oldFqn, newFqn, tags));
    }
    
    @Override
    public boolean allowProhibitedReferenceEditing(IBmCrossReference reference) {
        return false;
    }
    
    /**
     * Extracts the FQN from a BM object.
     */
    private String extractFqn(IBmObject bmObject) {
        // First try the BM API
        String fqn = TagUtils.extractFqn(bmObject);
        if (fqn != null && !fqn.isEmpty()) {
            return fqn;
        }
        // Fallback to EObject-based extraction (IBmObject extends EObject)
        return TagUtils.extractFqn((EObject) bmObject);
    }
    
    /**
     * Builds a new FQN by replacing the last name component with the new name.
     */
    private String buildNewFqn(String oldFqn, String newName) {
        return TagUtils.buildNewFqn(oldFqn, newName);
    }
    
    /**
     * Gets the project for an EObject.
     */
    private IProject getProject(EObject object) {
        try {
            // Use IResourceLookup service - the proper EDT way
            IResourceLookup resourceLookup = ServiceAccess.get(IResourceLookup.class);
            if (resourceLookup != null) {
                IProject project = resourceLookup.getProject(object);
                if (project != null) {
                    return project;
                }
            }
            
            // Fallback: Try to get the project from eResource
            org.eclipse.emf.ecore.resource.Resource resource = object.eResource();
            if (resource != null && resource.getURI() != null) {
                String path = resource.getURI().toPlatformString(true);
                if (path != null && path.startsWith("/")) {
                    String projectName = path.substring(1);
                    int slashIndex = projectName.indexOf('/');
                    if (slashIndex > 0) {
                        projectName = projectName.substring(0, slashIndex);
                    }
                    return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                            .getRoot().getProject(projectName);
                }
            }
        } catch (Exception e) {
            Activator.logError("Failed to get project for object", e);
        }
        return null;
    }
    
    /**
     * Change that updates tag FQN assignments in YAML after refactoring.
     */
    private static class TagFqnRenameChange extends Change {
        
        private final IProject project;
        private final String oldFqn;
        private final String newFqn;
        private final Set<String> tagNames;
        
        public TagFqnRenameChange(IProject project, String oldFqn, String newFqn, Set<String> tagNames) {
            this.project = project;
            this.oldFqn = oldFqn;
            this.newFqn = newFqn;
            this.tagNames = tagNames;
        }
        
        @Override
        public String getName() {
            return "Update tag assignments: " + oldFqn + " -> " + newFqn;
        }
        
        @Override
        public void initializeValidationData(org.eclipse.core.runtime.IProgressMonitor pm) {
            // Nothing to validate
        }
        
        @Override
        public org.eclipse.ltk.core.refactoring.RefactoringStatus isValid(
                org.eclipse.core.runtime.IProgressMonitor pm) {
            return new org.eclipse.ltk.core.refactoring.RefactoringStatus();
        }
        
        @Override
        public Change perform(org.eclipse.core.runtime.IProgressMonitor pm) 
                throws org.eclipse.core.runtime.CoreException {
            
            TagService tagService = TagService.getInstance();
            
            // Rename the object in tag storage
            boolean success = tagService.renameObject(project, oldFqn, newFqn);
            
            if (success) {
                Activator.logInfo("Tag assignments updated: " + oldFqn + " -> " + newFqn);
            }
            
            // Return an undo change
            return new TagFqnRenameChange(project, newFqn, oldFqn, tagNames);
        }
        
        @Override
        public Object getModifiedElement() {
            return project;
        }
    }
}
