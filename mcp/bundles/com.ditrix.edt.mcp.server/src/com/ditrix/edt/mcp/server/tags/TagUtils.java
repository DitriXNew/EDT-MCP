/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.wiring.ServiceAccess;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class providing common operations for the tags module.
 * Consolidates duplicate code for FQN extraction, project resolution, and reflection.
 * 
 * <p>This class uses reflection to access metadata object properties because:
 * <ul>
 *   <li>Avoids compile-time dependencies on internal EDT metadata model classes</li>
 *   <li>Provides better plugin compatibility across EDT versions</li>
 *   <li>Handles various object types uniformly</li>
 * </ul>
 * </p>
 */
public final class TagUtils {
    
    private TagUtils() {
        // Utility class
    }
    
    // ===== FQN Extraction =====
    
    /**
     * Extracts the fully qualified name (FQN) from a metadata object.
     * 
     * <p>The FQN is built by traversing the containment hierarchy upwards,
     * combining type names and object names. For example:
     * <ul>
     *   <li>"Document.SalesOrder" - top-level document</li>
     *   <li>"Catalog.Products.Attribute.Name" - nested attribute</li>
     *   <li>"Subsystem.Sales.Subsystem.Orders" - nested subsystem</li>
     * </ul>
     * </p>
     * 
     * <p>Special handling is applied for Subsystems to use getParentSubsystem()
     * instead of eContainer() for proper nested subsystem FQN building.</p>
     * 
     * @param mdObject the metadata EObject (can be null)
     * @return the FQN string or null if extraction fails
     */
    public static String extractFqn(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            StringBuilder fqnBuilder = new StringBuilder();
            EObject current = mdObject;
            
            while (current != null) {
                // Stop at Configuration root or internal model objects
                if (current instanceof Configuration 
                        || isInternalModelObject(current)) {
                    break;
                }
                
                String typeName = current.eClass().getName();
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
            Activator.logDebug("Failed to extract FQN from " + mdObject.eClass().getName());
            return null;
        }
    }
    
    /**
     * Checks if the object is an internal model object (not a real metadata object).
     * 
     * @param eObject the object to check
     * @return true if internal model object
     */
    private static boolean isInternalModelObject(EObject eObject) {
        String typeName = eObject.eClass().getName();
        return typeName.startsWith("Md");
    }
    
    /**
     * Extracts the FQN from a BM object, with fallback to manual extraction.
     * 
     * @param bmObject the BM object
     * @return the FQN string or null
     */
    public static String extractFqn(IBmObject bmObject) {
        if (bmObject == null) {
            return null;
        }
        
        try {
            String fqn = bmObject.bmGetFqn();
            if (fqn != null && !fqn.isEmpty()) {
                return fqn;
            }
        } catch (Exception e) {
            // Fallback to manual extraction
        }
        
        return extractFqn((EObject) bmObject);
    }
    
    /**
     * Gets the parent object for FQN building.
     * 
     * <p>Special handling for Subsystem: uses getParentSubsystem() to correctly
     * handle nested subsystems, which are not contained via eContainer().</p>
     * 
     * @param eObject the current object
     * @return the parent object to use for FQN building, or null if at root
     */
    public static EObject getParentForFqn(EObject eObject) {
        if (eObject == null) {
            return null;
        }
        
        // Special handling for Subsystem - use getParentSubsystem() for nested subsystems
        if (eObject instanceof Subsystem subsystem) {
            Subsystem parentSubsystem = subsystem.getParentSubsystem();
            if (parentSubsystem != null) {
                return parentSubsystem;
            }
            // If no parent subsystem, fall through to eContainer
        }
        
        // Default: use eContainer for other types
        return eObject.eContainer();
    }
    
    /**
     * Gets the name of a metadata object.
     * 
     * <p>For MdObject instances, uses the getName() method directly.
     * Falls back to reflection for other types.</p>
     * 
     * @param eObject the object
     * @return the name string or null if not found
     */
    public static String getObjectName(EObject eObject) {
        if (eObject == null) {
            return null;
        }
        
        // Use MdObject interface directly if available
        if (eObject instanceof MdObject mdObject) {
            return mdObject.getName();
        }
        
        // Fallback to reflection for other types
        try {
            for (Method m : eObject.getClass().getMethods()) {
                if ("getName".equals(m.getName()) 
                        && m.getParameterCount() == 0) {
                    Object name = m.invoke(eObject);
                    return name != null ? name.toString() : null;
                }
            }
        } catch (Exception e) {
            Activator.logDebug("Failed to get name from " + eObject.eClass().getName());
        }
        return null;
    }
    
    // ===== Project Extraction =====
    
    /**
     * Extracts the project from an EObject.
     * 
     * <p>Tries multiple strategies:
     * <ol>
     *   <li>IResourceLookup service (preferred EDT way)</li>
     *   <li>Platform resource URI parsing</li>
     *   <li>BM URI scheme parsing (bm://projectName/...)</li>
     *   <li>V8ProjectManager service</li>
     * </ol>
     * </p>
     * 
     * @param eObject the EObject
     * @return the project or null if not found
     */
    public static IProject extractProject(EObject eObject) {
        if (eObject == null) {
            return null;
        }
        
        // Try 1: Use IResourceLookup service - the proper EDT way
        try {
            IResourceLookup resourceLookup = ServiceAccess.get(IResourceLookup.class);
            if (resourceLookup != null) {
                IProject project = resourceLookup.getProject(eObject);
                if (project != null && project.exists()) {
                    return project;
                }
            }
        } catch (Exception e) {
            Activator.logDebug("IResourceLookup failed, trying other methods");
        }
        
        // Try 2: Parse from resource URI
        IProject project = extractProjectFromUri(eObject);
        if (project != null) {
            return project;
        }
        
        // Try 3: Use V8ProjectManager
        try {
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            if (v8ProjectManager != null) {
                IV8Project v8Project = v8ProjectManager.getProject(eObject);
                if (v8Project != null) {
                    return v8Project.getProject();
                }
            }
        } catch (Exception e) {
            Activator.logDebug("V8ProjectManager failed for project lookup");
        }
        
        return null;
    }
    
    /**
     * Extracts the project from an EObject's resource URI.
     * 
     * @param eObject the EObject
     * @return the project or null
     */
    private static IProject extractProjectFromUri(EObject eObject) {
        if (eObject.eResource() == null || eObject.eResource().getURI() == null) {
            return null;
        }
        
        URI uri = eObject.eResource().getURI();
        
        // Try platform string first
        String uriPath = uri.toPlatformString(true);
        if (uriPath != null && uriPath.length() > 1) {
            String projectName = uriPath.split("/")[1];
            IProject project = ResourcesPlugin.getWorkspace()
                .getRoot().getProject(projectName);
            if (project != null && project.exists()) {
                return project;
            }
        }
        
        // Try BM URI scheme
        String uriString = uri.toString();
        if (uriString.startsWith(TagConstants.BM_URI_SCHEME)) {
            // Format: bm://projectName/...
            String[] parts = uriString.substring(TagConstants.BM_URI_SCHEME.length()).split("/");
            if (parts.length > 0) {
                IProject project = ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(parts[0]);
                if (project != null && project.exists()) {
                    return project;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extracts the project from any element type.
     * Handles IProject, EObject, and wrapper objects.
     * 
     * @param element the element (IProject, EObject, or wrapper)
     * @return the project or null
     */
    public static IProject extractProjectFromElement(Object element) {
        if (element == null) {
            return null;
        }
        
        // Direct project
        if (element instanceof IProject project) {
            return project;
        }
        
        // EObject
        if (element instanceof EObject eObject) {
            return extractProject(eObject);
        }
        
        // Try to unwrap to EObject
        EObject unwrapped = unwrapToEObject(element);
        if (unwrapped != null) {
            return extractProject(unwrapped);
        }
        
        // Try getProject() method via reflection
        try {
            Method getProject = element.getClass().getMethod(TagConstants.METHOD_GET_PROJECT);
            Object result = getProject.invoke(element);
            if (result instanceof IProject project) {
                return project;
            }
        } catch (Exception e) {
            // Not found, ignore
        }
        
        // Try getModel() method
        try {
            Method getModel = element.getClass().getMethod(TagConstants.METHOD_GET_MODEL);
            Object model = getModel.invoke(element);
            if (model instanceof EObject eObject) {
                return extractProject(eObject);
            }
        } catch (Exception e) {
            // Not found, ignore
        }
        
        return null;
    }
    
    // ===== Unwrapping =====
    
    /**
     * Attempts to unwrap a wrapper object to find an EObject inside.
     * 
     * <p>This handles various navigator wrapper classes by trying common
     * getter methods: getTarget(), getData(), getElement(), getValue(), getObject(), getModel().</p>
     * 
     * @param element the wrapper element
     * @return the unwrapped EObject or null
     */
    public static EObject unwrapToEObject(Object element) {
        if (element == null) {
            return null;
        }
        
        if (element instanceof EObject eObject) {
            return eObject;
        }
        
        String[] methodNames = {"getTarget", "getData", "getElement", "getValue", "getObject", "getModel"};
        
        for (String methodName : methodNames) {
            try {
                Method method = element.getClass().getMethod(methodName);
                Object result = method.invoke(element);
                if (result instanceof EObject eObj) {
                    return eObj;
                }
                // Recurse one level
                if (result != null && !(result instanceof EObject)) {
                    EObject nested = unwrapToEObject(result);
                    if (nested != null) {
                        return nested;
                    }
                }
            } catch (NoSuchMethodException e) {
                // Method not found, try next
            } catch (Exception e) {
                // Invocation failed, try next
            }
        }
        
        return null;
    }
    
    /**
     * Extracts a metadata object from a selection element.
     * Objects in EDT navigator tree are directly EObjects (MdObject instances).
     * 
     * @param element the selection element
     * @return the EObject or null
     */
    public static EObject extractMdObject(Object element) {
        if (element == null) {
            return null;
        }
        
        // Objects in EDT navigator are directly EObjects
        if (element instanceof EObject eobj) {
            return eobj;
        }
        
        // Try Platform adapter as fallback
        try {
            Object adapted = org.eclipse.core.runtime.Platform.getAdapterManager()
                .getAdapter(element, EObject.class);
            if (adapted instanceof EObject eobj) {
                return eobj;
            }
        } catch (Exception e) {
            Activator.logDebug("Failed to adapt element to EObject");
        }
        
        return null;
    }
    
    // ===== FQN Manipulation =====
    
    /**
     * Builds a new FQN by replacing the last name component with a new name.
     * Used during rename refactoring.
     * 
     * @param oldFqn the old FQN (e.g., "Document.OldName")
     * @param newName the new name (e.g., "NewName")
     * @return the new FQN (e.g., "Document.NewName") or null if invalid input
     */
    public static String buildNewFqn(String oldFqn, String newName) {
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
     * Checks if an FQN starts with the given type prefix.
     * 
     * @param fqn the FQN to check
     * @param type the type prefix (e.g., "Document", "Catalog")
     * @return true if FQN starts with type + "."
     */
    public static boolean isFqnOfType(String fqn, String type) {
        if (fqn == null || type == null) {
            return false;
        }
        return fqn.startsWith(type + ".");
    }
    
    /**
     * Gets the top-level type from an FQN.
     * 
     * @param fqn the FQN (e.g., "Document.SalesOrder.Attribute.Name")
     * @return the type (e.g., "Document") or null
     */
    public static String getTypeFromFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }
        int dot = fqn.indexOf('.');
        return dot > 0 ? fqn.substring(0, dot) : null;
    }
    
    /**
     * Gets the top-level object name from an FQN.
     * 
     * @param fqn the FQN (e.g., "Document.SalesOrder.Attribute.Name")
     * @return the object name (e.g., "SalesOrder") or null
     */
    public static String getTopNameFromFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }
        String[] parts = fqn.split("\\.");
        return parts.length >= 2 ? parts[1] : null;
    }
}
