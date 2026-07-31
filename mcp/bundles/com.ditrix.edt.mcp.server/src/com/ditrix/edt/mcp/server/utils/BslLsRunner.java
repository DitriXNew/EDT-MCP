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
 * {@code .bsl-language-server.json} (see {@link Request#configFile}).
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

    private static Result execute(File java, File jar, File config, Request request, Path outputDir)
    {
        List<String> command = new ArrayList<>();
        command.add(java.getAbsolutePath());
        command.add("-Dfile.encoding=UTF-8"); //$NON-NLS-1$
        command.add("-jar"); //$NON-NLS-1$
        command.add(jar.getAbsolutePath());
        command.add("--analyze"); //$NON-NLS-1$
        command.add("--srcDir"); //$NON-NLS-1$
        command.add(request.srcDir.getAbsolutePath());
        command.add("--outputDir"); //$NON-NLS-1$
        command.add(outputDir.toString());
        command.add("--reporter"); //$NON-NLS-1$
        command.add("json"); //$NON-NLS-1$
        if (config != null)
        {
            command.add("--configuration"); //$NON-NLS-1$
            command.add(config.getAbsolutePath());
        }

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

    private static File scanForExecJar(File dir)
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
        // Prefer the lexicographically largest name (roughly the newest version).
        File best = jars[0];
        for (File j : jars)
        {
            if (j.getName().compareTo(best.getName()) > 0)
            {
                best = j;
            }
        }
        return best;
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
