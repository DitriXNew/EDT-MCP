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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Handler for toggling tags by index (1-10) via keyboard shortcuts.
 * 
 * <p>Keyboard shortcuts Ctrl+Alt+1 through Ctrl+Alt+0 toggle the 1st through 10th tag.
 * If the tag is already assigned to the object, it will be removed.
 * If not assigned, it will be added.</p>
 * 
 * <p>Supports multi-selection: all selected metadata objects will have the tag toggled.
 * If objects are from different projects, each object uses tags from its own project.</p>
 */
public class ToggleTagByIndexHandler extends AbstractTagHandler {
    
    /**
     * Command ID for the toggle tag command.
     */
    public static final String COMMAND_ID = "com.ditrix.edt.mcp.server.tags.toggleTag";
    
    /**
     * Command parameter ID for the tag index (1-10, where 10 is represented as 0).
     */
    public static final String PARAM_TAG_INDEX = "com.ditrix.edt.mcp.server.tags.toggleTag.index";
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String indexParam = event.getParameter(PARAM_TAG_INDEX);
        if (indexParam == null) {
            return null;
        }
        
        int tagIndex;
        try {
            tagIndex = Integer.parseInt(indexParam);
            // Convert 0 to 10 (Ctrl+Alt+0 = 10th tag)
            if (tagIndex == 0) {
                tagIndex = 10;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        
        // Get all selected metadata objects
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection ssel) || ssel.isEmpty()) {
            return null;
        }
        
        // Group selected objects by project
        List<ObjectInfo> objectsToProcess = new ArrayList<>();
        for (Iterator<?> it = ssel.iterator(); it.hasNext();) {
            Object element = it.next();
            EObject mdObject = TagUtils.extractMdObject(element);
            if (mdObject != null) {
                IProject project = TagUtils.extractProjectFromElement(element);
                String fqn = TagUtils.extractFqn(mdObject);
                if (project != null && fqn != null) {
                    objectsToProcess.add(new ObjectInfo(project, fqn));
                }
            }
        }
        
        if (objectsToProcess.isEmpty()) {
            return null;
        }
        
        // Process each object
        TagService tagService = TagService.getInstance();
        for (ObjectInfo info : objectsToProcess) {
            List<Tag> projectTags = tagService.getTags(info.project());
            if (tagIndex > projectTags.size()) {
                // Not enough tags in this project, skip
                continue;
            }
            
            Tag targetTag = projectTags.get(tagIndex - 1);
            String tagName = targetTag.getName();
            
            // Check if already assigned
            Set<Tag> assignedTags = tagService.getObjectTags(info.project(), info.fqn());
            boolean isAssigned = assignedTags.stream()
                .anyMatch(t -> t.getName().equals(tagName));
            
            if (isAssigned) {
                tagService.unassignTag(info.project(), info.fqn(), tagName);
            } else {
                tagService.assignTag(info.project(), info.fqn(), tagName);
            }
        }
        
        return null;
    }
    
    /**
     * Record for storing object info during processing.
     */
    private record ObjectInfo(IProject project, String fqn) {}
}
