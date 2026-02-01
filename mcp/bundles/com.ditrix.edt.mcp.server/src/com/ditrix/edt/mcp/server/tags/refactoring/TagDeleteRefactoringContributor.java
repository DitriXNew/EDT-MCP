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

import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.refactoring.core.IDeleteRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringPostProcessor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com._1c.g5.wiring.ServiceAccess;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * Refactoring contributor that removes tag assignments when metadata objects are deleted.
 * Listens to EDT's refactoring framework and removes entries from YAML storage accordingly.
 */
public class TagDeleteRefactoringContributor implements IDeleteRefactoringContributor {
    
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object, 
            RefactoringSettings settings, RefactoringStatus status) {
        
        if (object == null || !(object instanceof IBmObject)) {
            return null;
        }
        
        IBmObject bmObject = (IBmObject) object;
        String fqn = extractFqn(bmObject);
        
        if (fqn == null || fqn.isEmpty()) {
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
        Set<String> tags = storage.getTagNames(fqn);
        
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        
        // Create an operation to remove the tag assignments after deletion
        return new RefactoringOperationDescriptor(
            new TagDeleteOperation(project, fqn));
    }
    
    @Override
    public RefactoringOperationDescriptor createCleanReferenceOperation(IBmObject targetObject, 
            IBmObject referencingObject, EStructuralFeature feature, 
            RefactoringSettings settings, RefactoringStatus status) {
        // We don't need to clean references
        return null;
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
            
            // Fallback: try eResource
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
     * Operation that removes tag assignments from YAML after object deletion.
     */
    private static class TagDeleteOperation implements IRefactoringOperation, IRefactoringPostProcessor {
        
        private final IProject project;
        private final String fqn;
        
        public TagDeleteOperation(IProject project, String fqn) {
            this.project = project;
            this.fqn = fqn;
        }
        
        @Override
        public void perform() {
            // Do nothing during BM transaction - we use postProcess for YAML changes
        }
        
        @Override
        public void postProcess() {
            // Remove the object from tag storage after deletion
            TagService tagService = TagService.getInstance();
            
            boolean success = tagService.removeObject(project, fqn);
            
            if (success) {
                Activator.logInfo("Tag assignments removed for deleted object: " + fqn);
            }
        }
    }
}
