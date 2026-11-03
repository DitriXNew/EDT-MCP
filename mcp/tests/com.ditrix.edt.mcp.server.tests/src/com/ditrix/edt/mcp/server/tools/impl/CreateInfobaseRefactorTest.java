/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.InfobaseTarget;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ServerInfobaseTarget;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CreateInfobaseRefactorTest
{
    @Test
    public void sharedContextKeepsRefusalTextAndLazyServiceLookup() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var lookups = new AtomicInteger();
        Supplier<IApplicationManager> lookup = () -> { lookups.incrementAndGet(); return manager; };
        Object absent = context(null, lookup);
        assertEquals(ProjectContext.notFoundMessage("Project"), error(absent));
        Object closed = context(project, lookup);
        assertEquals("Project is closed: Project. Open the project in EDT first.", error(closed));
        assertEquals(0, lookups.get());
        when(project.isOpen()).thenReturn(true);
        Object unavailable = context(project, () -> null);
        assertEquals("IApplicationManager service is not available", error(unavailable));
        Object resolved = context(project, lookup);
        assertNull(value(resolved, "error"));
        assertSame(project, value(resolved, "project"));
        assertSame(manager, value(resolved, "manager"));
        assertEquals(1, lookups.get());
    }

    @Test
    public void boundResultPayloadsKeepFileClusterAndStandaloneContracts() throws Exception
    {
        for (int kind : new int[] {0, 1, 2})
        {
            for (boolean register : new boolean[] {false, true})
            {
                var project = mock(IProject.class);
                var manager = mock(IApplicationManager.class);
                var directory = Path.of("infobases", "Refactor").toAbsolutePath();
                var server = kind == 1 ? new ServerInfobaseTarget("host:1541", "base") : null;
                var reference = new InfobaseTarget(kind == 1 ? null : directory, server).reference("Base");
                var type = mock(IApplicationType.class);
                String typeId = kind == 2 ? StandaloneServerSupport.WST_SERVER_APP_TYPE
                    : "com.e1c.g5.dt.applications.type.infobase";
                when(type.getId()).thenReturn(typeId);
                IApplication application;
                if (kind == 2)
                {
                    application = mock(IApplication.class);
                }
                else
                {
                    var infobase = mock(IInfobaseApplication.class);
                    when(infobase.getInfobase()).thenReturn(reference);
                    application = infobase;
                }
                when(application.getId()).thenReturn("application-id");
                when(application.getName()).thenReturn("Base");
                when(application.getType()).thenReturn(type);
                when(manager.getUpdateState(application)).thenReturn(ApplicationUpdateState.UPDATED);
                when(manager.getApplications(project)).thenReturn(List.of(application));
                var context = new CreateInfobaseTool.ResultContext("Project", directory, "Base", manager, project, server);
                String raw = kind == 2
                    ? CreateInfobaseTool.buildStandaloneServerResult(context, 8314, "http://localhost:8314/base",
                        false, register, new CreateInfobaseTool.Credentials(null, null, null))
                    : CreateInfobaseTool.buildSuccessResult(context, reference, false, register, (String)null);
                var expected = new JsonObject();
                expected.addProperty("success", true);
                expected.addProperty("action", register ? "registered" : "created");
                expected.addProperty("project", "Project");
                expected.addProperty("infobaseName", "Base");
                expected.addProperty("applicationId", "application-id");
                expected.addProperty("boundToProject", true);
                var app = new JsonObject();
                app.addProperty("id", "application-id");
                app.addProperty("name", "Base");
                app.addProperty("type", typeId);
                app.addProperty("updateState", "UPDATED");
                var applications = new JsonArray();
                applications.add(app);
                expected.add("applications", applications);
                if (kind == 1)
                {
                    expected.addProperty("infobaseServer", "host:1541");
                    expected.addProperty("infobaseRef", "base");
                }
                else { expected.addProperty("infobaseFile", directory.toString()); }
                if (kind == 2)
                {
                    expected.addProperty("applicationKind", "standaloneServer");
                    expected.addProperty("port", 8314);
                    expected.addProperty("webUrl", "http://localhost:8314/base");
                    String subject = register ? "Registered a standalone server over the existing infobase 'Base'"
                        : "Standalone server for infobase 'Base' created";
                    expected.addProperty("message", subject + " at '" + directory + "'"
                        + (register ? " and bound it to project 'Project'" : " and bound to project 'Project'")
                        + " (web port 8314). Web URL for HTTP testing: http://localhost:8314/base."
                        + " To load the configuration, use the coordinated launch flow (launch or"
                        + " run_yaxunit_tests with updateBeforeLaunch=true) rather than a bare update_database,"
                        + " which would start the server in RUN mode.");
                }
                else
                {
                    String location = kind == 1 ? "host:1541/base" : directory.toString();
                    expected.addProperty("message", "Infobase 'Base' " + (register ? "registered" : "created")
                        + " at '" + location + "' and bound to project 'Project'."
                        + " Use update_database to push the configuration into the infobase.");
                }
                assertEquals("kind=" + kind + ", register=" + register, expected, JsonParser.parseString(raw));
                verify(manager, never()).setDefaultApplication(any(), any());
            }
        }
    }

    @Test
    public void sharedWaitKeepsCompletionAndTimeoutCancellation() throws Exception
    {
        Method wait = CreateInfobaseTool.class.getDeclaredMethod("awaitCreationJob", Job.class,
            long.class, Supplier.class, Supplier.class);
        wait.setAccessible(true);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var monitor = new AtomicReference<IProgressMonitor>();
        Job job = new Job("creation-wait-refactor") {
            @Override protected IStatus run(IProgressMonitor progress)
            {
                monitor.set(progress);
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
        try
        {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            String result = (String)wait.invoke(null, job, 50L,
                (Supplier<String>)() -> "original timeout diagnosis", (Supplier<String>)() -> "interrupted");
            assertEquals("original timeout diagnosis", JsonParser.parseString(result).getAsJsonObject().get("error").getAsString());
            assertTrue(monitor.get().isCanceled());
        }
        finally
        {
            release.countDown();
            assertTrue(job.join(5_000, null));
        }
        assertNull(wait.invoke(null, job, 5_000L,
            (Supplier<String>)() -> { fail("completed job must not report timeout"); return null; },
            (Supplier<String>)() -> { fail("completed job must not report interruption"); return null; }));
    }

    private static Object context(IProject project, Supplier<IApplicationManager> lookup) throws Exception
    {
        Method resolve = CreateInfobaseTool.class.getDeclaredMethod("resolveApplicationContext", String.class,
            IProject.class, Supplier.class);
        resolve.setAccessible(true);
        return resolve.invoke(null, "Project", project, lookup);
    }

    private static Object value(Object context, String accessor) throws Exception
    {
        Method method = context.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(context);
    }

    private static String error(Object context) throws Exception
    {
        return JsonParser.parseString((String)value(context, "error")).getAsJsonObject().get("error").getAsString();
    }
}
