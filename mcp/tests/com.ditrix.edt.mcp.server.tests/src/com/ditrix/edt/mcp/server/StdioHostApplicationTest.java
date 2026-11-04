// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.equinox.app.IApplicationContext;
import org.junit.Test;

public class StdioHostApplicationTest
{
    private static class Runtime implements StdioHostApplication.Runtime {
        final List<String> calls = new ArrayList<>();
        Throwable failure;
        String failAt;
        boolean lock = true;
        boolean ipc = true;
        StdioHostApplication app;
        private void step(String name) { calls.add(name); if (name.equals(failAt)) { throw new IllegalStateException(name); } }
        public boolean lockWorkspace() { step("lock"); return lock; }
        public void initialize() { step("initialize"); }
        public void startCore(HeadlessLifetime lifetime) { step("core"); app.stop(); }
        public boolean ipcRunning() { step("ipc"); return ipc; }
        public void stopContexts() { step("stop"); }
        public void waitForContexts() { step("wait"); }
        public void save() { step("save"); }
        public void release() { step("release"); }
        public void failure(String message, Throwable error) { failure = error; calls.add("failure"); }
    }

    @Test public void cleanShutdownStopsContextsSavesAndReleasesWorkspace() {
        Runtime runtime = new Runtime(); runtime.app = new StdioHostApplication(runtime);
        IApplicationContext context = mock(IApplicationContext.class);
        assertEquals(0, runtime.app.start(context));
        assertEquals(List.of("lock", "initialize", "core", "ipc", "stop", "wait", "save", "release"), runtime.calls);
        verify(context).applicationRunning(); assertNull(runtime.failure);
    }

    @Test public void occupiedWorkspaceNeverInitializesOrReleasesAnotherHostsLock() {
        Runtime runtime = new Runtime(); runtime.lock = false; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(mock(IApplicationContext.class)));
        assertEquals(List.of("lock", "failure"), runtime.calls); assertNotNull(runtime.failure);
    }

    @Test public void coreFailurePreservesTheThrowableAndStillSaves() {
        Runtime runtime = new Runtime(); runtime.failAt = "core"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(mock(IApplicationContext.class)));
        assertEquals("core", runtime.failure.getMessage());
        assertEquals(List.of("lock", "initialize", "core", "failure", "stop", "wait", "save", "release"), runtime.calls);
    }

    @Test public void failedContextStopStillSavesAndReleases() {
        Runtime runtime = new Runtime(); runtime.failAt = "stop"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(mock(IApplicationContext.class)));
        assertEquals(List.of("lock", "initialize", "core", "ipc", "stop", "failure", "wait", "save", "release"), runtime.calls);
    }

    @Test public void failedListenerDoesNotAdvertiseReadiness() {
        Runtime runtime = new Runtime(); runtime.ipc = false; runtime.app = new StdioHostApplication(runtime);
        IApplicationContext context = mock(IApplicationContext.class);
        assertEquals(1, runtime.app.start(context)); verify(context, never()).applicationRunning();
        assertTrue(runtime.calls.contains("save")); assertEquals("release", runtime.calls.get(runtime.calls.size() - 1));
    }

    @Test public void partialInitializationStillAttemptsNormalWorkspaceSave() {
        Runtime runtime = new Runtime(); runtime.failAt = "initialize"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(mock(IApplicationContext.class)));
        assertEquals(List.of("lock", "initialize", "failure", "stop", "wait", "save", "release"), runtime.calls);
        assertEquals("initialize", runtime.failure.getMessage());
    }

    @Test public void saveFailureIsLoggedAndTheWorkspaceLockIsReleased() {
        Runtime runtime = new Runtime(); runtime.failAt = "save"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(mock(IApplicationContext.class)));
        assertEquals("save", runtime.failure.getMessage());
        assertEquals("release", runtime.calls.get(runtime.calls.size() - 1));
    }

    @Test public void releaseFailureReturnsAnErrorWithoutAnUncaughtUiFailure() {
        Runtime runtime = new Runtime(); runtime.failAt = "release"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(mock(IApplicationContext.class)));
        assertEquals("release", runtime.failure.getMessage());
    }

    @Test public void idlePropertyDefaultsAndValidation() {
        assertEquals(Duration.ofMinutes(30), StdioHostApplication.idleDuration(null));
        assertEquals(Duration.ZERO, StdioHostApplication.idleDuration("0"));
        assertEquals(Duration.ofMinutes(7), StdioHostApplication.idleDuration("7"));
        for (String bad : List.of("-1", "abc", "999999999999999999")) {
            try { StdioHostApplication.idleDuration(bad); fail(bad); } catch (IllegalArgumentException | ArithmeticException expected) { }
        }
    }
}
