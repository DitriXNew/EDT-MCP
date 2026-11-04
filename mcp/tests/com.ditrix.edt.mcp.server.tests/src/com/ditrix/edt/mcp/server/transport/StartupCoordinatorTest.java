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
