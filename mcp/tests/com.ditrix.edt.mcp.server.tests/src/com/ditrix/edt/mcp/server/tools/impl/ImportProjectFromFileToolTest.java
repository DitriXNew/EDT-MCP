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
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportProjectFromFileTool.IProjectImporter;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.ConversionException;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.IThickClient;

/**
 * Tests for {@link ImportProjectFromFileTool}.
 * <p>
 * The platform is injected: a fake thick client writes the dump a real one would, a fake importer
 * records the call, and a fake lifecycle starts the project at once. So the whole job - refusals,
 * conversion, the pre-commit checks, the rollback of a failed import and the error markers - runs
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
            lifecycle, 5_000, 300);
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
    public void testAFailedImportIsRolledBack() throws IOException
    {
        String name = uniqueName();
        projectsToDelete.add(name);
        importer.createProjectThenFail = true;
        String result = tool().execute(params(tempFile(".cf").toString(), name, FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "simulated import failure"); //$NON-NLS-1$
        assertTrue(result, result.contains("deleted again")); //$NON-NLS-1$
        assertNoMutationMarker(result);
        assertFalse("the half-created project must be gone", projectHandle(name).exists()); //$NON-NLS-1$
        assertFalse(Files.exists(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
            .resolve(name)));
    }

    @Test
    public void testAFailedImportThatLeftNothingNeedsNoRollback() throws IOException
    {
        importer.failure = new IllegalStateException("XML version is not supported"); //$NON-NLS-1$
        String result = tool().execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertError(result, "XML version is not supported"); //$NON-NLS-1$
        assertTrue(result, result.contains("No project was created")); //$NON-NLS-1$
        assertNoMutationMarker(result);
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
    public void testAStartStillRunningIsReportedAsStarting() throws IOException
    {
        lifecycle.neverStarts = true;
        ImportProjectFromFileTool tool = new ImportProjectFromFileTool(jobs, this::provide, importer,
            lifecycle, 500, ImportProjectFromFileTool.CONVERSION_BUDGET_MS);
        String result = tool.execute(params(tempFile(".cf").toString(), uniqueName(), FULL_WAIT)); //$NON-NLS-1$
        assertTrue(result, result.contains("| State | starting |")); //$NON-NLS-1$
        assertTrue(result, result.contains("do not import it again")); //$NON-NLS-1$
    }

    // ==================== Helpers ====================

    private ImportProjectFromFileTool tool()
    {
        return new ImportProjectFromFileTool(jobs, this::provide, importer, lifecycle, 5_000,
            ImportProjectFromFileTool.CONVERSION_BUDGET_MS);
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
            Files.write(rootXml, "<MetaDataObject/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
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
        volatile int startRequests;
        volatile int permits;

        @Override
        public boolean projectExists(IProject project)
        {
            return true;
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
