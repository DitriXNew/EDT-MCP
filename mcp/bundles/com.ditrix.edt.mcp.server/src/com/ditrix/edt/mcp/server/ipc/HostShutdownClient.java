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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
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
    /**
     * The stop was not confirmed: no receipt, the host did not exit, or the registry could not be
     * read so the host state is unknown. The caller may force-kill.
     */
    public static final int NOT_STOPPED = 1;
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
            err.println("edt-mcp-shutdown: no live host is registered for " + workspace + ".");
            return ABSENT;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
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
            closer.schedule(() -> { try { channel.close(); } catch (IOException ignored) { } },
                Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
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
            String receipt;
            do { receipt = socket.read(); }
            while (receipt != null && !isShutdownReceipt(receipt) && isUnrelatedNotification(receipt));
            if (!isShutdownReceipt(receipt))
            {
                err.println("edt-mcp-shutdown: shutdown receipt was missing or reported failure.");
                return NOT_STOPPED;
            }
            if (exitedBy(endpoint, deadline)) { return STOPPED; }
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
