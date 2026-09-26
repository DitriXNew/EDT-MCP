/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;

import org.eclipse.core.resources.IProject;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ConfigurationFilesFormat;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ConfigurationFilesKind;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.CreateInfobaseArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Turns a 1C binary file ({@code .cf}, {@code .cfe}, {@code .epf}, {@code .erf}) into the XML
 * source files EDT imports, by driving the thick client of an installed 1C:Enterprise platform.
 *
 * <p>A configuration or an extension goes through a throw-away file infobase; an external object is
 * dumped without one. Every thick-client step is ONE designer command in its own process: a batch
 * run that loads and dumps in one process dumps before it loads.
 *
 * <p>Each step runs on its own thread against the caller's deadline. At the deadline the thread is
 * interrupted, and EDT's executor answers an interrupted wait by killing the 1cv8 process.
 */
public final class BinaryToXmlConverter
{
    /** The runtime type of the 1C:Enterprise platform in EDT's installation registry. */
    private static final String ENTERPRISE_PLATFORM =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$

    /** The name the extension is loaded under in the temporary infobase; the dump keeps the real one. */
    static final String TEMPORARY_EXTENSION_NAME = "McpImport"; //$NON-NLS-1$

    /** How long an interrupted step is given to kill its process and return. */
    private static final long STOP_GRACE_MS = TimeUnit.SECONDS.toMillis(30);

    private static final String CONFIGURATION_XML = "Configuration.xml"; //$NON-NLS-1$

    /** The byte-order mark a designer log starts with. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    /** The most of a designer log a failure message carries; the verdict is at its end. */
    static final int LOG_TAIL_BYTES = 16 * 1024;

    private BinaryToXmlConverter()
    {
    }

    /** What a 1C binary file holds, judged by its extension. */
    public enum SourceKind
    {
        /** A configuration ({@code .cf}). */
        CONFIGURATION("cf", "configuration"), //$NON-NLS-1$ //$NON-NLS-2$
        /** A configuration extension ({@code .cfe}). */
        EXTENSION("cfe", "configuration extension"), //$NON-NLS-1$ //$NON-NLS-2$
        /** An external data processor ({@code .epf}). */
        EXTERNAL_DATA_PROCESSOR("epf", "external data processor"), //$NON-NLS-1$ //$NON-NLS-2$
        /** An external report ({@code .erf}). */
        EXTERNAL_REPORT("erf", "external report"); //$NON-NLS-1$ //$NON-NLS-2$

        private final String extension;
        private final String label;

        SourceKind(String extension, String label)
        {
            this.extension = extension;
            this.label = label;
        }

        /** @return the file extension without the dot, lower case */
        public String extension()
        {
            return extension;
        }

        /** @return a human-readable name of what the file holds */
        public String label()
        {
            return label;
        }

        /** @return {@code true} for an external data processor or report */
        public boolean isExternalObject()
        {
            return this == EXTERNAL_DATA_PROCESSOR || this == EXTERNAL_REPORT;
        }

        /**
         * @param file the source file
         * @return its kind, or {@code null} when the extension is not one of the four supported
         */
        public static SourceKind ofFile(Path file)
        {
            Path name = file == null ? null : file.getFileName();
            if (name == null)
            {
                return null;
            }
            String text = name.toString();
            int dot = text.lastIndexOf('.');
            if (dot < 0)
            {
                return null;
            }
            String extension = text.substring(dot + 1).toLowerCase(Locale.ROOT);
            for (SourceKind kind : values())
            {
                if (kind.extension.equals(extension))
                {
                    return kind;
                }
            }
            return null;
        }

        /** @return the supported extensions for an error message, e.g. {@code .cf, .cfe, .epf, .erf} */
        public static String supportedExtensions()
        {
            List<String> extensions = new ArrayList<>();
            for (SourceKind kind : values())
            {
                extensions.add('.' + kind.extension);
            }
            return String.join(", ", extensions); //$NON-NLS-1$
        }
    }

    /**
     * The thick-client operations a conversion needs. Each call may block for minutes and must
     * answer an interrupt by killing its process.
     */
    public interface IThickClient
    {
        /** @return the platform version with build, for the answer */
        String platformVersion();

        /**
         * Creates a file infobase, empty or from a configuration template.
         *
         * @param infobaseDir the infobase directory
         * @param template the {@code .cf} to create it from, or {@code null} for an empty infobase
         * @throws Exception when the platform refuses
         */
        void createInfobase(Path infobaseDir, Path template) throws Exception; // NOSONAR platform failures vary

        /**
         * Loads a {@code .cfe} into the infobase as an extension.
         *
         * @param infobaseDir the infobase directory
         * @param cfe the extension file
         * @param extensionName the name to load it under
         * @param log the designer log file to write
         * @throws Exception when the platform refuses
         */
        void loadExtension(Path infobaseDir, Path cfe, String extensionName, Path log)
            throws Exception; // NOSONAR platform failures vary

        /**
         * Dumps the configuration, or one extension, to hierarchical XML files.
         *
         * @param infobaseDir the infobase directory
         * @param extensionName the extension to dump, or {@code null} for the configuration
         * @param xmlDir the target directory
         * @throws Exception when the platform refuses
         */
        void dumpConfiguration(Path infobaseDir, String extensionName, Path xmlDir)
            throws Exception; // NOSONAR platform failures vary

        /**
         * Dumps an external data processor or report to hierarchical XML files.
         *
         * @param binary the {@code .epf}/{@code .erf} file
         * @param rootXml the root XML file to write
         * @throws Exception when the platform refuses
         */
        void dumpExternalObject(Path binary, Path rootXml) throws Exception; // NOSONAR platform failures vary
    }

    /** Resolves the thick client of an installed platform. */
    @FunctionalInterface
    public interface IThickClientProvider
    {
        /**
         * @param platformVersion a version or version mask, or {@code null} for the best match
         * @param baseProject the project the result will depend on, or {@code null}
         * @return the thick client
         * @throws ConversionException when no installed platform fits
         */
        IThickClient resolve(String platformVersion, IProject baseProject) throws ConversionException;
    }

    /** An expected conversion failure; the message is written for the caller. */
    public static final class ConversionException extends Exception
    {
        private static final long serialVersionUID = 1L;

        /** @param message the caller-facing message */
        public ConversionException(String message)
        {
            super(message);
        }

        /**
         * @param message the caller-facing message
         * @param cause the underlying failure
         */
        public ConversionException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /** One blocking step of a conversion. */
    @FunctionalInterface
    interface Step
    {
        void run() throws Exception; // NOSONAR platform failures vary
    }

    /**
     * Converts the file into a directory of XML files under {@code workDir}.
     *
     * @param kind what the file holds
     * @param file the source file
     * @param workDir an empty scratch directory the caller deletes afterwards
     * @param client the thick client to drive
     * @param deadlineNanos the {@link System#nanoTime()} instant every step must finish by
     * @param progress receives one line per step
     * @return the directory holding the XML files, ready for EDT's import
     * @throws ConversionException when a step fails or runs past the deadline
     * @throws InterruptedException when the calling thread is interrupted; the running step is
     *     stopped first
     */
    public static Path convert(SourceKind kind, Path file, Path workDir, IThickClient client,
        long deadlineNanos, Consumer<String> progress) throws ConversionException, InterruptedException
    {
        Path xmlDir = workDir.resolve("xml"); //$NON-NLS-1$
        Path infobaseDir = workDir.resolve("ib"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(xmlDir);
        }
        catch (IOException e)
        {
            throw new ConversionException("Could not create the scratch directory " + xmlDir //$NON-NLS-1$
                + ": " + PlatformFailures.describe(e), e); //$NON-NLS-1$
        }
        switch (kind)
        {
            case CONFIGURATION:
                progress.accept("Creating a temporary infobase from " + file.getFileName() + "."); //$NON-NLS-1$ //$NON-NLS-2$
                runStep("creating a temporary infobase from the .cf file", deadlineNanos, //$NON-NLS-1$
                    () -> client.createInfobase(infobaseDir, file));
                progress.accept("Dumping the configuration to XML files."); //$NON-NLS-1$
                runStep("dumping the configuration to XML files", deadlineNanos, //$NON-NLS-1$
                    () -> client.dumpConfiguration(infobaseDir, null, xmlDir));
                break;
            case EXTENSION:
                progress.accept("Creating an empty temporary infobase."); //$NON-NLS-1$
                runStep("creating an empty temporary infobase", deadlineNanos, //$NON-NLS-1$
                    () -> client.createInfobase(infobaseDir, null));
                progress.accept("Loading " + file.getFileName() + " into it as an extension."); //$NON-NLS-1$ //$NON-NLS-2$
                runStep("loading the .cfe file as an extension", deadlineNanos, //$NON-NLS-1$
                    () -> client.loadExtension(infobaseDir, file, TEMPORARY_EXTENSION_NAME,
                        workDir.resolve("load-extension.log"))); //$NON-NLS-1$
                progress.accept("Dumping the extension to XML files."); //$NON-NLS-1$
                runStep("dumping the extension to XML files", deadlineNanos, //$NON-NLS-1$
                    () -> client.dumpConfiguration(infobaseDir, TEMPORARY_EXTENSION_NAME, xmlDir));
                break;
            default:
                progress.accept("Dumping the " + kind.label() + " to XML files."); //$NON-NLS-1$ //$NON-NLS-2$
                Path rootXml = xmlDir.resolve(stem(file) + ".xml"); //$NON-NLS-1$
                runStep("dumping the " + kind.label() + " to XML files", deadlineNanos, //$NON-NLS-1$ //$NON-NLS-2$
                    () -> client.dumpExternalObject(file, rootXml));
                break;
        }
        return xmlDir;
    }

    /**
     * Runs one step on its own thread and waits for it until the deadline.
     *
     * @param label what the step does, for the messages
     * @param deadlineNanos the {@link System#nanoTime()} instant the step must finish by
     * @param step the step
     * @throws ConversionException when the step fails or does not finish in time
     * @throws InterruptedException when the calling thread is interrupted; the step is stopped first
     */
    static void runStep(String label, long deadlineNanos, Step step)
        throws ConversionException, InterruptedException
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try
            {
                step.run();
            }
            catch (Throwable t) // NOSONAR handed to the waiting thread
            {
                failure.set(t);
            }
        }, "EDT-MCP import step: " + label); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
        try
        {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs > 0)
            {
                worker.join(remainingMs);
            }
        }
        catch (InterruptedException e)
        {
            stop(worker);
            throw e;
        }
        if (worker.isAlive())
        {
            boolean stopped = stop(worker);
            throw new ConversionException("The conversion ran out of time while " + label //$NON-NLS-1$
                + (stopped ? ", so that step was stopped and its 1cv8 process killed." //$NON-NLS-1$
                    : "; the step was told to stop but had not returned, so its 1cv8 process " //$NON-NLS-1$
                        + "may still be running.") //$NON-NLS-1$
                + " No project was created."); //$NON-NLS-1$
        }
        Throwable thrown = failure.get();
        if (thrown != null)
        {
            throw new ConversionException("The 1C:Enterprise platform failed while " + label + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + PlatformFailures.describeWithRootCause(thrown) + " No project was created.", thrown); //$NON-NLS-1$
        }
    }

    private static boolean stop(Thread worker)
    {
        worker.interrupt();
        try
        {
            worker.join(STOP_GRACE_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        return !worker.isAlive();
    }

    /**
     * EDT's CREATEINFOBASE command splits {@code File="<path>";} on every space into separate
     * process arguments. Java on Windows re-joins them with one space, so only a run of spaces is
     * lost there; elsewhere each space splits the path.
     *
     * @param infobaseParent the directory the temporary infobase is created under
     * @param windows whether the process arguments are re-joined into one Windows command line
     * @return {@code null} when the path survives that split, otherwise why it does not
     */
    public static String infobasePathProblem(Path infobaseParent, boolean windows)
    {
        String path = infobaseParent.toString();
        if (windows ? path.contains("  ") : path.indexOf(' ') >= 0) //$NON-NLS-1$
        {
            return windows
                ? "that path holds two spaces in a row, which EDT's infobase command does not pass on intact" //$NON-NLS-1$
                : "that path holds a space, at which EDT's infobase command splits it"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Checks that the dump holds what the file extension promised, before anything is imported.
     *
     * @param kind what the file was expected to hold
     * @param xmlDir the dump directory
     * @return {@code null} when the dump matches, otherwise the caller-facing problem
     */
    public static String verifyDump(SourceKind kind, Path xmlDir)
    {
        if (kind.isExternalObject())
        {
            // EDT's CLI import takes any directory holding Configuration.xml for a configuration.
            if (Files.exists(xmlDir.resolve(CONFIGURATION_XML)))
            {
                return "The file's name makes the root file of its dump " + CONFIGURATION_XML //$NON-NLS-1$
                    + ", and EDT imports a dump holding that file as a configuration, not as an " //$NON-NLS-1$
                    + kind.label() + ". Copy the file under another name and import the copy."; //$NON-NLS-1$
            }
            List<Path> roots;
            try (Stream<Path> entries = Files.list(xmlDir))
            {
                roots = entries.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) //$NON-NLS-1$
                    .collect(Collectors.toList());
            }
            catch (IOException e)
            {
                return "The platform dump could not be read: " + PlatformFailures.describe(e); //$NON-NLS-1$
            }
            if (roots.size() != 1)
            {
                return "The platform dump of the " + kind.label() + " holds " + roots.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " root XML files instead of one."; //$NON-NLS-1$
            }
            // The platform converts .epf and .erf alike, so the root object says which one it is.
            String expected = kind == SourceKind.EXTERNAL_REPORT ? "ExternalReport" : "ExternalDataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$
            String actual = rootObjectName(roots.get(0));
            if (actual == null)
            {
                return "The platform dump could not be read: its root file " + roots.get(0).getFileName() //$NON-NLS-1$
                    + " is not a metadata object dump."; //$NON-NLS-1$
            }
            return expected.equals(actual) ? null
                : "The file's extension promises an " + kind.label() + ", but it holds an " //$NON-NLS-1$ //$NON-NLS-2$
                    + ("ExternalReport".equals(actual) ? SourceKind.EXTERNAL_REPORT.label() //$NON-NLS-1$
                        : "ExternalDataProcessor".equals(actual) ? SourceKind.EXTERNAL_DATA_PROCESSOR.label() //$NON-NLS-1$
                        : "object of type " + actual) //$NON-NLS-1$
                    + ". Rename the file to the matching extension (.epf or .erf) and import it again."; //$NON-NLS-1$
        }
        Path configurationXml = xmlDir.resolve(CONFIGURATION_XML);
        if (!Files.isRegularFile(configurationXml))
        {
            return "The platform dump holds no " + CONFIGURATION_XML + ", so the file is not a " //$NON-NLS-1$ //$NON-NLS-2$
                + kind.label() + "."; //$NON-NLS-1$
        }
        Boolean adopted = isExtensionDump(configurationXml);
        if (adopted == null)
        {
            return "The platform dump's " + CONFIGURATION_XML + " could not be read."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (kind == SourceKind.CONFIGURATION && adopted.booleanValue())
        {
            return "The .cf file holds a configuration EXTENSION, not a configuration. Rename it to " //$NON-NLS-1$
                + ".cfe and pass baseProjectName."; //$NON-NLS-1$
        }
        if (kind == SourceKind.EXTENSION && !adopted.booleanValue())
        {
            return "The .cfe file holds a configuration, not an extension. Rename it to .cf and " //$NON-NLS-1$
                + "omit baseProjectName."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * @param rootXml the root file of an external object dump
     * @return the local name of the object element under {@code MetaDataObject}, or {@code null}
     */
    static String rootObjectName(Path rootXml)
    {
        try
        {
            DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
            org.w3c.dom.Element root = builder.parse(rootXml.toFile()).getDocumentElement();
            if (!"MetaDataObject".equals(root.getLocalName() != null ? root.getLocalName() : root.getNodeName())) //$NON-NLS-1$
            {
                return null;
            }
            for (org.w3c.dom.Node child = root.getFirstChild(); child != null; child = child.getNextSibling())
            {
                if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE)
                {
                    return child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                }
            }
            return null;
        }
        catch (Exception e) // NOSONAR any parse failure means "not a dump"
        {
            return null;
        }
    }

    /**
     * @param configurationXml a dumped {@code Configuration.xml}
     * @return whether it declares {@code ObjectBelonging=Adopted} (an extension), or {@code null}
     *     when it cannot be parsed
     */
    static Boolean isExtensionDump(Path configurationXml)
    {
        try
        {
            DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
            Document document = builder.parse(configurationXml.toFile());
            NodeList belonging = document.getElementsByTagNameNS("*", "ObjectBelonging"); //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.valueOf(belonging.getLength() > 0
                && "Adopted".equals(belonging.item(0).getTextContent().trim())); //$NON-NLS-1$
        }
        catch (Exception e) // NOSONAR any parse failure means "unknown"
        {
            return null; // NOSONAR tri-state by design
        }
    }

    /**
     * Deletes a scratch directory, retrying briefly because a killed 1cv8 process may still hold
     * a file for a moment.
     *
     * @param dir the directory, or {@code null}
     * @return {@code true} when nothing is left
     */
    public static boolean deleteQuietly(Path dir)
    {
        if (dir == null)
        {
            return true;
        }
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try (Stream<Path> walk = Files.walk(dir))
            {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException e)
                    {
                        // retried below
                    }
                });
            }
            catch (IOException e)
            {
                // retried below
            }
            if (!Files.exists(dir))
            {
                return true;
            }
            try
            {
                Thread.sleep(500L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !Files.exists(dir);
    }

    private static String stem(Path file)
    {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * The production provider: EDT's own runtime registry and thick-client launcher.
     *
     * @return the provider
     */
    public static IThickClientProvider installedPlatforms()
    {
        return BinaryToXmlConverter::resolveInstalledThickClient;
    }

    private static IThickClient resolveInstalledThickClient(String platformVersion,
        IProject baseProject) throws ConversionException
    {
        IResolvableRuntimeInstallationManager installations;
        IRuntimeComponentManager components;
        try
        {
            installations = ServiceAccess.get(IResolvableRuntimeInstallationManager.class);
            components = ServiceAccess.get(IRuntimeComponentManager.class);
        }
        catch (RuntimeException e)
        {
            throw new ConversionException("EDT's 1C:Enterprise runtime services are not available (" //$NON-NLS-1$
                + PlatformFailures.describe(e) + "), so no installed platform can convert the file. " //$NON-NLS-1$
                + "Wait for EDT to finish starting up and retry; without a platform, dump the file to " //$NON-NLS-1$
                + "XML in Designer and use import_configuration_from_xml. No project was created.", e); //$NON-NLS-1$
        }
        try
        {
            IResolvableRuntimeInstallation resolvable = platformVersion == null
                ? installations.resolveByProjectAndInfobase(ENTERPRISE_PLATFORM, baseProject, null,
                    InfobaseAccessType.UPDATE)
                : installations.resolveByVersionOrMask(ENTERPRISE_PLATFORM, platformVersion);
            RuntimeInstallation installation =
                resolvable.resolve(List.of(IRuntimeComponentTypes.THICK_CLIENT), AppArch.AUTO);
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> info =
                components.resolveExecutor(ILaunchableRuntimeComponent.class,
                    IThickClientLauncher.class, installation, IRuntimeComponentTypes.THICK_CLIENT);
            return new PlatformThickClient(info.getExecutor(), info.getComponent(),
                installation.getVersionWithBuild());
        }
        catch (MatchingRuntimeNotFound e)
        {
            throw new ConversionException(noPlatformMessage(platformVersion, e), e);
        }
        catch (Exception e) // NOSONAR RuntimeExecutionException and runtime failures alike
        {
            throw new ConversionException("Could not resolve the 1C:Enterprise thick client" //$NON-NLS-1$
                + (platformVersion == null ? "" : " for platformVersion '" + platformVersion + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ": " + PlatformFailures.describe(e) + ". Check the installation in EDT " //$NON-NLS-1$ //$NON-NLS-2$
                + "(Window -> Preferences -> 1C:Enterprise -> Installed Installations) and retry; " //$NON-NLS-1$
                + "without a platform, dump the file to XML in Designer and use " //$NON-NLS-1$
                + "import_configuration_from_xml. No project was created.", e); //$NON-NLS-1$
        }
    }

    /**
     * @param platformVersion the requested version or mask, or {@code null}
     * @param failure EDT's own explanation
     * @return the caller-facing message for "no installed platform fits"
     */
    static String noPlatformMessage(String platformVersion, Throwable failure)
    {
        return "No installed 1C:Enterprise platform with a thick client fits this import" //$NON-NLS-1$
            + (platformVersion == null ? "" : " for platformVersion '" + platformVersion + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " (" + PlatformFailures.describe(failure) + "). Converting a .cf, .cfe, .epf or .erf " //$NON-NLS-1$ //$NON-NLS-2$
            + "file needs the 1C:Enterprise platform installed on this machine and registered in " //$NON-NLS-1$
            + "EDT (Window -> Preferences -> 1C:Enterprise -> Installed Installations)" //$NON-NLS-1$
            + (platformVersion == null ? "" : "; omit platformVersion to use the best installed one") //$NON-NLS-1$ //$NON-NLS-2$
            + ". Without a platform, dump the file to XML in Designer and use " //$NON-NLS-1$
            + "import_configuration_from_xml. No project was created."; //$NON-NLS-1$
    }

    /** The thick client of one installed platform, driven through EDT's launcher. */
    private static final class PlatformThickClient implements IThickClient
    {
        private final IThickClientLauncher launcher;
        private final ILaunchableRuntimeComponent component;
        private final String version;

        PlatformThickClient(IThickClientLauncher launcher, ILaunchableRuntimeComponent component,
            String version)
        {
            this.launcher = launcher;
            this.component = component;
            this.version = version;
        }

        @Override
        public String platformVersion()
        {
            return version;
        }

        @Override
        public void createInfobase(Path infobaseDir, Path template) throws Exception
        {
            CreateInfobaseArguments arguments = ModelFactory.eINSTANCE.createCreateInfobaseArguments();
            InfobaseReference infobase = temporaryInfobase(infobaseDir);
            if (template == null)
            {
                launcher.createInfobase(component, infobase, arguments, false);
            }
            else
            {
                launcher.createInfobaseUsingTemplate(component, infobase, arguments, template, false);
            }
        }

        @Override
        public void loadExtension(Path infobaseDir, Path cfe, String extensionName, Path log)
            throws Exception
        {
            // IThickClientLauncher.importCfToInfobase has no extension switch, so this one designer
            // command is built here with the same startup flags EDT's executor adds.
            List<String> command = List.of(component.getFile().getAbsolutePath(), "DESIGNER", //$NON-NLS-1$
                "/F", infobaseDir.toString(), //$NON-NLS-1$
                "/DisableStartupDialogs", "/DisableStartupMessages", "/AppAutoCheckVersion-", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "/Out", log.toString(), //$NON-NLS-1$
                "/LoadCfg", cfe.toString(), "-Extension", extensionName); //$NON-NLS-1$ //$NON-NLS-2$
            runDesigner(command, log);
        }

        @Override
        public void dumpConfiguration(Path infobaseDir, String extensionName, Path xmlDir)
            throws Exception
        {
            RuntimeExecutionArguments arguments = new RuntimeExecutionArguments();
            arguments.setExtensionName(extensionName);
            launcher.exportFullXmlFromInfobase(component, temporaryInfobase(infobaseDir),
                ConfigurationFilesFormat.HIERARCHICAL, ConfigurationFilesKind.PLAIN_FILES, arguments,
                xmlDir);
        }

        @Override
        public void dumpExternalObject(Path binary, Path rootXml) throws Exception
        {
            launcher.convertBinaryExternalToXml(component, null, new RuntimeExecutionArguments(),
                rootXml, binary);
        }

        /** A file-infobase reference; the uuid is required by the executor's registry lookup. */
        private static InfobaseReference temporaryInfobase(Path infobaseDir)
        {
            InfobaseReference infobase = InfobaseReferences.newFileInfobaseReference(infobaseDir.toString());
            infobase.setUuid(UUID.randomUUID());
            infobase.setName("EDT-MCP import"); //$NON-NLS-1$
            return infobase;
        }
    }

    /**
     * Runs one designer command and fails on a non-zero exit code with the designer's log, as EDT's
     * own executor does. An interrupt kills the process.
     */
    private static void runDesigner(List<String> command, Path log) throws Exception
    {
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try
        {
            int exitCode = process.waitFor();
            if (exitCode != 0)
            {
                throw new IOException("1C:Enterprise exited with code " + exitCode + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + readLog(log));
            }
        }
        finally
        {
            process.destroyForcibly();
        }
    }

    /**
     * Reads the tail of a designer log: UTF-8 with a BOM on every platform EDT supports. Only the
     * last {@link #LOG_TAIL_BYTES} are read, since the message is kept by the job registry.
     *
     * @param log the log file
     * @return its text, marked when cut
     */
    static String readLog(Path log)
    {
        try (SeekableByteChannel channel = Files.newByteChannel(log))
        {
            long size = channel.size();
            long skipped = Math.max(0L, size - LOG_TAIL_BYTES);
            channel.position(skipped);
            ByteBuffer buffer = ByteBuffer.allocate((int)(size - skipped));
            while (buffer.hasRemaining() && channel.read(buffer) > 0)
            {
                // fill the buffer
            }
            byte[] bytes = buffer.array();
            int start = 0;
            // A cut can land inside a character: skip its UTF-8 continuation bytes.
            while (skipped > 0 && start < buffer.position() && (bytes[start] & 0xC0) == 0x80)
            {
                start++;
            }
            String text = new String(bytes, start, buffer.position() - start, StandardCharsets.UTF_8);
            if (skipped == 0)
            {
                return (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text).trim();
            }
            return "(designer log cut to its last " + (buffer.position() - start) + " of " + size //$NON-NLS-1$ //$NON-NLS-2$
                + " bytes) ..." + text.trim(); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return "(no designer log)"; //$NON-NLS-1$
        }
    }
}
