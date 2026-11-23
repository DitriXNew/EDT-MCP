// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.HostKind;

/**
 * The optional object/manager module is an EDT external BSL resource, not a BM
 * top object. EDT Compare/Merge removes this kind of resource through a workspace
 * file operation (BslModuleComparisonParticipant -> DeleteFilePostponedOperation).
 * No BM detach, hand-written .mdo or operating-system unlink is involved.
 */
public final class EmptyModuleRemoval
{
    private EmptyModuleRemoval() { }

    /** Only immutable identity and a resource handle leave the model read. */
    public record Owner(IFile file, long bmId, long modificationStamp) { }

    /** Verifies the exact path against the native owner-to-external-file mapping. */
    public static Owner resolveOwner(IFile file, MetadataScope scope)
    {
        String path = file.getProjectRelativePath().toPortableString();
        String fqn = VendorSupportGuard.moduleOwnerFqn(path, true);
        String feature = moduleFeature(file.getName());
        if (scope.isExternalObjects() || scope.configuration() == null || feature == null || fqn == null)
        {
            throw Refusals.argument("deleteModule supports only owned ObjectModule.bsl or ManagerModule.bsl "
                + "of a configuration or extension. Nothing was deleted.");
        }

        return ReadToolExecution.readModel(file.getProject(), "Resolve optional BSL module", () -> {
            var owner = MetadataNodeResolver.resolveAddress(scope, fqn, false);
            if (!(owner instanceof IBmObject) || owner.getObjectBelonging() != ObjectBelonging.NATIVE)
            {
                throw Refusals.argument("No owned metadata object was resolved for " + path
                    + ". Adopted objects and external objects are not supported. Nothing was deleted.");
            }
            var ref = owner.eClass().getEStructuralFeature(feature);
            if (!(ref instanceof EReference) || ((EReference)ref).isContainment() || ref.isMany()
                || ((EReference)ref).getEReferenceType() != BslPackage.Literals.MODULE)
            {
                throw Refusals.argument("The resolved object does not own this kind of module. Nothing was deleted.");
            }
            var provider = ServiceAccess.get(IProjectFileSystemSupportProvider.class);
            var fs = provider == null ? null : provider.getProjectFileSystemSupport(file.getProject());
            if (fs == null || !file.equals(fs.getFile(owner, (EReference)ref)))
            {
                throw Refusals.state("EDT could not verify this module's workspace path. Nothing was deleted.");
            }
            IFile ownerFile = fs.getFile(owner);
            if (ownerFile == null || !ownerFile.exists())
            {
                throw Refusals.state("EDT could not verify this module's owner file. Nothing was deleted.");
            }
            return new Owner(ownerFile, ((IBmObject)owner).bmGetId(), ownerFile.getModificationStamp());
        });
    }

    private static String moduleFeature(String name)
    {
        switch (name)
        {
        case "ObjectModule.bsl": return "objectModule";
        case "ManagerModule.bsl": return "managerModule";
        default: return null;
        }
    }

    public static void validateSource(IFile file, String expectedHash) throws Exception
    {
        if (!file.exists() || !file.isSynchronized(IResource.DEPTH_ZERO))
        {
            throw Refusals.state("The module is missing or out of sync with EDT. Nothing was deleted. "
                + "Refresh the project and read the module again with read_module_source.");
        }
        var path = file.getLocation();
        var project = file.getProject().getLocation();
        if (path == null || project == null || file.isLinked(IResource.CHECK_ANCESTORS)
            || !Files.isRegularFile(path.toFile().toPath(), LinkOption.NOFOLLOW_LINKS)
            || !path.toFile().toPath().toRealPath().equals(project.toFile().toPath().toRealPath()
                .resolve(file.getProjectRelativePath().toOSString())))
        {
            throw Refusals.argument("deleteModule requires a local, unlinked file inside the EDT project. "
                + "Nothing was deleted.");
        }
        requireEmptyRevision(BslModuleUtils.readFileText(file, true), expectedHash);
    }

    static void requireEmptyRevision(String text, String expectedHash)
    {
        if (!ContentHash.matches(text, expectedHash))
        {
            throw Refusals.state("expectedHash does not match the current module. Nothing was deleted. "
                + "Read it again with read_module_source and pass its contentHash.");
        }
        if (!text.isBlank())
        {
            throw Refusals.argument("deleteModule only removes a blank module. Comments, directives and code "
                + "are not blank. Nothing was deleted.");
        }
    }

    /** The resource rule excludes a concurrent EDT save between the final hash check and deletion. */
    public static void remove(IFile file, Owner owner, String expectedHash, Runnable entered, Runnable committed)
        throws Exception
    {
        AtomicReference<Exception> failure = new AtomicReference<>();
        // In a workbench the editor check and resource operation run in one dispatch. No editor opens
        // or accepts typing between them. Never wait for a build or export on the UI thread.
        ReadToolExecution.dispatch(() -> {
            IWorkspace workspace = file.getWorkspace();
            ICoreRunnable op = monitor -> {
                try
                {
                    if (HostKind.current() != HostKind.HEADLESS)
                    {
                        Windowed.requireClosed(file, owner.file());
                    }
                    if (!owner.file().exists() || !owner.file().isSynchronized(IResource.DEPTH_ZERO)
                        || owner.file().getModificationStamp() != owner.modificationStamp())
                    {
                        throw Refusals.state("The module's owner changed or is not in sync. Nothing was deleted.");
                    }
                    validateSource(file, expectedHash);
                    entered.run();
                    // Like EDT's merge operation, mutate the workspace, not the path on disk. Do not FORCE
                    // an out-of-sync file, keep Eclipse local history, and never remove parent folders.
                    file.delete(IResource.KEEP_HISTORY, monitor);
                    committed.run();
                }
                catch (Exception e)
                {
                    failure.set(e);
                }
            };
            try
            {
                var rules = workspace.getRuleFactory();
                var rule = MultiRule.combine(rules.deleteRule(file), rules.modifyRule(owner.file()));
                workspace.run(op, rule, IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
            }
            catch (Exception e)
            {
                failure.set(e);
            }
        });
        if (failure.get() != null)
        {
            throw failure.get();
        }
        // Verify both the workspace and physical file; UNKNOWN storage state must not report success.
        var path = file.getLocation();
        if (file.exists() || path == null || !Files.notExists(path.toFile().toPath(), LinkOption.NOFOLLOW_LINKS))
        {
            throw new IllegalStateException("The module's removal could not be verified on disk and in EDT. "
                + "Read back its state before retrying.");
        }
    }

    private static final class Windowed
    {
        private static void requireClosed(IFile file, IFile owner) throws org.eclipse.ui.PartInitException
        {
            for (var window : org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (var page : window.getPages())
                {
                    for (var ref : page.getEditorReferences())
                    {
                        var input = ref.getEditorInput();
                        IResource resource = input == null ? null : input.getAdapter(IResource.class);
                        if (file.equals(resource) || owner.equals(resource) || (resource == null && ref.isDirty()))
                        {
                            throw Refusals.state("Close the module and its owning object's editors in EDT before "
                                + "removing the module. Nothing was deleted.");
                        }
                    }
                }
            }
        }
    }
}
