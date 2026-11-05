// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.designer.ssh.client.operation.IDbStructureChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver.IConflictResolveAssist;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;

/** Core synchronization callbacks for an explicitly unattended host. */
public final class HeadlessInfobaseUpdate
{
    public static final String EXTERNAL_CHANGES = "edt.mcp.externalInfobaseChanges";

    private HeadlessInfobaseUpdate() { }

    public static String runBounded(java.util.function.Supplier<String> work)
    {
        return runBounded(work, 120_000L);
    }

    static String runBounded(java.util.function.Supplier<String> work, long timeoutMs)
    {
        return runBounded(work, timeoutMs, McpJobs::schedule);
    }

    static String runBounded(java.util.function.Supplier<String> work, long timeoutMs,
        BoundedJob.IJobScheduler scheduler)
    {
        var answer = new java.util.concurrent.atomic.AtomicReference<String>();
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        var ended = new java.util.concurrent.atomic.AtomicBoolean();
        var outcome = BoundedJob.run("Unattended database update", timeoutMs,
            monitor -> { entered.set(true); answer.set(work.get()); }, () -> ended.set(true), scheduler);
        if (outcome.getOutcome() == BoundedJob.Outcome.COMPLETED && outcome.getFailure() == null)
            return answer.get();
        String detail = outcome.getFailure() == null ? outcome.getOutcome().name()
            : PlatformFailures.describe(outcome.getFailure());
        if (outcome.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START
            || outcome.getOutcome() == BoundedJob.Outcome.NOT_RUN && ended.get() && !entered.get())
            return com.ditrix.edt.mcp.server.protocol.ToolResult.error(
                "The unattended database update did not start. No update ran; retry update_database.")
                .put("outcome", "notStarted").toJson();
        if (outcome.getOutcome() == BoundedJob.Outcome.COMPLETED)
            return com.ditrix.edt.mcp.server.protocol.ToolResult.errorWithUnknownMutationOutcome("Unattended database update failed: " + detail
                + ". Inspect the database before retrying update_database.").put("outcome", "failed").toJson();
        return com.ditrix.edt.mcp.server.protocol.ToolResult.errorWithUnknownMutationOutcome("Unattended database update did not complete: "
            + detail + ". Close active infobase sessions and inspect the database. "
            + "The operation may still run; do not submit another update or assume rollback.")
            .put("outcome", "unknown").toJson();
    }

    public static ApplicationUpdateState update(IApplication application, ApplicationUpdateType type,
        ExecutionContext context, IProgressMonitor monitor)
    {
        ExternalInfobaseChangesPolicy policy = context.<ExternalInfobaseChangesPolicy>getProperty(EXTERNAL_CHANGES)
            .orElse(ExternalInfobaseChangesPolicy.DEFAULT);
        return update(ServiceAccess.get(IInfobaseSynchronizationManager.class), application, type, policy, monitor);
    }

    static ApplicationUpdateState update(IInfobaseSynchronizationManager manager, IApplication target,
        ApplicationUpdateType type, ExternalInfobaseChangesPolicy policy, IProgressMonitor monitor)
    {
        InfobaseReference reference = InfobaseAccessSupport.resolveInfobaseReference(target);
        if (reference == null)
            throw new ApplicationException("No infobase reference resolves for application '" + target.getName()
                + "'. Use get_applications to select a bound infobase or server application.");
        IInfobaseUpdateCallback callback = callback(policy);
        IStatus status = type == ApplicationUpdateType.FULL
            ? manager.reloadInfobase(target.getProject(), reference, callback, false, monitor)
            : manager.updateInfobase(target.getProject(), reference, callback, false, monitor);
        if (status.matches(IStatus.ERROR | IStatus.CANCEL))
        {
            throw new ApplicationException("Unattended update failed: "
                + PlatformFailures.describeStatus(status)
                + ". Close active infobase sessions and retry update_database; inspect the project and database "
                + "before retrying an interrupted update.");
        }
        return ApplicationUpdateState.UPDATED;
    }

    static IInfobaseUpdateCallback callback(ExternalInfobaseChangesPolicy policy)
    {
        return new IInfobaseUpdateCallback()
        {
            @Override public boolean onConfirm(IProject project, InfobaseReference infobase,
                List<IDbStructureChange> changes, IProgressMonitor monitor)
            {
                return true;
            }

            @Override public InfobaseConflictResolution resolveInfobaseChanges(IProject project,
                InfobaseReference infobase, Set<EObject> added, Set<EObject> modified, Set<String> removed,
                IInfobaseConfigurationChange change, IInfobaseUpdateConflictResolver resolver,
                IConflictResolveAssist assist, IInfobaseSynchronizationFlow flow, IProgressMonitor monitor)
                throws InfobaseSynchronizationException
            {
                if (policy == ExternalInfobaseChangesPolicy.CANCEL)
                {
                    throw new InfobaseSynchronizationException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "External infobase changes were detected; externalInfobaseChanges=cancel stopped the update. "
                            + "Choose import to retain them or override to discard them, then retry update_database."));
                }
                if (policy == ExternalInfobaseChangesPolicy.IMPORT)
                {
                    return resolver.resolveConflict(project, infobase, added, modified, removed, change,
                        assist, flow, monitor);
                }
                if (!(flow instanceof IUpdateInfobaseFlow update))
                {
                    throw new InfobaseSynchronizationException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "This synchronization flow cannot override external changes. Use import or windowed EDT."));
                }
                return new InfobaseConflictResolution(resolver.overrideConflict(project, infobase, added,
                    modified, removed, change, assist, update, monitor));
            }
        };
    }
}
