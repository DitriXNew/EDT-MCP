/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Base handler for tag-related commands.
 * Provides utility methods for extracting selected metadata objects.
 */
public abstract class AbstractTagHandler extends AbstractHandler {
    
    /**
     * Gets the selected metadata object from the current selection.
     * 
     * @param event the execution event
     * @return the selected object, or null if none
     */
    protected EObject getSelectedMdObject(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection ssel) {
            Object element = ssel.getFirstElement();
            return extractMdObject(element);
        }
        return null;
    }
    
    /**
     * Gets the project for the selected element.
     * 
     * @param event the execution event
     * @return the project, or null if not found
     */
    protected IProject getSelectedProject(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection ssel) {
            Object element = ssel.getFirstElement();
            return extractProject(element);
        }
        return null;
    }
    
    /**
     * Extracts the full FQN from a metadata object, including parent hierarchy.
     * For nested objects like attributes, returns full path like
     * "Document.SalesOrder.TabularSection.Products.Attribute.Quantity"
     * 
     * @param mdObject the metadata object
     * @return the full FQN string
     */
    protected String extractFqn(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            // Build full path by traversing the containment hierarchy
            StringBuilder fqnBuilder = new StringBuilder();
            EObject current = mdObject;
            
            while (current != null) {
                // Get eClass name (type like Document, Catalog, Attribute, etc.)
                String typeName = current.eClass().getName();
                
                // Skip Configuration root and internal types
                if ("Configuration".equals(typeName) || typeName.startsWith("Md")) {
                    break;
                }
                
                // Try to get name property
                String name = getObjectName(current);
                
                if (name != null && !name.isEmpty()) {
                    String part = typeName + "." + name;
                    if (fqnBuilder.length() > 0) {
                        fqnBuilder.insert(0, ".");
                    }
                    fqnBuilder.insert(0, part);
                }
                
                // Move to parent
                current = current.eContainer();
            }
            
            return fqnBuilder.length() > 0 ? fqnBuilder.toString() : null;
        } catch (Exception e) {
            Activator.logError("Failed to extract FQN from " + mdObject, e);
            return null;
        }
    }
    
    /**
     * Gets the name of a metadata object using reflection.
     * 
     * @param eObject the object
     * @return the name or null
     */
    private String getObjectName(EObject eObject) {
        try {
            for (java.lang.reflect.Method m : eObject.getClass().getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object name = m.invoke(eObject);
                    return name != null ? name.toString() : null;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    /**
     * Extracts a metadata object from a selection element.
     * The objects in EDT navigator tree are directly EObjects (MdObject instances).
     */
    private EObject extractMdObject(Object element) {
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
            Activator.logError("Error adapting element to EObject", e);
        }
        
        return null;
    }
    
    /**
     * Extracts the project from a selection element.
     */
    private IProject extractProject(Object element) {
        if (element == null) {
            return null;
        }
        
        // If it's a project directly
        if (element instanceof IProject project) {
            return project;
        }
        
        // Try to get project from EObject's resource
        if (element instanceof EObject eobj) {
            // First try resource URI
            if (eobj.eResource() != null && eobj.eResource().getURI() != null) {
                org.eclipse.emf.common.util.URI uri = eobj.eResource().getURI();
                
                String uriPath = uri.toPlatformString(true);
                
                if (uriPath != null && uriPath.length() > 1) {
                    String projectName = uriPath.split("/")[1];
                    return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                        .getRoot().getProject(projectName);
                }
                
                // Try to extract from bm: URI scheme
                String uriString = uri.toString();
                if (uriString.startsWith("bm://")) {
                    // Format: bm://projectName/...
                    String[] parts = uriString.substring(5).split("/");
                    if (parts.length > 0) {
                        return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                            .getRoot().getProject(parts[0]);
                    }
                }
            }
            
            // Fallback: try to get project from V8ProjectManager via Activator
            try {
                com._1c.g5.v8.dt.core.platform.IV8ProjectManager v8ProjectManager = 
                    Activator.getDefault().getV8ProjectManager();
                if (v8ProjectManager != null) {
                    com._1c.g5.v8.dt.core.platform.IV8Project v8Project = 
                        v8ProjectManager.getProject(eobj);
                    if (v8Project != null) {
                        return v8Project.getProject();
                    }
                }
            } catch (Exception e) {
                Activator.logError("Error getting project from V8ProjectManager", e);
            }
        }
        
        // Try getProject() method via reflection
        try {
            java.lang.reflect.Method getProjectMethod = 
                element.getClass().getMethod("getProject");
            Object project = getProjectMethod.invoke(element);
            if (project instanceof IProject iproject) {
                return iproject;
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return null;
    }
}
