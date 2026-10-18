/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.Objects;
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
    private IApplication previous;
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
                    change.previous = value.get();
                    change.captured = change.previousId.equals(change.previous.getId());
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
        UNVERIFIED
    }

    /** Use the raw ID before dissociation; resolving a default here can erase the evidence. */
    public Restoration restore()
    {
        if (ownedId == null)
        {
            return Restoration.NOT_CHANGED;
        }
        try (var coordination = InfobaseMutationCoordinator.acquire())
        {
            if (!ownedId.equals(rawId.get()))
            {
                return Restoration.PRESERVED_OTHER;
            }
            // Public SDK has no compare-and-set against independent GUI edits. This check/write
            // is serialized with explicit MCP default writes only; SDK lookups can also clear
            // a stale default. Do not claim atomic GUI or implicit-SDK-write safety.
            manager.setDefaultApplication(project, previous);
            return Objects.equals(previousId, rawId.get()) ? Restoration.RESTORED : Restoration.UNVERIFIED;
        }
        catch (Exception failure)
        {
            return Restoration.UNVERIFIED;
        }
    }
}
