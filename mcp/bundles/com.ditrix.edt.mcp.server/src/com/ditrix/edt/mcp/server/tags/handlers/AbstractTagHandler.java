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

import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Base handler for tag-related commands.
 * Provides utility methods for extracting selected metadata objects.
 * 
 * <p>This class delegates FQN and project extraction to {@link TagUtils}
 * to avoid code duplication across multiple handlers.</p>
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
            return TagUtils.extractMdObject(element);
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
            return TagUtils.extractProjectFromElement(element);
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
        return TagUtils.extractFqn(mdObject);
    }
}
