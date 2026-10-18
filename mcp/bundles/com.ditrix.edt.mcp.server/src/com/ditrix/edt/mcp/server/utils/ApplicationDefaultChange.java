/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/** Owns one project-global default write so failed creation can compensate before dissociation. */
public final class ApplicationDefaultChange
{
    private final IApplicationManager manager;
    private final IProject project;
    private final Supplier<String> rawId;
    private String previousId;
    private boolean captured;
    private String ownedId;

    private ApplicationDefaultChange(IApplicationManager manager, IProject project, Supplier<String> rawId)
    {
        this.manager = manager;
        this.project = project;
        this.rawId = rawId;
    }

    public static ApplicationDefaultChange capture(IApplicationManager manager, IProject project)
    {
        // EDT 2026.1/2026.2 persist this project-global key. Read ONLY: native writes must update
        // EDT's private cache and notify listeners. The public default getter can clear this key
        // after a branch switch, or substitute an implicit single application, so it is not used.
        return capture(manager, project, () -> InstanceScope.INSTANCE
            .getNode("com.e1c.g5.dt.applications").node("applicationDefaults").node("applications") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get(project.getName(), null));
    }

    public static ApplicationDefaultChange capture(IApplicationManager manager, IProject project,
            Supplier<String> rawId)
    {
        var change = new ApplicationDefaultChange(manager, project, rawId);
        try
        {
            change.previousId = rawId.get();
            if (change.previousId == null)
            {
                change.captured = true;
            }
            else
            {
                var lookup = ApplicationSupport.getApplicationBounded(manager, project,
                    change.previousId, ApplicationSupport.LOOKUP_TIMEOUT_MS);
                var value = lookup.concluded() ? lookup.valueOrRethrow() : null;
                if (value != null && value.isPresent())
                {
                    change.captured = change.previousId.equals(value.get().getId());
                }
            }
        }
        catch (Exception unavailable)
        {
            // Optional setDefault must not replace a value that cannot be restored.
            change.captured = false;
        }
        return change;
    }

    /** Serializes MCP default writes with registration/credential mutations, never an SDK monitor. */
    public static void write(IApplicationManager manager, IProject project, IApplication application)
            throws Exception
    {
        try (var coordination = InfobaseMutationCoordinator.acquire())
        {
            manager.setDefaultApplication(project, application);
        }
    }

    /** A non-null note means the optional setting was skipped or failed. */
    public String apply(IApplication application, Runnable contextGuard)
    {
        if (!captured)
        {
            return " setDefault was not applied: the previous explicit default could not be captured; inspect get_applications and set it manually."; //$NON-NLS-1$
        }
        try (var coordination = InfobaseMutationCoordinator.acquire())
        {
            String current;
            try
            {
                current = rawId.get();
            }
            catch (Exception unavailable)
            {
                return " setDefault was not applied: the stored default could not be verified; set it manually."; //$NON-NLS-1$
            }
            if (!Objects.equals(previousId, current))
            {
                return " setDefault was not applied: the default changed during creation; the newer choice was kept."; //$NON-NLS-1$
            }
            contextGuard.run();
            String id = application.getId();
            if (id == null)
            {
                return " setDefault was not applied: the new application has no ID; inspect get_applications."; //$NON-NLS-1$
            }
            // Record BEFORE entry: EDT may persist the preference before notifying listeners fails.
            ownedId = id;
            try
            {
                manager.setDefaultApplication(project, application);
                return null;
            }
            catch (Exception failure)
            {
                return " setDefault failed; inspect get_applications and set it manually."; //$NON-NLS-1$
            }
        }
        catch (InfobaseMutationCoordinator.Unavailable unavailable)
        {
            return " setDefault was not applied: " + unavailable.getMessage(); //$NON-NLS-1$
        }
    }

    public enum Restoration
    {
        NOT_CHANGED,
        RESTORED,
        PRESERVED_OTHER,
        PENDING,
        UNVERIFIED
    }

    /** Use the raw ID before dissociation; resolving a default here can erase the evidence. */
    public Restoration restore()
    {
        return restore(ApplicationSupport.LOOKUP_TIMEOUT_MS);
    }

    Restoration restore(long timeoutMs)
    {
        if (ownedId == null)
        {
            return Restoration.NOT_CHANGED;
        }
        // Resolution may block: the caller never waits for a worker under the coordinator.
        // The worker serializes explicit default/launcher writes and revalidates liveness.
        // Association changes and GUI edits still have no atomic public SDK boundary here.
        var restored = new AtomicReference<>(Restoration.UNVERIFIED);
        var admission = new CredentialWriteControl();
        try (admission)
        {
            var result = BoundedJob.runWithCancellation("Restore explicit EDT application default", //$NON-NLS-1$
                timeoutMs, monitor -> {
                    try (var coordination = InfobaseMutationCoordinator.acquire())
                    {
                        if (!ownedId.equals(rawId.get()))
                        {
                            restored.set(Restoration.PRESERVED_OTHER);
                            return;
                        }
                        IApplication livePrevious = null;
                        if (previousId != null)
                        {
                            var found = manager.getApplication(project, previousId);
                            if (found.isEmpty() || !previousId.equals(found.get().getId()))
                            {
                                return;
                            }
                            livePrevious = found.get();
                        }
                        // The lookup can block while a GUI choice or an implicit SDK clear occurs.
                        if (!ownedId.equals(rawId.get()))
                        {
                            restored.set(Restoration.PRESERVED_OTHER);
                            return;
                        }
                        if (monitor.isCanceled())
                        {
                            return;
                        }
                        try (var permit = admission.tryBeginWrite())
                        {
                            if (permit == null)
                            {
                                return;
                            }
                            // No public CAS against GUI edits/implicit SDK clears is available.
                            manager.setDefaultApplication(project, livePrevious);
                        }
                        // A raw preference alone does not prove that its application still exists.
                        if (previousId != null)
                        {
                            var found = manager.getApplication(project, previousId);
                            if (found.isEmpty() || !previousId.equals(found.get().getId()))
                            {
                                return;
                            }
                        }
                        if (Objects.equals(previousId, rawId.get()))
                        {
                            restored.set(Restoration.RESTORED);
                        }
                    }
                }, admission::close);
            // Close admission before reading pending, as with bounded credential writes. A
            // native call that already owns a permit can still commit after this response.
            admission.close();
            if (admission.writeInProgress())
            {
                return Restoration.PENDING;
            }
            return result.isSuccess() ? restored.get() : Restoration.UNVERIFIED;
        }
        catch (Exception failure)
        {
            return admission.writeInProgress() ? Restoration.PENDING : Restoration.UNVERIFIED;
        }
    }
}
