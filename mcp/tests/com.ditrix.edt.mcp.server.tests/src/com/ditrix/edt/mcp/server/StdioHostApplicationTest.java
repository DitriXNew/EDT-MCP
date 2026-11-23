// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.app.IApplicationContext;
import org.junit.Test;

public class StdioHostApplicationTest
{
    /**
     * Minimal context: only {@link #applicationRunning()} is ever called on it, and a Mockito jar
     * is not part of this fragment's target platform - the interface has nine methods, so a fake
     * is cheaper than a mock library that is not there.
     */
    private static class Context implements IApplicationContext {
        boolean running;
        public void applicationRunning() { running = true; }
        public java.util.Map getArguments() { return null; }
        public String getBrandingApplication() { return null; }
        public String getBrandingName() { return null; }
        public String getBrandingDescription() { return null; }
        public String getBrandingId() { return null; }
        public String getBrandingProperty(String key) { return null; }
        public org.osgi.framework.Bundle getBrandingBundle() { return null; }
        public void setResult(Object result, org.eclipse.equinox.app.IApplication application) { }
    }

    private static class Runtime implements StdioHostApplication.Runtime {
        final List<String> calls = new ArrayList<>();
        Throwable failure;
        String failAt;
        boolean lock = true;
        boolean ipc = true;
        boolean stopOnCore = true;
        StdioHostApplication app;
        java.util.function.Consumer<java.util.function.Consumer<Boolean>> shutdown;
        final java.util.concurrent.CountDownLatch coreStarted = new java.util.concurrent.CountDownLatch(1);
        private void step(String name) { calls.add(name); if (name.equals(failAt)) { throw new IllegalStateException(name); } }
        public boolean lockWorkspace() { step("lock"); return lock; }
        public void initialize() { step("initialize"); }
        public void armHostShutdown(java.util.function.Consumer<java.util.function.Consumer<Boolean>> action) { step("arm"); shutdown = action; }
        public void startCore(HeadlessLifetime lifetime) { step("core"); coreStarted.countDown(); if (stopOnCore) { app.stop(); } }
        public boolean ipcRunning() { step("ipc"); return ipc; }
        public void stopContexts() { step("stop"); }
        public void waitForContexts() { step("wait"); }
        public void save() throws CoreException { step("save"); }
        public void release() { step("release"); }
        public void failure(String message, Throwable error) { failure = error; calls.add("failure"); }
    }

    @Test public void cleanShutdownStopsContextsSavesAndReleasesWorkspace() {
        Runtime runtime = new Runtime(); runtime.app = new StdioHostApplication(runtime);
        Context context = new Context();
        assertEquals(0, runtime.app.start(context));
        assertEquals(List.of("lock", "initialize", "arm", "core", "ipc", "stop", "wait", "save", "release"), runtime.calls);
        assertTrue(context.running); assertNull(runtime.failure);
    }

    @Test public void clientRequestReportsTheWorkspaceCleanupOutcome() throws Exception {
        assertShutdownOutcome(null, true);
        assertShutdownOutcome("save", false);
    }

    private static void assertShutdownOutcome(String failure, boolean success) throws Exception {
        Runtime runtime = new Runtime(); runtime.app = new StdioHostApplication(runtime);
        runtime.stopOnCore = false;
        runtime.failAt = failure;
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var completion = new java.util.concurrent.CompletableFuture<Boolean>();
        try {
            var result = executor.submit(() -> runtime.app.start(new Context()));
            assertTrue(runtime.coreStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(runtime.shutdown);
            runtime.shutdown.accept(ok -> {
                assertEquals("release", runtime.calls.get(runtime.calls.size() - 1));
                completion.complete(ok);
            });
            assertEquals(Boolean.valueOf(success), completion.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(success ? 0 : 1), result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            runtime.app.stop();
            executor.shutdownNow();
        }
    }

    @Test public void workspaceSaveWarningCannotBeConfirmed() throws Exception {
        var workspace = mock(IWorkspace.class);
        var warning = new Status(IStatus.WARNING, "test.save", "Participant could not save");
        when(workspace.save(eq(true), any(IProgressMonitor.class))).thenReturn(warning);
        assertWorkspaceShutdown(workspace, false, warning, null);
    }

    @Test public void workspaceSaveOkCanBeConfirmed() throws Exception {
        var workspace = mock(IWorkspace.class);
        when(workspace.save(eq(true), any(IProgressMonitor.class))).thenReturn(Status.OK_STATUS);
        assertWorkspaceShutdown(workspace, true, null, null);
    }

    @Test public void workspaceSaveErrorStatusCannotBeConfirmed() throws Exception {
        var workspace = mock(IWorkspace.class);
        var error = new Status(IStatus.ERROR, "test.save", "Workspace could not save");
        when(workspace.save(eq(true), any(IProgressMonitor.class))).thenReturn(error);
        assertWorkspaceShutdown(workspace, false, error, null);
    }

    @Test public void workspaceSaveExceptionIsPreserved() throws Exception {
        var workspace = mock(IWorkspace.class);
        var thrown = new CoreException(new Status(IStatus.ERROR, "test.save", "Workspace save threw"));
        when(workspace.save(eq(true), any(IProgressMonitor.class))).thenThrow(thrown);
        assertWorkspaceShutdown(workspace, false, null, thrown);
    }

    private static void assertWorkspaceShutdown(IWorkspace workspace, boolean success, IStatus status,
        CoreException thrown) throws Exception {
        Runtime runtime = new Runtime() {
            @Override public void save() throws CoreException {
                super.save();
                StdioHostPlatform.saveWorkspace(workspace);
            }
            @Override public void stopTransport() { calls.add("transport"); }
        };
        runtime.app = new StdioHostApplication(runtime);
        runtime.stopOnCore = false;
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var completion = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        try {
            var result = executor.submit(() -> runtime.app.start(new Context()));
            assertTrue(runtime.coreStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            runtime.shutdown.accept(ok -> { completion.set(ok); runtime.calls.add("receipt"); });
            Object exit = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("Workspace shutdown receipt", Boolean.valueOf(success), completion.get());
            assertEquals(Integer.valueOf(success ? 0 : 1), exit);
            assertEquals(success ? List.of("save", "release", "receipt", "transport")
                : List.of("save", "failure", "release", "receipt", "transport"),
                runtime.calls.subList(runtime.calls.indexOf("save"), runtime.calls.size()));
            if (success) { assertNull(runtime.failure); }
            else if (thrown != null) { assertSame(thrown, runtime.failure); }
            else { assertSame(status, ((CoreException)runtime.failure).getStatus()); }
        } finally {
            runtime.app.stop();
            executor.shutdownNow();
        }
    }

    @Test public void shutdownChannelIsArmedBeforeTheCoreStarts() {
        // The IPC listener appears inside core; arming after it would refuse a request that
        // arrived during workspace loading.
        Runtime runtime = new Runtime(); runtime.app = new StdioHostApplication(runtime);
        assertEquals(0, runtime.app.start(new Context()));
        assertTrue(runtime.calls.indexOf("arm") < runtime.calls.indexOf("core"));
    }

    @Test public void occupiedWorkspaceNeverInitializesOrReleasesAnotherHostsLock() {
        Runtime runtime = new Runtime(); runtime.lock = false; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(new Context()));
        assertEquals(List.of("lock", "failure"), runtime.calls); assertNotNull(runtime.failure);
    }

    @Test public void coreFailurePreservesTheThrowableAndStillSaves() {
        Runtime runtime = new Runtime(); runtime.failAt = "core"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(new Context()));
        assertEquals("core", runtime.failure.getMessage());
        assertEquals(List.of("lock", "initialize", "arm", "core", "failure", "stop", "wait", "save", "release"), runtime.calls);
    }

    @Test public void failedContextStopStillSavesAndReleases() {
        Runtime runtime = new Runtime(); runtime.failAt = "stop"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(new Context()));
        assertEquals(List.of("lock", "initialize", "arm", "core", "ipc", "stop", "failure", "wait", "save", "release"), runtime.calls);
    }

    @Test public void failedListenerDoesNotAdvertiseReadiness() {
        Runtime runtime = new Runtime(); runtime.ipc = false; runtime.app = new StdioHostApplication(runtime);
        Context context = new Context();
        assertEquals(1, runtime.app.start(context)); assertFalse(context.running);
        assertTrue(runtime.calls.contains("save")); assertEquals("release", runtime.calls.get(runtime.calls.size() - 1));
    }

    @Test public void partialInitializationStillAttemptsNormalWorkspaceSave() {
        Runtime runtime = new Runtime(); runtime.failAt = "initialize"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(new Context()));
        assertEquals(List.of("lock", "initialize", "failure", "stop", "wait", "save", "release"), runtime.calls);
        assertEquals("initialize", runtime.failure.getMessage());
    }

    @Test public void saveFailureIsLoggedAndTheWorkspaceLockIsReleased() {
        Runtime runtime = new Runtime(); runtime.failAt = "save"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(new Context()));
        assertEquals("save", runtime.failure.getMessage());
        assertEquals("release", runtime.calls.get(runtime.calls.size() - 1));
    }

    @Test public void releaseFailureReturnsAnErrorWithoutAnUncaughtUiFailure() {
        Runtime runtime = new Runtime(); runtime.failAt = "release"; runtime.app = new StdioHostApplication(runtime);
        assertEquals(1, runtime.app.start(new Context()));
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
