/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the external BSL Language Server engine ({@code bsl-language-server-*-exec.jar})
 * as a subprocess in analyze mode and returns its parsed JSON report. This is the
 * process/IO half of {@code code_review}; the pure model + JSON parsing live in
 * {@link BslLsReport}.
 * <p>
 * <b>Why a subprocess and not in-process:</b> the engine is a Spring-Boot fat jar
 * (its own classloader graph); running it inside the EDT OSGi runtime risks
 * classloader conflicts and buys nothing. We invoke the stable CLI
 * ({@code --analyze --reporter json}) exactly as the reference plugin does.
 * <p>
 * <b>Two independent knobs</b> (neither requires rebuilding the plugin):
 * <ul>
 * <li>the engine jar — {@link #ENV_JAR} / an explicit override / a default folder;</li>
 * <li>the Java used to launch it — {@link #ENV_JAVA} / an explicit override, falling
 * back to the JRE running EDT ({@code java.home}). The {@code 1.x} engine line needs
 * Java 21; {@code 0.28.x} runs on Java 17.</li>
 * </ul>
 * The configuration of <i>which</i> checks run is the engine's own
 * {@code .bsl-language-server.json} (see {@link Request#configFile}); {@code --workspaceDir}
 * (see {@link Request#workspaceDir}) pins the project root the engine scopes that
 * configuration and its report paths to, independent of how narrow {@code --srcDir} is
 * for a given run (see {@link #buildCommand}).
 */
public final class BslLsRunner
{
    /** Env var pointing at the engine {@code exec.jar}. */
    public static final String ENV_JAR = "EDT_MCP_BSL_LS_JAR"; //$NON-NLS-1$

    /** Env var pointing at the {@code java(.exe)} used to launch the engine. */
    public static final String ENV_JAVA = "EDT_MCP_BSL_LS_JAVA"; //$NON-NLS-1$

    /** Releases page cited in the not-found error so a client can self-serve. */
    public static final String RELEASES_URL = "https://github.com/1c-syntax/bsl-language-server/releases"; //$NON-NLS-1$

    private static final String REPORT_FILE = "bsl-json.json"; //$NON-NLS-1$
    private static final int DEFAULT_TIMEOUT_SECONDS = 180;

    /**
     * Bound on the engine's captured stdout+stderr (merged via
     * {@link ProcessBuilder#redirectErrorStream}) kept in memory WHILE the process runs. Only the
     * last {@link #tail}-sized slice of this is ever shown to a caller (in the "no report produced"
     * error), so retaining more than this was pure waste that could exhaust the EDT heap against a
     * runaway or pathologically chatty subprocess. {@link #drainAsync} trims the front once this is
     * exceeded — the stream itself is still fully drained (never blocking the child on a full pipe
     * buffer), only what is RETAINED is bounded.
     */
    static final int MAX_CAPTURED_OUTPUT_CHARS = 8_000;

    /**
     * Bound on the engine's JSON report file size, checked BEFORE it is read into memory. A report
     * this large indicates a pathological/misconfigured run (or a corrupt engine process) — reading
     * it fully via {@link Files#readAllBytes} and then having Gson build a full DOM over it could
     * exhaust the EDT heap long before {@code OutputSizeGuard} ever gets a chance to cap the FINAL
     * response text (that guard only bounds the rendered Markdown, not this intermediate JSON).
     */
    static final long MAX_REPORT_BYTES = 50_000_000L;

    private BslLsRunner()
    {
    }

    /**
     * Inputs for one analyze run. Only {@link #srcDir} is required; the rest resolve
     * from env/defaults when left {@code null}.
     */
    public static final class Request
    {
        private final File srcDir;
        private File workspaceDir;
        private File configFile;
        private File jarOverride;
        private File javaOverride;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        /**
         * @param srcDir the directory to analyze (a project {@code src} folder or a
         *            narrower subtree); must exist
         */
        public Request(File srcDir)
        {
            this.srcDir = srcDir;
        }

        /**
         * @param dir the project's own workspace root (conventionally the directory that
         *            hosts its {@code .bsl-language-server.json}, e.g. the project's
         *            {@code src} folder) — passed to the engine as {@code --workspaceDir}
         *            so report paths and workspace-local settings stay scoped to THIS
         *            project even when {@link #srcDir} is narrowed to a single module's
         *            containing folder. When {@code null}, {@link #srcDir} itself is used
         * @return this request
         */
        public Request workspaceDir(File dir)
        {
            this.workspaceDir = dir;
            return this;
        }

        /**
         * @param file the project's {@code .bsl-language-server.json}; when {@code null}
         *            or absent the engine-home config (next to the jar) is used, else
         *            engine defaults
         * @return this request
         */
        public Request configFile(File file)
        {
            this.configFile = file;
            return this;
        }

        /**
         * @param jar an explicit engine jar path, taking precedence over {@link #ENV_JAR}
         * @return this request
         */
        public Request jarOverride(File jar)
        {
            this.jarOverride = jar;
            return this;
        }

        /**
         * @param java an explicit {@code java(.exe)} path, taking precedence over
         *            {@link #ENV_JAVA}
         * @return this request
         */
        public Request javaOverride(File java)
        {
            this.javaOverride = java;
            return this;
        }

        /**
         * @param seconds the subprocess timeout; non-positive resets to the default
         * @return this request
         */
        public Request timeoutSeconds(int seconds)
        {
            this.timeoutSeconds = seconds > 0 ? seconds : DEFAULT_TIMEOUT_SECONDS;
            return this;
        }
    }

    /** Outcome of a run: either a parsed {@link BslLsReport} or an actionable error message. */
    public static final class Result
    {
        private final BslLsReport report;
        private final String errorMessage;

        private Result(BslLsReport report, String errorMessage)
        {
            this.report = report;
            this.errorMessage = errorMessage;
        }

        static Result ok(BslLsReport report)
        {
            return new Result(report, null);
        }

        static Result error(String message)
        {
            return new Result(null, message);
        }

        /** @return {@code true} when the engine ran and its report parsed. */
        public boolean ok()
        {
            return errorMessage == null;
        }

        /** @return the parsed report, or {@code null} on failure. */
        public BslLsReport report()
        {
            return report;
        }

        /** @return the actionable failure message, or {@code null} on success. */
        public String errorMessage()
        {
            return errorMessage;
        }
    }

    /**
     * Resolves the jar and Java, launches the engine on {@code request.srcDir}, and
     * parses its JSON report. Never throws for an operational problem (missing
     * jar/Java, non-zero exit, timeout, unreadable report) — those come back as
     * {@link Result#error(String)} with an actionable message.
     *
     * @param request the run inputs (must be non-{@code null} with an existing srcDir)
     * @return the run outcome (never {@code null})
     */
    public static Result run(Request request)
    {
        if (request == null || request.srcDir == null)
        {
            return Result.error("Internal error: no source directory provided to the BSL Language Server."); //$NON-NLS-1$
        }
        if (!request.srcDir.isDirectory())
        {
            return Result.error("Source directory does not exist: " + request.srcDir); //$NON-NLS-1$
        }

        File jar = resolveJar(request.jarOverride);
        if (jar == null)
        {
            return Result.error(jarNotFoundMessage());
        }
        File java = resolveJava(request.javaOverride);
        if (java == null)
        {
            return Result.error(javaNotFoundMessage());
        }
        File config = resolveConfig(request.configFile, jar);

        Path outputDir;
        try
        {
            outputDir = Files.createTempDirectory("bslls"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return Result.error("Could not create a temporary output directory for the BSL Language Server: " //$NON-NLS-1$
                + e.getMessage());
        }

        try
        {
            return execute(java, jar, config, request, outputDir);
        }
        finally
        {
            deleteQuietly(outputDir);
        }
    }

    /**
     * Builds the engine CLI invocation. Pure/side-effect-free (no process launched), so
     * it is directly unit-testable.
     * <p>
     * {@code --workspaceDir} is always passed explicitly (never left to the engine's own
     * default, which is its process CWD): it is the project's own workspace root, kept
     * stable across a whole-project run and a single-module run (where {@code --srcDir}
     * narrows to just the module's containing folder). This matters because the CLI's
     * {@code --configuration}/{@code -c} flag is documented upstream as populating the
     * engine's <b>global</b> configuration slot (searched, when omitted, via the process
     * CWD then the user's home directory) — it is not itself workspace-scoped. Pinning
     * {@code --workspaceDir} to the project root keeps report path relativization and any
     * workspace-local {@code .bsl-language-server.json} discovery tied to THIS project,
     * regardless of how narrow {@code --srcDir} is for this particular run.
     *
     * @param java the resolved java(.exe) launcher
     * @param jar the resolved engine jar
     * @param config the resolved configuration file, or {@code null} to omit {@code --configuration}
     * @param request the run inputs ({@link Request#srcDir} and optional {@link Request#workspaceDir})
     * @param outputDir the temp directory the engine writes its report into
     * @return the full command line, ready for {@link ProcessBuilder}
     */
    static List<String> buildCommand(File java, File jar, File config, Request request, Path outputDir)
    {
        List<String> command = new ArrayList<>();
        command.add(java.getAbsolutePath());
        command.add("-Dfile.encoding=UTF-8"); //$NON-NLS-1$
        command.add("-jar"); //$NON-NLS-1$
        command.add(jar.getAbsolutePath());
        command.add("--analyze"); //$NON-NLS-1$
        command.add("--srcDir"); //$NON-NLS-1$
        command.add(request.srcDir.getAbsolutePath());
        command.add("--workspaceDir"); //$NON-NLS-1$
        command.add(resolveWorkspaceDir(request).getAbsolutePath());
        command.add("--outputDir"); //$NON-NLS-1$
        command.add(outputDir.toString());
        command.add("--reporter"); //$NON-NLS-1$
        command.add("json"); //$NON-NLS-1$
        command.add("--silent"); //$NON-NLS-1$
        if (config != null)
        {
            command.add("--configuration"); //$NON-NLS-1$
            command.add(config.getAbsolutePath());
        }
        return command;
    }

    /**
     * @param request the run inputs
     * @return {@link Request#workspaceDir} when set, else {@link Request#srcDir} (so a
     *         caller that does not care about the whole-project/single-module distinction
     *         keeps today's behaviour of scoping the workspace to the analyzed directory)
     */
    static File resolveWorkspaceDir(Request request)
    {
        return request.workspaceDir != null ? request.workspaceDir : request.srcDir;
    }

    private static Result execute(File java, File jar, File config, Request request, Path outputDir)
    {
        List<String> command = buildCommand(java, jar, config, request, outputDir);

        ProcessBuilder pb = new ProcessBuilder(command);
        // Working directory MUST share a filesystem root with the analyzed sources: the engine
        // relativizes each source file against the process CWD (getFileInfoFromFile), which throws
        // "'other' has different root" when CWD and the sources are on different drives (e.g. a temp
        // dir on C: vs a project on D:, common on Windows). The scope dir is always under the project,
        // so use it as CWD; the outputDir stays an absolute path and may live on any drive.
        pb.directory(request.srcDir);
        pb.redirectErrorStream(true);

        Process process;
        try
        {
            process = pb.start();
        }
        catch (IOException e)
        {
            return Result.error("Failed to launch the BSL Language Server (" + java.getAbsolutePath() //$NON-NLS-1$
                + "): " + e.getMessage()); //$NON-NLS-1$
        }

        StringBuilder captured = new StringBuilder();
        Thread drain = drainAsync(process, captured);

        boolean finished;
        try
        {
            finished = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Result.error("Interrupted while waiting for the BSL Language Server."); //$NON-NLS-1$
        }

        if (!finished)
        {
            process.destroyForcibly();
            join(drain);
            return Result.error("BSL Language Server timed out after " + request.timeoutSeconds //$NON-NLS-1$
                + "s. Narrow the scope or raise the timeout."); //$NON-NLS-1$
        }
        join(drain);

        int exit = process.exitValue();
        Path reportPath = outputDir.resolve(REPORT_FILE);
        if (!Files.isRegularFile(reportPath))
        {
            return Result.error("BSL Language Server produced no JSON report (exit " + exit + "). " //$NON-NLS-1$ //$NON-NLS-2$
                + "Engine output: " + tail(captured.toString())); //$NON-NLS-1$
        }

        // Checked BEFORE any read: a pathologically large report must not be pulled fully into
        // memory (then handed to Gson to build a full DOM over) just to eventually get truncated by
        // OutputSizeGuard on the rendered response text - fail loud instead, with the same "narrow
        // the scope" guidance the timeout error gives.
        long reportSize;
        try
        {
            reportSize = Files.size(reportPath);
        }
        catch (IOException e)
        {
            return Result.error("Could not read the BSL Language Server report: " + e.getMessage()); //$NON-NLS-1$
        }
        if (reportSize > MAX_REPORT_BYTES)
        {
            return Result.error("BSL Language Server report is " + reportSize + " bytes, over the " //$NON-NLS-1$ //$NON-NLS-2$
                + MAX_REPORT_BYTES + "-byte limit; not read into memory. Narrow the scope (pass a " //$NON-NLS-1$
                + "modulePath) and re-run."); //$NON-NLS-1$
        }

        String json;
        try
        {
            json = new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return Result.error("Could not read the BSL Language Server report: " + e.getMessage()); //$NON-NLS-1$
        }

        try
        {
            return Result.ok(BslLsReport.parse(json));
        }
        catch (IllegalArgumentException e)
        {
            return Result.error("BSL Language Server report was not parseable: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the engine jar: explicit override, then {@link #ENV_JAR}, then a scan of
     * a default folder ({@code <user.home>/bsl-language-server}). Returns the first
     * existing jar, or {@code null} when none is found.
     */
    static File resolveJar(File override)
    {
        if (isFile(override))
        {
            return override;
        }
        File fromEnv = fileFromEnv(ENV_JAR);
        if (isFile(fromEnv))
        {
            return fromEnv;
        }
        File defaultDir = new File(System.getProperty("user.home", ""), "bsl-language-server"); //$NON-NLS-1$ //$NON-NLS-2$
        File scanned = scanForExecJar(defaultDir);
        return scanned;
    }

    static File scanForExecJar(File dir)
    {
        if (dir == null || !dir.isDirectory())
        {
            return null;
        }
        File[] jars = dir.listFiles((d, name) -> name.startsWith("bsl-language-server") //$NON-NLS-1$
            && name.endsWith("-exec.jar")); //$NON-NLS-1$
        if (jars == null || jars.length == 0)
        {
            return null;
        }
        // Prefer the NEWEST version, comparing the embedded version NUMERICALLY component-by-
        // component (see compareJarVersions) - a plain filename compareTo is lexicographic, which
        // gets this backwards ("...-1.9.0-exec.jar" sorts AFTER "...-1.10.0-exec.jar", silently
        // keeping the OLDER of two releases). This matters especially here: the two claimed major
        // lines (0.28.x / 1.x) need DIFFERENT Java versions to run, so picking the wrong one is not
        // just "an older version" but potentially a launch failure.
        File best = jars[0];
        for (File j : jars)
        {
            if (compareJarVersions(j.getName(), best.getName()) > 0)
            {
                best = j;
            }
        }
        return best;
    }

    /**
     * Compares two {@code bsl-language-server-<version>-exec.jar} filenames by their embedded
     * version (see {@link #compareVersions}). When a version cannot be extracted from EITHER name
     * (an unexpected filename shape slipped past the {@link #scanForExecJar} glob), falls back to a
     * plain filename comparison so scanning still terminates deterministically rather than throwing.
     *
     * @return negative/zero/positive as {@code nameA}'s version is older/equal/newer than {@code nameB}'s
     */
    static int compareJarVersions(String nameA, String nameB)
    {
        String va = extractVersion(nameA);
        String vb = extractVersion(nameB);
        if (va == null || vb == null)
        {
            return nameA.compareTo(nameB);
        }
        return compareVersions(va, vb);
    }

    /**
     * @param fileName a candidate exec-jar filename
     * @return the dotted version substring between the {@code "bsl-language-server-"} prefix and
     *         the {@code "-exec.jar"} suffix (e.g. {@code "1.10.0"} from
     *         {@code "bsl-language-server-1.10.0-exec.jar"}), or {@code null} when the name does not
     *         have that shape
     */
    static String extractVersion(String fileName)
    {
        String prefix = "bsl-language-server-"; //$NON-NLS-1$
        String suffix = "-exec.jar"; //$NON-NLS-1$
        if (fileName == null || !fileName.startsWith(prefix) || !fileName.endsWith(suffix)
            || fileName.length() < prefix.length() + suffix.length())
        {
            return null;
        }
        return fileName.substring(prefix.length(), fileName.length() - suffix.length());
    }

    /**
     * Compares two dotted version strings (e.g. {@code "0.28.0"}, {@code "1.10.0"}) NUMERICALLY,
     * component by component - NOT lexicographically, where {@code "1.9.0"} would wrongly sort
     * after {@code "1.10.0"}. A version with fewer components is padded with {@code 0} for the
     * comparison (so {@code "1.9"} == {@code "1.9.0"}). A non-numeric component (e.g. a pre-release
     * suffix glued onto the last segment, like {@code "0-rc1"}) falls back to a plain string
     * comparison for just THAT component - full SemVer pre-release precedence is not implemented,
     * this only needs to stay deterministic and not throw on the rare pre-release jar name.
     *
     * @return negative/zero/positive as {@code a} is older/equal/newer than {@code b}
     */
    static int compareVersions(String a, String b)
    {
        String[] pa = a.split("\\."); //$NON-NLS-1$
        String[] pb = b.split("\\."); //$NON-NLS-1$
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++)
        {
            String sa = i < pa.length ? pa[i] : "0"; //$NON-NLS-1$
            String sb = i < pb.length ? pb[i] : "0"; //$NON-NLS-1$
            int cmp = compareVersionComponent(sa, sb);
            if (cmp != 0)
            {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareVersionComponent(String sa, String sb)
    {
        try
        {
            return Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
        }
        catch (NumberFormatException e)
        {
            return sa.compareTo(sb);
        }
    }

    /**
     * Resolves the Java launcher: explicit override, then {@link #ENV_JAVA}, then the
     * JRE running EDT ({@code java.home}). Returns {@code null} only if none resolves to
     * an existing file (practically never — {@code java.home} is always set).
     */
    static File resolveJava(File override)
    {
        if (isFile(override))
        {
            return override;
        }
        File fromEnv = fileFromEnv(ENV_JAVA);
        if (isFile(fromEnv))
        {
            return fromEnv;
        }
        String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
        if (javaHome != null && !javaHome.isEmpty())
        {
            File bin = new File(javaHome, "bin"); //$NON-NLS-1$
            File exe = new File(bin, isWindows() ? "java.exe" : "java"); //$NON-NLS-1$ //$NON-NLS-2$
            if (exe.isFile())
            {
                return exe;
            }
        }
        return null;
    }

    /**
     * Resolves the engine configuration file: the project's own
     * {@code .bsl-language-server.json} (if present), else the one sitting next to the
     * jar (engine home), else {@code null} (engine defaults).
     */
    static File resolveConfig(File projectConfig, File jar)
    {
        if (isFile(projectConfig))
        {
            return projectConfig;
        }
        if (jar != null && jar.getParentFile() != null)
        {
            File engineHomeConfig = new File(jar.getParentFile(), ".bsl-language-server.json"); //$NON-NLS-1$
            if (engineHomeConfig.isFile())
            {
                return engineHomeConfig;
            }
        }
        return null;
    }

    private static String jarNotFoundMessage()
    {
        return "BSL Language Server engine not found. Set " + ENV_JAR //$NON-NLS-1$
            + " to the path of bsl-language-server-<version>-exec.jar, or place it in " //$NON-NLS-1$
            + "<user.home>/bsl-language-server. Download it from " + RELEASES_URL //$NON-NLS-1$
            + " (the 1.x line needs Java 21; the 0.28.x line runs on Java 17)."; //$NON-NLS-1$
    }

    private static String javaNotFoundMessage()
    {
        return "No Java runtime found to launch the BSL Language Server. Set " + ENV_JAVA //$NON-NLS-1$
            + " to a java executable (Java 21+ for the 1.x engine, Java 17 for 0.28.x)."; //$NON-NLS-1$
    }

    /**
     * Drains the process's merged stdout+stderr on a background thread so the child never blocks
     * on a full OS pipe buffer, while keeping {@code sink}'s RETAINED size bounded to
     * {@link #MAX_CAPTURED_OUTPUT_CHARS}: the stream is read in full regardless (every line is
     * consumed), but once the buffer exceeds the cap its FRONT is trimmed, so a subprocess that
     * produces gigabytes of chatter (or loops printing) cannot grow this buffer without bound — only
     * the tail is ever shown to a caller anyway (see {@link #tail}).
     */
    private static Thread drainAsync(Process process, StringBuilder sink)
    {
        Thread t = new Thread(() -> {
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    synchronized (sink)
                    {
                        sink.append(line).append('\n');
                        if (sink.length() > MAX_CAPTURED_OUTPUT_CHARS)
                        {
                            sink.delete(0, sink.length() - MAX_CAPTURED_OUTPUT_CHARS);
                        }
                    }
                }
            }
            catch (IOException e)
            {
                // Stream closed on process exit/kill — nothing actionable.
            }
        }, "bslls-drain"); //$NON-NLS-1$
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t)
    {
        try
        {
            t.join(2000);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns the last portion of engine output, so an error message stays bounded. */
    private static String tail(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        String trimmed = s.trim();
        int max = 600;
        if (trimmed.length() <= max)
        {
            return trimmed;
        }
        return "…" + trimmed.substring(trimmed.length() - max); //$NON-NLS-1$
    }

    private static void deleteQuietly(Path dir)
    {
        if (dir == null)
        {
            return;
        }
        try
        {
            Files.walk(dir)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException ignored)
                    {
                        // best effort
                    }
                });
        }
        catch (IOException ignored)
        {
            // best effort
        }
    }

    private static File fileFromEnv(String var)
    {
        String value = System.getenv(var);
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        return new File(value.trim());
    }

    private static boolean isFile(File f)
    {
        return f != null && f.isFile();
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
