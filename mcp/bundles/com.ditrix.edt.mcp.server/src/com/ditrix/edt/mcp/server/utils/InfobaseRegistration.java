/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.Group;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.git.BranchContexts;

/** Registration ownership, current-context binding and compensation for a fresh reference. */
public final class InfobaseRegistration
{
    private InfobaseRegistration()
    {
    }

    /** Use the same provider EDT's application reader uses, including detached HEAD contexts. */
    public static InfobaseAssociationContext currentContext(IProject project)
    {
        IInfobaseAssociationContextProvider provider =
            Activator.getDefault().getInfobaseAssociationContextProvider();
        if (provider == null)
        {
            throw new IllegalStateException("IInfobaseAssociationContextProvider is unavailable."); //$NON-NLS-1$
        }
        return currentContext(provider, project);
    }

    static InfobaseAssociationContext currentContext(IInfobaseAssociationContextProvider provider,
            IProject project)
    {
        InfobaseAssociationContext context = provider.get(project);
        if (context == null)
        {
            throw new IllegalStateException("EDT returned no current infobase context."); //$NON-NLS-1$
        }
        return BranchContexts.isBranchContext(context)
            ? BranchContexts.toContext(context.getContext().orElseThrow()) : context;
    }

    public static void associate(IInfobaseAssociationManager manager, IProject project,
            InfobaseReference reference, InfobaseAssociationContext context)
    {
        manager.associate(project, reference, InfobaseAssociationSettings.notSynchronized(context));
    }

    /** Check the entire launcher tree, serializing this tool's check/add pairs. */
    public static String add(IInfobaseManager manager, InfobaseReference reference)
    {
        synchronized (manager)
        {
            try
            {
                reload(manager);
            }
            catch (Exception failure)
            {
                return "Could not read the current EDT launcher list (" + failure.getClass().getSimpleName() //$NON-NLS-1$
                    + "). Check launcher-list access and retry; no registration was added."; //$NON-NLS-1$
            }
            if (containsAddress(manager.getAll(), reference))
            {
                return "This infobase address is already registered in the EDT launcher list. " //$NON-NLS-1$
                    + "Use its existing application from get_applications, or bind the existing " //$NON-NLS-1$
                    + "launcher entry in EDT; no duplicate registration was added."; //$NON-NLS-1$
            }
            try
            {
                manager.add(reference, null);
                reload(manager);
                if (manager.findInfobaseByUuid(reference.getUuid()).isEmpty())
                {
                    throw new IllegalStateException("EDT did not persist the new launcher entry."); //$NON-NLS-1$
                }
                return null;
            }
            catch (Exception failure)
            {
                boolean removed = false;
                try
                {
                    remove(manager, reference);
                    removed = true;
                }
                catch (Exception cleanupFailure)
                {
                    Activator.logWarning("create_infobase: failed-add rollback could not be verified: " //$NON-NLS-1$
                        + cleanupFailure.getClass().getSimpleName());
                }
                return "Could not persist the new infobase registration (" + failure.getClass().getSimpleName() //$NON-NLS-1$
                    + "). Check EDT launcher-list write permissions. Rollback: new launcher entry " //$NON-NLS-1$
                    + (removed ? "removed." : "could NOT be removed; inspect EDT settings before retrying."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /** EDT save swallows write errors; reloading and checking status proves the on-disk state. */
    private static void reload(IInfobaseManager manager) throws Exception
    {
        manager.reload(null);
        IStatus status = manager.getLoadStatus();
        if (status == null || status.matches(IStatus.ERROR | IStatus.CANCEL))
        {
            throw new IllegalStateException("The EDT launcher list could not be read back."); //$NON-NLS-1$
        }
    }

    /** Delete the reloaded object with the requested UUID, then verify persistence. */
    public static void remove(IInfobaseManager manager, InfobaseReference reference) throws Exception
    {
        synchronized (manager)
        {
            reload(manager);
            var current = manager.findInfobaseByUuid(reference.getUuid());
            if (current.isPresent())
            {
                manager.delete(current.get());
            }
            reload(manager);
            if (manager.findInfobaseByUuid(reference.getUuid()).isPresent())
            {
                throw new IllegalStateException("EDT did not remove the launcher entry from disk."); //$NON-NLS-1$
            }
        }
    }

    private static boolean containsAddress(List<Section> sections, InfobaseReference reference)
    {
        String connection = reference.getConnectionString().asConnectionString();
        for (Section section : sections)
        {
            if (section instanceof InfobaseReference existing
                && existing.getConnectionString() != null
                && connection.equalsIgnoreCase(existing.getConnectionString().asConnectionString()))
            {
                return true;
            }
            if (section instanceof Group group && containsAddress(group.getSubsections(), reference))
            {
                return true;
            }
        }
        return false;
    }

    /** Resolve an explicit launcher-only server deletion without relying on application visibility. */
    public static Optional<InfobaseReference> findUniqueServer(IInfobaseManager manager, String name)
            throws Exception
    {
        reload(manager);
        List<InfobaseReference> matches = new ArrayList<>();
        collectNamed(manager.getAll(), name, matches);
        if (matches.size() > 1)
        {
            throw new IllegalArgumentException("Multiple launcher entries have this infobaseName; " //$NON-NLS-1$
                + "select and remove the intended entry in EDT instead."); //$NON-NLS-1$
        }
        return matches.isEmpty() || !(matches.get(0).getConnectionString() instanceof ServerConnectionString)
            ? Optional.empty() : Optional.of(matches.get(0));
    }

    private static void collectNamed(List<Section> sections, String name, List<InfobaseReference> matches)
    {
        for (Section section : sections)
        {
            if (section instanceof InfobaseReference reference && name.equals(reference.getName()))
            {
                matches.add(reference);
            }
            if (section instanceof Group group)
            {
                collectNamed(group.getSubsections(), name, matches);
            }
        }
    }

    @FunctionalInterface
    public interface CredentialCleanup
    {
        void clear(InfobaseReference reference) throws Exception;
    }

    public record Rollback(boolean associationRemoved, boolean registrationRemoved,
            boolean credentialsRemoved)
    {
        public String message()
        {
            return "Rollback: project association " + outcome(associationRemoved) //$NON-NLS-1$
                + ", launcher registration " + outcome(registrationRemoved) //$NON-NLS-1$
                + ", connection credentials " + outcome(credentialsRemoved) //$NON-NLS-1$
                + ". The existing database is untouched."; //$NON-NLS-1$
        }

        public ToolResult put(ToolResult result)
        {
            return result.put("rollbackCompleted", associationRemoved && registrationRemoved && credentialsRemoved) //$NON-NLS-1$
                .put("associationRemoved", associationRemoved) //$NON-NLS-1$
                .put("registrationRemoved", registrationRemoved) //$NON-NLS-1$
                .put("credentialsRemoved", credentialsRemoved); //$NON-NLS-1$
        }

        private static String outcome(boolean removed)
        {
            return removed ? "removed" : "could NOT be removed; inspect EDT settings before retrying"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Only compensate objects owned by this call; never delete database files. */
    public static Rollback rollback(IInfobaseAssociationManager associations, IInfobaseManager infobases,
            IProject project, InfobaseReference reference, InfobaseAssociationContext context,
            CredentialCleanup credentials)
    {
        boolean associationRemoved = false;
        boolean registrationRemoved = false;
        boolean credentialsRemoved = credentials == null;
        try
        {
            associations.dissociate(project, reference, context);
            associationRemoved = true;
        }
        catch (Exception e)
        {
            Activator.logWarning("create_infobase: association rollback failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
        }
        if (credentials != null)
        {
            try
            {
                credentials.clear(reference);
                credentialsRemoved = true;
            }
            catch (Exception e)
            {
                Activator.logWarning("create_infobase: credential rollback failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
            }
        }
        try
        {
            remove(infobases, reference);
            registrationRemoved = true;
        }
        catch (Exception e)
        {
            Activator.logWarning("create_infobase: registration rollback failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
        }
        return new Rollback(associationRemoved, registrationRemoved, credentialsRemoved);
    }
}
