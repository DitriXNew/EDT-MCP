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
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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
 * <li>{@value #STOPPED} - the request was delivered and the host exited.</li>
 * <li>{@value #NOT_STOPPED} - nothing confirmed the stop within the timeout; the caller decides
 * whether to force-kill, and a host running an older plugin that ignores the notification lands
 * here.</li>
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
    /** Nothing confirmed the stop within the timeout; the caller may force-kill. */
    public static final int NOT_STOPPED = 1;
    /** No host is registered for this workspace. */
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
        if (!Files.isDirectory(directory))
        {
            err.println("edt-mcp-shutdown: the workspace directory does not exist: " + directory);
            return ABSENT;
        }

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
            err.println("edt-mcp-shutdown: cannot locate the per-user MCP storage: " + message(e));
            return ABSENT;
        }
        return stop(ipc, directory, timeout, err);
    }

    /** The private per-user directory the plugin registers its endpoints in. */
    static Path registryDirectory() throws IOException
    {
        return EndpointRegistry.userDirectory().resolve("ipc");
    }

    /**
     * Delivers the shutdown request for one workspace and waits for its host to exit.
     *
     * @param ipc the per-user IPC registry directory the host published its endpoint into
     * @param workspace the workspace directory, as the host was started with
     * @param timeoutMillis how long to wait for the process to disappear
     * @param err where a non-zero outcome is explained
     * @return the exit code
     */
    static int stop(Path ipc, Path workspace, long timeoutMillis, PrintStream err)
    {
        if (!Files.isDirectory(ipc))
        {
            err.println("edt-mcp-shutdown: no MCP endpoint registry under " + ipc + "; no host is running.");
            return ABSENT;
        }
        EndpointRegistry registry;
        try { registry = new EndpointRegistry(EndpointRegistry.privateDirectory(ipc), workspace); }
        catch (IOException | RuntimeException e)
        {
            err.println("edt-mcp-shutdown: cannot read the MCP endpoint registry for " + workspace + ": " + message(e));
            return ABSENT;
        }

        EndpointRegistry.Endpoint endpoint;
        try { endpoint = registry.read(); }
        catch (IOException e)
        {
            err.println("edt-mcp-shutdown: the endpoint record for " + workspace + " is unreadable: " + message(e));
            return ABSENT;
        }
        if (endpoint == null || !EndpointRegistry.live(endpoint))
        {
            err.println("edt-mcp-shutdown: no live host is registered for " + workspace + ".");
            return ABSENT;
        }

        try (var socket = new JsonFrames.Socket(SocketChannel.open(StandardProtocolFamily.UNIX)))
        {
            socket.connect(UnixDomainSocketAddress.of(endpoint.socket()));
            IpcHandshake.client(socket, endpoint);
            socket.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            String reply = socket.read();
            if (reply == null || !JsonParser.parseString(reply).isJsonObject()
                || !JsonParser.parseString(reply).getAsJsonObject().has("result"))
            {
                err.println("edt-mcp-shutdown: the host rejected the MCP initialize; it was not asked to stop.");
                return NOT_STOPPED;
            }
            socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            // A notification is never answered. The host closing this connection during its own
            // shutdown is the acknowledgement; the exit check below is what proves it happened.
            socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown\"}");
        }
        catch (IOException | RuntimeException e)
        {
            err.println("edt-mcp-shutdown: cannot reach the host of " + workspace + " over its IPC socket: " + message(e));
            return NOT_STOPPED;
        }

        if (exited(endpoint, timeoutMillis)) { return STOPPED; }
        err.println("edt-mcp-shutdown: PID " + endpoint.pid() + " is still running "
            + (timeoutMillis / 1000) + "s after the shutdown request; the host did not close.");
        return NOT_STOPPED;
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
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (EndpointRegistry.live(endpoint))
        {
            if (System.nanoTime() - deadline >= 0) { return false; }
            try { Thread.sleep(POLL_MILLIS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
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
