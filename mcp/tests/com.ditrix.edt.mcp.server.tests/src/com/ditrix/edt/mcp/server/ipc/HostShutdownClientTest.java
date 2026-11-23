// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.ipc;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonParser;

/**
 * The external stop: a CLI on the shipped runtime jar asks a live host to shut down over the same
 * authenticated IPC the tools use, and grades the outcome by exit code.
 * <p>
 * Lives in the {@code ipc} package because the whole point of the client is that it is protocol
 * code with no Eclipse behind it - and its seams (the registry directory, the exit wait) are
 * package-private on purpose, so they are tested here rather than widened for a test.
 */
public class HostShutdownClientTest
{
    /** The notification the client must send, and the last frame the host must see. */
    private static final String SHUTDOWN_METHOD = "notifications/host_shutdown";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Before public void secureTemporaryDirectory() throws Exception
    { PrivatePaths.directory(temporary.getRoot().toPath()); }

    private EndpointRegistry registry() throws Exception
    {
        return new EndpointRegistry(PrivatePaths.directory(temporary.newFolder().toPath()),
            temporary.newFolder().toPath());
    }

    /** A fake host: it accepts one connection, replays the handshake and records the methods it saw. */
    private static final class FakeHost implements AutoCloseable
    {
        private final ServerSocketChannel server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final CountDownLatch shutdown = new CountDownLatch(1);

        FakeHost(EndpointRegistry registry, Boolean initializes, Boolean receipt) throws Exception
        {
            Path socket = registry.directory().resolve("s-client-test");
            registry.publish(new EndpointRegistry.Endpoint(registry.workspace().toString(), "client-test",
                ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                "2.26.1", EndpointRegistry.IPC_VERSION, socket.toString(), "test-secret"));
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            worker.execute(() -> serve(registry, initializes, receipt));
        }

        private void serve(EndpointRegistry registry, Boolean initializes, Boolean receipt)
        {
            try (var channel = server.accept(); var socket = new JsonFrames.Socket(channel))
            {
                if (!IpcHandshake.accepts(socket.read(), registry.read())) { return; }
                socket.write("{\"accepted\":true}");
                String frame;
                while ((frame = socket.read()) != null)
                {
                    String method = JsonParser.parseString(frame).getAsJsonObject().get("method").getAsString();
                    methods.add(method);
                    // The client blocks on its initialize reply, so a refusing host must still answer.
                    if ("initialize".equals(method) && initializes != null)
                    {
                        socket.write(initializes ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
                            : "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600,\"message\":\"no\"}}");
                    }
                    if (SHUTDOWN_METHOD.equals(method)) {
                        shutdown.countDown();
                        if (receipt == null) { return; }
                        socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
                        socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown_complete\",\"params\":{\"success\":" + receipt + "}}");
                    }
                }
            }
            catch (Exception e) { /* The client hanging up is the normal end of the exchange. */ }
        }

        boolean sawShutdown() throws Exception { return shutdown.await(5, TimeUnit.SECONDS); }

        @Override public void close() throws Exception
        {
            server.close(); worker.shutdownNow();
        }
    }

    private static PrintStream sink(ByteArrayOutputStream bytes)
    { return new PrintStream(bytes, true, StandardCharsets.UTF_8); }

    private static String text(ByteArrayOutputStream bytes)
    { return bytes.toString(StandardCharsets.UTF_8); }

    @Test public void stopReplaysTheHandshakeAndSendsTheShutdownNotification() throws Exception
    {
        var registry = registry();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        try (var host = new FakeHost(registry, true, true))
        {
            // Exit code 1: this FakeHost never exits, which is exactly the "did not stop" outcome -
            // and it is what a host running a plugin older than the notification reports too.
            assertEquals(HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
            assertTrue(host.sawShutdown());
            assertTrue(text(messages), text(messages).contains("did not close"));
        }
    }

    @Test public void stopReportsNoHostAsNoHostRatherThanAFailure() throws Exception
    {
        var registry = registry();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void stopNeverAsksAHostThatRefusedInitialize() throws Exception
    {
        var registry = registry();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        try (var host = new FakeHost(registry, false, null))
        {
            assertEquals(HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
            assertFalse("A connection that never became an MCP session must not be a shutdown request",
                host.sawShutdown());
            assertTrue(text(messages), text(messages).contains("rejected the MCP initialize"));
        }
    }

    @Test public void installIsAcceptedAndIgnoredBecauseDiscoveryIsKeyedByWorkspace() throws Exception
    {
        assertEquals("E:\\EDT\\ws", HostShutdownClient.option(new String[]{"-data", "E:\\EDT\\ws"}, "-data"));
        assertNull(HostShutdownClient.option(new String[]{"-data"}, "-data"));
        assertNull(HostShutdownClient.option(new String[]{"-install", "x"}, "-data"));
        assertEquals("E:\\EDT\\ws", HostShutdownClient.option(
            new String[]{"-install", "E:\\1CE", "-data", "E:\\EDT\\ws"}, "-data"));
    }

    @Test public void aBadCommandLineIsRefusedWithAdviceRatherThanThrowing() throws Exception
    {
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED, HostShutdownClient.run(new String[]{"-data"}, sink(messages)));
        assertTrue(text(messages), text(messages).contains("-data is required"));

        messages.reset();
        assertEquals(HostShutdownClient.NOT_STOPPED, HostShutdownClient.run(
            new String[]{"-data", temporary.getRoot().toString(), "-timeout", "soon"}, sink(messages)));
        assertTrue(text(messages), text(messages).contains("-timeout must be"));

        messages.reset();
        Path absent = temporary.getRoot().toPath().resolve("not-a-workspace");
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.run(new String[]{"-data", absent.toString()}, sink(messages)));
        assertTrue(text(messages), text(messages).contains("does not exist"));
    }

    @Test public void anAbsentRegistryDirectoryIsNoHostRatherThanACrash() throws Exception
    {
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        Path missing = temporary.getRoot().toPath().resolve("no-ipc");
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(missing, temporary.getRoot().toPath(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("MCP endpoint registry does not exist"));
    }

    @Test public void anUnreadableEndpointRecordIsNotReportedAsNoHost() throws Exception
    {
        // The record exists and cannot be read: the registry is broken, which proves nothing
        // about the process - a live host may still hold the workspace.
        var registry = registry();
        Files.writeString(registry.recordPath(), "not a valid endpoint record");
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("unreadable"));
        assertTrue(text(messages), text(messages).contains("unknown"));
    }

    @Test public void aRegistryPathOfTheWrongTypeDoesNotConfirmAbsence() throws Exception
    {
        Path file = temporary.newFile().toPath();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(file, temporary.getRoot().toPath(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("not a directory"));
    }

    @Test public void anInvalidWorkspacePathDoesNotConfirmAbsence() throws Exception
    {
        Path file = temporary.newFile().toPath();
        for (Path path : List.of(file, file.resolve("workspace")))
        {
            ByteArrayOutputStream messages = new ByteArrayOutputStream();
            assertEquals(path.toString(), HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.run(new String[]{"-data", path.toString()}, sink(messages)));
            assertTrue(text(messages), text(messages).contains("workspace directory"));
        }
    }

    @Test public void anUnreadableRegistryIsNotReportedAsNoHost() throws Exception
    {
        // A parent that cannot be traversed makes the registry state undeterminable. The result
        // must be "unknown", never "absent", because a live host may still hold the workspace.
        var registry = registry();
        // The parent of the registry path is a regular file, so the state of the path below it
        // cannot be determined: that is not the same as an absent registry.
        Path file = registry.directory().resolve("blocked");
        Files.writeString(file, "not a directory");
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(file.resolve("child"), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("unknown"));
    }

    @Test public void aWorkspaceLockedByAHostWithoutAnEndpointIsNotReportedAsNoHost() throws Exception
    {
        // A host owns the workspace between locking it and publishing its listener, and an idle
        // teardown removes the record before releasing the lock. Both must stay unknown.
        var registry = registry();
        Path metadata = registry.workspace().resolve(".metadata");
        Files.createDirectories(metadata);
        Path lock = metadata.resolve(".lock");
        Files.writeString(lock, "");
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE);
             var held = channel.lock())
        {
            assertEquals(HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
            assertTrue(text(messages), text(messages).contains("starting or shutting down"));
        }
    }

    @Test public void aFreshStartupClaimWithoutAnEndpointIsNotReportedAsNoHost() throws Exception
    {
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.claimPath(),
            "{\"requested\":\"" + java.time.Instant.now() + "\",\"process\":null}");
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("starting or shutting down"));
    }

    @Test public void aWorkspaceWithoutClaimsOrLockIsStillNoHost() throws Exception
    {
        var registry = registry();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void anAgedClaimNamingALiveProcessIsNotReportedAsNoHost() throws Exception
    {
        // A host slower than the age cutoff to reach lockWorkspace() is still starting. The claim
        // records the process identity, and StartupCoordinator reads it the same way.
        var registry = registry();
        String start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        EndpointRegistry.atomicWrite(registry.claimPath(),
            "{\"requested\":\"" + java.time.Instant.now().minusSeconds(600) + "\",\"process\":{\"pid\":"
                + ProcessHandle.current().pid() + ",\"startTime\":\"" + start + "\"}}");
        Files.setLastModifiedTime(registry.claimPath(),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(600)));
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("starting or shutting down"));
    }

    @Test public void aCorruptAgedClaimStillFallsThroughToNoHost() throws Exception
    {
        // A readable but corrupt claim must not block every stop forever: with a free lock the
        // workspace is free, so the answer stays exit 2.
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.claimPath(), "{not json at all");
        Files.setLastModifiedTime(registry.claimPath(),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(600)));
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void aClaimWithWrongFieldTypesStillFallsThroughToNoHost() throws Exception
    {
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.claimPath(),
            "{\"requested\":\"not-a-time\",\"process\":{\"pid\":\"not-a-number\",\"startTime\":42}}");
        Files.setLastModifiedTime(registry.claimPath(),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(600)));
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void aStringPidIsNotCoercedToALiveProcess() throws Exception
    {
        // Gson coerces "pid":"1234" to a number; a live pid must not make an aged corrupt claim
        // look like a running host while the lock is free.
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.claimPath(),
            "{\"requested\":\"" + java.time.Instant.now().minusSeconds(600)
                + "\",\"process\":{\"pid\":\"" + ProcessHandle.current().pid()
                + "\",\"startTime\":\"" + ProcessHandle.current().info().startInstant().orElseThrow() + "\"}}");
        Files.setLastModifiedTime(registry.claimPath(),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(600)));
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void aFractionalPidIsNotTruncatedToALiveProcess() throws Exception
    {
        // Gson truncates 3491.5 to 3491 on getAsLong; the real live pid must not let an aged
        // corrupt claim look like a running host while the lock is free.
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.claimPath(),
            "{\"requested\":\"" + java.time.Instant.now().minusSeconds(600)
                + "\",\"process\":{\"pid\":" + ProcessHandle.current().pid() + ".5"
                + ",\"startTime\":\"" + ProcessHandle.current().info().startInstant().orElseThrow() + "\"}}");
        Files.setLastModifiedTime(registry.claimPath(),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(600)));
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void aDanglingAgedClaimKeepsTheStateUnknown() throws Exception
    {
        // The claim exists without following it but its target does not: it cannot be resolved and
        // must not be read as absent while the lock is free.
        var registry = registry();
        try { Files.createSymbolicLink(registry.claimPath(), registry.directory().resolve("missing-claim")); }
        catch (IOException | UnsupportedOperationException e) { Assume.assumeNoException(e); }
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("starting or shutting down"));
    }

    @Test public void anAgedClaimOfADeadProcessFallsThroughToNoHost() throws Exception
    {
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.claimPath(),
            "{\"requested\":\"" + java.time.Instant.now().minusSeconds(600) + "\",\"process\":null}");
        Files.setLastModifiedTime(registry.claimPath(),
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(600)));
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.ABSENT,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("no live host"));
    }

    @Test public void aDanglingWorkspaceLeafIsNotReportedAsNoHost() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Path link = root.resolve("workspace");
        try { Files.createSymbolicLink(link, root.resolve("missing-target")); }
        catch (IOException | UnsupportedOperationException e) { Assume.assumeNoException(e); }
        var messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED, HostShutdownClient.run(
            new String[] { "-data", link.toString(), "-timeout", "0" }, sink(messages)));
        assertTrue(text(messages), text(messages).contains("unknown"));
    }

    @Test public void anUnreadableWorkspaceLockKeepsTheStateUnknown() throws Exception
    {
        // The lock exists but cannot be reached: the parent directory is not searchable for the
        // bring-up. Only a proven absence may clear the workspace.
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        var registry = registry();
        Path metadata = registry.workspace().resolve(".metadata");
        Files.createDirectories(metadata);
        Path lock = metadata.resolve(".lock");
        Files.writeString(lock, "");
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("---------");
        try
        {
            Files.setPosixFilePermissions(metadata, permissions);
            var messages = new ByteArrayOutputStream();
            assertEquals(HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
            assertTrue(text(messages), text(messages).contains("starting or shutting down"));
        }
        finally { Files.setPosixFilePermissions(metadata, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
    }

    @Test public void anUnreachableSocketIsReportedWithoutThrowing() throws Exception
    {
        var registry = registry();
        Path socket = registry.directory().resolve("s-dead");
        Files.writeString(socket, "");
        registry.publish(new EndpointRegistry.Endpoint(registry.workspace().toString(), "dead",
            ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().orElseThrow().toString(),
            "2.26.1", EndpointRegistry.IPC_VERSION, socket.toString(), "secret"));
        try (var probe = SocketChannel.open(StandardProtocolFamily.UNIX))
        {
            probe.connect(UnixDomainSocketAddress.of(socket));
            fail("A regular file must not be connectable as a Unix socket; this test needs a dead endpoint");
        }
        catch (IOException expected) { }
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        assertEquals(HostShutdownClient.NOT_STOPPED,
            HostShutdownClient.stop(registry.directory(), registry.workspace(), 500, sink(messages)));
        assertTrue(text(messages), text(messages).contains("cannot reach"));
    }

    @Test public void receiptMustMatchMethodAndBooleanSuccess()
    {
        assertTrue(HostShutdownClient.isShutdownReceipt("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown_complete\",\"params\":{\"success\":true}}"));
        assertFalse(HostShutdownClient.isShutdownReceipt("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown_complete\",\"params\":{\"success\":false}}"));
        assertFalse(HostShutdownClient.isShutdownReceipt("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown_complete\",\"params\":{\"success\":\"true\"}}"));
        assertFalse(HostShutdownClient.isShutdownReceipt("{\"jsonrpc\":\"2.0\",\"method\":\"other\",\"params\":{\"success\":true}}"));
        assertFalse(HostShutdownClient.isShutdownReceipt("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"notifications/host_shutdown_complete\",\"params\":{\"success\":true}}"));
        assertTrue(HostShutdownClient.isUnrelatedNotification("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"));
    }

    @Test public void invalidInitializeResponseIsRefused()
    {
        assertFalse(HostShutdownClient.isInitializeSuccess(null));
        assertFalse(HostShutdownClient.isInitializeSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{}}"));
        assertTrue(HostShutdownClient.isInitializeSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
    }

    @Test(timeout = 5000) public void connectedPeerThatNeverAnswersInitializeCannotHangTheClient() throws Exception
    {
        var registry = registry();
        var messages = new ByteArrayOutputStream();
        try (var host = new FakeHost(registry, null, null)) {
            assertEquals(HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 750, sink(messages)));
            assertTrue(host.methods.contains("initialize"));
            assertFalse(host.methods.contains(SHUTDOWN_METHOD));
        }
    }

    @Test(timeout = 5000) public void closedConnectionAndFailedCleanupAreNotSuccess() throws Exception
    {
        for (Boolean receipt : new Boolean[] {null, false}) {
            var registry = registry();
            var messages = new ByteArrayOutputStream();
            try (var host = new FakeHost(registry, true, receipt)) {
                assertEquals(HostShutdownClient.NOT_STOPPED,
                    HostShutdownClient.stop(registry.directory(), registry.workspace(), 1000, sink(messages)));
                assertTrue(host.methods.contains(SHUTDOWN_METHOD));
                assertTrue(text(messages), text(messages).contains("receipt was missing or reported failure"));
            }
        }
    }

    /** The wait that grades the outcome is bounded by the requested timeout, never open-ended. */
    @Test public void theExitWaitIsBoundedByItsTimeout() throws Exception
    {
        var registry = registry();
        Path socket = registry.directory().resolve("s-wait");
        registry.publish(new EndpointRegistry.Endpoint(registry.workspace().toString(), "wait",
            ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().orElseThrow().toString(),
            "2.26.1", EndpointRegistry.IPC_VERSION, socket.toString(), "secret"));
        var endpoint = registry.read();
        long started = System.nanoTime();
        assertFalse("This process is alive, so the wait must expire", HostShutdownClient.exited(endpoint, 200));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("The wait must not overrun its budget: " + elapsed + "ms", elapsed < 5000);
        assertTrue("The wait must actually use its budget rather than give up: " + elapsed + "ms", elapsed >= 100);
    }
}
