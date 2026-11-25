package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.google.gson.Gson;

public class StartupCoordinatorTest extends StdioTestSupport
{
    private void claim(EndpointRegistry registry, StartupCoordinator.Started process) throws Exception
    {
        var value = new java.util.HashMap<String, Object>();
        value.put("requested", Instant.now().toString()); value.put("process", process);
        EndpointRegistry.atomicWrite(registry.claimPath(), new Gson().toJson(value));
    }

    @Test public void unreadableEndpointDoesNotLaunchAnotherHost() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        EndpointRegistry.atomicWrite(registry.recordPath(), "not an endpoint");
        var coordinator = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        assertThrows(java.io.IOException.class, coordinator::ensureStarted);
        assertEquals(0, starts.get());
    }

    @Test public void corruptReadableClaimFallsThroughToWorkspaceOccupancy() throws Exception
    {
        var registry = registry(); var starts = new AtomicInteger();
        var coordinator = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        EndpointRegistry.atomicWrite(registry.claimPath(), "not a startup claim");
        var metadata = Files.createDirectories(registry.workspace().resolve(".metadata"));
        try (var channel = FileChannel.open(metadata.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock())
        {
            assertTrue(coordinator.ensureStarted().contains("already open"));
            assertEquals(0, starts.get());
        }
        assertEquals(StartupCoordinator.STARTING, coordinator.ensureStarted());
        assertEquals(1, starts.get());
    }

    @Test public void recentClaimWithKnownDeadIdentityRecoversImmediately() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var coordinator = new StartupCoordinator(registry, () -> {
            starts.incrementAndGet(); return new StartupCoordinator.Started(Long.MAX_VALUE, Instant.EPOCH.toString());
        });
        coordinator.ensureStarted(); assertEquals(1, starts.get());
        assertEquals(StartupCoordinator.STARTING, coordinator.ensureStarted());
        assertEquals("A known-dead launch must not consume the 180-second lease", 2, starts.get());
    }

    @Test public void recentUncertainAndLiveClaimsStillPreventAnotherLaunch() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var coordinator = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        claim(registry, null); assertEquals(StartupCoordinator.STARTING, coordinator.ensureStarted());
        claim(registry, new StartupCoordinator.Started(ProcessHandle.current().pid(),
            ProcessHandle.current().info().startInstant().orElseThrow().toString()));
        assertEquals(StartupCoordinator.STARTING, coordinator.ensureStarted());
        assertEquals(0, starts.get());
    }

    @Test public void knownDeadClaimStillRespectsAnOccupiedWorkspace() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var metadata = Files.createDirectories(registry.workspace().resolve(".metadata"));
        claim(registry, new StartupCoordinator.Started(Long.MAX_VALUE, Instant.EPOCH.toString()));
        try (var channel = FileChannel.open(metadata.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock())
        {
            var coordinator = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
            assertTrue(coordinator.ensureStarted().contains("already open"));
            assertEquals(0, starts.get());
        }
    }

    private static StartupCoordinator.Starter starter(AtomicInteger starts, java.util.concurrent.Callable<Long> running)
    {
        return new StartupCoordinator.Starter()
        {
            @Override public StartupCoordinator.Started start() { starts.incrementAndGet(); return null; }
            @Override public Long runningFor() throws Exception { return running.call(); }
        };
    }

    @Test public void anEdtStartedElsewhereForThisWorkspaceIsNotStartedAgain() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        String answer = new StartupCoordinator(registry, starter(starts, () -> 4242L)).ensureStarted();
        assertTrue(answer, answer.contains("already starting (process 4242)") && answer.contains("No tool ran"));
        assertEquals(0, starts.get());
        assertFalse("No claim is taken for an EDT this bootstrap did not start", Files.exists(registry.claimPath()));
    }

    @Test public void anUnreadableProcessListStillStartsOnce() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var coordinator = new StartupCoordinator(registry, starter(starts, () -> { throw new java.io.IOException("WMI down"); }));
        assertEquals(StartupCoordinator.STARTING, coordinator.ensureStarted());
        assertEquals(1, starts.get());
    }

    @Test public void workspaceLaunchMatchingFollowsWindowsArguments()
    {
        Path ws = Path.of("D:\\WS\\My Space");
        assertTrue(WindowsEdtStarter.launchesWorkspace("\"C:\\EDT\\1cedt.exe\" -clean -data \"D:\\WS\\My Space\"", ws));
        assertTrue(WindowsEdtStarter.launchesWorkspace("javaw.exe -jar plugins/launcher.jar -DATA \"d:/ws/my space/\"", ws));
        assertFalse("A longer sibling path is another workspace",
            WindowsEdtStarter.launchesWorkspace("1cedt.exe -data \"D:\\WS\\My Space2\"", ws));
        assertFalse("A stdio bootstrap names the workspace but is not an EDT",
            WindowsEdtStarter.launchesWorkspace("1cedtc.exe -startup C:\\Users\\u\\EDT-MCP\\bootstrap\\x\\edt-mcp-bootstrap.jar -data \"D:\\WS\\My Space\"", ws));
        assertFalse("-data without a value", WindowsEdtStarter.launchesWorkspace("1cedt.exe -data", ws));
    }

    @Test public void startupMutexAllowsOneStarterAcrossRacingClients() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var first = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); entered.countDown(); release.await(); return null; });
        var second = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<String> result = executor.submit(() -> first.ensureStarted()); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(second.ensureStarted().contains("starting")); release.countDown();
            assertTrue(result.get(3, TimeUnit.SECONDS).contains("starting"));
            assertTrue(second.ensureStarted().contains("starting")); assertEquals(1, starts.get());
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void expiredClaimAndDeadRecordCanStartButLiveOwnerCannot() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var coordinator = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        registry.publish(endpoint(registry, "crashed", Long.MAX_VALUE));
        EndpointRegistry.atomicWrite(registry.claimPath(), "{\"requested\":\"2000-01-01T00:00:00Z\"}");
        assertTrue(coordinator.ensureStarted().contains("starting")); assertEquals(1, starts.get());
        registry.publish(endpoint(registry, "live", ProcessHandle.current().pid()));
        coordinator.ensureStarted(); assertEquals(1, starts.get());
    }
}
