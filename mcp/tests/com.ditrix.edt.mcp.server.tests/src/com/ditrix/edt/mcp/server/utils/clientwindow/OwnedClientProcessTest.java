// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.eclipse.debug.core.ILaunch;
import org.junit.Test;

public class OwnedClientProcessTest
{
    static final class Owner implements IsolatedClientLauncher.OwnedClient
    {
        boolean alive = true;
        long pid = 42;
        int closes;
        @Override public long pid() { return pid; }
        @Override public boolean alive() { return alive; }
        @Override public int exitCode() { return 7; }
        @Override public List<WindowController.Window> windows()
        {
            return List.of(new WindowController.Window("1", 42, "Blocking modal", "ModalClass"));
        }
        @Override public void close() { alive = false; closes++; }
    }

    @Test public void terminationIsIdempotentAndNaturalExitCleansUp() throws Exception
    {
        Owner owner = new Owner();
        OwnedClientProcess process = new OwnedClientProcess(owner);
        assertTrue(process.isAlive());
        assertThrows(IllegalThreadStateException.class, process::exitValue);
        process.destroy();
        process.destroyForcibly();
        assertFalse(process.isAlive());
        assertEquals(1, owner.closes);
        assertEquals(1, process.waitFor());

        owner = new Owner();
        process = new OwnedClientProcess(owner);
        owner.alive = false;
        assertEquals(7, process.waitFor());
        assertEquals(1, owner.closes);
    }

    @Test public void registryReportsPidModalAndClosesOwnedProcesses()
    {
        Owner owner = new Owner();
        ILaunch launch = mock(ILaunch.class);
        ClientWindowRegistry.owned(launch, WindowRequest.parse("isolated", null), owner, new OwnedClientProcess(owner));
        try
        {
            Map<String, Object> status = ClientWindowRegistry.describe(launch);
            assertEquals(42L, status.get("pid"));
            assertEquals("isolated", status.get("windowMode"));
            assertEquals(true, status.get("windowModeApplied"));
            assertEquals(true, status.get("windowListingAvailable"));
            assertEquals("running", status.get("state"));
            assertTrue(status.get("windows").toString().contains("Blocking modal"));
            assertTrue(status.get("windows").toString().contains("ModalClass"));
        }
        finally { ClientWindowRegistry.closeOwned(); }
        assertEquals(1, owner.closes);
        assertTrue(ClientWindowRegistry.describe(launch).isEmpty());
    }

    @Test public void failedWindowModeSurvivesTheLaunchSuccessEnvelope()
    {
        var execution = new ClientWindowLaunch.Execution(null,
            Map.of("windowMode", "minimized", "windowModeApplied", false, "windowModeReason", "No client window"));
        var json = com.google.gson.JsonParser.parseString(execution.decorate("{\"success\":true}")).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean());
        assertFalse(json.get("windowModeApplied").getAsBoolean());
        assertEquals("No client window", json.get("windowModeReason").getAsString());
    }

    @Test public void isolatedClientsUseEdtsClientLaunchLifecycle() throws Exception
    {
        var monitoring = mock(com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager.class);
        var config = mock(org.eclipse.debug.core.ILaunchConfiguration.class);
        var launch = ClientWindowLaunch.ownedLaunch(config, "run", monitoring);
        assertTrue(launch instanceof com._1c.g5.v8.dt.debug.core.launchconfigurations.DtLaunch);
        assertEquals("run", launch.getLaunchMode());
        verify(monitoring).launchStarted("Client");
        launch.terminate();
        verify(monitoring).debugLaunchTerminated(launch);
    }

    @Test public void edtPidsComeFromTheRuntimeProcessAttribute()
    {
        var launch = mock(ILaunch.class);
        var runtime = mock(org.eclipse.debug.core.model.IProcess.class);
        var terminated = mock(org.eclipse.debug.core.model.IProcess.class);
        when(runtime.getAttribute(org.eclipse.debug.core.model.IProcess.ATTR_PROCESS_ID))
            .thenReturn(Long.toString(ProcessHandle.current().pid()));
        when(terminated.isTerminated()).thenReturn(true);
        when(launch.getProcesses()).thenReturn(new org.eclipse.debug.core.model.IProcess[] {runtime, terminated});
        assertTrue(ClientWindowLaunch.pids(launch).contains(ProcessHandle.current().pid()));
        verify(terminated, never()).getAttribute(anyString());
    }

    @Test public void processHandleIdentityIsCapturedOnce()
    {
        Owner owner = new Owner();
        owner.pid = ProcessHandle.current().pid();
        OwnedClientProcess process = new OwnedClientProcess(owner);
        ProcessHandle captured = process.toHandle();
        process.destroy();
        assertSame(captured, process.toHandle());
        assertEquals(1, owner.closes);
    }
}
