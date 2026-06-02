/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags.refactoring;

import java.util.Set;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.refactoring.AbstractObjectRenameRefactoringContributor;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/**
 * Refactoring contributor that updates tag assignments when metadata objects are renamed.
 * Listens to EDT's refactoring framework and updates FQNs in YAML storage accordingly.
 *
 * <p>Thin subclass of {@link AbstractObjectRenameRefactoringContributor}: the shared base
 * supplies the LTK participant wiring and the post-refactoring FQN-rewrite change; this
 * class only binds the tag-storage specifics.</p>
 */
public class TagRenameRefactoringContributor extends AbstractObjectRenameRefactoringContributor {

    @Override
    protected boolean hasAssociation(IProject project, String fqn) {
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);

        // Check if this object has any tags assigned
        Set<String> tags = storage.getTagNames(fqn);
        return tags != null && !tags.isEmpty();
    }

    @Override
    protected boolean performRename(IProject project, String oldFqn, String newFqn) {
        return TagService.getInstance().renameObject(project, oldFqn, newFqn);
    }

    @Override
    protected String describeRename(String oldFqn, String newFqn) {
        return "Update tag assignments: " + oldFqn + " -> " + newFqn;
    }

    @Override
    protected String renameLogMessage(String oldFqn, String newFqn) {
        return "Tag assignments updated: " + oldFqn + " -> " + newFqn;
    }
}
