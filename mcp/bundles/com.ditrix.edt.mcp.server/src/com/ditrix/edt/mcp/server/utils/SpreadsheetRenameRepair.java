// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ltk.core.refactoring.Change;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.moxel.LanguageInfo;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.refactoring.core.IBmRefactoringOperation;
import com._1c.g5.v8.dt.refactoring.core.IRenameRefactoringContributor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.utils.SpreadsheetRenameGuard.LanguageKey;

/** Preserves verified saved descriptions in the same native BM transaction as the owner rename. */
public final class SpreadsheetRenameRepair implements IRenameRefactoringContributor
{
    private static final ThreadLocal<Scope> CONSTRUCTING = new ThreadLocal<>();
    // EDT passes precisely the same settings from the direct pre-change hook to the BM visitor,
    // which can run on a different thread. Never associate a normal EDT editor rename with a plan.
    private static final Map<RefactoringSettings, Scope> BUILDING = new ConcurrentHashMap<>();
    private static final String UNVERIFIED_MODELS =
        "Could not verify the affected EDT models. Nothing was renamed."; //$NON-NLS-1$

    record Entry(long workbookId, IFile file, long modificationStamp, Map<LanguageKey, String> descriptions)
    {
    }

    private static boolean supported(MdObject owner)
    {
        return HostKind.current() == HostKind.WINDOWED && owner instanceof IBmObject
            && (owner instanceof DataProcessor || owner instanceof ExternalDataProcessor);
    }

    private static Collection<IProject> dependents(IProject project)
    {
        var activator = Activator.getDefault();
        return activator == null || activator.getV8ProjectManager() == null ? null
            : IDependentProject.getDependent(project, activator.getV8ProjectManager().getProjects());
    }

    public static String dirtyRefusal(IProject project, MdObject owner)
    {
        if (!supported(owner))
        {
            return null;
        }
        try
        {
            var dependentProjects = dependents(project);
            if (dependentProjects == null)
            {
                return UNVERIFIED_MODELS;
            }
            var projects = new LinkedHashSet<>(dependentProjects);
            projects.add(project);
            for (var affected : projects)
            {
                var resolution = BmModelResolver.resolve(affected);
                if (!resolution.isAvailable())
                {
                    return UNVERIFIED_MODELS;
                }
                // EDT's native planner saves dirty local contexts before contributors run.
                if (resolution.getModel().getAllLocalContexts().stream().anyMatch(context -> context.isDirty()))
                {
                    return "Save or preserve unsaved edits in project '" + affected.getName() //$NON-NLS-1$
                        + "' before preparing this rename. Nothing was renamed."; //$NON-NLS-1$
                }
            }
        }
        catch (RuntimeException e)
        {
            return UNVERIFIED_MODELS;
        }
        return null;
    }

    public static Scope prepare(IProject project, MdObject owner, boolean confirm)
    {
        var dependentProjects = confirm && supported(owner) ? dependents(project) : null;
        if (dependentProjects == null || !dependentProjects.isEmpty()
            || CONSTRUCTING.get() != null || !(owner instanceof IBmObject bm))
        {
            return new Scope(null, null, IBmObject.BM_NULL_ID);
        }
        Scope scope = new Scope(project, owner, bm.bmGetId());
        CONSTRUCTING.set(scope);
        return scope;
    }

    public static final class Scope implements AutoCloseable
    {
        private final IProject project;
        private final MdObject original;
        private final long ownerId;
        private volatile RefactoringSettings settings;
        private final AtomicBoolean attached = new AtomicBoolean();
        private volatile List<Entry> entries = List.of();

        private Scope(IProject project, MdObject original, long ownerId)
        {
            this.project = project;
            this.original = original;
            this.ownerId = ownerId;
        }

        public boolean canRestore()
        {
            var currentDependents = project == null ? null : dependents(project);
            return settings != null && attached.get() && currentDependents != null
                && currentDependents.isEmpty();
        }

        void arm(List<Entry> entries)
        {
            this.entries = List.copyOf(entries);
        }

        @Override
        public void close()
        {
            if (CONSTRUCTING.get() == this)
            {
                CONSTRUCTING.remove();
            }
            if (settings != null)
            {
                BUILDING.remove(settings, this);
            }
        }
    }

    @Override
    public Collection<Change> createNativePreChanges(EObject object, String newName, RefactoringSettings settings,
        RefactoringStatus status)
    {
        Scope scope = CONSTRUCTING.get();
        if (scope != null && scope.original == object && scope.settings == null)
        {
            scope.settings = settings;
            BUILDING.put(settings, scope);
        }
        return List.of();
    }

    @Override
    public RefactoringOperationDescriptor createParticipatingOperation(EObject object, RefactoringSettings settings,
        RefactoringStatus status)
    {
        Scope scope = BUILDING.get(settings);
        boolean sameOwner = scope != null && object instanceof MdObject md && object instanceof IBmObject visited
            && scope.original instanceof IBmObject original && visited.bmGetId() == scope.ownerId
            && visited.bmGetNamespace() == original.bmGetNamespace()
            && md.eClass() == scope.original.eClass() && Objects.equals(md.getUuid(), scope.original.getUuid());
        if (!sameOwner || !scope.attached.compareAndSet(false, true))
        {
            return null;
        }
        // The unnamed descriptor is mandatory and has no IRefactoringItem in EDT.
        // It must not occupy a preview index or alter the preview's change-point fingerprint.
        return new RefactoringOperationDescriptor(new RepairOperation(scope));
    }

    @Override
    public RefactoringOperationDescriptor createPreReferenceUpdateParticipatingOperation(IBmObject object,
        RefactoringSettings settings, RefactoringStatus status)
    {
        return null;
    }

    @Override
    public Collection<Change> createNativePostChanges(EObject object, String newName, RefactoringSettings settings,
        RefactoringStatus status)
    {
        return List.of();
    }

    private static final class RepairOperation implements IBmRefactoringOperation
    {
        private final Scope scope;
        private IBmTransaction transaction;
        RepairOperation(Scope scope)
        {
            this.scope = scope;
        }

        @Override
        public void setActiveTransaction(IBmTransaction transaction)
        {
            this.transaction = transaction;
        }

        @Override
        public void perform()
        {
            if (scope.entries.isEmpty())
            {
                return;
            }
            if (transaction == null || !(transaction.getObjectById(scope.ownerId) instanceof MdObject))
            {
                throw changed();
            }
            for (Entry entry : scope.entries)
            {
                if (!entry.file().exists() || !entry.file().isSynchronized(IResource.DEPTH_ZERO)
                    || entry.file().getModificationStamp() != entry.modificationStamp()
                    || entry.file().isLinked(IResource.CHECK_ANCESTORS)
                    || entry.file().getProject().getLocation() == null || entry.file().getLocation() == null
                    || !(transaction.getObjectById(entry.workbookId()) instanceof SpreadsheetDocument workbook))
                {
                    throw changed();
                }
                var observed = ProjectSpreadsheetReader.read(entry.file().getProject().getLocation().toFile().toPath(),
                    entry.file().getLocation().toFile().toPath(), entry.descriptions().keySet());
                if (observed.availability() != ProjectSpreadsheetReader.Availability.SECURE
                    || !observed.descriptions().equals(entry.descriptions()))
                {
                    throw changed();
                }
                restore(workbook, entry.descriptions());
            }
        }
    }

    static void restore(SpreadsheetDocument workbook, Map<LanguageKey, String> descriptions)
    {
        if (workbook.getLanguageSettings() == null)
        {
            throw changed();
        }
        Map<LanguageKey, LanguageInfo> restore = new LinkedHashMap<>();
        for (LanguageInfo language : workbook.getLanguageSettings().getLanguages())
        {
            var key = new LanguageKey(language.getLanguageId(), language.getLanguageCode());
            if (!descriptions.containsKey(key))
            {
                continue;
            }
            String current = language.getDescription();
            if (restore.putIfAbsent(key, language) != null
                || (current != null && !current.isEmpty() && !current.equals(descriptions.get(key))))
            {
                throw changed();
            }
        }
        if (restore.size() != descriptions.size())
        {
            throw changed();
        }
        restore.forEach((key, language) -> language.setDescription(descriptions.get(key)));
    }

    private static IllegalStateException changed()
    {
        return new IllegalStateException("Could not verify the saved spreadsheet language description during rename."); //$NON-NLS-1$
    }
}
