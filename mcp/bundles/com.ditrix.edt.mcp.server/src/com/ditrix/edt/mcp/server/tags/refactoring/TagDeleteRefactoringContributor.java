/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags.refactoring;

import java.util.Set;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.refactoring.AbstractObjectDeleteRefactoringContributor;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * Refactoring contributor that removes tag assignments when metadata objects are deleted.
 * Listens to EDT's refactoring framework and removes entries from YAML storage accordingly.
 *
 * <p>Thin subclass of {@link AbstractObjectDeleteRefactoringContributor}: the shared base
 * supplies the LTK participant wiring and the {@code perform()}/{@code postProcess()}
 * transaction split (YAML mutation runs after the BM transaction commits); this class only
 * binds the tag-storage specifics.</p>
 */
public class TagDeleteRefactoringContributor extends AbstractObjectDeleteRefactoringContributor {

    @Override
    protected boolean hasAssociation(IProject project, String fqn) {
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);

        // Check if this object has any tags assigned
        Set<String> tags = storage.getTagNames(fqn);
        return tags != null && !tags.isEmpty();
    }

    @Override
    protected boolean performRemove(IProject project, String fqn) {
        return TagService.getInstance().removeObject(project, fqn);
    }

    @Override
    protected String removeLogMessage(IProject project, String fqn) {
        return "Tag assignments removed for deleted object: " + fqn;
    }
}
