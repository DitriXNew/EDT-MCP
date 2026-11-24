/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Asks a running EDT MCP host to shut down cleanly, from outside its process.
 * <p>
 * A stop script cannot speak the IPC protocol, and it must not: the bootstrap owns the framing,
 * the private registry and the handshake secret. This class is that protocol reduced to one
 * operation, and it runs from the same immutable runtime jar the bootstrap uses - so it needs
 * neither Eclipse nor OSGi, and it is versioned with the host it talks to.
 * <p>
 * The host closes the workspace through the same path the idle timer uses (project contexts
 * stopped, workspace saved, instance lock released), so the next start does not have to recover
 * from a half-written model. That clean close is the whole point: it cannot be reached by killing
 * the JVM.
 * <h2>Exit codes</h2>
 * <ul>
 * <li>{@value #STOPPED} - the host accepted the request and exited.</li>
 * <li>{@value #REFUSED} - the host answered that it will not shut down. It keeps running, and
 * killing it is wrong: this is an interactive EDT, or a host that does not implement the
 * notification.</li>
 * <li>{@value #NOT_STOPPED} - the host accepted but did not exit, or the answer never arrived, so
 * the state is unknown. A bounded force-kill is the caller's reserve.</li>
 * <li>{@value #ABSENT} - there was nothing to stop. Not an error: the caller may start the host
 * next.</li>
 * </ul>
 * <h2>Command line</h2>
 * <pre>
 * java -cp edt-mcp-runtime.jar com.ditrix.edt.mcp.server.ipc.HostShutdownClient
 *      -data &lt;workspace&gt; [-install &lt;edt&gt;] [-timeout &lt;seconds&gt;]
 * </pre>
 * {@code -install} is accepted and ignored: the workspace key the command needs is derived from
 * the workspace path, and requiring the installation would make the caller repeat a value the
 * bootstrap does not use for discovery either.
 */
public final class HostShutdownClient
{
    /** The host confirmed the request and exited. */
    public static final int STOPPED = 0;
    /**
     * The stop was not confirmed: the host accepted but did not exit, or the registry could not be
     * read so the host state is unknown. The caller may force-kill.
     */
    public static final int NOT_STOPPED = 1;

    /**
     * The host answered that it will not shut down. It is still running deliberately - an
     * interactive EDT, or one that ignores the notification - so a wrapper must NOT kill it.
     */
    public static final int REFUSED = 3;
    /** No host is registered for this workspace: the registry and its record do not exist. */
    public static final int ABSENT = 2;

    /** Seconds to wait for the process to go away unless {@code -timeout} overrides it. */
    static final int DEFAULT_TIMEOUT_SECONDS = 20;

    /** How often the exit is re-checked; the wait is short because the host is already dying. */
    private static final long POLL_MILLIS = 50;

    private HostShutdownClient() { }

    public static void main(String[] args)
    {
        System.exit(run(args, System.err));
    }

    /**
     * Runs the command and reports its exit code, without terminating the JVM - so a caller (and a
     * test) sees the outcome itself.
     *
     * @param args command-line arguments
     * @param err where the one-line verdict of a non-zero outcome is written
     * @return {@link #STOPPED}, {@link #NOT_STOPPED} or {@link #ABSENT}
     */
    public static int run(String[] args, PrintStream err)
    {
        String workspace = option(args, "-data");
        if (workspace == null || workspace.isBlank())
        {
            usage(err, "-data is required: name the workspace whose host should stop.");
            return NOT_STOPPED;
        }
        Path directory;
        try { directory = Path.of(workspace); }
        catch (InvalidPathException e) { usage(err, "-data is not a usable path: " + workspace); return NOT_STOPPED; }
        int workspaceState = checkDirectory(directory, "workspace directory", err);
        if (workspaceState != STOPPED) { return workspaceState; }

        long timeout;
        String requested = option(args, "-timeout");
        if (requested == null) { timeout = TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_SECONDS); }
        else
        {
            try
            {
                long seconds = Long.parseLong(requested);
                if (seconds < 0) { throw new NumberFormatException(requested); }
                timeout = TimeUnit.SECONDS.toMillis(seconds);
            }
            catch (NumberFormatException e)
            {
                usage(err, "-timeout must be a nonnegative number of seconds, not " + requested);
                return NOT_STOPPED;
            }
        }
        Path ipc;
        try { ipc = registryDirectory(); }
        catch (IOException e)
        {
            // The storage is unknown, not absent: a live host may still hold the workspace, so
            // the caller must be told the outcome is unconfirmed rather than nothing.
            err.println("edt-mcp-shutdown: cannot locate the per-user MCP storage: " + message(e));
            return NOT_STOPPED;
        }
        return stop(ipc, directory, timeout, err);
    }

    /** The private per-user directory the plugin registers its endpoints in. */
    static Path registryDirectory() throws IOException
    {
        return EndpointRegistry.userDirectory().resolve("ipc");
    }

    private static int checkDirectory(Path path, String label, PrintStream err, LinkOption... options)
    {
        try
        {
            if (Files.readAttributes(path, BasicFileAttributes.class, options).isDirectory()) { return STOPPED; }
            err.println("edt-mcp-shutdown: the " + label + " is not a directory: " + path);
            return NOT_STOPPED;
        }
        catch (NoSuchFileException e)
        {
            if (EndpointRegistry.provablyAbsent(path, options))
            {
                err.println("edt-mcp-shutdown: the " + label + " does not exist: " + path);
                return ABSENT;
            }
            return unknownDirectory(path, label, e, err);
        }
        catch (IOException | SecurityException e) { return unknownDirectory(path, label, e, err); }
    }

    private static int unknownDirectory(Path path, String label, Exception error, PrintStream err)
    {
        err.println("edt-mcp-shutdown: cannot read the " + label + " at " + path
            + ": " + message(error) + "; the host state is unknown");
        return NOT_STOPPED;
    }

    /**
     * True while a start or a teardown may still be in flight for this workspace even though no
     * live endpoint record exists: a recent startup claim, or a workspace lock that cannot be
     * taken because a process still owns it. A claim left behind by a dead process ages out; the
     * lock is the authoritative signal, and a lock we can take means nothing owns the workspace.
     *
     * @param registry the private per-workspace registry
     * @return whether the workspace may still belong to a running host
     */
    static boolean startupOrTeardownInFlight(EndpointRegistry registry)
    {
        // One shared probe: the stop command and StartupCoordinator read the same claim and the
        // same workspace lock, so they cannot disagree about whether a workspace is occupied.
        try
        {
            var claim = registry.readStartupClaim();
            if (claim != null && (claim.namesALiveProcess() || claim.isRecent(java.time.Instant.now())))
            {
                return true;
            }
        }
        catch (IOException e)
        {
            // A claim that exists and cannot be resolved is not a missing claim.
            return true;
        }
        // FREE and HELD both answer; only an unreadable lock leaves the workspace unknown.
        return registry.workspaceLockState() != EndpointRegistry.LockState.FREE;
    }


    /**
     * Delivers the shutdown request for one workspace and waits for its host to exit. This is the
     * programmatic entry point: {@link #run(String[], PrintStream)} resolves the per-user registry
     * directory from the arguments and calls this, and a caller that already knows the directory
     * can call it directly.
     *
     * @param ipc the per-user IPC registry directory the host published its endpoint into
     * @param workspace the workspace directory, as the host was started with
     * @param timeoutMillis how long to wait for the process to disappear
     * @param err where a non-zero outcome is explained
     * @return the exit code
     */
    public static int stop(Path ipc, Path workspace, long timeoutMillis, PrintStream err)
    {
        // The workspace lock is probed first and without the registry: a directly launched headless
        // EDT holds it before its IPC directory exists, so answering "no host" from a missing
        // registry would let a wrapper start a second host against a workspace that is owned.
        var locked = EndpointRegistry.workspaceLockState(workspace);
        int registryState = checkDirectory(ipc, "MCP endpoint registry", err, LinkOption.NOFOLLOW_LINKS);
        if (registryState == ABSENT && locked != EndpointRegistry.LockState.FREE)
        {
            err.println("edt-mcp-shutdown: no IPC registry for " + workspace
                + ", but a process holds its workspace lock; the host state is unknown");
            return NOT_STOPPED;
        }
        if (registryState != STOPPED) { return registryState; }
        EndpointRegistry registry;
        try { registry = new EndpointRegistry(EndpointRegistry.privateDirectory(ipc), workspace); }
        catch (IOException | RuntimeException e)
        {
            // An unreadable registry proves nothing about the process: the host may be running.
            err.println("edt-mcp-shutdown: cannot read the MCP endpoint registry for " + workspace
                + ": " + message(e) + "; the host state is unknown");
            return NOT_STOPPED;
        }

        EndpointRegistry.Endpoint endpoint;
        try { endpoint = registry.read(); }
        catch (IOException e)
        {
            // A record that exists and cannot be read is a broken registry, not a stopped host.
            err.println("edt-mcp-shutdown: the endpoint record for " + workspace
                + " is unreadable: " + message(e) + "; the host state is unknown");
            return NOT_STOPPED;
        }
        if (endpoint == null || !EndpointRegistry.live(endpoint))
        {
            // A missing record is not proof that nothing is running: a host holds the workspace
            // between locking it and publishing its listener, and an idle teardown removes the
            // record before releasing the lock. Both states must stay unknown, or a caller would
            // start a second EDT against a workspace that is still owned.
            if (startupOrTeardownInFlight(registry))
            {
                err.println("edt-mcp-shutdown: an EDT host for " + workspace
                    + " is starting or shutting down; the host state is unknown");
                return NOT_STOPPED;
            }
            err.println("edt-mcp-shutdown: no live host is registered for " + workspace + ".");
            return ABSENT;
        }

        // The connect and decision have their own bound; -timeout is the exit budget the option
        // documents, and it starts when the host accepts. Sharing one deadline would hand a slow
        // but clean shutdown less than the caller asked for and have it force-killed.
        long decisionDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        SocketChannel channel;
        try { channel = SocketChannel.open(StandardProtocolFamily.UNIX); }
        catch (IOException | RuntimeException e)
        {
            err.println("edt-mcp-shutdown: cannot reach the host of " + workspace + " over its IPC socket: " + message(e));
            return NOT_STOPPED;
        }
        ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "edt-mcp-shutdown-timeout"); t.setDaemon(true); return t;
        });
        try (var socket = new JsonFrames.Socket(channel))
        {
            var awaitingDecision = new java.util.concurrent.atomic.AtomicBoolean(true);
            var decisionTimeout = closer.schedule(() -> {
                if (awaitingDecision.getAndSet(false)) {
                    try { channel.close(); } catch (IOException ignored) { }
                }
            }, Math.max(0, decisionDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            socket.connect(UnixDomainSocketAddress.of(endpoint.socket()));
            IpcHandshake.client(socket, endpoint);
            socket.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            String reply = socket.read();
            if (!isInitializeSuccess(reply))
            {
                err.println("edt-mcp-shutdown: the host rejected the MCP initialize; it was not asked to stop.");
                return NOT_STOPPED;
            }
            socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown\"}");
            // The first answer is the decision: accepted means the host is closing, refused means it
            // keeps running on purpose. Neither is a timeout, so neither has to wait one out.
            String decision = readDecision(socket);
            boolean decidedBeforeTimeout = awaitingDecision.getAndSet(false);
            decisionTimeout.cancel(false);
            if (!decidedBeforeTimeout || decision == null)
            {
                err.println("edt-mcp-shutdown: the host did not answer the shutdown request; the host state is unknown.");
                return NOT_STOPPED;
            }
            if (decision.equals(McpConstants.METHOD_HOST_SHUTDOWN_REFUSED))
            {
                err.println("edt-mcp-shutdown: the host of " + workspace
                    + " refused to shut down and is still running; do not kill it.");
                return REFUSED;
            }
            if (decision.equals(McpConstants.METHOD_HOST_SHUTDOWN_ACCEPTED))
            {
                // Accepted: the exit budget starts now, so the option means what it documents.
                long exitDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
                closer.schedule(() -> { try { channel.close(); } catch (IOException ignored) { } },
                    Math.max(0, exitDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                // The cleanup result is the host's own verdict and it arrives before the process
                // goes. A process that exits after a failed save must not read as a clean stop, or
                // a wrapper would start another host over an unsaved workspace.
                String receipt = readCompletion(socket);
                if (exitedBy(endpoint, exitDeadline))
                {
                    if (!isShutdownReceipt(receipt))
                    {
                        err.println("edt-mcp-shutdown: the host exited without confirming the cleanup; the workspace may not be saved.");
                        return NOT_STOPPED;
                    }
                    return STOPPED;
                }
                err.println("edt-mcp-shutdown: the host accepted the request but PID " + endpoint.pid()
                    + " is still running; the host may still be saving.");
                return NOT_STOPPED;
            }
            // A host running an older plugin answers the completion receipt without a decision.
            if (!isShutdownReceipt(decision))
            {
                err.println("edt-mcp-shutdown: shutdown receipt was missing or reported failure.");
                return NOT_STOPPED;
            }
            long legacyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            if (exitedBy(endpoint, legacyDeadline)) { return STOPPED; }
            err.println("edt-mcp-shutdown: PID " + endpoint.pid() + " is still running after the shutdown request; the host did not close.");
            return NOT_STOPPED;
        }
        catch (IOException | RuntimeException e)
        {
            err.println("edt-mcp-shutdown: cannot reach the host of " + workspace + " over its IPC socket: " + message(e));
            return NOT_STOPPED;
        }
        finally { closer.shutdownNow(); }
    }

    /**
     * Reads frames until the host's decision arrives, skipping unrelated notifications.
     *
     * @param socket the connected peer
     * @return the accepted or refused method name, or a non-decision frame; null on end of stream
     * @throws IOException when the peer fails
     */
    private static String readDecision(JsonFrames.Socket socket) throws IOException
    {
        String frame;
        do
        {
            frame = socket.read();
            if (frame == null) { return null; }
            if (McpConstants.METHOD_HOST_SHUTDOWN_ACCEPTED.equals(methodOf(frame))
                || McpConstants.METHOD_HOST_SHUTDOWN_REFUSED.equals(methodOf(frame)))
            {
                return methodOf(frame);
            }
        }
        while (isUnrelatedNotification(frame));
        return frame;
    }

    /**
     * Reads until the cleanup result arrives, skipping unrelated notifications. The host sends it
     * before it closes the transport, so an end of stream here means the host exited without one.
     *
     * @param socket the connected peer
     * @return the completion frame, or null when the stream ended first
     * @throws IOException when the peer fails
     */
    private static String readCompletion(JsonFrames.Socket socket)
    {
        try
        {
            String frame;
            while ((frame = socket.read()) != null)
            {
                if (McpConstants.METHOD_HOST_SHUTDOWN_COMPLETE.equals(methodOf(frame))) { return frame; }
            }
        }
        catch (IOException e)
        {
            // The exit budget closed the socket, or the host went down without a receipt. Either
            // way there is no verdict to read, which the caller reports as unconfirmed.
        }
        return null;
    }

    /** The {@code method} of a framed notification, or null when the frame is not one. */
    static String methodOf(String frame)
    {
        try
        {
            JsonObject object = JsonParser.parseString(frame).getAsJsonObject();
            return object.has("method") ? object.get("method").getAsString() : null;
        }
        catch (RuntimeException e) { return null; }
    }

    static boolean isInitializeSuccess(String frame)
    {
        try { return frame != null && JsonParser.parseString(frame).isJsonObject()
            && JsonParser.parseString(frame).getAsJsonObject().has("result"); }
        catch (RuntimeException e) { return false; }
    }

    static boolean isUnrelatedNotification(String frame)
    {
        try
        {
            JsonObject object = JsonParser.parseString(frame).getAsJsonObject();
            return object.has("jsonrpc") && "2.0".equals(object.get("jsonrpc").getAsString())
                && object.has("method") && !object.has("id")
                && !"notifications/host_shutdown_complete".equals(object.get("method").getAsString());
        }
        catch (RuntimeException e) { return false; }
    }

    static boolean isShutdownReceipt(String frame)
    {
        try
        {
            JsonObject object = JsonParser.parseString(frame).getAsJsonObject();
            var params = object.getAsJsonObject("params");
            var success = params == null ? null : params.get("success");
            return object.has("jsonrpc") && "2.0".equals(object.get("jsonrpc").getAsString())
                && !object.has("id") && "notifications/host_shutdown_complete".equals(object.get("method").getAsString())
                && success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                && success.getAsBoolean();
        }
        catch (RuntimeException e) { return false; }
    }

    /**
     * Waits for the endpoint's process to disappear, using the recorded start time so a recycled
     * PID is not mistaken for the host.
     *
     * @param endpoint the host that was asked to stop
     * @param timeoutMillis how long to wait
     * @return true when the process is gone
     */
    static boolean exited(EndpointRegistry.Endpoint endpoint, long timeoutMillis)
    {
        return exitedBy(endpoint, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis)));
    }

    private static boolean exitedBy(EndpointRegistry.Endpoint endpoint, long deadline)
    {
        while (true)
        {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { return false; }
            if (!EndpointRegistry.live(endpoint)) { return true; }
            try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    /** The value that follows {@code name}, or {@code null} when it is absent or has none. */
    static String option(String[] args, String name)
    {
        for (int i = 0; i + 1 < args.length; i++)
        {
            if (name.equals(args[i])) { return args[i + 1]; }
        }
        return null;
    }

    private static void usage(PrintStream err, String problem)
    {
        err.println("edt-mcp-shutdown: " + problem);
        err.println("usage: java -cp edt-mcp-runtime.jar "
            + HostShutdownClient.class.getName() + " -data <workspace> [-install <edt>] [-timeout <seconds>]");
    }

    private static String message(Throwable e)
    {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName()
            : e.getClass().getSimpleName() + ": " + text.replace('\r', ' ').replace('\n', ' ');
    }
}
