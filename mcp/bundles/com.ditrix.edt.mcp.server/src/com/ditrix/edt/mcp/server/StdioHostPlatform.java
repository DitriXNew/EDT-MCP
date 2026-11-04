// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import org.eclipse.core.resources.ResourcesPlugin;
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
    @Override public void startCore(HeadlessLifetime lifetime) {
        Activator.getDefault().startHeadlessCore(lifetime);
    }
    @Override public boolean ipcRunning() { return StdioPluginSupport.isIpcRunning(); }
    @Override public void stopContexts() {
        StdioPluginSupport.stop();
        CoreServiceLifecycleUtils.stopDefaultContexts();
    }
    @Override public void waitForContexts() { CoreServiceLifecycleUtils.waitForStartingContexts(); }
    @Override public void save() throws Exception {
        ResourcesPlugin.getWorkspace().save(true, new NullProgressMonitor());
        Activator.logInfo("EDT MCP headless workspace saved");
    }
    @Override public void release() {
        try { if (questions != null) { questions.unregister(); } }
        finally { questions = null; location.release(); }
    }
    @Override public void failure(String message, Throwable error) { Activator.logError(message, error); }
}
