/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.ImportProjectFromFileTool.IProjectImporter;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.ConversionException;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.IThickClient;

/** File-import preflight and the two places a name may become taken after it. */
public class ImportProjectFileFolderCaseTest extends ImportFolderCaseTestSupport
{
    private BackgroundJobs jobs;
    private IThickClient client;
    private RecordingImporter importer;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private boolean refuseProvider;
    private Runnable providerHook;
    private Runnable conversionHook;

    @Before
    public void setUpPlatform() throws Exception
    {
        jobs = new BackgroundJobs(20, 2);
        client = mock(IThickClient.class);
        importer = new RecordingImporter();
        when(client.platformVersion()).thenReturn("8.3.99.1");
        doAnswer(invocation ->
        {
            Path target = invocation.getArgument(2);
            Files.writeString(target.resolve("Configuration.xml"), CONFIGURATION_XML);
            if (conversionHook != null)
            {
                conversionHook.run();
            }
            return null;
        }).when(client).dumpConfiguration(any(Path.class), nullable(String.class), any(Path.class));
    }

    @After
    public void closeJobs()
    {
        jobs.close();
    }

    @Test
    public void testAnImportOfACaseVariantNameInFlightIsRefused() throws Exception
    {
        String name = name();
        assertNull(ImportConfigurationFromXmlTool.tryClaimImport(name));
        refuseProvider = true;
        String result = executeFile(variant(name));
        if (caseSensitive())
        {
            error(result, "provider entered");
            assertEquals(1, providerCalls.get());
        }
        else
        {
            error(result, "already in progress", "`" + name + "`", "`" + variant(name) + "`",
                "letter case", "list_projects");
            assertEquals(0, providerCalls.get());
        }
        verifyZeroInteractions(client);
        assertEquals(0, importer.calls.get());
        assertNotNull("a refused import cannot release its competitor",
            ImportConfigurationFromXmlTool.importClaimedAt(name));
    }

    @Test
    public void testATakenNameDifferingOnlyByCaseIsRefusedBeforeAnyJob() throws Exception
    {
        String name = name();
        createProject(name);
        refuseProvider = true;
        String result = executeFile(variant(name));
        if (caseSensitive())
        {
            error(result, "provider entered");
            assertEquals(1, providerCalls.get());
        }
        else
        {
            assertCaseRefusal(result, name, "already exists", "new project name");
            error(result, "remove the old one with delete_project first.");
            assertEquals(0, providerCalls.get());
            assertEquals(0, importer.apiChecks.get());
            assertEquals(0, importer.calls.get());
        }
        verifyZeroInteractions(client);
        assertFalse(result, result.contains("jobId"));
    }

    @Test
    public void testATakenCaseVariantOutsideItsDefaultFolderIsNotSaidToShareIt() throws Exception
    {
        String name = name();
        org.eclipse.core.resources.IProject existing = createProjectElsewhere(name);
        refuseProvider = true;
        String result = executeFile(variant(name));
        if (caseSensitive())
        {
            error(result, "provider entered");
        }
        else
        {
            String message = error(result, "letter case", "one resource",
                "The existing project is located at " + location(existing) + ". ",
                "remove the old one with delete_project first.");
            assertFalse(message, message.contains(defaultFolder(name)));
            assertFalse(message, message.contains(defaultFolder(variant(name))));
            assertEquals(0, providerCalls.get());
        }
        verifyZeroInteractions(client);
    }

    @Test
    public void testACaseVariantCreatedDuringConversionIsRefusedByTheFinalRecheck() throws Exception
    {
        String name = name();
        conversionHook = () ->
        {
            try
            {
                createProject(name);
            }
            catch (Exception e)
            {
                throw new AssertionError(e);
            }
        };
        String result = executeFile(variant(name));
        assertEquals(1, providerCalls.get());
        if (caseSensitive())
        {
            assertFalse(result, result.startsWith("{"));
            assertEquals(1, importer.calls.get());
            assertEquals(variant(name), importer.importedName);
        }
        else
        {
            assertCaseRefusal(result, name, "already exists", "new project name");
            error(result, "It appeared while the file was being converted, so nothing was imported.");
            assertEquals(0, importer.calls.get());
        }
        assertNull("the job releases its own claim", ImportConfigurationFromXmlTool.importClaimedAt(variant(name)));
    }

    @Test
    public void testAClaimTakenAfterPreflightIsRefusedBeforeConversion() throws Exception
    {
        String name = name();
        providerHook = () -> assertNull(ImportConfigurationFromXmlTool.tryClaimImport(name));
        String result = executeFile(variant(name));
        assertEquals(1, providerCalls.get());
        if (caseSensitive())
        {
            assertFalse(result, result.startsWith("{"));
            assertEquals(1, importer.calls.get());
            assertEquals(variant(name), importer.importedName);
        }
        else
        {
            assertCaseRefusal(result, name, "already in progress", "list_projects");
            verifyZeroInteractions(client);
            assertEquals(0, importer.calls.get());
        }
        assertNotNull("job refusal preserves the other tool's claim",
            ImportConfigurationFromXmlTool.importClaimedAt(name));
    }

    @Test
    public void testExactNameRefusalKeepsItsExistingText() throws Exception
    {
        String name = name();
        createProject(name);
        assertEquals("Project already exists in workspace: " + name
            + ". The import creates a NEW project: pass a new project name, or remove the old one with delete_project first.",
            error(executeFile(name)));
        assertEquals(0, providerCalls.get());
    }

    private String executeFile(String name)
    {
        ImportProjectFromFileTool tool = new ImportProjectFromFileTool(jobs, (version, base) ->
        {
            providerCalls.incrementAndGet();
            if (refuseProvider)
            {
                throw new ConversionException("provider entered");
            }
            if (providerHook != null)
            {
                providerHook.run();
            }
            return client;
        }, importer, new ReadyLifecycle(), 5_000, ImportProjectFromFileTool.CONVERSION_BUDGET_MS, dump);
        return tool.execute(Map.of("filePath", binary.toString(), "projectName", name, "waitSeconds", "45"));
    }

    /** The package-private interface needs a recorder, not a cross-loader Mockito proxy. */
    private static final class RecordingImporter implements IProjectImporter
    {
        final AtomicInteger apiChecks = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        volatile String importedName;

        @Override
        public boolean isAvailable()
        {
            apiChecks.incrementAndGet();
            return true;
        }

        @Override
        public void importProject(Path xmlDir, String projectName, String version, String base)
        {
            importedName = projectName;
            calls.incrementAndGet();
        }
    }

    private static void assertCaseRefusal(String result, String name, String reason, String suggestion)
    {
        error(result, reason, "`" + name + "`", "`" + variant(name) + "`", "letter case", suggestion);
    }
}
