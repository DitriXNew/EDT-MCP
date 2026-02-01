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
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * Refactoring contributor that updates tag assignments when metadata objects are renamed.
 * Listens to EDT's refactoring framework and updates FQNs in YAML storage accordingly.
 */
public class TagRenameRefactoringContributor implements IRenameRefactoringContributor {
    
    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object, 
            RefactoringSettings settings, RefactoringStatus status) {
        Activator.logInfo("[TagRename] createParticipatingOperation called for: " + 
            (object != null ? object.eClass().getName() : "null"));
        // We don't need to participate in the BM transaction itself
        return null;
    }
    
    @Override
    public RefactoringOperationDescriptor createPreReferenceUpdateParticipatingOperation(
            IBmObject object, RefactoringSettings settings, RefactoringStatus status) {
        try {
            Activator.logInfo("[TagRename] createPreReferenceUpdateParticipatingOperation called for: " + 
                (object != null ? object.bmGetFqn() : "null"));
        } catch (Exception e) {
            Activator.logInfo("[TagRename] createPreReferenceUpdateParticipatingOperation called (FQN unavailable)");
        }
        return null;
    }
    
    @Override
    public Collection<Change> createNativePreChanges(EObject object, String newName, 
            RefactoringSettings settings, RefactoringStatus status) {
        Activator.logInfo("[TagRename] createNativePreChanges called for newName: " + newName);
        // We don't need pre-changes
        return null;
    }
    
    @Override
    public Collection<Change> createNativePostChanges(EObject object, String newName, 
            RefactoringSettings settings, RefactoringStatus status) {
        Activator.logInfo("[TagRename] createNativePostChanges called: object=" + 
            (object != null ? object.eClass().getName() : "null") + ", newName=" + newName);
        
        // Check if this object has any tags assigned
        if (object == null || !(object instanceof IBmObject)) {
            Activator.logInfo("[TagRename] Object is null or not IBmObject, skipping");
            return null;
        }
        
        IBmObject bmObject = (IBmObject) object;
        String oldFqn = extractFqn(bmObject);
        Activator.logInfo("[TagRename] Old FQN: " + oldFqn);
        
        if (oldFqn == null || oldFqn.isEmpty()) {
            Activator.logInfo("[TagRename] Old FQN is null or empty, skipping");
            return null;
        }
        
        // Get the project for this object
        IProject project = getProject(object);
        if (project == null) {
            Activator.logInfo("[TagRename] Project is null, skipping");
            return null;
        }
        Activator.logInfo("[TagRename] Project: " + project.getName());
        
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);
        
        // Check if this object has any tags assigned
        Set<String> tags = storage.getTagNames(oldFqn);
        Activator.logInfo("[TagRename] Tags for object: " + tags);
        
        if (tags == null || tags.isEmpty()) {
            Activator.logInfo("[TagRename] No tags for object, skipping");
            return null;
        }
        
        // Build the new FQN based on the new name
        String newFqn = buildNewFqn(oldFqn, newName);
        Activator.logInfo("[TagRename] New FQN: " + newFqn);
        
        if (newFqn == null || newFqn.equals(oldFqn)) {
            Activator.logInfo("[TagRename] New FQN is null or same as old, skipping");
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
     * Builds a new FQN by replacing the last name component with the new name.
     */
    private String buildNewFqn(String oldFqn, String newName) {
        if (oldFqn == null || newName == null) {
            return null;
        }
        
        // FQN format: Type.Name or Type.Name.Type.Name...
        // We need to replace the last Name with newName
        int lastDot = oldFqn.lastIndexOf('.');
        if (lastDot > 0) {
            return oldFqn.substring(0, lastDot + 1) + newName;
        }
        
        return newName;
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
                    Activator.logInfo("[TagRename] Got project via IResourceLookup: " + project.getName());
                    return project;
                }
            }
            
            // Fallback: Try to get the project from eResource
            org.eclipse.emf.ecore.resource.Resource resource = object.eResource();
            Activator.logInfo("[TagRename] eResource: " + resource);
            
            if (resource != null && resource.getURI() != null) {
                Activator.logInfo("[TagRename] Resource URI: " + resource.getURI());
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
            
            // Fallback: try to get project from BM object
            if (object instanceof IBmObject) {
                IBmObject bmObject = (IBmObject) object;
                Activator.logInfo("[TagRename] Trying BM fallback for object ID: " + bmObject.bmGetId());
                
                // Try to get FQN and find project through it
                String fqn = null;
                try {
                    fqn = bmObject.bmGetFqn();
                } catch (Exception e) {
                    // Not a top object, use manual extraction
                    fqn = extractFqnManually(object);
                }
                
                if (fqn != null) {
                    // Search all open projects for this object
                    for (IProject project : org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                            .getRoot().getProjects()) {
                        if (project.isOpen() && project.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature")) {
                            // Check if this project has the object
                            TagService tagService = TagService.getInstance();
                            // Just return first EDT project for now
                            Activator.logInfo("[TagRename] Found EDT project: " + project.getName());
                            return project;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("[TagRename] Failed to get project for object", e);
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
