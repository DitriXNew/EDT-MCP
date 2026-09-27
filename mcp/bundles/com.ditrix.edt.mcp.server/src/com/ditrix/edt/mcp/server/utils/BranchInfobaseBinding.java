/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.IApplication;

/**
 * Checks that an application's infobase is bound to the project's CURRENT Git branch context
 * before a configuration update writes into it.
 *
 * <p>Infobase applications are listed from the current context's association, so they follow a
 * branch switch; standalone-server applications are listed from the server modules and do not.
 * The platform's update path does not consult the association either. "The current context" is
 * whatever {@link IInfobaseAssociationManager#getAssociation(IProject)} resolves: the checked-out
 * branch for a Git-shared project, the default context otherwise.
 *
 * <p>The target counts as bound when its UUID is bound, or when it and a bound FILE infobase
 * use the same existing database directory (file-system identity) (a standalone server registered over an existing file
 * infobase carries its own UUID). Anything that cannot be proven the same database is refused.
 */
public final class BranchInfobaseBinding
{
    /** Deadline for one directory-identity probe. */
    static final long PROBE_TIMEOUT_MS = 10_000L;

    private BranchInfobaseBinding()
    {
    }

    /**
     * Returns a refusal message when {@code application}'s infobase is not among the infobases bound
     * to the project's current branch context, or {@code null} when the update may proceed. No
     * binding recorded for the context is not a conflict.
     *
     * @param project the application's project (never {@code null})
     * @param application the resolved update target (never {@code null})
     * @param applicationId the id the caller addressed, named in the message
     * @return the refusal text, or {@code null}
     */
    public static String refusalOrNull(IProject project, IApplication application, String applicationId)
    {
        return refusalOrNull(Activator.getDefault().getInfobaseAssociationManager(), project, application,
            applicationId);
    }

    /** {@link #refusalOrNull(IProject, IApplication, String)} with the manager supplied (unit-testable). */
    static String refusalOrNull(IInfobaseAssociationManager assocManager, IProject project,
        IApplication application, String applicationId)
    {
        if (assocManager == null)
        {
            return unreadable(project.getName(), "IInfobaseAssociationManager service is not available"); //$NON-NLS-1$
        }
        try
        {
            Optional<IInfobaseAssociation> association = assocManager.getAssociation(project);
            Collection<InfobaseReference> bound =
                association.map(IInfobaseAssociation::getInfobases).orElse(null);
            if (bound == null || bound.isEmpty())
            {
                return null;
            }
            InfobaseReference target = InfobaseAccessSupport.resolveInfobaseReference(application);
            Path targetDir = fileDirOf(target);
            if (targetDir == null)
            {
                targetDir = servedDatabaseDirOf(application);
            }
            return decide(association, target, targetDir, project.getName(), application.getName(),
                applicationId);
        }
        catch (RuntimeException e)
        {
            Activator.logError("update_database: reading the branch binding of project '" //$NON-NLS-1$
                + project.getName() + "' failed", e); //$NON-NLS-1$
            return unreadable(project.getName(), String.valueOf(e.getMessage()));
        }
    }

    /**
     * The decision itself, over already-read values (unit-testable without a live platform).
     *
     * @param association the current context's association (may be empty)
     * @param target the target application's infobase, or {@code null} when it cannot be resolved
     * @param targetDir the on-disk database directory the target uses, or {@code null} when unknown
     *            or not file-backed
     * @param projectName the project named in the message
     * @param applicationName the target application's display name
     * @param applicationId the id the caller addressed
     * @return the refusal text, or {@code null} when the target is bound or nothing is bound
     */
    static String decide(Optional<IInfobaseAssociation> association, InfobaseReference target,
        Path targetDir, String projectName, String applicationName, String applicationId)
    {
        return decide(association, target, targetDir, projectName, applicationName, applicationId,
            BranchInfobaseBinding::sameExistingDirectory, PROBE_TIMEOUT_MS);
    }

    /** {@link #decide} with the directory probe and its deadline supplied (unit-testable). */
    static String decide(Optional<IInfobaseAssociation> association, InfobaseReference target,
        Path targetDir, String projectName, String applicationName, String applicationId,
        DirectoryProbe probe, long probeTimeoutMs)
    {
        Collection<InfobaseReference> bound = association.map(IInfobaseAssociation::getInfobases).orElse(null);
        List<String> boundNames = new ArrayList<>();
        boolean targetBound = false;
        String unansweredProbe = null;
        UUID targetUuid = target == null ? null : target.getUuid();
        if (bound != null)
        {
            for (InfobaseReference ib : bound)
            {
                if (ib == null)
                {
                    continue;
                }
                boundNames.add(ib.getName());
                if (targetUuid != null && targetUuid.equals(ib.getUuid()))
                {
                    targetBound = true;
                }
            }
            // Directory probes can block on a dead share; run them only when no UUID matched.
            for (InfobaseReference ib : bound)
            {
                if (ib != null && targetDir != null && !targetBound)
                {
                    Path boundDir = fileDirOf(ib);
                    Boolean same = boundDir == null ? Boolean.FALSE
                        : probeBounded(probe, targetDir, boundDir, probeTimeoutMs);
                    if (same == null)
                    {
                        unansweredProbe = "'" + targetDir + "' and bound infobase '" + ib.getName() //$NON-NLS-1$ //$NON-NLS-2$
                            + "' ('" + boundDir + "')"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else if (same.booleanValue())
                    {
                        targetBound = true;
                    }
                }
            }
        }
        if (boundNames.isEmpty() || targetBound)
        {
            return null;
        }
        if (unansweredProbe != null)
        {
            return "Refusing to update: the file system did not answer within " + probeTimeoutMs //$NON-NLS-1$
                + " ms whether " + unansweredProbe + " are the same database directory, so it is " //$NON-NLS-1$ //$NON-NLS-2$
                + "unknown whether the target belongs to the current Git branch of project '" //$NON-NLS-1$
                + projectName + "'. Nothing was updated. Retry once the path is reachable, or re-call " //$NON-NLS-1$
                + "with ignoreBranchBinding=true if this target is intended."; //$NON-NLS-1$
        }
        String targetDescription = target == null
            ? "an infobase that could not be resolved" //$NON-NLS-1$
            : "infobase '" + target.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return "Refusing to update: application '" + applicationName + "' (" + applicationId //$NON-NLS-1$ //$NON-NLS-2$
            + ") targets " + targetDescription + ", which is not bound to the current Git branch of " //$NON-NLS-1$ //$NON-NLS-2$
            + "project '" + projectName + "' (bound: " + String.join(", ", boundNames) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "). The update would write this branch's configuration into another branch's " //$NON-NLS-1$
            + "database. Nothing was updated. Update an application of a bound infobase instead " //$NON-NLS-1$
            + "(get_applications lists them), bind this infobase to the current branch in EDT, or " //$NON-NLS-1$
            + "re-call with ignoreBranchBinding=true if this target is intended."; //$NON-NLS-1$
    }

    /**
     * File-system identity of two EXISTING directories ({@link Files#isSameFile}); a missing path is
     * "not proven", never a match. Unbounded: call it through {@link #probeBounded}.
     */
    static boolean sameExistingDirectory(Path a, Path b) throws IOException
    {
        if (a == null || b == null || !Files.isDirectory(a) || !Files.isDirectory(b))
        {
            return false;
        }
        return Files.isSameFile(a, b);
    }

    /**
     * Runs a directory probe under a deadline: a network path can hang the file system call.
     *
     * @return {@code TRUE}/{@code FALSE} when the probe answered ({@code FALSE} also when it
     *         failed - not proven), {@code null} when it did not answer in time
     */
    static Boolean probeBounded(DirectoryProbe probe, Path a, Path b, long timeoutMs)
    {
        AtomicReference<Boolean> answer = new AtomicReference<>();
        BoundedJob.Result result = BoundedJob.run("update_database: compare database directories", //$NON-NLS-1$
            timeoutMs, monitor -> answer.set(Boolean.valueOf(probe.same(a, b))));
        // A probe that published its answer at the deadline has still answered.
        Boolean published = answer.get();
        if (published != null)
        {
            return published;
        }
        return result.getOutcome() == BoundedJob.Outcome.COMPLETED ? Boolean.FALSE : null;
    }

    /** File-system identity of two directories; may block on an unreachable path. */
    @FunctionalInterface
    interface DirectoryProbe
    {
        boolean same(Path a, Path b) throws IOException;
    }

    /** The directory of a FILE infobase reference, or {@code null} for any other or unreadable one. */
    static Path fileDirOf(InfobaseReference ref)
    {
        if (ref == null)
        {
            return null;
        }
        try
        {
            IConnectionString cs = ref.getConnectionString();
            if (cs instanceof FileConnectionString)
            {
                String file = ((FileConnectionString)cs).getFile();
                if (file != null && !file.trim().isEmpty())
                {
                    return Paths.get(file.trim());
                }
            }
        }
        catch (RuntimeException e) // NOSONAR an unreadable reference proves nothing - no match
        {
            return null;
        }
        return null;
    }

    /** The served database directory of a file-backed standalone server application, else {@code null}. */
    private static Path servedDatabaseDirOf(IApplication application)
    {
        try
        {
            Object module = StandaloneServerSupport.moduleOfApplication(application);
            String dir = module == null ? null : StandaloneServerSupport.databaseDirOrThrow(module);
            return dir == null || dir.trim().isEmpty() ? null : Paths.get(dir.trim());
        }
        catch (Exception e) // NOSONAR an unknown directory proves nothing - the UUID rule alone decides
        {
            return null;
        }
    }

    private static String unreadable(String projectName, String reason)
    {
        return "Refusing to update: the infobase binding of the current Git branch of project '" //$NON-NLS-1$
            + projectName + "' could not be read (" + reason + "), so it is unknown whether the " //$NON-NLS-1$ //$NON-NLS-2$
            + "target belongs to this branch. Nothing was updated. Retry, or re-call with " //$NON-NLS-1$
            + "ignoreBranchBinding=true if this target is intended."; //$NON-NLS-1$
    }
}
