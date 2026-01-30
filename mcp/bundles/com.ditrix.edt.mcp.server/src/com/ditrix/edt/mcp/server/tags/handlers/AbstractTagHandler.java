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
     * Extracts the FQN from a metadata object.
     * 
     * @param mdObject the metadata object
     * @return the FQN string (e.g., "Catalog.Products")
     */
    protected String extractFqn(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            // Try to get the name using reflection (MdObject interface)
            Class<?> mdObjectClass = mdObject.getClass();
            
            // Get eClass name
            String typeName = mdObject.eClass().getName();
            
            // Get name property
            java.lang.reflect.Method getNameMethod = null;
            for (java.lang.reflect.Method m : mdObjectClass.getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) {
                    getNameMethod = m;
                    break;
                }
            }
            
            if (getNameMethod != null) {
                Object name = getNameMethod.invoke(mdObject);
                if (name != null) {
                    return typeName + "." + name.toString();
                }
            }
            
            return typeName;
        } catch (Exception e) {
            Activator.logError("Failed to extract FQN from " + mdObject, e);
            return null;
        }
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
                Activator.logInfo("EObject URI: " + uri);
                
                String uriPath = uri.toPlatformString(true);
                Activator.logInfo("Platform string: " + uriPath);
                
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
