/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectImportValidation;

/** Whole XML-tool refusals, including a workspace change during validation. */
public class ImportConfigurationFolderCaseTest extends ImportFolderCaseTestSupport
{
    @Test
    public void testASecondImportWhoseNameDiffersOnlyByCaseIsRefusedWhileTheFirstRuns() throws Exception
    {
        String name = name();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingRunner first = new RecordingRunner()
        {
            @Override
            public void runImport(Object api, Path path, String project, String version, String base)
                throws Exception
            {
                super.runImport(api, path, project, version, base);
                entered.countDown();
                assertTrue("release the first import", release.await(30, TimeUnit.SECONDS));
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> firstAnswer = pool.submit(() -> executeXml(name, first));
        try
        {
            assertTrue("the first import entered the CLI runner", entered.await(30, TimeUnit.SECONDS));
            RecordingRunner second = new RecordingRunner();
            String result = executeXml(variant(name), second);
            if (caseSensitive())
            {
                assertTrue(result, result.contains("status: success"));
                assertEquals(1, second.calls.get());
            }
            else
            {
                error(result, "already in progress", "`" + name + "`", "`" + variant(name) + "`",
                    "letter case", "list_projects");
                assertEquals("refused before resolving the CLI", 0, second.apiChecks.get());
                assertEquals(0, second.calls.get());
            }
        }
        finally
        {
            release.countDown();
            try
            {
                String result = firstAnswer.get(30, TimeUnit.SECONDS);
                assertTrue(result, result.contains("status: success"));
            }
            finally
            {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
        assertNull(ImportConfigurationFromXmlTool.importClaimedAt(name));
        assertNull(ImportConfigurationFromXmlTool.importClaimedAt(variant(name)));
    }

    @Test
    public void testAnExistingProjectWhoseNameDiffersOnlyByCaseIsRefusedBeforeImport() throws Exception
    {
        String name = name();
        createProject(name);
        RecordingRunner runner = new RecordingRunner();
        String result = executeXml(variant(name), runner);
        assertTakenOrDistinct(result, name, runner);
        if (!caseSensitive())
        {
            // The existing project IS in its default folder, so that folder is really shared.
            error(result, "Both would use the workspace folder " + defaultFolder(name) + ". ",
                "Import requires a new project name.");
        }
    }

    @Test
    public void testAnExistingCaseVariantOutsideItsDefaultFolderIsNotSaidToShareIt() throws Exception
    {
        String name = name();
        IProject existing = createProjectElsewhere(name);
        RecordingRunner runner = new RecordingRunner();
        String result = executeXml(variant(name), runner);
        assertTakenOrDistinct(result, name, runner);
        if (!caseSensitive())
        {
            String message = error(result, "one resource", "refuses to create the second one",
                "The existing project is located at " + location(existing) + ". ",
                "Import requires a new project name.");
            assertFalse(message, message.contains(defaultFolder(name)));
            assertFalse(message, message.contains(defaultFolder(variant(name))));
            assertFalse(message, message.contains("workspace folder"));
        }
    }

    @Test
    public void testExistingCaseVariantRefusalPrecedesDumpValidation() throws Exception
    {
        String name = name();
        createProject(name);
        RecordingRunner runner = new RecordingRunner();
        String result = ImportConfigurationFromXmlTool.executeWith(Map.of("importPath", dump.toString(),
            "projectName", variant(name), "projectNature", "unknown.nature"), runner,
            new ReadyLifecycle(), 5_000);
        if (caseSensitive())
        {
            String message = error(result, "unknown.nature");
            assertFalse(message, message.contains("already exists"));
        }
        else
        {
            error(result, "already exists", "`" + name + "`", "`" + variant(name) + "`",
                "letter case", "new project name");
        }
        assertEquals(0, runner.apiChecks.get());
    }

    @Test
    public void testACaseVariantCreatedDuringValidationIsRefusedByTheRecheckUnderTheClaim() throws Exception
    {
        String name = name();
        IProject base = createProject(name());
        IProjectDescription description = base.getDescription();
        description.setNatureIds(new String[] { ProjectImportValidation.NATURE_CONFIGURATION });
        base.setDescription(description, IResource.AVOID_NATURE_CONFIG, new NullProgressMonitor());
        Files.writeString(dump.resolve("Configuration.xml"), CONFIGURATION_XML.replace("<Properties/>",
            "<Properties><ObjectBelonging>Adopted</ObjectBelonging></Properties>"));

        IV8Project v8Project = mock(IV8Project.class);
        when(v8Project.getVersion()).thenReturn(new Version("8.3.27"));
        IV8ProjectManager manager = mock(IV8ProjectManager.class);
        Activator activator = mock(Activator.class);
        when(activator.getV8ProjectManager()).thenReturn(manager);
        when(manager.getProject(base)).thenAnswer(invocation ->
        {
            if (!org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(name).exists())
            {
                createProject(name);
            }
            return v8Project;
        });

        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object saved = plugin.get(null);
        RecordingRunner runner = new RecordingRunner();
        try
        {
            plugin.set(null, activator);
            String result = ImportConfigurationFromXmlTool.executeWith(Map.of("importPath", dump.toString(),
                "projectName", variant(name), "baseProjectName", base.getName()), runner,
                new ReadyLifecycle(), 5_000);
            verify(manager, atLeastOnce()).getProject(base);
            assertTakenOrDistinct(result, name, runner);
        }
        finally
        {
            plugin.set(null, saved);
        }
        assertNull("the recheck releases its claim", ImportConfigurationFromXmlTool.importClaimedAt(variant(name)));
    }

    @Test
    public void testExactNameRefusalKeepsItsExistingText() throws Exception
    {
        String name = name();
        createProject(name);
        RecordingRunner runner = new RecordingRunner();
        assertEquals("Project already exists in workspace: " + name + ". Import requires a new project name.",
            error(executeXml(name, runner)));
        assertEquals(0, runner.apiChecks.get());
    }

    private static void assertTakenOrDistinct(String result, String name, RecordingRunner runner)
    {
        if (caseSensitive())
        {
            assertTrue(result, result.contains("status: success"));
            assertEquals(1, runner.calls.get());
        }
        else
        {
            error(result, "already exists", "`" + name + "`", "`" + variant(name) + "`",
                "letter case", "new project name");
            assertEquals("refused before resolving the CLI", 0, runner.apiChecks.get());
            assertEquals(0, runner.calls.get());
        }
    }
}
