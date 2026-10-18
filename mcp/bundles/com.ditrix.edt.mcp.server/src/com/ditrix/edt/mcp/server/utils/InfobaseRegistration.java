/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

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
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
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
        synchronized (InfobaseMutationCoordinator.LOCK)
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
        try (var deletion = InfobaseMutationCoordinator.beginDeletion(reference.getUuid()))
        {
            remove(manager, Identity.of(reference), () -> { }, deletion);
        }
    }

    private static void remove(IInfobaseManager manager, Identity expected, Runnable contextGuard,
            InfobaseMutationCoordinator.Deletion deletion) throws Exception
    {
        synchronized (InfobaseMutationCoordinator.LOCK)
        {
            reload(manager);
            var current = manager.findInfobaseByUuid(expected.id());
            if (current.isPresent())
            {
                expected.requireMatch(current.get());
                contextGuard.run();
                deletion.beforeRemoval();
                manager.delete(current.get());
            }
            reload(manager);
            if (manager.findInfobaseByUuid(expected.id()).isPresent())
            {
                throw new IllegalStateException("EDT did not remove the launcher entry from disk."); //$NON-NLS-1$
            }
        }
    }

    private static boolean containsAddress(List<Section> sections, InfobaseReference reference)
    {
        for (Section section : sections)
        {
            if (section instanceof InfobaseReference existing
                && existing.getConnectionString() != null
                && sameAddress(reference, existing))
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

    private static boolean sameAddress(InfobaseReference first, InfobaseReference second)
    {
        if (first.getConnectionString() instanceof FileConnectionString left
            && second.getConnectionString() instanceof FileConnectionString right)
        {
            Path leftPath = Path.of(left.getFile()).toAbsolutePath().normalize();
            Path rightPath = Path.of(right.getFile()).toAbsolutePath().normalize();
            try
            {
                return Files.isSameFile(leftPath, rightPath);
            }
            catch (java.io.IOException e)
            {
                return leftPath.equals(rightPath);
            }
        }
        return first.getConnectionString() instanceof ServerConnectionString
            && second.getConnectionString() instanceof ServerConnectionString
            && first.getConnectionString().asConnectionString().equalsIgnoreCase(
                second.getConnectionString().asConnectionString());
    }

    /** Explicit global-list target: UUID and name must both match; never fall back to a name. */
    public static InfobaseReference findRegistration(IInfobaseManager manager, UUID registrationId,
            String name) throws Exception
    {
        synchronized (InfobaseMutationCoordinator.LOCK)
        {
            reload(manager);
            InfobaseReference reference = manager.findInfobaseByUuid(registrationId).orElseThrow(() ->
                new IllegalArgumentException("registrationId was not found; inspect the EDT launcher list. " //$NON-NLS-1$
                    + "Do not substitute a same-named registration.")); //$NON-NLS-1$
            if (!name.equals(reference.getName())
                || !(reference.getConnectionString() instanceof FileConnectionString
                    || reference.getConnectionString() instanceof ServerConnectionString))
            {
                throw new IllegalArgumentException("registrationId does not match infobaseName or a file/cluster entry; " //$NON-NLS-1$
                    + "inspect the EDT launcher list before retrying."); //$NON-NLS-1$
            }
            return reference;
        }
    }

    /** Preserve the UUID handle if credential removal fails, or the selected entry changes. */
    public static void cleanupRegistration(IInfobaseManager manager, UUID registrationId, String name,
            String connection, CredentialCleanup credentials, Runnable contextGuard) throws Exception
    {
        Identity expected = new Identity(registrationId, name, connection);
        try (var deletion = InfobaseMutationCoordinator.beginDeletion(registrationId))
        {
            InfobaseReference reference = findRegistration(manager, registrationId, name);
            expected.requireMatch(reference);
            credentials.clear(reference, credentialGuard(manager, expected, contextGuard, deletion));
            remove(manager, expected, contextGuard, deletion);
        }
    }

    /** Runs in the credential worker, under its UUID stripe and our private coordinator. */
    private static InfobaseAccessSupport.CredentialWriteGuard credentialGuard(IInfobaseManager manager,
            Identity expected, Runnable contextGuard, InfobaseMutationCoordinator.Deletion deletion)
    {
        return write -> {
            synchronized (InfobaseMutationCoordinator.LOCK)
            {
                deletion.requireActive();
                InfobaseReference current = findRegistration(manager, expected.id(), expected.name());
                expected.requireMatch(current);
                contextGuard.run();
                // Our launcher calls cannot replace this reference before the native write.
                // Independent GUI edits remain subject to the SDK's lack of a conditional write.
                write.run(current);
            }
        };
    }

    private record Identity(UUID id, String name, String connection)
    {
        static Identity of(InfobaseReference reference)
        {
            return new Identity(reference.getUuid(), reference.getName(),
                reference.getConnectionString().asConnectionString());
        }

        void requireMatch(InfobaseReference reference)
        {
            if (!id.equals(reference.getUuid()) || !name.equals(reference.getName())
                || reference.getConnectionString() == null
                || !connection.equals(reference.getConnectionString().asConnectionString()))
            {
                throw new IllegalStateException("The registration changed after preview; request a new preview. Nothing was removed."); //$NON-NLS-1$
            }
        }
    }

    @FunctionalInterface
    public interface CredentialCleanup
    {
        void clear(InfobaseReference reference, InfobaseAccessSupport.CredentialWriteGuard guard) throws Exception;
    }

    public record Rollback(boolean associationRemoved, boolean registrationRemoved,
            boolean credentialsRemoved)
    {
        public String message()
        {
            return "Rollback: project association " + outcome(associationRemoved) //$NON-NLS-1$
                + ", launcher registration " + outcome(registrationRemoved) //$NON-NLS-1$
                + ", connection credentials " + outcome(credentialsRemoved) //$NON-NLS-1$
                + (registrationRemoved ? "." : credentialsRemoved //$NON-NLS-1$
                    ? ". Launcher removal could not be verified; inspect the launcher and retry delete_infobase with its registrationId and infobaseName if still present." //$NON-NLS-1$
                    : ". The registration is retained: retry delete_infobase with its registrationId and infobaseName after fixing cleanup.") //$NON-NLS-1$
                + " The existing database is untouched."; //$NON-NLS-1$
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
        boolean credentialsRemoved = false;
        Identity expected = Identity.of(reference);
        try
        {
            associations.dissociate(project, reference, context);
            associationRemoved = true;
        }
        catch (Exception e)
        {
            Activator.logWarning("create_infobase: association rollback failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
        }
        boolean[] cleared = {credentialsRemoved};
        try
        {
            cleanupRegistration(infobases, expected.id(), expected.name(), expected.connection(),
                (target, guard) -> {
                    CredentialCleanup cleanup = credentials == null ? InfobaseAccessSupport::clearCredentials : credentials;
                    cleanup.clear(target, guard);
                    cleared[0] = true;
                }, () -> { });
            registrationRemoved = true;
        }
        catch (Exception e)
        {
            Activator.logWarning("create_infobase: registration/credential rollback failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName());
        }
        credentialsRemoved = cleared[0];
        return new Rollback(associationRemoved, registrationRemoved, credentialsRemoved);
    }
}
