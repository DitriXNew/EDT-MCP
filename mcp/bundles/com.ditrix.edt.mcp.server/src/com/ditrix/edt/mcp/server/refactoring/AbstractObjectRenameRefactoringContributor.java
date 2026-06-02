/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.refactoring;

import java.util.Collection;
import java.util.Collections;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ltk.core.refactoring.Change;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.refactoring.core.IRenameRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Shared base for refactoring contributors that update an FQN-keyed association
 * (tag assignments, group membership) when a metadata object is renamed.
 *
 * <p>This base holds the common LTK {@link IRenameRefactoringContributor} wiring:
 * it participates only via {@link #createNativePostChanges} (no BM-transaction
 * participation, no pre-changes, no prohibited reference editing). When the
 * renamed object actually carries an association, it produces a single
 * post-refactoring {@link Change} that rewrites the stored FQN <em>after</em> the
 * rename has been applied, and supplies an undo change that swaps the FQNs back.</p>
 *
 * <p>The tag-vs-group specifics are supplied by subclasses via the abstract
 * template methods: whether an object has an association
 * ({@link #hasAssociation}) and how to perform the FQN rename in the backing
 * storage ({@link #performRename}). The human-readable change label is provided
 * by {@link #describeRename}.</p>
 */
public abstract class AbstractObjectRenameRefactoringContributor implements IRenameRefactoringContributor {

    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object,
            RefactoringSettings settings, RefactoringStatus status) {
        // We don't need to participate in the BM transaction itself
        return null;
    }

    @Override
    public RefactoringOperationDescriptor createPreReferenceUpdateParticipatingOperation(
            IBmObject object, RefactoringSettings settings, RefactoringStatus status) {
        return null;
    }

    @Override
    public Collection<Change> createNativePreChanges(EObject object, String newName,
            RefactoringSettings settings, RefactoringStatus status) {
        // We don't need pre-changes
        return null;
    }

    @Override
    public Collection<Change> createNativePostChanges(EObject object, String newName,
            RefactoringSettings settings, RefactoringStatus status) {
        // Check if this object has any association of this kind
        if (object == null || !(object instanceof IBmObject bmObject)) {
            return null;
        }

        String oldFqn = TagUtils.extractFqn(bmObject);
        if (oldFqn == null || oldFqn.isEmpty()) {
            return null;
        }

        // Get the project for this object
        IProject project = TagUtils.extractProject(object);
        if (project == null) {
            return null;
        }

        // Subclass decides whether this object carries an association worth updating
        if (!hasAssociation(project, oldFqn)) {
            return null;
        }

        // Build the new FQN based on the new name
        String newFqn = TagUtils.buildNewFqn(oldFqn, newName);

        if (newFqn == null || newFqn.equals(oldFqn)) {
            return null;
        }

        // Create a change to update the FQN in YAML after refactoring
        return Collections.singletonList(
            new FqnRenameChange(project, oldFqn, newFqn));
    }

    @Override
    public boolean allowProhibitedReferenceEditing(IBmCrossReference reference) {
        return false;
    }

    // ===== Template methods supplied by subclasses =====

    /**
     * Tells whether the given object currently carries an association of this
     * contributor's kind (a tag assignment, a group membership, ...).
     *
     * @param project the owning project
     * @param fqn the object's current FQN
     * @return {@code true} if an association exists and should be updated on rename
     */
    protected abstract boolean hasAssociation(IProject project, String fqn);

    /**
     * Performs the actual FQN rename in the backing association storage.
     *
     * @param project the owning project
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return {@code true} if the storage was updated
     */
    protected abstract boolean performRename(IProject project, String oldFqn, String newFqn);

    /**
     * Produces the human-readable {@link Change#getName() change label} for a
     * rename, e.g. {@code "Update tag assignments: old -> new"}.
     *
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return the change label
     */
    protected abstract String describeRename(String oldFqn, String newFqn);

    /**
     * Produces the success log message emitted after a rename is applied,
     * e.g. {@code "Tag assignments updated: old -> new"}. Kept distinct from
     * {@link #describeRename} to preserve each contributor's original log text.
     *
     * @param oldFqn the old FQN
     * @param newFqn the new FQN
     * @return the log message
     */
    protected abstract String renameLogMessage(String oldFqn, String newFqn);

    /**
     * Change that updates the FQN-keyed association in YAML after refactoring.
     */
    private final class FqnRenameChange extends Change {

        private final IProject project;
        private final String oldFqn;
        private final String newFqn;

        FqnRenameChange(IProject project, String oldFqn, String newFqn) {
            this.project = project;
            this.oldFqn = oldFqn;
            this.newFqn = newFqn;
        }

        @Override
        public String getName() {
            return describeRename(oldFqn, newFqn);
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

            // Rename the object in the backing association storage
            boolean success = performRename(project, oldFqn, newFqn);

            if (success) {
                Activator.logInfo(renameLogMessage(oldFqn, newFqn));
            }

            // Return an undo change
            return new FqnRenameChange(project, newFqn, oldFqn);
        }

        @Override
        public Object getModifiedElement() {
            return project;
        }
    }
}
