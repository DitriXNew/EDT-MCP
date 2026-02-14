/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com._1c.g5.v8.dt.core.lifecycle.ProjectContext;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.lifecycle.ILifecycleContext;
import com._1c.g5.v8.dt.lifecycle.IServiceContextLifecycleListener;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.lifecycle.ServiceState;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for waiting on project lifecycle events.
 * Uses IServicesOrchestrator listener to properly wait for project context state changes.
 * 
 * <p>Usage for clean build (to avoid race condition):
 * <pre>
 * ProjectRestartWaiter waiter = LifecycleWaiter.prepareForRestart(dtProject);
 * if (waiter != null) {
 *     project.build(CLEAN_BUILD, monitor);  // Trigger the clean build
 *     waiter.await(timeoutMs);              // Wait for restart to complete
 * }
 * </pre>
 */
public final class LifecycleWaiter
{
    private LifecycleWaiter()
    {
        // Utility class
    }
    
    /**
     * Prepares to wait for project context restart by registering listener BEFORE the operation.
     * This avoids the race condition where the STOPPED event could be missed.
     * 
     * @param dtProject the DT project to wait for
     * @return ProjectRestartWaiter instance or null if services not available
     */
    public static ProjectRestartWaiter prepareForRestart(IDtProject dtProject)
    {
        if (dtProject == null)
        {
            Activator.logInfo("No DtProject provided for lifecycle wait"); //$NON-NLS-1$
            return null;
        }
        
        IServicesOrchestrator orchestrator = Activator.getDefault().getServicesOrchestrator();
        if (orchestrator == null)
        {
            Activator.logInfo("IServicesOrchestrator not available, skipping lifecycle wait"); //$NON-NLS-1$
            return null;
        }
        
        String projectName = dtProject.getName();
        Activator.logInfo("Preparing lifecycle listener for project restart: " + projectName); //$NON-NLS-1$
        
        return new ProjectRestartWaiter(orchestrator, projectName);
    }
    
    /**
     * Waits for project context to reach STARTED state.
     * Use this when the project is already in the process of starting.
     * 
     * @param dtProject the DT project to wait for
     * @param timeoutMs timeout in milliseconds
     * @return true if STARTED state was reached, false if timeout occurred
     */
    public static boolean waitForProjectStarted(IDtProject dtProject, long timeoutMs)
    {
        if (dtProject == null)
        {
            Activator.logInfo("No DtProject provided for lifecycle wait"); //$NON-NLS-1$
            return false;
        }
        
        IServicesOrchestrator orchestrator = Activator.getDefault().getServicesOrchestrator();
        if (orchestrator == null)
        {
            Activator.logInfo("IServicesOrchestrator not available, skipping lifecycle wait"); //$NON-NLS-1$
            return false;
        }
        
        String projectName = dtProject.getName();
        Activator.logInfo("Waiting for project STARTED state: " + projectName); //$NON-NLS-1$
        
        CountDownLatch startedLatch = new CountDownLatch(1);
        
        IServiceContextLifecycleListener listener = new IServiceContextLifecycleListener()
        {
            @Override
            public void contextStateChanged(ILifecycleContext context, ServiceState state)
            {
                if (context instanceof ProjectContext)
                {
                    ProjectContext projectContext = (ProjectContext) context;
                    IDtProject contextProject = projectContext.getProject();
                    
                    if (contextProject != null && projectName.equals(contextProject.getName()))
                    {
                        Activator.logInfo("Lifecycle event for " + projectName + ": " + state); //$NON-NLS-1$ //$NON-NLS-2$
                        
                        if (state == ServiceState.STARTED)
                        {
                            startedLatch.countDown();
                        }
                    }
                }
            }
        };
        
        try
        {
            orchestrator.addListener(listener);
            
            boolean started = startedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!started)
            {
                Activator.logInfo("Timeout waiting for project STARTED: " + projectName); //$NON-NLS-1$
                return false;
            }
            
            Activator.logInfo("Project reached STARTED state: " + projectName); //$NON-NLS-1$
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            Activator.logInfo("Lifecycle wait interrupted for: " + projectName); //$NON-NLS-1$
            return false;
        }
        finally
        {
            try
            {
                orchestrator.removeListener(listener);
            }
            catch (Exception e)
            {
                Activator.logError("Error removing lifecycle listener", e); //$NON-NLS-1$
            }
        }
    }
    
    /**
     * Helper class for waiting on project restart with pre-registered listener.
     * This allows registering the listener BEFORE triggering the operation
     * that causes the restart, avoiding race conditions.
     */
    public static class ProjectRestartWaiter
    {
        private final IServicesOrchestrator orchestrator;
        private final String projectName;
        private final CountDownLatch stoppedLatch = new CountDownLatch(1);
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private final IServiceContextLifecycleListener listener;
        private final long registrationTime;
        
        ProjectRestartWaiter(IServicesOrchestrator orchestrator, String projectName)
        {
            this.orchestrator = orchestrator;
            this.projectName = projectName;
            this.registrationTime = System.currentTimeMillis();
            
            this.listener = new IServiceContextLifecycleListener()
            {
                private volatile boolean seenStopped = false;
                
                @Override
                public void contextStateChanged(ILifecycleContext context, ServiceState state)
                {
                    if (context instanceof ProjectContext)
                    {
                        ProjectContext projectContext = (ProjectContext) context;
                        IDtProject contextProject = projectContext.getProject();
                        
                        if (contextProject != null && projectName.equals(contextProject.getName()))
                        {
                            Activator.logInfo("Lifecycle event for " + projectName + ": " + state); //$NON-NLS-1$ //$NON-NLS-2$
                            
                            if (state == ServiceState.STOPPED)
                            {
                                seenStopped = true;
                                stoppedLatch.countDown();
                            }
                            else if (state == ServiceState.STARTED && seenStopped)
                            {
                                startedLatch.countDown();
                            }
                        }
                    }
                }
            };
            
            // Register listener immediately in constructor
            orchestrator.addListener(listener);
            Activator.logInfo("Lifecycle listener registered for: " + projectName); //$NON-NLS-1$
        }
        
        /**
         * Waits for the project restart to complete.
         * Call this AFTER triggering the operation that causes the restart.
         * 
         * @param timeoutMs timeout in milliseconds
         * @return true if restart completed, false on timeout
         */
        public boolean await(long timeoutMs)
        {
            try
            {
                // Calculate time already elapsed since listener registration
                long elapsed = System.currentTimeMillis() - registrationTime;
                long remainingForStopped = Math.max(0, timeoutMs - elapsed);
                
                // Wait for STOPPED state
                boolean stopped = stoppedLatch.await(remainingForStopped, TimeUnit.MILLISECONDS);
                if (!stopped)
                {
                    Activator.logInfo("Timeout waiting for project STOPPED state: " + projectName); //$NON-NLS-1$
                    return false;
                }
                
                // Calculate remaining time for STARTED
                elapsed = System.currentTimeMillis() - registrationTime;
                long remainingForStarted = Math.max(0, timeoutMs - elapsed);
                
                // Wait for STARTED state
                boolean started = startedLatch.await(remainingForStarted, TimeUnit.MILLISECONDS);
                if (!started)
                {
                    Activator.logInfo("Timeout waiting for project STARTED state: " + projectName); //$NON-NLS-1$
                    return false;
                }
                
                Activator.logInfo("Project lifecycle restart completed: " + projectName); //$NON-NLS-1$
                return true;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                Activator.logInfo("Lifecycle wait interrupted for: " + projectName); //$NON-NLS-1$
                return false;
            }
            finally
            {
                cleanup();
            }
        }
        
        /**
         * Cleanup resources. Called automatically by await(), but can be called
         * manually if await() is not going to be called.
         */
        public void cleanup()
        {
            try
            {
                orchestrator.removeListener(listener);
                Activator.logInfo("Lifecycle listener removed for: " + projectName); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Activator.logError("Error removing lifecycle listener", e); //$NON-NLS-1$
            }
        }
    }
}
