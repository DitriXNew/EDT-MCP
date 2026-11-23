// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import java.util.function.Consumer;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import com._1c.g5.v8.dt.core.lifecycle.CoreServiceLifecycleUtils;
import com._1c.g5.wiring.ServiceInitialization;
import com.ditrix.edt.mcp.server.transport.StdioPluginSupport;

/** Platform operations used by the windowless application; no workbench API. */
final class StdioHostPlatform implements StdioHostApplication.Runtime
{
    private Location location;
    private org.osgi.framework.ServiceRegistration<?> questions;
    @Override public boolean lockWorkspace() throws Exception {
        location = Platform.getInstanceLocation();
        return location != null && location.isSet() && location.lock();
    }
    @Override public void initialize() {
        if (HostKind.current() == HostKind.HEADLESS) {
            questions = com.ditrix.edt.mcp.server.utils.HeadlessPlatformQuestions.register(
                org.osgi.framework.FrameworkUtil.getBundle(StdioHostPlatform.class).getBundleContext());
        }
        ServiceInitialization.startInitialization();
        ServiceInitialization.join();
    }
    @Override public void armHostShutdown(Consumer<Consumer<Boolean>> shutdown) {
        // The immersive host is the only one allowed to accept the request; the windowed EDT
        // leaves the channel empty and logs the refusal. A host whose plugin is not active keeps
        // running without a channel rather than failing to start over one optional request.
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getMcpServer() == null) {
            Activator.logError("Cannot arm the host shutdown channel: the MCP plugin is not active", null);
            return;
        }
        activator.getMcpServer().setHostShutdown(shutdown);
    }
    @Override public void startCore(HeadlessLifetime lifetime) {
        Activator.getDefault().startHeadlessCore(lifetime);
    }
    @Override public boolean ipcRunning() { return StdioPluginSupport.isIpcRunning(); }
    @Override public void stopTransport() { StdioPluginSupport.stop(); }
    @Override public void stopContexts() {
        CoreServiceLifecycleUtils.stopDefaultContexts();
    }
    @Override public void waitForContexts() { CoreServiceLifecycleUtils.waitForStartingContexts(); }
    @Override public void save() throws Exception {
        saveWorkspace(ResourcesPlugin.getWorkspace());
        Activator.logInfo("EDT MCP headless workspace saved");
    }
    static void saveWorkspace(IWorkspace workspace) throws CoreException {
        var status = workspace.save(true, new NullProgressMonitor());
        // Eclipse can report a failed save participant as a warning without throwing.
        if (!status.isOK()) { throw new CoreException(status); }
    }
    @Override public void release() {
        try { if (questions != null) { questions.unregister(); } }
        finally { questions = null; location.release(); }
    }
    @Override public void failure(String message, Throwable error) { Activator.logError(message, error); }
}
