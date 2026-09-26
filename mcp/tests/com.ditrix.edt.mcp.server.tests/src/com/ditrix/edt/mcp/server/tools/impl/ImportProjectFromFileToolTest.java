/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com.google.gson.JsonParser;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportProjectFromFileTool.IProjectImporter;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.ConversionException;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.IThickClient;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

/**
 * Tests for {@link ImportProjectFromFileTool}.
 * <p>
 * The platform is injected: a fake thick client writes the dump a real one would, a fake importer
 * records the call, and a fake lifecycle starts the project at once. So the whole job - refusals,
 * conversion, the pre-commit checks, a failed import and the error markers - runs
 * headless. The real conversion and import are covered by the E2E suite on a stand with a platform.
 */
public class ImportProjectFromFileToolTest
{
    private static final String PLAIN_CONFIGURATION_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //$NON-NLS-1$
        + "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><Configuration uuid=\"1\">" //$NON-NLS-1$
        + "<Properties><Name>Imported</Name></Properties></Configuration></MetaDataObject>"; //$NON-NLS-1$

    private static final String EXTENSION_CONFIGURATION_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //$NON-NLS-1$
        + "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><Configuration uuid=\"1\">" //$NON-NLS-1$
        + "<Properties><ObjectBelonging>Adopted</ObjectBelonging><Name>Ext</Name></Properties>" //$NON-NLS-1$
        + "</Configuration></MetaDataObject>"; //$NON-NLS-1$

    /** Long enough for every job here to finish inside the call. */
    private static final String FULL_WAIT = "45"; //$NON-NLS-1$

    private static final long SANE_WAIT_MS = 30_000;

    private BackgroundJobs jobs;
    private FakeThickClient client;
    private FakeImporter importer;
    private FakeLifecycle lifecycle;
    private final List<Path> tempFiles = new ArrayList<>();
    private final List<String> projectsToDelete = new ArrayList<>();
    private final List<Path> foldersToDelete = new CopyOnWriteArrayList<>();
    private int providerCalls;
    private ConversionException providerFailure;

    @Before
    public void setUp()
    {
        jobs = new BackgroundJobs(20, 2);
        client = new FakeThickClient();
        importer = new FakeImporter();
        lifecycle = new FakeLifecycle();
    }

    @After
    public void tearDown() throws Exception
    {
        client.release();
        jobs.close();
        for (Path file : tempFiles)
        {
            Files.deleteIfExists(file);
        }
        for (String name : projectsToDelete)
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (project.exists())
            {
                project.delete(true, true, new NullProgressMonitor());
            }
        }
        for (Path folder : foldersToDelete)
        {
            BinaryToXmlConverter.deleteQuietly(folder);
        }
    }

    // ==================== Contract ====================

    @Test
    public void testNameAndResponseType()
    {
        ImportProjectFromFileTool tool = new ImportProjectFromFileTool();
        assertEquals("import_project_from_file", tool.getName()); //$NON-NLS-1$
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionCarriesTheLoadBearingClauses()
    {
        String description = new ImportProjectFromFileTool().getDescription();
        assertTrue(description.contains("get_tool_guide('import_project_from_file')")); //$NON-NLS-1$
        assertTrue("the job protocol must be in the always-loaded text", //$NON-NLS-1$
            description.contains("get_job_status")); //$NON-NLS-1$
        assertTrue("the .cfe requirement must be in the always-loaded text", //$NON-NLS-1$
            description.contains("baseProjectName required")); //$NON-NLS-1$
        assertTrue(description.contains("NEW")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresEveryParameterAndOnlyTwoRequired()
    {
        String schema = new ImportProjectFromFileTool().getInputSchema();
        for (String key : List.of("filePath", "projectName", "baseProjectName", "platformVersion", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "waitSeconds")) //$NON-NLS-1$
        {
            assertTrue("schema must declare " + key, schema.contains("\"" + key + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Matcher required = Pattern.compile("\"required\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(schema); //$NON-NLS-1$
        assertTrue("schema must declare a required array", required.find()); //$NON-NLS-1$
        assertEquals("\"filePath\",\"projectName\"", required.group(1).replace(" ", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testGuideDocumentsTheJobAndTheBaseRule()
    {
        String guide = new ImportProjectFromFileTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.contains("get_job_status")); //$NON-NLS-1$
        assertTrue(guide.contains("baseProjectName")); //$NON-NLS-1$
        assertTrue("the guide's start wait must match the code", //$NON-NLS-1$
            guide.contains(TimeUnit.MILLISECONDS.toSeconds(
                ImportConfigurationFromXmlTool.PROJECT_START_BUDGET_MS) + " seconds")); //$NON-NLS-1$
        assertTrue("the guide's conversion budget must match the code", //$NON-NLS-1$
            guide.contains(TimeUnit.MILLISECONDS.toHours(ImportProjectFromFileTool.CONVERSION_BUDGET_MS)
                + " hours")); //$NON-NLS-1$
    }

    // ==================== Refusals before any job ====================

    @Test
    public void testMissingFilePathIsRefused()
    {
        String result = tool().execute(Map.of("projectName", "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(result, "filePath is required"); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testMissingProjectNameIsRefusedAndNamesANewProject() throws IOException
    {
        String result = tool().execute(Map.of("filePath", tempFile(".cf").toString())); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(result, "projectName is required"); //$NON-NLS-1$
        assertFalse("a NEW project name must not be sent to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFileIsRefused()
    {
        String missing = System.getProperty("java.io.tmpdir") + "/no-such-" + UUID.randomUUID() + ".cf"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String result = tool().execute(params(missing, uniqueName()));
        assertError(result, "does not exist"); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testDirectoryIsRefusedAndPointsToTheXmlTool() throws IOException
    {
        Path dir = Files.createTempDirectory("import-dir"); //$NON-NLS-1$
        tempFiles.add(dir);
        String result = tool().execute(params(dir.toString(), uniqueName()));
        assertError(result, "is not a file"); //$NON-NLS-1$
        assertTrue(result.contains("import_configuration_from_xml")); //$NON-NLS-1$
    }

    @Test
    public void testUnsupportedExtensionIsRefusedNamingTheSupportedOnes() throws IOException
    {
        String result = tool().execute(params(tempFile(".dt").toString(), uniqueName())); //$NON-NLS-1$
        assertError(result, "unsupported extension"); //$NON-NLS-1$
        assertTrue(result.contains(".cf, .cfe, .epf, .erf")); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testIllegalProjectNameIsRefused() throws IOException
    {
        String result = tool().execute(params(tempFile(".cf").toString(), "bad/name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(result, "not a legal project name"); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testExistingProjectIsRefused() throws Exception
    {
        String name = createRealProject();
        String result = tool().execute(params(tempFile(".cf").toString(), name)); //$NON-NLS-1$
        assertError(result, "already exists"); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testAWorkspaceFolderOfThatNameIsRefused() throws IOException
    {
        // EDT's project setup checks only the project, so it would create one over this folder.
        String name = uniqueName();
        Path folder = createWorkspaceFolder(name);
        String result = tool().execute(params(tempFile(".cf").toString(), name)); //$NON-NLS-1$
        assertError(result, "already exists"); //$NON-NLS-1$
        assertEquals("The workspace folder " + folder + " already exists, so project '" + name //$NON-NLS-1$ //$NON-NLS-2$
            + "' cannot be created there. Pass a new project name, or remove that folder.", //$NON-NLS-1$
            errorText(result));
        assertEquals(0, providerCalls);
        assertFalse(projectHandle(name).exists());
    }

    @Test
    public void testAClosedBaseProjectIsRefused() throws Exception
    {
        String base = createRealProject();
        projectHandle(base).close(new NullProgressMonitor());
        Map<String, String> params = params(tempFile(".epf").toString(), uniqueName()); //$NON-NLS-1$
        params.put("baseProjectName", base); //$NON-NLS-1$
        assertError(tool().execute(params), "Base project '" + base + "' is closed. Open it in EDT first."); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testBaseProjectIsRefusedForACf() throws IOException
    {
        Map<String, String> params = params(tempFile(".cf").toString(), uniqueName()); //$NON-NLS-1$
        params.put("baseProjectName", "Base"); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(tool().execute(params), "does not apply to a .cf file"); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testBaseProjectIsRequiredForACfe() throws IOException
    {
        String result = tool().execute(params(tempFile(".CFE").toString(), uniqueName())); //$NON-NLS-1$
        assertError(result, "baseProjectName is required for a .cfe file"); //$NON-NLS-1$
        assertEquals(0, providerCalls);
    }

    @Test
    public void testUnknownBaseProjectIsRefusedWithTheDiscoveryHint() throws IOException
    {
        Map<String, String> params = params(tempFile(".epf").toString(), uniqueName()); //$NON-NLS-1$
        params.put("baseProjectName", "NoSuchBase" + UUID.randomUUID().toString().substring(0, 8)); //$NON-NLS-1$ //$NON-NLS-2$
        String result = tool().execute(params);
        assertError(result, "Project not found"); //$NON-NLS-1$
        assertTrue(result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testWaitSecondsOutOfRangeIsRefused() throws IOException
    {
        Map<String, String> params = params(tempFile(".cf").toString(), uniqueName()); //$NON-NLS-1$
        params.put("waitSeconds", "46"); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(tool().execute(params), "waitSeconds must be an integer from 0 to 45"); //$NON-NLS-1$
    }

    @Test
    public void testNoPlatformIsRefusedBeforeAnyJob() throws IOException
    {
        providerFailure = new ConversionException("No installed 1C:Enterprise platform fits"); //$NON-NLS-1$
        String name = uniqueName();
        String result = tool().execute(params(tempFile(".cf").toString(), name)); //$NON-NLS-1$
        assertError(result, "No installed 1C:Enterprise platform"); //$NON-NLS-1$
        assertTrue(client.calls.isEmpty());
        assertTrue(importer.calls.isEmpty());
        assertFalse(projectHandle(name).exists());
    }

    @Test
    public void testMissingImportApiIsRefused() throws IOException
    {
        importer.available = false;
        assertError(tool().execute(params(tempFile(".cf").toString(), uniqueName())), //$NON-NLS-1$
            "IImportConfigurationFilesApi is not available"); //$NON-NLS-1$
    }

    @Test
    public void testAnImportOfTheSameNameInFlightIsRefused() throws IOException
    {
        String name = uniqueName();
        assertEquals(null, ImportConfigurationFromXmlTool.tryClaimImport(name));
        try
        {
            String result = tool().execute(params(tempFile(".cf").toString(), name)); //$NON-NLS-1$
            assertError(result, "already in progress"); //$NON-NLS-1$
            assertEquals(0, providerCalls);
        }
        finally
        {
            ImportConfigurationFromXmlTool.releaseImport(name);
        }
    }

    @Test
    public void testAScratchPathEdtWouldSplitIsRefusedForAnInfobaseConversion() throws IOException
    {
        // Two spaces in a row do not survive EDT's infobase command on any OS.
        Path root = spacedScratchRoot();
        String result = tool(root).execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "The temporary infobase of a .cf conversion is created under "); //$NON-NLS-1$
        String error = errorText(result);
        assertTrue(error, error.startsWith("The temporary infobase of a .cf conversion is created under " //$NON-NLS-1$
            + root + ", and that path holds ")); //$NON-NLS-1$
        assertTrue(error, error.endsWith(". Start EDT with -Djava.io.tmpdir=<a directory whose path has " //$NON-NLS-1$
            + "no spaces> (after -vmargs in 1cedt.ini) and retry. No project was created.")); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertEquals("refused before the platform is resolved", 0, providerCalls); //$NON-NLS-1$
        assertTrue(client.calls.isEmpty());
        assertTrue(importer.calls.isEmpty());
    }

    @Test
    public void testAnExternalObjectNeedsNoInfobaseSoTheSameScratchPathServes() throws IOException
    {
        Path root = spacedScratchRoot();
        String result = tool(root).execute(params(tempFile(".epf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertTrue(result, result.contains("| State | ready |")); //$NON-NLS-1$
        assertEquals("the scratch directory is made under the given root", root, //$NON-NLS-1$
            client.workDir().getParent());
    }

    // ==================== The job ====================

    @Test
    public void testConfigurationHappyPathConvertsImportsStartsAndCleansUp() throws IOException
    {
        String name = uniqueName();
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertFalse("a successful import is not an error: " + result, result.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("| State | ready |")); //$NON-NLS-1$
        assertTrue(result, result.contains("| Project | " + name + " |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("1C:Enterprise 8.3.99.1")); //$NON-NLS-1$
        assertTrue("a source in the system temp directory is flagged: " + result, //$NON-NLS-1$
            result.contains("| Outside workspace | yes")); //$NON-NLS-1$
        assertEquals(List.of("create:template", "dump:configuration"), client.calls); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a configuration gets no base and a derived version", //$NON-NLS-1$
            List.of(name + "|null|null"), importer.calls); //$NON-NLS-1$
        assertTrue("the import must read the dump", importer.sawConfigurationXml); //$NON-NLS-1$
        assertEquals(1, lifecycle.startRequests);
        assertFalse("the scratch directory must be deleted", Files.exists(client.workDir())); //$NON-NLS-1$
        assertEquals("the claim must be released", null, ImportConfigurationFromXmlTool.importClaimedAt(name)); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectIsDumpedUnderTheFileStem() throws IOException
    {
        Path file = tempFile(".epf"); //$NON-NLS-1$
        String result = tool().execute(params(file.toString(), uniqueName(), FULL_WAIT));
        assertTrue(result, result.contains("| State | ready |")); //$NON-NLS-1$
        String stem = file.getFileName().toString().replace(".epf", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("dumpExternal:" + stem + ".xml"), client.calls); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("external data processor (.epf)")); //$NON-NLS-1$
    }

    @Test
    public void testACfHoldingAnExtensionIsRefusedBeforeAnythingIsCreated() throws IOException
    {
        client.configurationXml = EXTENSION_CONFIGURATION_XML;
        String name = uniqueName();
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "holds a configuration EXTENSION"); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertTrue("nothing may be imported", importer.calls.isEmpty()); //$NON-NLS-1$
        assertFalse(Files.exists(client.workDir()));
    }

    @Test
    public void testAConversionFailureCreatesNothing() throws IOException
    {
        client.createFailure = new IOException("designer said no"); //$NON-NLS-1$
        String result = tool().execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "designer said no"); //$NON-NLS-1$
        assertTrue(result, result.contains("No project was created")); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertTrue(importer.calls.isEmpty());
    }

    @Test
    public void testAConversionPastItsBudgetIsStoppedAndCreatesNothing() throws IOException
    {
        client.blockCreate = true;
        ImportProjectFromFileTool tool = new ImportProjectFromFileTool(jobs, this::provide, importer,
            lifecycle, 5_000, 300, null);
        String result = tool.execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "ran out of time"); //$NON-NLS-1$
        assertTrue("the blocked step must have been interrupted, which kills its process", //$NON-NLS-1$
            client.interrupted);
        assertTrue(importer.calls.isEmpty());
    }

    @Test
    public void testACancelledJobStopsTheStepAndCreatesNothing() throws Exception
    {
        client.blockCreate = true;
        Map<String, String> params = params(tempFile(".cf").toString(), uniqueName(), "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = tool().execute(params);
        assertTrue(result, result.startsWith("**Pending:**")); //$NON-NLS-1$
        Matcher id = Pattern.compile("jobId=\\\\?\"([0-9a-f-]+)").matcher(result); //$NON-NLS-1$
        assertTrue(result, id.find());
        // Cancelled while still queued, the job would never reach the step this test is about.
        long runningBy = System.currentTimeMillis() + SANE_WAIT_MS;
        while (!client.calls.contains("create:template") && System.currentTimeMillis() < runningBy) //$NON-NLS-1$
        {
            Thread.sleep(20L);
        }
        assertTrue("the step must be running before the cancel", client.calls.contains("create:template")); //$NON-NLS-1$ //$NON-NLS-2$
        jobs.cancel(id.group(1));
        long deadline = System.currentTimeMillis() + SANE_WAIT_MS;
        while (!client.interrupted && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(50L);
        }
        assertTrue("cancelling before the commit must stop the running step", client.interrupted); //$NON-NLS-1$
        JobSnapshot job = jobs.await(id.group(1), SANE_WAIT_MS);
        assertEquals(BackgroundJobs.Status.CANCELLED, job.getStatus());
        assertTrue(importer.calls.isEmpty());
    }

    @Test
    public void testAProjectThatAppearsDuringTheConversionIsNotOverwritten() throws IOException
    {
        String name = uniqueName();
        client.onDump = () -> createProjectQuietly(name);
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "appeared while the file was being converted"); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertTrue(importer.calls.isEmpty());
    }

    @Test
    public void testAWorkspaceFolderThatAppearsDuringTheConversionIsNotImportedInto() throws IOException
    {
        String name = uniqueName();
        client.onDump = () -> createWorkspaceFolderQuietly(name);
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "A project or workspace folder named '" + name //$NON-NLS-1$
            + "' appeared while the file was being converted, so nothing was imported."); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertTrue("the re-check must stop the job before the import", importer.calls.isEmpty()); //$NON-NLS-1$
        assertFalse(projectHandle(name).exists());
    }

    @Test
    public void testAnExternalObjectFileNamedConfigurationIsRefusedBeforeAnythingIsCreated()
        throws IOException
    {
        // EDT's CLI import takes a dump holding Configuration.xml for a configuration.
        Path dir = Files.createTempDirectory("import-named"); //$NON-NLS-1$
        foldersToDelete.add(dir);
        Path file = Files.createFile(dir.resolve("Configuration.epf")); //$NON-NLS-1$
        String name = uniqueName();
        String result = tool().execute(params(file.toString(), name, FULL_WAIT));
        assertError(result, "The file's name makes the root file of its dump Configuration.xml"); //$NON-NLS-1$
        assertTrue(result, result.contains("No project was created.")); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertEquals(List.of("dumpExternal:Configuration.xml"), client.calls); //$NON-NLS-1$
        assertTrue("nothing may be imported", importer.calls.isEmpty()); //$NON-NLS-1$
        assertFalse(projectHandle(name).exists());
    }

    @Test
    public void testAFailedImportLeavesTheProjectItFindsWithoutClaimingIt() throws IOException
    {
        // The same answer as when EDT refused because another operation took the name meanwhile.
        String name = uniqueName();
        importer.createProjectThenFail = true;
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "simulated import failure"); //$NON-NLS-1$
        assertTrue(errorText(result), errorText(result).endsWith("A project of that name exists now and is " //$NON-NLS-1$
            + "left as it is. This import may have created it before failing, or another operation may " //$NON-NLS-1$
            + "have created it meanwhile: check what it holds before deleting it with delete_project " //$NON-NLS-1$
            + "(deleteContent=true) or importing again. If this import created it, EDT's import start " //$NON-NLS-1$
            + "latch on it may still be set, and while it is - until project '" + name + "' is deleted, " //$NON-NLS-1$ //$NON-NLS-2$
            + "closed or started - EDT starts no project on its own (an opened or added project stays " //$NON-NLS-1$
            + "unstarted). delete_project, closing the project in EDT or restarting EDT clears it; it " //$NON-NLS-1$
            + "is not released here, because nothing proves the project is this import's.")); //$NON-NLS-1$
        assertTrue("a project of that name need not be this call's: " + result, //$NON-NLS-1$
            result.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
        assertFalse(result, result.contains("mutationCommitted")); //$NON-NLS-1$
        assertTrue("nothing proves the project is the import's own, so it is not deleted", //$NON-NLS-1$
            projectHandle(name).exists());
        assertEquals("the latch is keyed by the project alone, so releasing it could start another " //$NON-NLS-1$
            + "importer's project early", 0, lifecycle.permits); //$NON-NLS-1$
        assertEquals("nothing to start", 0, lifecycle.startRequests); //$NON-NLS-1$
    }

    @Test
    public void testAFailedImportThatLeftOnlyAFolderSaysSoWithoutClaimingIt() throws IOException
    {
        String name = uniqueName();
        foldersToDelete.add(WorkspacePaths.defaultProjectFolder(name));
        importer.createFolderThenFail = true;
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "simulated import failure"); //$NON-NLS-1$
        assertTrue(errorText(result), errorText(result).endsWith("No project exists, but the workspace " //$NON-NLS-1$
            + "folder " + WorkspacePaths.defaultProjectFolder(name) + " does now. This import may have " //$NON-NLS-1$ //$NON-NLS-2$
            + "created it before failing, or another operation may have created it meanwhile: check " //$NON-NLS-1$
            + "what it holds before removing it or importing again.")); //$NON-NLS-1$
        assertTrue(result, result.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
        assertFalse(result, result.contains("mutationCommitted")); //$NON-NLS-1$
        assertEquals("no project, so no latch to release", 0, lifecycle.permits); //$NON-NLS-1$
    }

    @Test
    public void testAnImportThatLeavesNoProjectIsNotReportedAsCommitted() throws IOException
    {
        lifecycle.projectMissing = true;
        String result = tool().execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "returned without an error"); //$NON-NLS-1$
        assertFalse(result, result.contains("mutationCommitted")); //$NON-NLS-1$
        assertTrue("the API claimed success, so what it left is unknown: " + result, //$NON-NLS-1$
            result.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
        assertEquals("nothing to start", 0, lifecycle.startRequests); //$NON-NLS-1$
    }

    @Test
    public void testAFailedImportThatLeftNothingVisibleStaysOfUnknownOutcome() throws IOException
    {
        importer.failure = new IllegalStateException("XML version is not supported"); //$NON-NLS-1$
        String result = tool().execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "XML version is not supported"); //$NON-NLS-1$
        assertTrue(result, result.contains("whether it left anything is unknown")); //$NON-NLS-1$
        assertFalse(result, result.contains("No project was created")); //$NON-NLS-1$
        assertTrue(result, result.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
        assertEquals(0, lifecycle.permits);
    }

    @Test
    public void testAFailedStartKeepsTheProjectAndSaysTheMutationHappened() throws IOException
    {
        lifecycle.startFails = true;
        String result = tool().execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "do NOT import it again"); //$NON-NLS-1$
        assertTrue("a start failure happens after the project exists: " + result, //$NON-NLS-1$
            result.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
        assertEquals("the latch is released when no start of ours is coming", 1, lifecycle.permits); //$NON-NLS-1$
    }

    @Test
    public void testAPolledFailureCarriesTheMarkerTheStartingCallWouldHave() throws Exception
    {
        client.blockCreate = true;
        lifecycle.startFails = true;
        String result = tool().execute(params(tempFile(".cf").toString(), uniqueName(), "0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.startsWith("**Pending:**")); //$NON-NLS-1$
        Matcher id = Pattern.compile("jobId=\\\\?\"([0-9a-f-]+)").matcher(result); //$NON-NLS-1$
        assertTrue(result, id.find());
        client.release();
        JobSnapshot job = jobs.await(id.group(1), SANE_WAIT_MS);
        assertEquals(BackgroundJobs.Status.FAILED, job.getStatus());
        assertEquals("mutationCommitted", job.getErrorMarker()); //$NON-NLS-1$
        String polled = BackgroundJobRenderer.render(job);
        assertTrue("get_job_status must show the marker: " + polled, //$NON-NLS-1$
            polled.contains("| mutationCommitted | true |")); //$NON-NLS-1$
        String answered = ImportProjectFromFileTool.renderStart(job);
        assertTrue(answered, answered.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
    }

    @Test
    public void testAStartStillRunningIsReportedAsStarting() throws IOException
    {
        lifecycle.neverStarts = true;
        ImportProjectFromFileTool tool = new ImportProjectFromFileTool(jobs, this::provide, importer,
            lifecycle, 500, ImportProjectFromFileTool.CONVERSION_BUDGET_MS, null);
        String result = tool.execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertTrue(result, result.contains("| State | starting |")); //$NON-NLS-1$
        assertTrue(result, result.contains("do not import it again")); //$NON-NLS-1$
    }

    // ==================== Helpers ====================

    private ImportProjectFromFileTool tool()
    {
        return tool(null);
    }

    private ImportProjectFromFileTool tool(Path scratchRoot)
    {
        return new ImportProjectFromFileTool(jobs, this::provide, importer, lifecycle, 5_000,
            ImportProjectFromFileTool.CONVERSION_BUDGET_MS, scratchRoot);
    }

    private IThickClient provide(String platformVersion, IProject baseProject) throws ConversionException
    {
        providerCalls++;
        if (providerFailure != null)
        {
            throw providerFailure;
        }
        return client;
    }

    /** A real directory whose path holds two spaces in a row. */
    private Path spacedScratchRoot() throws IOException
    {
        Path parent = Files.createTempDirectory("import-root"); //$NON-NLS-1$
        foldersToDelete.add(parent);
        return Files.createDirectories(parent.resolve("scratch  root")); //$NON-NLS-1$
    }

    private Path tempFile(String extension) throws IOException
    {
        Path file = Files.createTempFile("import-src", extension); //$NON-NLS-1$
        tempFiles.add(file);
        return file;
    }

    private static Map<String, String> params(String filePath, String projectName)
    {
        Map<String, String> params = new HashMap<>();
        params.put("filePath", filePath); //$NON-NLS-1$
        params.put("projectName", projectName); //$NON-NLS-1$
        return params;
    }

    private static Map<String, String> params(String filePath, String projectName, String waitSeconds)
    {
        Map<String, String> params = params(filePath, projectName);
        params.put("waitSeconds", waitSeconds); //$NON-NLS-1$
        return params;
    }

    private String uniqueName()
    {
        String name = "ZzImportTest" + UUID.randomUUID().toString().replace("-", "").substring(0, 10); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        projectsToDelete.add(name);
        return name;
    }

    private String createRealProject() throws CoreException
    {
        String name = uniqueName();
        IProject project = projectHandle(name);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return name;
    }

    private static void createProjectQuietly(String name)
    {
        try
        {
            IProject project = projectHandle(name);
            project.create(new NullProgressMonitor());
            project.open(new NullProgressMonitor());
        }
        catch (CoreException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static IProject projectHandle(String name)
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(name);
    }

    /** A folder in the workspace directory with no project behind it. */
    private Path createWorkspaceFolder(String name) throws IOException
    {
        Path folder = WorkspacePaths.defaultProjectFolder(name);
        foldersToDelete.add(folder);
        return Files.createDirectories(folder);
    }

    private void createWorkspaceFolderQuietly(String name)
    {
        try
        {
            createWorkspaceFolder(name);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** The decoded error message, for a comparison a path's JSON escaping would break. */
    private static String errorText(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    private static void assertError(String result, String fragment)
    {
        assertTrue("expected an error JSON, got: " + result, result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("expected an error JSON, got: " + result, result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("expected '" + fragment + "' in: " + result, result.contains(fragment)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertNoMutationMarker(String result)
    {
        assertFalse(result, result.contains("mutationCommitted")); //$NON-NLS-1$
        assertFalse(result, result.contains("mutationOutcomeUnknown")); //$NON-NLS-1$
    }

    /** Writes the dump a real thick client would, and records the calls. */
    private static final class FakeThickClient implements IThickClient
    {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile String configurationXml = PLAIN_CONFIGURATION_XML;
        volatile Exception createFailure;
        volatile boolean blockCreate;
        volatile boolean interrupted;
        volatile Runnable onDump;
        volatile Path xmlDir;
        private final Object gate = new Object();
        private boolean released;

        @Override
        public String platformVersion()
        {
            return "8.3.99.1"; //$NON-NLS-1$
        }

        @Override
        public void createInfobase(Path infobaseDir, Path template) throws Exception
        {
            calls.add("create:" + (template == null ? "empty" : "template")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (blockCreate)
            {
                try
                {
                    synchronized (gate)
                    {
                        long deadline = System.currentTimeMillis() + SANE_WAIT_MS;
                        while (!released && System.currentTimeMillis() < deadline)
                        {
                            gate.wait(100L);
                        }
                    }
                }
                catch (InterruptedException e)
                {
                    interrupted = true;
                    throw e;
                }
            }
            if (createFailure != null)
            {
                throw createFailure;
            }
        }

        @Override
        public void loadExtension(Path infobaseDir, Path cfe, String extensionName, Path log)
        {
            calls.add("load:" + extensionName); //$NON-NLS-1$
        }

        @Override
        public void dumpConfiguration(Path infobaseDir, String extensionName, Path target)
            throws IOException
        {
            calls.add("dump:" + (extensionName == null ? "configuration" : extensionName)); //$NON-NLS-1$ //$NON-NLS-2$
            xmlDir = target;
            Files.write(target.resolve("Configuration.xml"), //$NON-NLS-1$
                configurationXml.getBytes(StandardCharsets.UTF_8));
            Runnable hook = onDump;
            if (hook != null)
            {
                hook.run();
            }
        }

        @Override
        public void dumpExternalObject(Path binary, Path rootXml) throws IOException
        {
            calls.add("dumpExternal:" + rootXml.getFileName()); //$NON-NLS-1$
            xmlDir = rootXml.getParent();
            String object = binary.getFileName().toString().endsWith(".erf") ? "ExternalReport" //$NON-NLS-1$ //$NON-NLS-2$
                : "ExternalDataProcessor"; //$NON-NLS-1$
            Files.write(rootXml, ("<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><" + object //$NON-NLS-1$
                + " uuid=\"1\"/></MetaDataObject>").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        }

        Path workDir()
        {
            return xmlDir.getParent();
        }

        void release()
        {
            synchronized (gate)
            {
                released = true;
                gate.notifyAll();
            }
        }
    }

    /** Records the import and, on request, fails it the ways the CLI API does. */
    private static final class FakeImporter implements IProjectImporter
    {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile boolean sawConfigurationXml;
        volatile boolean createProjectThenFail;
        volatile boolean createFolderThenFail;
        volatile Exception failure;

        @Override
        public boolean isAvailable()
        {
            return available;
        }

        @Override
        public void importProject(Path xmlDir, String projectName, String runtimeVersion,
            String baseProjectName) throws Exception
        {
            calls.add(projectName + "|" + runtimeVersion + "|" + baseProjectName); //$NON-NLS-1$ //$NON-NLS-2$
            sawConfigurationXml = Files.exists(xmlDir.resolve("Configuration.xml")); //$NON-NLS-1$
            if (createProjectThenFail)
            {
                createProjectQuietly(projectName);
                throw new IllegalStateException("simulated import failure"); //$NON-NLS-1$
            }
            if (createFolderThenFail)
            {
                Files.createDirectories(WorkspacePaths.defaultProjectFolder(projectName));
                throw new IllegalStateException("simulated import failure"); //$NON-NLS-1$
            }
            if (failure != null)
            {
                throw failure;
            }
        }
    }

    /** A lifecycle that starts the project on the first request, or never. */
    private static final class FakeLifecycle implements IImportLifecycle
    {
        volatile boolean started;
        volatile boolean startFails;
        volatile boolean neverStarts;
        volatile boolean projectMissing;
        volatile int startRequests;
        volatile int permits;

        @Override
        public boolean projectExists(IProject project)
        {
            return !projectMissing;
        }

        @Override
        public void refreshLocal(IProject project, IProgressMonitor monitor)
        {
            // nothing to refresh
        }

        @Override
        public void permitImport(IProject project)
        {
            permits++;
        }

        @Override
        public boolean isStarted(IProject project)
        {
            return started;
        }

        @Override
        public void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
            IProgressMonitor monitor)
        {
            startRequests++;
            if (startFails)
            {
                throw new IllegalStateException("simulated start failure"); //$NON-NLS-1$
            }
            started = !neverStarts;
        }

        @Override
        public boolean hasDtProject(IProject project)
        {
            return started;
        }
    }
}
