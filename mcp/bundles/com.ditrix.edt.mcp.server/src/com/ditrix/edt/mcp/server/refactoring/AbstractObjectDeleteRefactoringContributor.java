/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.refactoring;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.refactoring.core.IDeleteRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringPostProcessor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Shared base for refactoring contributors that drop an FQN-keyed association
 * (tag assignments, group membership) when a metadata object is deleted.
 *
 * <p>This base holds the common LTK {@link IDeleteRefactoringContributor} wiring.
 * When the deleted object actually carries an association, it produces a
 * {@link RefactoringOperationDescriptor} around an operation that implements
 * <em>both</em> {@link IRefactoringOperation} and {@link IRefactoringPostProcessor}.</p>
 *
 * <p><b>Transaction split (do not change).</b> {@link IRefactoringOperation#perform()}
 * runs <em>inside</em> the BM transaction and intentionally does nothing here; the
 * YAML storage mutation runs in {@link IRefactoringPostProcessor#postProcess()},
 * <em>after</em> the BM transaction commits. Mutating the YAML inside the
 * transaction would strip the object from storage even when a delete is rolled
 * back, diverging storage from the model (see GroupDelete commit {@code 7ddc2da},
 * audit A11).</p>
 *
 * <p>The tag-vs-group specifics are supplied by subclasses via the abstract
 * template methods: whether an object has an association ({@link #hasAssociation})
 * and how to remove it from the backing storage ({@link #performRemove}).</p>
 */
public abstract class AbstractObjectDeleteRefactoringContributor implements IDeleteRefactoringContributor {

    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object,
            RefactoringSettings settings, RefactoringStatus status) {

        if (object == null || !(object instanceof IBmObject bmObject)) {
            return null;
        }

        String fqn = TagUtils.extractFqn(bmObject);
        if (fqn == null || fqn.isEmpty()) {
            return null;
        }

        // Get the project for this object
        IProject project = TagUtils.extractProject(object);
        if (project == null) {
            return null;
        }

        // Subclass decides whether this object carries an association worth removing
        if (!hasAssociation(project, fqn)) {
            return null;
        }

        // Capture the success log message now, while the association still exists.
        // Subclasses may reference details (e.g. the group path) that are no longer
        // resolvable after the removal runs in postProcess().
        String logMessage = removeLogMessage(project, fqn);

        // Create an operation to remove the association after deletion commits
        return new RefactoringOperationDescriptor(
            new AssociationRemoveOperation(project, fqn, logMessage));
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

    // ===== Template methods supplied by subclasses =====

    /**
     * Tells whether the given object currently carries an association of this
     * contributor's kind (a tag assignment, a group membership, ...).
     *
     * @param project the owning project
     * @param fqn the object's current FQN
     * @return {@code true} if an association exists and should be removed on delete
     */
    protected abstract boolean hasAssociation(IProject project, String fqn);

    /**
     * Removes the object's association from the backing storage. Runs in
     * {@link IRefactoringPostProcessor#postProcess()}, after the BM transaction commits.
     *
     * @param project the owning project
     * @param fqn the deleted object's FQN
     * @return {@code true} if the storage was updated
     */
    protected abstract boolean performRemove(IProject project, String fqn);

    /**
     * Produces the success log message emitted after the association is removed,
     * e.g. {@code "Tag assignments removed for deleted object: <fqn>"}. Computed at
     * descriptor-creation time, while the association still exists, so subclasses
     * may reference details (e.g. the group path) that are no longer resolvable
     * once the removal has run.
     *
     * @param project the owning project
     * @param fqn the deleted object's FQN
     * @return the log message
     */
    protected abstract String removeLogMessage(IProject project, String fqn);

    /**
     * Operation that removes an object's association from YAML after it is deleted.
     * <p>
     * The YAML storage mutation runs in {@link #postProcess()} (after the BM
     * transaction commits), NOT in {@link #perform()} inside the transaction, so a
     * rolled-back delete does not strip the association and diverge storage from
     * the model. (audit A11)
     */
    private final class AssociationRemoveOperation implements IRefactoringOperation, IRefactoringPostProcessor {

        private final IProject project;
        private final String fqn;
        private final String logMessage;

        AssociationRemoveOperation(IProject project, String fqn, String logMessage) {
            this.project = project;
            this.fqn = fqn;
            this.logMessage = logMessage;
        }

        @Override
        public void perform() {
            // Do nothing during the BM transaction - YAML changes go in postProcess().
        }

        @Override
        public void postProcess() {
            // Remove the object's association from storage after deletion
            boolean success = performRemove(project, fqn);

            if (success) {
                Activator.logInfo(logMessage);
            }
        }
    }
}
