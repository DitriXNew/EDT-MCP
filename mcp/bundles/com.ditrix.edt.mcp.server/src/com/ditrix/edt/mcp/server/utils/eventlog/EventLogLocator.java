/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Locates the {@code 1Cv8Log} directory of an infobase so that {@code get_event_log} can read it,
 * WITHOUT running the infobase.
 *
 * <p>Resolution is reflective and reuses the project/infobase model the other application tools use
 * ({@link ApplicationSupport#resolveManager(String)} &rarr; {@link IApplicationManager#getApplications}
 * &rarr; filter {@link IInfobaseApplication} &rarr; {@link IInfobaseApplication#getInfobase()}), NOT a
 * hand-rolled workspace walk nor a global infobase registry. It mirrors the FILE-infobase directory
 * resolution used by {@code delete_infobase}
 * ({@code getConnectionString() instanceof FileConnectionString} &rarr; {@code getFile()}), then
 * appends {@code 1Cv8Log}.
 *
 * <p>Cases:
 * <ul>
 * <li><b>logDir override</b> ({@link #TYPE_OVERRIDE}) &mdash; an explicit directory short-circuits all
 *     resolution ({@code projectName}/{@code applicationId} are ignored); used for off-host / offline
 *     copies and for SERVER-mode infobases.</li>
 * <li><b>FILE</b> ({@link #TYPE_FILE}) &mdash; resolves {@code <connectionString.file>/1Cv8Log}.</li>
 * <li><b>SERVER</b> &mdash; the log lives inside the 1C cluster data directory ({@code srvinfo}) on the
 *     server host and is NOT derivable from the connection string, so this returns an actionable error
 *     asking the caller to pass {@code logDir} (no default {@code srvinfo} paths are guessed).</li>
 * <li><b>&gt;1 infobase, no applicationId</b> &mdash; an ambiguity error listing the candidate ids.</li>
 * <li><b>no {@code 1Cv8Log}</b> &mdash; an error naming the resolved path.</li>
 * </ul>
 *
 * <p>The locator returns an EXISTING {@code 1Cv8Log} directory; distinguishing the legacy text format
 * from a modern SQLite ({@code .lgd}) log is the reader's job ({@code EventLogReader} throws an
 * actionable {@code EventLogException} for an unsupported log), keeping format detection in one place.
 *
 * <p><b>PII:</b> the located log contains personal data (user names, data presentations). This class
 * only resolves the path; the reader/tool is the {@code returnsInfobaseData} surface.
 */
public final class EventLogLocator
{
    /** The 1C event-log directory name that lives beside a file infobase's data. */
    public static final String LOG_DIR_NAME = "1Cv8Log"; //$NON-NLS-1$

    /** Echoed {@code infobaseType} for a log resolved from a FILE infobase. */
    public static final String TYPE_FILE = "FILE"; //$NON-NLS-1$

    /** Echoed {@code infobaseType} for a log supplied directly via a {@code logDir} override. */
    public static final String TYPE_OVERRIDE = "OVERRIDE"; //$NON-NLS-1$

    private EventLogLocator()
    {
    }

    /**
     * Resolves the {@code 1Cv8Log} directory to read.
     *
     * @param projectName the EDT configuration project the infobase belongs to (required unless
     *            {@code logDir} is given)
     * @param applicationId the infobase application id from {@code get_applications}; required only to
     *            disambiguate when the project has more than one infobase
     * @param logDir an explicit {@code 1Cv8Log} directory that short-circuits all resolution (may be
     *            {@code null}/blank)
     * @return a {@link Resolution}: on success the resolved directory + infobase-type tag; otherwise an
     *         actionable error JSON via {@link Resolution#getError()}
     */
    public static Resolution resolve(String projectName, String applicationId, String logDir)
    {
        // An explicit override is a first-class escape hatch (SERVER / off-host / offline copies): it
        // decouples the reader from resolution and skips the project/infobase model entirely.
        if (logDir != null && !logDir.trim().isEmpty())
        {
            return fromLogDirOverride(logDir.trim());
        }

        if (projectName == null || projectName.trim().isEmpty())
        {
            return Resolution.failed(ToolResult.error(
                "projectName is required to locate the event log (or pass logDir to point at a " //$NON-NLS-1$
                    + "1Cv8Log directory directly).").toJson()); //$NON-NLS-1$
        }

        // Reflective resolution: the same open-project + IApplicationManager block the other
        // application tools share (no hand-rolled ResourcesPlugin walk, no global infobase registry).
        ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
        if (!mr.ok())
        {
            return Resolution.failed(mr.errorJson());
        }
        IProject project = mr.project();
        IApplicationManager manager = mr.manager();

        List<IApplication> apps;
        try
        {
            apps = manager.getApplications(project);
        }
        catch (ApplicationException e)
        {
            Activator.logError("get_event_log: could not list applications for project " + projectName, e); //$NON-NLS-1$
            return Resolution.failed(ToolResult.error(
                "Could not list applications for project '" + projectName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage()).toJson());
        }

        SelectResult sel = selectInfobase(apps, applicationId, projectName);
        if (sel.errorJson != null)
        {
            return Resolution.failed(sel.errorJson);
        }
        return fromInfobaseReference(sel.app.getInfobase());
    }

    /**
     * Validates an explicit {@code logDir} override (must be an existing directory). The infobase type
     * is reported as {@link #TYPE_OVERRIDE}.
     */
    static Resolution fromLogDirOverride(String logDir)
    {
        Path dir;
        try
        {
            dir = Paths.get(logDir);
        }
        catch (RuntimeException e)
        {
            return Resolution.failed(ToolResult.error(
                "logDir '" + logDir + "' is not a valid path: " + e.getMessage()).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return validateExistingLogDir(dir, TYPE_OVERRIDE);
    }

    /**
     * Selects the single target infobase application from the project's application list. When an
     * {@code applicationId} is given it is looked up exactly; otherwise a lone infobase is used, an
     * empty list yields a "no infobase / pass logDir" error, and more than one yields an ambiguity
     * error listing the candidate ids.
     */
    static SelectResult selectInfobase(List<IApplication> apps, String applicationId, String projectName)
    {
        List<IInfobaseApplication> infobases = new ArrayList<>();
        if (apps != null)
        {
            for (IApplication app : apps)
            {
                if (app instanceof IInfobaseApplication)
                {
                    infobases.add((IInfobaseApplication)app);
                }
            }
        }

        boolean hasId = applicationId != null && !applicationId.trim().isEmpty();
        if (hasId)
        {
            String wanted = applicationId.trim();
            for (IInfobaseApplication ib : infobases)
            {
                if (wanted.equals(ib.getId()))
                {
                    return SelectResult.of(ib);
                }
            }
            return SelectResult.failed(ToolResult.error(
                "Infobase application '" + wanted + "' not found in project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                    + "'. Use get_applications to list available application IDs.").toJson()); //$NON-NLS-1$
        }

        if (infobases.isEmpty())
        {
            return SelectResult.failed(ToolResult.error(
                "No infobase applications found in project '" + projectName //$NON-NLS-1$
                    + "'. The event log is stored per infobase; create/associate a FILE infobase " //$NON-NLS-1$
                    + "(see create_infobase) or pass logDir to point at a 1Cv8Log directory " //$NON-NLS-1$
                    + "directly.").toJson()); //$NON-NLS-1$
        }
        if (infobases.size() > 1)
        {
            return SelectResult.failed(ToolResult.error(
                "Project '" + projectName + "' has " + infobases.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " infobase applications; pass applicationId to select one. Available IDs: " //$NON-NLS-1$
                    + joinIds(infobases) + " (see get_applications).").toJson()); //$NON-NLS-1$
        }
        return SelectResult.of(infobases.get(0));
    }

    /**
     * Resolves the {@code 1Cv8Log} directory from an infobase reference: FILE &rarr;
     * {@code <connectionString.file>/1Cv8Log} (validated for existence); SERVER &rarr; an actionable
     * "pass logDir" error; any other/unknown connection string &rarr; a "pass logDir" error naming what
     * was seen.
     */
    static Resolution fromInfobaseReference(InfobaseReference ref)
    {
        // The connection-string access and FILE path build are guarded (mirrors
        // DeleteInfobaseTool.resolveFileInfobaseDir): lazy getConnectionString()/getFile() resolution can
        // throw at runtime, and a malformed/hand-edited path stored in the connection string makes
        // Paths.get throw InvalidPathException. Any such failure becomes an actionable error (pass logDir)
        // rather than an exception escaping resolve()/the tool (unattended-safety, CLAUDE.md #8).
        IConnectionString cs;
        try
        {
            cs = (ref != null) ? ref.getConnectionString() : null;
            if (cs instanceof FileConnectionString)
            {
                String file = ((FileConnectionString)cs).getFile();
                if (file == null || file.trim().isEmpty())
                {
                    return Resolution.failed(ToolResult.error(
                        "The file infobase has no directory path, so its 1Cv8Log cannot be located. " //$NON-NLS-1$
                            + "Pass logDir to point at the log directory directly.").toJson()); //$NON-NLS-1$
                }
                Path logDir = Paths.get(file.trim()).resolve(LOG_DIR_NAME);
                return validateExistingLogDir(logDir, TYPE_FILE);
            }
        }
        catch (Exception e)
        {
            Activator.logError("get_event_log: could not resolve the file infobase's log directory", e); //$NON-NLS-1$
            return Resolution.failed(ToolResult.error(
                "Could not resolve the file infobase's log directory: " + e.getMessage() //$NON-NLS-1$
                    + "; pass logDir to point at the 1Cv8Log directory directly.").toJson()); //$NON-NLS-1$
        }
        if (cs instanceof ServerConnectionString)
        {
            ServerConnectionString scs = (ServerConnectionString)cs;
            String reference = safe(scs.getReference());
            String server = safe(scs.getServer());
            return Resolution.failed(ToolResult.error(
                "This is a SERVER (client/server) infobase" //$NON-NLS-1$
                    + (reference.isEmpty() ? "" : " '" + reference + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + (server.isEmpty() ? "" : " on server '" + server + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ". Its 1Cv8Log lives inside the 1C server cluster data directory (srvinfo) on the " //$NON-NLS-1$
                    + "server host, which is not derivable from the connection string. Pass logDir with " //$NON-NLS-1$
                    + "the absolute path to that infobase's 1Cv8Log directory to read it.").toJson()); //$NON-NLS-1$
        }
        return Resolution.failed(ToolResult.error(
            "Could not determine the infobase location from its connection string" //$NON-NLS-1$
                + (cs == null ? " (no connection string)" //$NON-NLS-1$
                    : " (" + cs.getClass().getSimpleName() + ")") //$NON-NLS-1$ //$NON-NLS-2$
                + ". Pass logDir with the absolute path to its 1Cv8Log directory.").toJson()); //$NON-NLS-1$
    }

    /**
     * Confirms a resolved {@code 1Cv8Log} directory exists (a missing/never-logged directory is an
     * actionable error naming the path). Format detection (text vs SQLite) is deferred to the reader.
     */
    private static Resolution validateExistingLogDir(Path logDir, String infobaseType)
    {
        if (!Files.isDirectory(logDir))
        {
            return Resolution.failed(ToolResult.error(
                "No event log directory at '" + logDir //$NON-NLS-1$
                    + "'. The infobase may have no event log yet (nothing has been logged) or logging " //$NON-NLS-1$
                    + "is off. Pass logDir if the log lives elsewhere.").toJson()); //$NON-NLS-1$
        }
        return Resolution.of(logDir, infobaseType);
    }

    /** Joins the infobase application ids for an ambiguity message. */
    private static String joinIds(List<IInfobaseApplication> infobases)
    {
        StringBuilder ids = new StringBuilder();
        for (IInfobaseApplication ib : infobases)
        {
            if (ids.length() > 0)
            {
                ids.append(", "); //$NON-NLS-1$
            }
            ids.append(ib.getId());
        }
        return ids.toString();
    }

    /** @return the trimmed string, or an empty string for {@code null}. */
    private static String safe(String value)
    {
        return value == null ? "" : value.trim(); //$NON-NLS-1$
    }

    /**
     * Outcome of {@link EventLogLocator#resolve}: either the resolved {@code 1Cv8Log} directory (with an
     * infobase-type tag) or an actionable error JSON. Check {@link #getError()} first; on failure return
     * it verbatim.
     */
    public static final class Resolution
    {
        private final String error;
        private final Path logDir;
        private final String infobaseType;

        private Resolution(String error, Path logDir, String infobaseType)
        {
            this.error = error;
            this.logDir = logDir;
            this.infobaseType = infobaseType;
        }

        static Resolution failed(String error)
        {
            return new Resolution(error, null, null);
        }

        static Resolution of(Path logDir, String infobaseType)
        {
            return new Resolution(null, logDir, infobaseType);
        }

        /** @return the ready-to-return error JSON on failure, or {@code null} on success. */
        public String getError()
        {
            return error;
        }

        /** @return the resolved {@code 1Cv8Log} directory, or {@code null} on error. */
        public Path getLogDir()
        {
            return logDir;
        }

        /**
         * @return the infobase-type tag ({@link #TYPE_FILE} / {@link #TYPE_OVERRIDE}), or {@code null}
         *         on error
         */
        public String getInfobaseType()
        {
            return infobaseType;
        }
    }

    /**
     * Outcome of {@link EventLogLocator#selectInfobase}: either the selected infobase application or an
     * actionable error JSON (not found / none / ambiguous).
     */
    static final class SelectResult
    {
        final String errorJson;
        final IInfobaseApplication app;

        private SelectResult(String errorJson, IInfobaseApplication app)
        {
            this.errorJson = errorJson;
            this.app = app;
        }

        static SelectResult failed(String errorJson)
        {
            return new SelectResult(errorJson, null);
        }

        static SelectResult of(IInfobaseApplication app)
        {
            return new SelectResult(null, app);
        }
    }
}
