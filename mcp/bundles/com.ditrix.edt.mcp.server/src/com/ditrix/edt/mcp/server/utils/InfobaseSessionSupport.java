/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.v8.dt.platform.standaloneserver.core.Ibcmd;
import com.e1c.g5.v8.dt.platform.standaloneserver.core.SessionCommandBuilder;

/**
 * Reads and terminates standalone-server infobase sessions through {@code ibcmd}.
 *
 * <p>The result deliberately makes reachability structural: a readable result always carries a
 * non-null session list, including an empty list that proves there are no sessions; an unreachable
 * result carries no list and must name why the server could not be inspected. This prevents a
 * failed lookup from silently becoming an empty-list claim.
 */
public final class InfobaseSessionSupport
{
    /** Internal EDT interface matched by name to keep the WST implementation package optional. */
    private static final String STANDALONE_SERVER_PROCESS_INTERFACE =
        "com.e1c.g5.v8.dt.internal.platform.standaloneserver.wst.core.IStandaloneServerProcess"; //$NON-NLS-1$

    /** EDT uses the same deadline for its own standalone-server session cleanup command. */
    private static final long COMMAND_TIMEOUT_MS = 10_000L;

    /** Outer bound includes forced process cleanup and output collection. */
    private static final long JOB_TIMEOUT_MS = 15_000L;

    /** Grace for a force-killed process and its stream readers to close. */
    private static final long PROCESS_CLEANUP_TIMEOUT_MS = 2_000L;

    /** Prevents a platform diagnostic from turning one tool result into an unbounded payload. */
    private static final int MAX_DIAGNOSTIC_CHARS = 1_000;

    private InfobaseSessionSupport()
    {
    }

    /** Whether the session list was actually read. */
    public enum Reachability
    {
        /** {@code ibcmd session list} completed and its list is available, possibly empty. */
        READABLE,
        /** The list could not be read; {@link ReadResult#unreachableReason()} names why. */
        UNREACHABLE
    }

    /** One session record parsed from an {@code ibcmd session list} key/value block. */
    public record SessionInfo(String sessionId, Long sessionNumber, String applicationKind,
        String userName, String host, String startedAt, String lastActiveAt, boolean edtAgent)
    {
    }

    /**
     * A three-state observation: a non-empty list, a proven empty list, or an unreachable target.
     * The compact constructor makes the last two impossible to conflate in Java code.
     */
    public record ReadResult(Reachability reachability, List<SessionInfo> sessions,
        String unreachableReason)
    {
        /** Enforces the mutually exclusive readable/unreachable payload shapes. */
        public ReadResult
        {
            if (reachability == Reachability.READABLE)
            {
                if (sessions == null || unreachableReason != null)
                {
                    throw new IllegalArgumentException(
                        "A readable session result requires a list and no unreachable reason"); //$NON-NLS-1$
                }
                sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
            }
            else if (reachability == Reachability.UNREACHABLE)
            {
                if (sessions != null || unreachableReason == null || unreachableReason.isBlank())
                {
                    throw new IllegalArgumentException(
                        "An unreachable session result requires a reason and no session list"); //$NON-NLS-1$
                }
            }
            else
            {
                throw new IllegalArgumentException("Session reachability is required"); //$NON-NLS-1$
            }
        }

        /** @return a readable observation, preserving an empty list as a proven answer */
        public static ReadResult readable(List<SessionInfo> sessions)
        {
            return new ReadResult(Reachability.READABLE, sessions, null);
        }

        /** @return an unreachable observation carrying its mandatory reason */
        public static ReadResult unreachable(String reason)
        {
            return new ReadResult(Reachability.UNREACHABLE, null, reason);
        }

        /** @return whether {@code sessions()} is a real observation */
        public boolean isReadable()
        {
            return reachability == Reachability.READABLE;
        }
    }

    /** Outcome of one requested termination. */
    public record TerminationResult(Reachability reachability, boolean terminated,
        String unreachableReason)
    {
        /** Enforces that only a completed command may claim a termination. */
        public TerminationResult
        {
            if (reachability == Reachability.READABLE)
            {
                if (!terminated || unreachableReason != null)
                {
                    throw new IllegalArgumentException(
                        "A completed termination requires no unreachable reason"); //$NON-NLS-1$
                }
            }
            else if (reachability == Reachability.UNREACHABLE)
            {
                if (terminated || unreachableReason == null || unreachableReason.isBlank())
                {
                    throw new IllegalArgumentException(
                        "A failed termination requires an unreachable reason"); //$NON-NLS-1$
                }
            }
            else
            {
                throw new IllegalArgumentException("Termination reachability is required"); //$NON-NLS-1$
            }
        }

        /** @return a completed termination */
        public static TerminationResult succeeded()
        {
            return new TerminationResult(Reachability.READABLE, true, null);
        }

        /** @return a termination command that could not be completed */
        public static TerminationResult unreachable(String reason)
        {
            return new TerminationResult(Reachability.UNREACHABLE, false, reason);
        }
    }

    /**
     * Lists sessions for a running standalone-server application.
     *
     * @param application the target application
     * @return readable sessions, or an unreachable result with a named reason
     */
    public static ReadResult listSessions(IApplication application)
    {
        try
        {
            PreparedTarget target = prepare(application);
            if (target.error != null)
            {
                return ReadResult.unreachable(target.error);
            }

            AtomicReference<CommandExecution> execution = new AtomicReference<>();
            BoundedJob.Result bounded = BoundedJob.run("Read standalone-server infobase sessions", //$NON-NLS-1$
                JOB_TIMEOUT_MS, monitor -> execution.set(runAtLivePid(target.server,
                    pid -> runCommand(target.ibcmd.session().forStandaloneServerProcessWithPid(pid)
                        .list().build(), target.credentials))));
            String boundedFailure = boundedFailure("list", bounded); //$NON-NLS-1$
            if (boundedFailure != null)
            {
                return ReadResult.unreachable(boundedFailure);
            }
            CommandExecution command = execution.get();
            if (command == null)
            {
                return ReadResult.unreachable("The standalone server is not running."); //$NON-NLS-1$
            }
            String commandFailure = commandFailure("list", command); //$NON-NLS-1$
            if (commandFailure != null)
            {
                return ReadResult.unreachable(commandFailure);
            }
            return readSessionsOutput(command.stdout);
        }
        catch (RuntimeException e)
        {
            Activator.logError("infobase sessions: session-list infrastructure failed", e); //$NON-NLS-1$
            return ReadResult.unreachable("Session inspection infrastructure failed: " //$NON-NLS-1$
                + PlatformFailures.describe(e));
        }
    }

    /**
     * Terminates one session UUID on a running standalone server. Callers must list first so the
     * UUID is known and the Designer session has already been excluded.
     *
     * @param application the target application
     * @param sessionId the full session UUID
     * @param message optional message shown to the terminated user
     * @return completed termination, or an unreachable result with a named reason
     */
    public static TerminationResult terminateSession(IApplication application, String sessionId,
        String message)
    {
        try
        {
            PreparedTarget target = prepare(application);
            if (target.error != null)
            {
                return TerminationResult.unreachable(target.error);
            }

            AtomicReference<CommandExecution> execution = new AtomicReference<>();
            BoundedJob.Result bounded = BoundedJob.run(
                "Terminate standalone-server infobase session", //$NON-NLS-1$
                JOB_TIMEOUT_MS, monitor -> execution.set(runAtLivePid(target.server, pid ->
                {
                    SessionCommandBuilder.TerminateBuilder builder = target.ibcmd.session()
                        .forStandaloneServerProcessWithPid(pid).terminate();
                    if (message != null && !message.isBlank())
                    {
                        builder = builder.withErrorMessage(message);
                    }
                    return runCommand(builder.sessionId(sessionId).build(), target.credentials);
                })));
            String boundedFailure = boundedFailure("terminate", bounded); //$NON-NLS-1$
            if (boundedFailure != null)
            {
                return TerminationResult.unreachable(boundedFailure);
            }
            CommandExecution command = execution.get();
            if (command == null)
            {
                return TerminationResult.unreachable("The standalone server is not running."); //$NON-NLS-1$
            }
            String commandFailure = commandFailure("terminate", command); //$NON-NLS-1$
            return commandFailure == null ? TerminationResult.succeeded()
                : TerminationResult.unreachable(commandFailure);
        }
        catch (RuntimeException e)
        {
            Activator.logError("infobase sessions: termination infrastructure failed", e); //$NON-NLS-1$
            return TerminationResult.unreachable("Session termination infrastructure failed: " //$NON-NLS-1$
                + PlatformFailures.describe(e));
        }
    }

    /** Resolves the WST server, runtime, ibcmd executable, and stored credentials. */
    private static PreparedTarget prepare(IApplication application)
    {
        if (application == null)
        {
            return PreparedTarget.error("No application was resolved."); //$NON-NLS-1$
        }
        String typeId = application.getType() == null ? null : application.getType().getId();
        if (!StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId))
        {
            return PreparedTarget.error("Application '" + application.getId() //$NON-NLS-1$
                + "' is not a standalone-server (wst-server) application; ibcmd sessions " //$NON-NLS-1$
                + "cannot be inspected for this application type."); //$NON-NLS-1$
        }
        Object server = StandaloneServerSupport.serverOfApplication(application);
        if (server == null)
        {
            return PreparedTarget.error("EDT could not resolve the standalone server for application '" //$NON-NLS-1$
                + application.getId() + "'."); //$NON-NLS-1$
        }
        Path runtimeLocation;
        try
        {
            runtimeLocation = runtimeLocation(server);
        }
        catch (Exception e)
        {
            return PreparedTarget.error("The standalone-server runtime location could not be read: " //$NON-NLS-1$
                + PlatformFailures.describe(unwrap(e)));
        }
        if (runtimeLocation == null)
        {
            return PreparedTarget.error("The standalone-server runtime location is unknown."); //$NON-NLS-1$
        }
        Ibcmd ibcmd = Ibcmd.of(runtimeLocation);
        if (!ibcmd.exists())
        {
            return PreparedTarget.error("ibcmd is missing at '" + ibcmd.getExecutablePath() //$NON-NLS-1$
                + "'. Select a 1C runtime that includes the standalone server."); //$NON-NLS-1$
        }
        return PreparedTarget.of(server, ibcmd,
            InfobaseAccessSupport.readCredentials(application));
    }

    /** Reads {@code IServer.getRuntime().getLocation()} without importing the optional WST API. */
    private static Path runtimeLocation(Object server) throws Exception
    {
        Object runtime = invokeNoArg(server, "getRuntime"); //$NON-NLS-1$
        if (runtime == null)
        {
            return null;
        }
        Object location = invokeNoArg(runtime, "getLocation"); //$NON-NLS-1$
        if (location instanceof IPath)
        {
            return ((IPath)location).toFile().toPath();
        }
        if (location instanceof Path)
        {
            return (Path)location;
        }
        return null;
    }

    /** Invokes a public no-argument accessor and unwraps the platform's real failure. */
    private static Object invokeNoArg(Object target, String methodName) throws Exception
    {
        try
        {
            return target.getClass().getMethod(methodName).invoke(target);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause != null ? cause : e);
        }
    }

    /**
     * Locates the internal standalone-server process by interface name and executes the callback
     * from {@code proceedSessionCleanup}. The callback runs while EDT still owns a live process.
     */
    private static CommandExecution runAtLivePid(Object server, PidCommand command) throws Exception
    {
        Object launchObject = invokeNoArg(server, "getLaunch"); //$NON-NLS-1$
        if (!(launchObject instanceof ILaunch))
        {
            return null;
        }
        IProcess[] processes = ((ILaunch)launchObject).getProcesses();
        if (processes == null)
        {
            return null;
        }
        for (IProcess process : processes)
        {
            Class<?> processInterface = namedInterface(process == null ? null : process.getClass(),
                STANDALONE_SERVER_PROCESS_INTERFACE);
            if (processInterface == null)
            {
                continue;
            }
            AtomicReference<CommandExecution> execution = new AtomicReference<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            LongConsumer consumer = pid ->
            {
                try
                {
                    execution.set(command.run(pid));
                }
                catch (Throwable t) // NOSONAR transferred to the bounded job thread below
                {
                    callbackFailure.set(t);
                }
            };
            try
            {
                Method proceed = processInterface.getMethod("proceedSessionCleanup", //$NON-NLS-1$
                    LongConsumer.class);
                proceed.invoke(process, consumer);
            }
            catch (InvocationTargetException e)
            {
                throwAsException(e.getCause() != null ? e.getCause() : e);
            }
            if (callbackFailure.get() != null)
            {
                throwAsException(callbackFailure.get());
            }
            return execution.get();
        }
        return null;
    }

    /** Finds an implemented interface recursively by its fully qualified name. */
    private static Class<?> namedInterface(Class<?> type, String interfaceName)
    {
        if (type == null)
        {
            return null;
        }
        for (Class<?> candidate : type.getInterfaces())
        {
            if (interfaceName.equals(candidate.getName()))
            {
                return candidate;
            }
            Class<?> nested = namedInterface(candidate, interfaceName);
            if (nested != null)
            {
                return nested;
            }
        }
        return namedInterface(type.getSuperclass(), interfaceName);
    }

    /** Runs one ibcmd process, drains both streams concurrently, and force-kills it on timeout. */
    private static CommandExecution runCommand(List<String> command,
        InfobaseAccessSupport.Credentials credentials) throws Exception
    {
        Process process = new ProcessBuilder(command).start();
        try
        {
            Charset readCharset = isWindows()
                ? Charset.forName("CP866") : StandardCharsets.UTF_8; //$NON-NLS-1$
            FutureTask<String> stdout = streamReader(process.getInputStream(), readCharset);
            FutureTask<String> stderr = streamReader(process.getErrorStream(), readCharset);
            writeCredentials(process, credentials);

            boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished)
            {
                process.destroyForcibly();
                waitAfterDestroy(process);
                return new CommandExecution(-1, readStream(stdout), readStream(stderr), true);
            }
            return new CommandExecution(process.exitValue(), readStream(stdout),
                readStream(stderr), false);
        }
        catch (InterruptedException e)
        {
            process.destroyForcibly();
            waitAfterDestroy(process);
            Thread.currentThread().interrupt();
            throw e;
        }
        catch (Exception e)
        {
            if (process.isAlive())
            {
                process.destroyForcibly();
                waitAfterDestroy(process);
            }
            throw e;
        }
    }

    /** Starts a daemon reader so a verbose session list cannot fill a process pipe and deadlock. */
    private static FutureTask<String> streamReader(InputStream stream, Charset charset)
    {
        FutureTask<String> task = new FutureTask<>(() -> readAll(stream, charset));
        Thread thread = new Thread(task, "EDT-MCP ibcmd output reader"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /** Reads one process stream to EOF. */
    private static String readAll(InputStream stream, Charset charset) throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset)))
        {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0)
            {
                text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }

    /** Supplies EDT's stored interactive credentials and closes stdin so a prompt fails fast. */
    private static void writeCredentials(Process process,
        InfobaseAccessSupport.Credentials credentials) throws IOException
    {
        try (OutputStream input = process.getOutputStream())
        {
            if (credentials != null && !credentials.userName().isBlank())
            {
                String auth = credentials.userName() + "\n" + credentials.password() + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
                input.write(auth.getBytes(StandardCharsets.UTF_8));
                input.flush();
            }
        }
    }

    /** Waits briefly for a force-killed process without extending the command deadline indefinitely. */
    private static void waitAfterDestroy(Process process)
    {
        try
        {
            process.waitFor(PROCESS_CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Collects a stream reader's terminal value under a short post-process deadline. */
    private static String readStream(FutureTask<String> task) throws Exception
    {
        try
        {
            return task.get(PROCESS_CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (ExecutionException e)
        {
            throwAsException(e.getCause() != null ? e.getCause() : e);
            return ""; //$NON-NLS-1$ unreachable, required by javac flow analysis
        }
        catch (TimeoutException e)
        {
            task.cancel(true);
            throw new IOException("Timed out while collecting ibcmd output", e); //$NON-NLS-1$
        }
    }

    /** Parsed sessions plus contentful key/value blocks that had no usable session UUID. */
    static record ParsedSessions(List<SessionInfo> sessions, int unreadableBlocks)
    {
        ParsedSessions
        {
            sessions = List.copyOf(sessions);
        }
    }

    /** Turns successful command output into a readable list only when every parsed block is usable. */
    static ReadResult readSessionsOutput(String output)
    {
        ParsedSessions parsed = parseSessions(output);
        if (parsed.unreadableBlocks() > 0)
        {
            return ReadResult.unreachable("ibcmd session list returned " //$NON-NLS-1$
                + parsed.unreadableBlocks() + " unreadable session block" //$NON-NLS-1$
                + (parsed.unreadableBlocks() == 1 ? "" : "s") + ": " + concise(output)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (parsed.sessions().isEmpty() && output != null && !output.isBlank())
        {
            return ReadResult.unreachable("ibcmd session list returned unrecognized output: " //$NON-NLS-1$
                + concise(output));
        }
        return ReadResult.readable(parsed.sessions());
    }

    /** Parses one blank-line-separated key/value block per session. */
    static ParsedSessions parseSessions(String output)
    {
        List<SessionInfo> sessions = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        int unreadableBlocks = 0;
        String normalized = output == null ? "" : output; //$NON-NLS-1$
        for (String line : normalized.split("\\R", -1)) //$NON-NLS-1$
        {
            if (line.isBlank())
            {
                unreadableBlocks += addSession(current, sessions);
                current.clear();
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0)
            {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("session".equals(key) && current.containsKey("session")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                unreadableBlocks += addSession(current, sessions);
                current.clear();
            }
            current.put(key, value);
        }
        unreadableBlocks += addSession(current, sessions);
        return new ParsedSessions(sessions, unreadableBlocks);
    }

    /** Converts a parsed block, returning one only for content that lacks a usable session UUID. */
    private static int addSession(Map<String, String> values, List<SessionInfo> sessions)
    {
        if (values.isEmpty())
        {
            return 0;
        }
        String id = values.get("session"); //$NON-NLS-1$
        if (id == null || id.isBlank())
        {
            return 1;
        }
        String applicationKind = value(values, "app-id"); //$NON-NLS-1$
        sessions.add(new SessionInfo(id, parseSessionNumber(values.get("session-id")), //$NON-NLS-1$
            applicationKind, value(values, "user-name"), value(values, "host"), //$NON-NLS-1$ //$NON-NLS-2$
            value(values, "started-at"), value(values, "last-active-at"), //$NON-NLS-1$ //$NON-NLS-2$
            "Designer".equalsIgnoreCase(applicationKind))); //$NON-NLS-1$
        return 0;
    }

    /** Null-safe parsed value; ibcmd uses empty strings for unavailable fields. */
    private static String value(Map<String, String> values, String key)
    {
        String value = values.get(key);
        return value == null ? "" : value; //$NON-NLS-1$
    }

    /** Parses the short numeric session-id without affecting the full UUID identity. */
    private static Long parseSessionNumber(String value)
    {
        try
        {
            return value == null || value.isBlank() ? null : Long.valueOf(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** Converts the outer job result into a named reachability reason, or {@code null}. */
    private static String boundedFailure(String action, BoundedJob.Result result)
    {
        if (result.isSuccess())
        {
            return null;
        }
        if (result.getFailure() != null)
        {
            Throwable failure = unwrap(result.getFailure());
            Activator.logError("infobase sessions: ibcmd session " + action + " failed", failure); //$NON-NLS-1$ //$NON-NLS-2$
            return "ibcmd session " + action + " failed: " + PlatformFailures.describe(failure); //$NON-NLS-1$ //$NON-NLS-2$
        }
        switch (result.getOutcome())
        {
            case TIMED_OUT:
                return "ibcmd session " + action //$NON-NLS-1$
                    + " exceeded its bounded worker deadline; the process was asked to stop."; //$NON-NLS-1$
            case TIMED_OUT_BEFORE_START:
                return "The bounded ibcmd session " + action //$NON-NLS-1$
                    + " job timed out before it started; retry after EDT background work settles."; //$NON-NLS-1$
            case INTERRUPTED:
                return "The ibcmd session " + action + " command was interrupted; retry the call."; //$NON-NLS-1$ //$NON-NLS-2$
            case NOT_RUN:
                return "The ibcmd session " + action //$NON-NLS-1$
                    + " job was cancelled before it ran; retry the call."; //$NON-NLS-1$
            default:
                return "The ibcmd session " + action + " command did not complete."; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Converts a completed process outcome into a named reason, or {@code null} on exit zero. */
    private static String commandFailure(String action, CommandExecution command)
    {
        if (command.timedOut)
        {
            return "ibcmd session " + action + " timed out after 10 seconds and was terminated."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (command.exitCode == 0)
        {
            return null;
        }
        String diagnostic = command.stderr.isBlank() ? command.stdout : command.stderr;
        diagnostic = concise(diagnostic);
        return "ibcmd session " + action + " failed with exit code " + command.exitCode //$NON-NLS-1$ //$NON-NLS-2$
            + (diagnostic.isEmpty() ? "." : ": " + diagnostic); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Flattens and caps command diagnostics while preserving their useful text. */
    private static String concise(String diagnostic)
    {
        String value = diagnostic == null ? "" : diagnostic.trim().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return value.length() <= MAX_DIAGNOSTIC_CHARS ? value
            : value.substring(0, MAX_DIAGNOSTIC_CHARS) + "..."; //$NON-NLS-1$
    }

    /** Unwraps reflective wrappers before they reach the platform failure formatter. */
    private static Throwable unwrap(Throwable failure)
    {
        Throwable current = failure;
        while (current instanceof InvocationTargetException
            || current instanceof ExecutionException)
        {
            Throwable cause = current.getCause();
            if (cause == null || cause == current)
            {
                break;
            }
            current = cause;
        }
        return current;
    }

    /** Rethrows a callback failure through the checked job boundary. */
    private static void throwAsException(Throwable failure) throws Exception
    {
        if (failure instanceof Exception)
        {
            throw (Exception)failure;
        }
        if (failure instanceof Error)
        {
            throw (Error)failure;
        }
        throw new IllegalStateException(failure);
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Checked callback invoked only while EDT confirms the standalone-server process exists. */
    @FunctionalInterface
    private interface PidCommand
    {
        CommandExecution run(long pid) throws Exception;
    }

    /** Resolved command prerequisites, or a named preparation error. */
    private static final class PreparedTarget
    {
        final Object server;
        final Ibcmd ibcmd;
        final InfobaseAccessSupport.Credentials credentials;
        final String error;

        private PreparedTarget(Object server, Ibcmd ibcmd,
            InfobaseAccessSupport.Credentials credentials, String error)
        {
            this.server = server;
            this.ibcmd = ibcmd;
            this.credentials = credentials;
            this.error = error;
        }

        static PreparedTarget of(Object server, Ibcmd ibcmd,
            InfobaseAccessSupport.Credentials credentials)
        {
            return new PreparedTarget(server, ibcmd, credentials, null);
        }

        static PreparedTarget error(String error)
        {
            return new PreparedTarget(null, null, null, error);
        }
    }

    /** Exit state and captured streams of one bounded ibcmd process. */
    private static final class CommandExecution
    {
        final int exitCode;
        final String stdout;
        final String stderr;
        final boolean timedOut;

        CommandExecution(int exitCode, String stdout, String stderr, boolean timedOut)
        {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout; //$NON-NLS-1$
            this.stderr = stderr == null ? "" : stderr; //$NON-NLS-1$
            this.timedOut = timedOut;
        }
    }
}
