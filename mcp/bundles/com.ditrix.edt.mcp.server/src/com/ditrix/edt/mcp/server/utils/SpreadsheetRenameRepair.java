// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ltk.core.refactoring.Change;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
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
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.utils.SpreadsheetRenameGuard.LanguageKey;

/** Preserves verified saved descriptions in the same native BM transaction as the owner rename. */
public final class SpreadsheetRenameRepair implements IRenameRefactoringContributor
{
    private static final ThreadLocal<Scope> CONSTRUCTING = new ThreadLocal<>();
    // EDT passes precisely the same settings from the direct pre-change hook to the BM visitor,
    // which can run on a different thread. Never associate a normal EDT editor rename with a plan.
    private static final Map<RefactoringSettings, Scope> BUILDING = new ConcurrentHashMap<>();
    record Entry(long workbookId, Map<LanguageKey, String> descriptions)
    {
    }

    private static boolean supported(HostKind host, MdObject owner)
    {
        return host == HostKind.WINDOWED && owner instanceof IBmObject
            && (owner instanceof DataProcessor || owner instanceof ExternalDataProcessor);
    }

    public static Scope prepare(IProject project, MdObject owner, boolean confirm)
    {
        return prepare(project, owner, confirm, HostKind.current(), RenameEditorGuard::dependents);
    }

    /** The affected projects could not be checked before native rename planning or apply. */
    public static final class DependencyLookupException extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        private DependencyLookupException(Throwable cause)
        {
            super(RenameEditorGuard.UNVERIFIED, cause);
        }
    }

    private static boolean hasNoDependents(IProject project,
        java.util.function.Function<IProject, Collection<IProject>> findDependents)
    {
        Collection<IProject> dependents;
        try
        {
            dependents = findDependents.apply(project);
        }
        catch (RuntimeException e)
        {
            throw new DependencyLookupException(e);
        }
        if (dependents == null)
        {
            throw new DependencyLookupException(null);
        }
        return dependents.isEmpty();
    }

    static Scope prepare(IProject project, MdObject owner, boolean confirm, HostKind host,
        java.util.function.Function<IProject, Collection<IProject>> findDependents)
    {
        if (!confirm || !supported(host, owner) || CONSTRUCTING.get() != null
            || !(owner instanceof IBmObject bm) || !hasNoDependents(project, findDependents))
        {
            return new Scope(null, null, IBmObject.BM_NULL_ID, findDependents);
        }
        Scope scope = new Scope(project, owner, bm.bmGetId(), findDependents);
        CONSTRUCTING.set(scope);
        return scope;
    }

    public static final class Scope implements AutoCloseable
    {
        private final IProject project;
        private final MdObject original;
        private final long ownerId;
        private final java.util.function.Function<IProject, Collection<IProject>> findDependents;
        private volatile RefactoringSettings settings;
        private final AtomicBoolean attached = new AtomicBoolean();
        private volatile List<Entry> entries = List.of();

        private Scope(IProject project, MdObject original, long ownerId,
            java.util.function.Function<IProject, Collection<IProject>> findDependents)
        {
            this.project = project;
            this.original = original;
            this.ownerId = ownerId;
            this.findDependents = findDependents;
        }

        public boolean canRestore()
        {
            return project != null && settings != null && attached.get()
                && hasNoDependents(project, findDependents);
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

    static final class RepairOperation implements IBmRefactoringOperation
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
            if (transaction == null)
            {
                return;
            }
            for (Entry entry : scope.entries)
            {
                // Native LTK pre-operations may already have written BSL reference updates.
                // Never veto the main rename here. All disk verification ran before perform().
                try
                {
                    if (transaction.getObjectById(entry.workbookId()) instanceof SpreadsheetDocument workbook)
                    {
                        restore(workbook, entry.descriptions());
                    }
                }
                catch (RuntimeException ignored)
                {
                    // A model invalidated by another participant cannot safely be repaired here.
                }
            }
        }
    }

    static void restore(SpreadsheetDocument workbook, Map<LanguageKey, String> descriptions)
    {
        var settings = workbook.getLanguageSettings();
        if (settings == null)
        {
            return;
        }
        Map<LanguageKey, LanguageInfo> unique = new LinkedHashMap<>();
        var duplicate = new java.util.HashSet<LanguageKey>();
        for (LanguageInfo language : settings.getLanguages())
        {
            var key = new LanguageKey(language.getLanguageId(), language.getLanguageCode());
            if (descriptions.containsKey(key) && unique.putIfAbsent(key, language) != null)
            {
                duplicate.add(key);
            }
        }
        unique.forEach((key, language) -> {
            String current = language.getDescription();
            if (!duplicate.contains(key) && (current == null || current.isBlank()))
            {
                language.setDescription(descriptions.get(key));
            }
        });
    }
}
