package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;

public class StartupCoordinatorTest extends StdioTestSupport
{
    @Test public void startupMutexAllowsOneStarterAcrossRacingClients() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var first = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); entered.countDown(); release.await(); return null; });
        var second = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<String> result = executor.submit(first::ensureStarted); assertTrue(entered.await(3, TimeUnit.SECONDS));
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
