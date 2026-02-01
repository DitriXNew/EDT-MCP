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
            new TagDeleteOperation(project, fqn, tags));
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
        try {
            String fqn = bmObject.bmGetFqn();
            if (fqn != null && !fqn.isEmpty()) {
                return fqn;
            }
        } catch (Exception e) {
            // Fallback to manual extraction
        }
        
        return extractFqnManually((EObject) bmObject);
    }
    
    /**
     * Manually extracts FQN from an EObject.
     */
    private String extractFqnManually(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            StringBuilder fqnBuilder = new StringBuilder();
            EObject current = mdObject;
            
            while (current != null) {
                String typeName = current.eClass().getName();
                
                if ("Configuration".equals(typeName) || typeName.startsWith("Md")) {
                    break;
                }
                
                String name = getObjectName(current);
                
                if (name != null && !name.isEmpty()) {
                    String part = typeName + "." + name;
                    if (fqnBuilder.length() > 0) {
                        fqnBuilder.insert(0, ".");
                    }
                    fqnBuilder.insert(0, part);
                }
                
                current = getParentForFqn(current);
            }
            
            return fqnBuilder.length() > 0 ? fqnBuilder.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Gets the parent object for FQN building.
     * Special handling for Subsystem to use getParentSubsystem() for nested subsystems.
     */
    private EObject getParentForFqn(EObject eObject) {
        if (eObject == null) {
            return null;
        }
        
        String typeName = eObject.eClass().getName();
        if ("Subsystem".equals(typeName)) {
            try {
                for (java.lang.reflect.Method m : eObject.getClass().getMethods()) {
                    if ("getParentSubsystem".equals(m.getName()) && m.getParameterCount() == 0) {
                        Object parent = m.invoke(eObject);
                        if (parent instanceof EObject) {
                            return (EObject) parent;
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // Fallback to eContainer
            }
        }
        
        return eObject.eContainer();
    }
    
    /**
     * Gets the name of an object using reflection.
     */
    private String getObjectName(EObject eObject) {
        try {
            for (java.lang.reflect.Method m : eObject.getClass().getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0 
                        && String.class.isAssignableFrom(m.getReturnType())) {
                    Object result = m.invoke(eObject);
                    if (result instanceof String) {
                        return (String) result;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
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
        
        public TagDeleteOperation(IProject project, String fqn, @SuppressWarnings("unused") Set<String> tagNames) {
            this.project = project;
            this.fqn = fqn;
            // tagNames is passed for potential future logging/undo support
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
