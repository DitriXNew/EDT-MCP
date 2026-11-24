// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.UIJob;

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
        String fqn = sourceOwnerFqn(file);
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

    static String sourceOwnerFqn(IFile file)
    {
        return VendorSupportGuard.moduleOwnerFqn(BslModuleUtils.sourceRelativePath(file), true);
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
        IWorkspace workspace = file.getWorkspace();
        var rules = workspace.getRuleFactory();
        var rule = MultiRule.combine(rules.deleteRule(file), rules.modifyRule(owner.file()));
        boolean windowed = HostKind.current() != HostKind.HEADLESS;
        var gate = new CommitGate();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean deleted = new AtomicBoolean();
        ICoreRunnable op = monitor -> {
            try
            {
                if (windowed)
                {
                    Windowed.requireClosed(file, owner.file());
                }
                if (!owner.file().exists() || !owner.file().isSynchronized(IResource.DEPTH_ZERO)
                    || owner.file().getModificationStamp() != owner.modificationStamp())
                {
                    throw Refusals.state("The module's owner changed or is not in sync. Nothing was deleted.");
                }
                validateSource(file, expectedHash);
                if (monitor.isCanceled() || !gate.enter())
                {
                    throw cancelled();
                }
                entered.run();
                // Like EDT's merge operation, mutate the workspace, not the path on disk. Do not FORCE
                // an out-of-sync file, keep Eclipse local history, and never remove parent folders.
                file.delete(IResource.KEEP_HISTORY, monitor);
                committed.run();
                deleted.set(true);
            }
            catch (Exception e)
            {
                failure.set(e);
            }
        };
        IStatus status = Status.OK_STATUS;
        if (windowed)
        {
            status = Windowed.run(workspace, op, rule, gate);
        }
        else
        {
            workspace.run(op, rule, IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
        }
        if (failure.get() != null)
        {
            throw failure.get();
        }
        if (!deleted.get())
        {
            if (status != null && !status.isOK() && status.getSeverity() != IStatus.CANCEL)
            {
                throw new CoreException(status);
            }
            throw cancelled();
        }
        // Verify both the workspace and physical file; UNKNOWN storage state must not report success.
        var path = file.getLocation();
        if (file.exists() || path == null || !Files.notExists(path.toFile().toPath(), LinkOption.NOFOLLOW_LINKS))
        {
            throw new IllegalStateException("The module's removal could not be verified on disk and in EDT. "
                + "Read back its state before retrying.");
        }
    }

    private static IllegalStateException cancelled()
    {
        return Refusals.state("Blank module removal was cancelled before deletion. Nothing was deleted.");
    }

    /** Exactly one of cancellation and entering the mutation may win. */
    static final class CommitGate
    {
        private final AtomicBoolean claimed = new AtomicBoolean();

        boolean enter()
        {
            return claimed.compareAndSet(false, true);
        }

        boolean cancelBeforeEntry()
        {
            return claimed.compareAndSet(false, true);
        }
    }

    static final class Windowed
    {
        private static IStatus run(IWorkspace workspace, ICoreRunnable op, ISchedulingRule rule, CommitGate gate)
            throws Exception
        {
            // UIJob acquires its rule before queuing work on SWT and associates the rule with
            // the UI thread for runInUIThread. The editor check and workspace mutation stay together.
            if (Display.getCurrent() != null)
            {
                throw Refusals.state("Call deleteModule from the MCP client, not the EDT UI thread. "
                    + "Nothing was deleted.");
            }
            if (Thread.currentThread().isInterrupted())
            {
                throw cancelled();
            }
            var currentRule = Job.getJobManager().currentRule();
            if (rule != null && currentRule != null && currentRule.isConflicting(rule))
            {
                throw Refusals.state("The caller already holds a conflicting workspace rule. "
                    + "Nothing was deleted.");
            }
            var job = UIJob.create("Remove blank BSL module", (ICoreRunnable) monitor ->
                workspace.run(op, rule, IWorkspace.AVOID_UPDATE, monitor));
            job.setRule(rule);
            job.setSystem(true);
            McpJobs.schedule(job);
            return await(job, gate);
        }

        static IStatus await(Job job, CommitGate gate)
        {
            boolean interrupted = false;
            try
            {
                for (;;)
                {
                    try
                    {
                        job.join();
                        var result = job.getResult();
                        if (result != null)
                        {
                            return result;
                        }
                        // A suspended Job manager can let join return while still holding the job.
                        // Do not report a refusal unless we also prevent a later commit.
                        if (gate.cancelBeforeEntry())
                        {
                            job.cancel();
                            return Status.CANCEL_STATUS;
                        }
                    }
                    catch (InterruptedException e)
                    {
                        interrupted = true;
                        if (gate.cancelBeforeEntry())
                        {
                            job.cancel();
                            return Status.CANCEL_STATUS;
                        }
                        // A deletion already admitted must report its actual result.
                    }
                }
            }
            finally
            {
                if (interrupted)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static void requireClosed(IFile file, IFile owner) throws PartInitException
        {
            for (var window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (var page : window.getPages())
                {
                    for (var ref : page.getEditorReferences())
                    {
                        requireEditorDoesNotBlock(ref, file, owner);
                    }
                }
            }
        }

        static void requireEditorDoesNotBlock(IEditorReference ref, IFile file, IFile owner)
            throws PartInitException
        {
            IResource resource = editorResource(ref.getEditorInput());
            if (file.equals(resource) || owner.equals(resource) || (resource == null && ref.isDirty()))
            {
                throw Refusals.state("Close the module and its owning object's editors in EDT before " //$NON-NLS-1$
                    + "removing the module. Nothing was deleted."); //$NON-NLS-1$
            }
        }

        private static IResource editorResource(IEditorInput input)
        {
            if (input instanceof IFileEditorInput)
            {
                // getFile is the typed contract; an implementation need not delegate getAdapter.
                IFile file = ((IFileEditorInput)input).getFile();
                if (file != null)
                {
                    return file;
                }
            }
            // Eclipse's adapter manager also consults EDT's native model-to-resource factory for
            // granular owner editors. Do not instantiate the editor or activate an unrelated plug-in.
            IFile file = Adapters.adapt(input, IFile.class, false);
            return file != null ? file : Adapters.adapt(input, IResource.class, false);
        }
    }
}
