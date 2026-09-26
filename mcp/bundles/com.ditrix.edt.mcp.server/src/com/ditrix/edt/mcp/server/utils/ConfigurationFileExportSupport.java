/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.infobases.export.ExportConfigurationFileException;
import com._1c.g5.v8.dt.platform.services.core.infobases.export.IExportConfigurationFileService;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.InfobaseUserAuthenticationException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.InfobaseUserNoAccessRightException;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Platform side of {@code export_configuration_to_file}: the output-file rules, the reading of
 * whether the infobase matches the project, and the call into EDT's public
 * {@link IExportConfigurationFileService} - the chain EDT's own "Export configuration to file"
 * wizard runs (resolve the platform for project + infobase, take its thick client, dump).
 *
 * <p>The dump is taken FROM THE INFOBASE, not from the workspace model, so the file holds what
 * the infobase contains. That is why the equality reading exists at all.
 */
public final class ConfigurationFileExportSupport
{
    /** Runtime type EDT resolves a 1C:Enterprise platform installation by. */
    static final String ENTERPRISE_PLATFORM_RUNTIME =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$

    /** File extension of a configuration dump. */
    public static final String CF = ".cf"; //$NON-NLS-1$

    /** File extension of an extension dump. */
    public static final String CFE = ".cfe"; //$NON-NLS-1$

    /** Slack between the file system clock and ours when judging "written by this call". */
    static final long CLOCK_SKEW_MS = 2_000L;

    /** How often a LOADING equality state is re-read while it settles. */
    private static final long SYNC_POLL_MS = 500L;

    private ConfigurationFileExportSupport()
    {
    }

    /** Whether the infobase matched the project when the export was decided. */
    public enum SyncState
    {
        /** EDT reports the infobase equal to the project. */
        IN_SYNC("inSync"), //$NON-NLS-1$
        /** EDT reports the infobase different from the project. */
        OUT_OF_DATE("outOfDate"), //$NON-NLS-1$
        /** Nothing was measured: no synchronization, no project, or a read failure. */
        UNKNOWN("unknown"); //$NON-NLS-1$

        private final String wire;

        SyncState(String wire)
        {
            this.wire = wire;
        }

        /** @return the value reported to the caller */
        public String wire()
        {
            return wire;
        }
    }

    /**
     * One reading of the infobase-vs-project state, with the sentence explaining it.
     *
     * @param state the classification
     * @param detail why, in one clause (never {@code null})
     */
    public record SyncReading(SyncState state, String detail)
    {
    }

    /**
     * A validated output path, or the refusal that replaces it.
     *
     * @param path the absolute, normalized file to create ({@code null} on refusal)
     * @param error the actionable refusal ({@code null} when valid)
     */
    public record OutputFile(Path path, String error)
    {
        /** @return whether the path passed every check */
        public boolean ok()
        {
            return error == null;
        }

        /** @return whether the file name asks for an extension dump */
        public boolean isExtensionFile()
        {
            return path != null && lowerName(path).endsWith(CFE);
        }
    }

    /**
     * A file this call published at the destination.
     *
     * @param path where it is
     * @param sizeBytes its size as read back after the move
     * @param lastModifiedMillis its modification time as read back after the move
     */
    public record PublishedFile(Path path, long sizeBytes, long lastModifiedMillis)
    {
    }

    /**
     * Checks the shape of the requested output file without touching EDT: absolute, named
     * {@code .cf}/{@code .cfe}, in an existing folder, and not existing yet (never overwritten).
     *
     * @param key the parameter name to use in messages
     * @param raw the caller's value (non-blank)
     * @return the validated path or the refusal
     */
    public static OutputFile validateOutputFile(String key, String raw)
    {
        Path path;
        try
        {
            path = Path.of(raw.trim());
        }
        catch (InvalidPathException e)
        {
            return new OutputFile(null, key + " '" + raw + "' is not a valid file path: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getReason() + ". Pass the absolute path of the .cf/.cfe file to create."); //$NON-NLS-1$
        }
        if (!path.isAbsolute())
        {
            return new OutputFile(null, key + " must be an absolute path, but was '" + raw //$NON-NLS-1$
                + "'. Pass the full path of the file to create, e.g. C:\\export\\MyConfiguration.cf."); //$NON-NLS-1$
        }
        path = path.normalize();
        Path fileName = path.getFileName();
        String lower = lowerName(path);
        boolean cf = lower.endsWith(CF);
        boolean cfe = lower.endsWith(CFE);
        if (fileName == null || (!cf && !cfe) || lower.equals(CF) || lower.equals(CFE))
        {
            return new OutputFile(null, key + " '" + path + "' must name a file ending in .cf " //$NON-NLS-1$ //$NON-NLS-2$
                + "(a configuration) or .cfe (an extension)."); //$NON-NLS-1$
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent))
        {
            return new OutputFile(null, "The folder of " + key + " does not exist: '" + parent //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Create it first - this tool does not create folders."); //$NON-NLS-1$
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return new OutputFile(null, key + " already exists: '" + path + "'. This tool never " //$NON-NLS-1$ //$NON-NLS-2$
                + "overwrites a file - pass a new file name, or remove the existing one first."); //$NON-NLS-1$
        }
        return new OutputFile(path, null);
    }

    /**
     * The refusal for a file name whose extension does not match what is being exported, or
     * {@code null} when it matches.
     *
     * @param key the parameter name to use in messages
     * @param output the validated output file
     * @param extensionName the extension being exported, or {@code null} for the configuration
     * @param projectName the configuration project, for the message
     * @return the refusal or {@code null}
     */
    public static String kindMismatch(String key, OutputFile output, String extensionName,
        String projectName)
    {
        if (extensionName == null && output.isExtensionFile())
        {
            return key + " '" + output.path() + "' ends with .cfe, but the source is the " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration of project '" + projectName + "', which is written as a .cf " //$NON-NLS-1$ //$NON-NLS-2$
                + "file. Use a .cf name, or pass extensionName to export an extension."; //$NON-NLS-1$
        }
        if (extensionName != null && !output.isExtensionFile())
        {
            return key + " '" + output.path() + "' ends with .cf, but the source is extension '" //$NON-NLS-1$ //$NON-NLS-2$
                + extensionName + "', which is written as a .cfe file. Use a .cfe name."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The temporary file the platform writes into, beside the destination. Keeping it in the same
     * folder makes the final publish a rename; keeping the real extension last keeps it a valid
     * dump name for the Designer.
     *
     * @param destination the requested output file
     * @param token a unique token for this call
     * @return the partial file path
     */
    public static Path partialFileFor(Path destination, String token)
    {
        String name = destination.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = name.substring(0, dot);
        String extension = name.substring(dot);
        return destination.resolveSibling(stem + ".partial-" + token + extension); //$NON-NLS-1$
    }

    /**
     * Publishes the platform's output: checks the partial file is a non-empty regular file written
     * after {@code notBeforeMillis}, renames it to the destination WITHOUT replacing anything, and
     * reads the destination back.
     *
     * @param partial the file the platform wrote
     * @param destination the requested output file
     * @param notBeforeMillis the start of the export, in epoch milliseconds
     * @return what was read back at the destination
     * @throws IOException when the file is missing, empty, stale, the destination appeared
     *     meanwhile, or the read-back disagrees; the message says which
     */
    public static PublishedFile publish(Path partial, Path destination, long notBeforeMillis)
        throws IOException
    {
        if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("the platform reported success but wrote no file"); //$NON-NLS-1$
        }
        BasicFileAttributes written = Files.readAttributes(partial, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (written.size() <= 0L)
        {
            throw new IOException("the platform wrote an EMPTY file"); //$NON-NLS-1$
        }
        if (written.lastModifiedTime().toMillis() < notBeforeMillis - CLOCK_SKEW_MS)
        {
            throw new IOException("the file the platform left is older than this export"); //$NON-NLS-1$
        }
        try
        {
            // No REPLACE_EXISTING: a file that appeared at the destination meanwhile is kept.
            Files.move(partial, destination);
        }
        catch (FileAlreadyExistsException e)
        {
            throw new IOException("'" + destination + "' appeared while the export ran and was " //$NON-NLS-1$ //$NON-NLS-2$
                + "left untouched", e); //$NON-NLS-1$
        }
        BasicFileAttributes published = Files.readAttributes(destination, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!published.isRegularFile() || published.size() != written.size())
        {
            throw new IOException("the file read back at '" + destination //$NON-NLS-1$
                + "' does not match what the platform wrote"); //$NON-NLS-1$
        }
        return new PublishedFile(destination, published.size(),
            published.lastModifiedTime().toMillis());
    }

    /**
     * Deletes a file this call owns, never throwing.
     *
     * @param path the file (may be {@code null})
     * @return {@code true} when the file is gone afterwards
     */
    public static boolean deleteQuietly(Path path)
    {
        if (path == null)
        {
            return true;
        }
        try
        {
            Files.deleteIfExists(path);
            return true;
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logInfo("export_configuration_to_file: could not remove " + path + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return false;
        }
    }

    /**
     * Classifies one equality reading. Only a CONNECTED project has a measured state: EDT answers
     * {@code NOT_EQUAL} for a project it is not synchronizing at all, which says nothing.
     *
     * @param connected whether EDT synchronizes the project with the infobase
     * @param state what EDT answered (may be {@code null})
     * @param projectName the project, for the detail
     * @return the reading
     */
    public static SyncReading classify(boolean connected, InfobaseEqualityState state,
        String projectName)
    {
        if (!connected)
        {
            return new SyncReading(SyncState.UNKNOWN, "EDT is not synchronizing project '" //$NON-NLS-1$
                + projectName + "' with this infobase"); //$NON-NLS-1$
        }
        if (state == InfobaseEqualityState.EQUAL)
        {
            return new SyncReading(SyncState.IN_SYNC, "EDT reports the infobase equal to project '" //$NON-NLS-1$
                + projectName + "'"); //$NON-NLS-1$
        }
        if (state == InfobaseEqualityState.NOT_EQUAL)
        {
            return new SyncReading(SyncState.OUT_OF_DATE, "EDT reports the infobase different " //$NON-NLS-1$
                + "from project '" + projectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new SyncReading(SyncState.UNKNOWN, "EDT was still comparing project '" //$NON-NLS-1$
            + projectName + "' with the infobase"); //$NON-NLS-1$
    }

    /**
     * Reads whether the infobase matches {@code project}, waiting up to {@code settleMs} for a
     * {@code LOADING} state to settle. Never throws for a read failure: that is UNKNOWN.
     *
     * @param project the project the dump corresponds to (configuration or extension)
     * @param infobase the infobase being dumped
     * @param settleMs how long a LOADING state may be waited out
     * @return the reading
     * @throws InterruptedException when the wait is interrupted
     */
    public static SyncReading readSyncState(IProject project, InfobaseReference infobase,
        long settleMs) throws InterruptedException
    {
        String projectName = project.getName();
        try
        {
            IInfobaseSynchronizationManager sync =
                ServiceAccess.get(IInfobaseSynchronizationManager.class);
            long deadline = System.currentTimeMillis() + Math.max(0L, settleMs);
            while (true)
            {
                boolean connected = sync.isConnected(project, infobase);
                InfobaseEqualityState state = sync.getEqualityState(project, infobase);
                if (!connected || state != InfobaseEqualityState.LOADING
                    || System.currentTimeMillis() >= deadline)
                {
                    return classify(connected, state, projectName);
                }
                Thread.sleep(SYNC_POLL_MS);
            }
        }
        catch (RuntimeException e)
        {
            Activator.logError("export_configuration_to_file: could not read the synchronization " //$NON-NLS-1$
                + "state of project " + projectName, e); //$NON-NLS-1$
            return new SyncReading(SyncState.UNKNOWN, "the synchronization state of project '" //$NON-NLS-1$
                + projectName + "' could not be read: " + PlatformFailures.describe(e)); //$NON-NLS-1$
        }
    }

    /**
     * Reads the name of a configuration or extension project's root configuration inside a read
     * transaction - for an extension this is the name the infobase knows it by.
     *
     * @param project the workspace project
     * @param v8Project its EDT project view
     * @return the name, or {@code null} when the model or configuration is unavailable
     */
    public static String configurationName(IProject project, IConfigurationAware v8Project)
    {
        BmModelResolver.Resolution resolution = BmModelResolver.resolve(project);
        if (!resolution.isAvailable())
        {
            return null;
        }
        IBmModel model = resolution.getModel();
        return BmTransactions.read(model, "export_configuration_to_file.configurationName", //$NON-NLS-1$
            (tx, monitor) -> {
                Configuration configuration = v8Project.getConfiguration();
                return configuration == null ? null : configuration.getName();
            });
    }

    /**
     * Resolves the platform installation and thick client for {@code project} + {@code infobase}
     * exactly as EDT's export wizard does, then dumps the configuration or extension to
     * {@code target}. A cancelled {@code monitor} makes EDT return at once without an exception,
     * without waiting for the Designer - the caller must judge the outcome by the file, not by the
     * return. EDT may re-run the dump with another platform version, so none is reported.
     *
     * @param project the configuration project the runtime is resolved for
     * @param infobase the infobase to dump
     * @param extensionName the extension to dump, or {@code null} for the configuration
     * @param target the file the platform writes
     * @param monitor the cancellation source
     * @throws Exception whatever the platform raised
     */
    public static void export(IProject project, InfobaseReference infobase, String extensionName,
        Path target, IProgressMonitor monitor) throws Exception // NOSONAR platform failures are reported by the caller
    {
        IExportConfigurationFileService service = ServiceAccess.get(IExportConfigurationFileService.class);
        IResolvableRuntimeInstallationManager installations =
            ServiceAccess.get(IResolvableRuntimeInstallationManager.class);
        IRuntimeComponentManager components = ServiceAccess.get(IRuntimeComponentManager.class);

        // The wizard refreshes the reference from the registry the same way.
        InfobaseReference actual = service.checked(infobase).orElse(infobase);
        IResolvableRuntimeInstallation resolvable = installations.resolveByProjectAndInfobase(
            ENTERPRISE_PLATFORM_RUNTIME, project, actual, InfobaseAccessType.UPDATE);
        RuntimeInstallation installation = resolvable.resolve(
            List.of(IRuntimeComponentTypes.THICK_CLIENT), actual.getAppArch());
        ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> thickClient =
            components.resolveExecutor(ILaunchableRuntimeComponent.class, IThickClientLauncher.class,
                installation, IRuntimeComponentTypes.THICK_CLIENT);
        service.exportConfigurationOrExtension(actual, thickClient.getComponent(),
            thickClient.getExecutor(), extensionName, target, monitor);
    }

    /**
     * The caller-facing sentence for a failed dump: the platform's own text, plus the one step that
     * fixes the classes of failure we can recognise.
     *
     * @param failure what the platform raised
     * @param extensionName the extension being dumped, or {@code null}
     * @param projectName the configuration project
     * @return the message (never {@code null})
     */
    public static String describeExportFailure(Throwable failure, String extensionName,
        String projectName)
    {
        String platform = withoutTrailingPeriod(PlatformFailures.describeWithRootCause(failure));
        if (inChain(failure, MatchingRuntimeNotFound.class))
        {
            return "EDT found no registered 1C:Enterprise platform with a thick client for project '" //$NON-NLS-1$
                + projectName + "' and this infobase: " + platform //$NON-NLS-1$
                + ". Install that platform version locally and register it in EDT's installed " //$NON-NLS-1$
                + "platforms, then retry."; //$NON-NLS-1$
        }
        if (inChain(failure, FileSystemNotFoundException.class))
        {
            return "EDT resolved a REMOTE thick client it cannot run (" + platform //$NON-NLS-1$
                + "). Install the project's platform version locally, with the thick client " //$NON-NLS-1$
                + "component, and register it in EDT's installed platforms, then retry."; //$NON-NLS-1$
        }
        if (inChain(failure, InfobaseUserAuthenticationException.class)
            || inChain(failure, InfobaseUserNoAccessRightException.class))
        {
            return "The 1C Designer could not log in to the infobase: " + platform //$NON-NLS-1$
                + ". Store the infobase user with set_infobase_credentials (it needs the right to " //$NON-NLS-1$
                + "administer the configuration), then retry."; //$NON-NLS-1$
        }
        String message = "The 1C Designer export failed: " + platform + '.'; //$NON-NLS-1$
        if (extensionName != null)
        {
            message += " Check that an extension named '" + extensionName + "' is installed in " //$NON-NLS-1$ //$NON-NLS-2$
                + "this infobase (apply the extension project with update_database first)."; //$NON-NLS-1$
        }
        return message;
    }

    /**
     * Whether {@code failure} is one of the failures the export chain declares - an operational
     * outcome (no platform, Designer refused, login failed), not a defect in our code. The platform
     * logs the Designer's own stack for these, so the caller's message is the whole story.
     *
     * @param failure what the export raised (may be {@code null})
     * @return {@code true} for a declared platform failure
     */
    public static boolean isDeclaredPlatformFailure(Throwable failure)
    {
        return failure instanceof ExportConfigurationFileException
            || failure instanceof MatchingRuntimeNotFound
            || failure instanceof RuntimeExecutionException;
    }

    private static String withoutTrailingPeriod(String text)
    {
        String trimmed = text == null ? "" : text.strip(); //$NON-NLS-1$
        while (trimmed.endsWith(".")) //$NON-NLS-1$
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    private static boolean inChain(Throwable failure, Class<? extends Throwable> type)
    {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++)
        {
            if (type.isInstance(current))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String lowerName(Path path)
    {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }
}
