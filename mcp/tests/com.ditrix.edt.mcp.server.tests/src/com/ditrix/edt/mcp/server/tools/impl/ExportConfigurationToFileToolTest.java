/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.PublishedFile;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.SyncReading;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.SyncState;
import com.e1c.g5.dt.applications.IApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless tests for {@link ExportConfigurationToFileTool}: metadata, the schema/execute contract,
 * every refusal raised before EDT is touched, and the rendering of job states and reports. The
 * real dump needs a live infobase and a 1C platform and is covered by the e2e suite.
 */
public class ExportConfigurationToFileToolTest
{
    private static final String[] EXECUTE_PARAMS = {"projectName", "outputFile", "applicationId", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "extensionName", "allowOutOfDate", "waitSeconds"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private Path tempDir;

    @Before
    public void setUp() throws Exception
    {
        tempDir = Files.createTempDirectory("export-cf-tool"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws Exception
    {
        try (Stream<Path> walk = Files.walk(tempDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // ==================== Metadata ====================

    @Test
    public void testNameAndResponseType()
    {
        ExportConfigurationToFileTool tool = new ExportConfigurationToFileTool();
        assertEquals("export_configuration_to_file", tool.getName()); //$NON-NLS-1$
        assertEquals(ExportConfigurationToFileTool.NAME, tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testConnectsToInfobase()
    {
        assertTrue("the dump logs in to the infobase, so the auth-dialog window must be armed", //$NON-NLS-1$
            new ExportConfigurationToFileTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionCarriesTheProtocolClauses()
    {
        String description = new ExportConfigurationToFileTool().getDescription();
        assertTrue(description.contains("get_tool_guide('export_configuration_to_file')")); //$NON-NLS-1$
        assertTrue("must point project-source exports at the sibling tool", //$NON-NLS-1$
            description.contains("export_configuration_to_xml")); //$NON-NLS-1$
        assertTrue("must name the staleness fix", description.contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the opt-in", description.contains("allowOutOfDate=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must state the no-replace rule", description.contains("never overwritten")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the polling protocol", description.contains("get_job_status")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideIsPresent()
    {
        String guide = new ExportConfigurationToFileTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.contains("## Parameter details")); //$NON-NLS-1$
        assertTrue(guide.contains("allowOutOfDate")); //$NON-NLS-1$
    }

    // ==================== Input schema ====================

    @Test
    public void testSchemaDeclaresExactlyTheExecuteParams()
    {
        JsonObject schema = JsonParser.parseString(new ExportConfigurationToFileTool().getInputSchema())
            .getAsJsonObject();
        TreeSet<String> declared = new TreeSet<>(schema.getAsJsonObject("properties").keySet()); //$NON-NLS-1$
        assertEquals(new TreeSet<>(List.of(EXECUTE_PARAMS)), declared);
        List<String> required = new ArrayList<>();
        schema.getAsJsonArray("required").forEach(e -> required.add(e.getAsString())); //$NON-NLS-1$
        required.sort(null);
        assertArrayEquals(new String[] {"outputFile", "projectName"}, required.toArray()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Refusals before EDT access ====================

    @Test
    public void testMissingRequiredArguments()
    {
        Map<String, String> noProject = new HashMap<>();
        noProject.put("outputFile", tempDir.resolve("a.cf").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new ExportConfigurationToFileTool().execute(noProject).contains("projectName")); //$NON-NLS-1$

        Map<String, String> noFile = new HashMap<>();
        noFile.put("projectName", "Anything"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new ExportConfigurationToFileTool().execute(noFile).contains("outputFile")); //$NON-NLS-1$
    }

    @Test
    public void testWaitSecondsOutOfRangeIsRefused()
    {
        for (String bad : new String[] {"46", "-1", "soon"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Map<String, String> params = params("Anything", tempDir.resolve("a.cf").toString()); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("waitSeconds", bad); //$NON-NLS-1$
            String result = new ExportConfigurationToFileTool().execute(params);
            assertError(result);
            assertTrue("must name the parameter for " + bad, result.contains("waitSeconds")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("must name the range for " + bad, result.contains("0 to 45")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testRelativeOutputFileIsRefused()
    {
        String result = new ExportConfigurationToFileTool().execute(params("Anything", "out/a.cf")); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(result);
        assertTrue(result.contains("absolute path")); //$NON-NLS-1$
    }

    @Test
    public void testWrongFileExtensionIsRefused()
    {
        String result = new ExportConfigurationToFileTool()
            .execute(params("Anything", tempDir.resolve("dump.zip").toString())); //$NON-NLS-1$ //$NON-NLS-2$
        assertError(result);
        assertTrue(result.contains(".cf")); //$NON-NLS-1$
        assertTrue(result.contains(".cfe")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFolderIsRefused()
    {
        Path missing = tempDir.resolve("no-such-folder").resolve("a.cf"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportConfigurationToFileTool().execute(params("Anything", missing.toString())); //$NON-NLS-1$
        assertError(result);
        assertTrue(result.contains("does not create folders")); //$NON-NLS-1$
        assertFalse(Files.exists(missing.getParent()));
    }

    @Test
    public void testExistingFileIsRefusedAndKept() throws Exception
    {
        Path existing = tempDir.resolve("existing.cf"); //$NON-NLS-1$
        Files.writeString(existing, "keep me", StandardCharsets.UTF_8); //$NON-NLS-1$
        String result = new ExportConfigurationToFileTool().execute(params("Anything", existing.toString())); //$NON-NLS-1$
        assertError(result);
        assertTrue(result.contains("already exists")); //$NON-NLS-1$
        assertEquals("keep me", Files.readString(existing, StandardCharsets.UTF_8)); //$NON-NLS-1$
    }

    @Test
    public void testUnknownProjectIsNamed()
    {
        String project = "NoSuchProject_" + System.nanoTime(); //$NON-NLS-1$
        String result = new ExportConfigurationToFileTool()
            .execute(params(project, tempDir.resolve("a.cf").toString())); //$NON-NLS-1$
        assertError(result);
        assertTrue(result.contains(project));
        assertTrue(result.contains("list_projects")); //$NON-NLS-1$
    }

    // ==================== Job rendering ====================

    @Test
    public void testRenderDoneReturnsTheReport() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(ExportConfigurationToFileTool.NAME, 10_000L, "start", //$NON-NLS-1$
                progress -> "the report"); //$NON-NLS-1$
            JobSnapshot done = jobs.await(started.getId(), 5_000L);
            assertEquals(BackgroundJobs.Status.DONE, done.getStatus());
            assertEquals("the report", ExportConfigurationToFileTool.renderSnapshot(done)); //$NON-NLS-1$
        }
    }

    @Test
    public void testRenderFailedIsAnErrorCarryingTheMessage() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(ExportConfigurationToFileTool.NAME, 10_000L, "start", //$NON-NLS-1$
                progress -> {
                    throw new IllegalStateException("The infobase is out of date"); //$NON-NLS-1$
                });
            JobSnapshot failed = jobs.await(started.getId(), 5_000L);
            assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
            String rendered = ExportConfigurationToFileTool.renderSnapshot(failed);
            assertError(rendered);
            assertTrue(rendered.contains("The infobase is out of date")); //$NON-NLS-1$
        }
    }

    @Test
    public void testRenderRunningNamesTheJobToPoll() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(ExportConfigurationToFileTool.NAME, 10_000L, "start", //$NON-NLS-1$
                progress -> {
                    release.await(5, TimeUnit.SECONDS);
                    return "late"; //$NON-NLS-1$
                });
            String rendered = ExportConfigurationToFileTool.renderSnapshot(jobs.get(started.getId()));
            assertTrue(rendered.startsWith("**Pending:**")); //$NON-NLS-1$
            assertTrue(rendered.contains(started.getId()));
            assertTrue(rendered.contains("get_job_status")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    // ==================== Refusal and report text ====================

    @Test
    public void testOutOfDateRefusalNamesBothWaysOut()
    {
        ExportConfigurationToFileTool.Request request = request(null);
        String refusal = ExportConfigurationToFileTool.outOfDateRefusal(request, "App.1", //$NON-NLS-1$
            new SyncReading(SyncState.OUT_OF_DATE, "EDT reports the infobase different")); //$NON-NLS-1$
        assertTrue(refusal.contains("Nothing was exported")); //$NON-NLS-1$
        assertTrue(refusal.contains("update_database (projectName='Config', applicationId='App.1')")); //$NON-NLS-1$
        assertTrue(refusal.contains("allowOutOfDate=true")); //$NON-NLS-1$
    }

    @Test
    public void testPortConflictRefusalNamesThePorts()
    {
        String refusal = ExportConfigurationToFileTool.portConflictRefusal("ServerApplication.X", //$NON-NLS-1$
            "1541, 1560"); //$NON-NLS-1$
        assertTrue(refusal.contains("1541, 1560")); //$NON-NLS-1$
        assertTrue(refusal.contains("Nothing was exported")); //$NON-NLS-1$
        assertFalse("this tool has no port-policy parameter to advise", //$NON-NLS-1$
            refusal.contains("standaloneServerPortConflict")); //$NON-NLS-1$
    }

    @Test
    public void testReportForAConfigurationInSync()
    {
        String report = report(request(null), new SyncReading(SyncState.IN_SYNC, "equal")); //$NON-NLS-1$
        assertTrue(report.startsWith("---\n")); //$NON-NLS-1$
        assertTrue(report.contains("status: success")); //$NON-NLS-1$
        assertTrue(report.contains("source: configuration")); //$NON-NLS-1$
        assertTrue(report.contains("sizeBytes: 4096")); //$NON-NLS-1$
        assertTrue(report.contains("infobaseSync: inSync")); //$NON-NLS-1$
        assertFalse("EDT may re-run the dump on another platform, so no version is claimed", //$NON-NLS-1$
            report.contains("platformVersion")); //$NON-NLS-1$
        assertFalse(report.contains("extensionName:")); //$NON-NLS-1$
        assertFalse("an in-sync dump carries no caveat", report.contains("> ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAbandonedDumpNoteNamesTheTemporaryFile()
    {
        Path output = Path.of("C:/export/Config.cf"); //$NON-NLS-1$
        Path partial = Path.of("C:/export/Config.partial-1a2b3c4d.cf"); //$NON-NLS-1$
        String note = ExportConfigurationToFileTool.abandonedDumpNote(output, partial);
        assertTrue(note.startsWith(output + " was not created.")); //$NON-NLS-1$
        assertTrue(note.contains("if " + partial + " appears later, delete it.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note.contains("Designer-agent session")); //$NON-NLS-1$
        assertFalse("EDT sends no stop to an agent session", note.contains("asked to stop")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideStatesWhatSyncAndCancelActuallyMean()
    {
        String guide = new ExportConfigurationToFileTool().getGuide();
        assertTrue(guide.contains("It is NOT the update state `get_applications` shows")); //$NON-NLS-1$
        assertFalse(guide.contains("(`get_applications`: up to date)")); //$NON-NLS-1$
        assertTrue(guide.contains("does NOT stop a dump running in its Designer-agent session")); //$NON-NLS-1$
        assertFalse(guide.contains("the temporary file is removed once it has ended")); //$NON-NLS-1$
        assertFalse(guide.contains("platformVersion")); //$NON-NLS-1$
    }

    // ==================== extensionName resolution ====================

    @Test
    public void testExtensionMatchesItsConfigurationNameIgnoringCase()
    {
        IProject config = project("Config"); //$NON-NLS-1$
        IProject tests = project("Config.tests"); //$NON-NLS-1$
        ExportConfigurationToFileTool.Target target = ExportConfigurationToFileTool.matchExtension(config,
            List.of(candidate(tests, "tests")), "TESTS"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTarget(target, config, "tests", tests); //$NON-NLS-1$
    }

    @Test
    public void testExtensionProjectNameResolvesToTheConfigurationName()
    {
        IProject config = project("Config"); //$NON-NLS-1$
        IProject tests = project("Config.tests"); //$NON-NLS-1$
        ExportConfigurationToFileTool.Target target = ExportConfigurationToFileTool.matchExtension(config,
            List.of(candidate(tests, "tests")), "config.TESTS"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTarget(target, config, "tests", tests); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationNameWinsOverAnotherProjectsName()
    {
        IProject config = project("Config"); //$NON-NLS-1$
        IProject namedLikeIt = project("Ext"); //$NON-NLS-1$
        IProject realExt = project("Config.ext"); //$NON-NLS-1$
        ExportConfigurationToFileTool.Target target = ExportConfigurationToFileTool.matchExtension(config,
            List.of(candidate(namedLikeIt, "Other"), candidate(realExt, "Ext")), "Ext"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTarget(target, config, "Ext", realExt); //$NON-NLS-1$
    }

    @Test
    public void testRussianExtensionNameMatchesIgnoringCase()
    {
        IProject config = project("Config"); //$NON-NLS-1$
        IProject ext = project("Config.ext"); //$NON-NLS-1$
        String name = "\u0422\u0435\u0441\u0442\u044B"; //$NON-NLS-1$ Тесты
        ExportConfigurationToFileTool.Target target = ExportConfigurationToFileTool.matchExtension(config,
            List.of(candidate(ext, name)), "\u0442\u0415\u0421\u0422\u042B"); //$NON-NLS-1$ тЕСТЫ
        assertTarget(target, config, name, ext);
    }

    @Test
    public void testUnknownExtensionGoesToTheDesignerAsGivenWithNoSyncProject()
    {
        IProject config = project("Config"); //$NON-NLS-1$
        IProject tests = project("Config.tests"); //$NON-NLS-1$
        ExportConfigurationToFileTool.Target unknown = ExportConfigurationToFileTool.matchExtension(config,
            List.of(candidate(tests, "tests")), "Installed"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTarget(unknown, config, "Installed", null); //$NON-NLS-1$
    }

    @Test
    public void testReportForAnExtensionOfUnknownState()
    {
        String report = report(request("Tests"), new SyncReading(SyncState.UNKNOWN, "no project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.contains("source: extension")); //$NON-NLS-1$
        assertTrue(report.contains("extensionName: Tests")); //$NON-NLS-1$
        assertTrue(report.contains("infobaseSync: unknown")); //$NON-NLS-1$
        assertTrue("an unknown state must be stated, not implied", //$NON-NLS-1$
            report.contains("could not tell whether the infobase matches the project")); //$NON-NLS-1$
    }

    @Test
    public void testReportForAnAllowedOutOfDateDump()
    {
        String report = report(request(null), new SyncReading(SyncState.OUT_OF_DATE, "different")); //$NON-NLS-1$
        assertTrue(report.contains("infobaseSync: outOfDate")); //$NON-NLS-1$
        assertTrue(report.contains("does NOT match the project")); //$NON-NLS-1$
    }

    // ==================== helpers ====================

    private static ExportConfigurationToFileTool.Request request(String extensionName)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Config"); //$NON-NLS-1$
        ExportConfigurationToFileTool.Target target =
            new ExportConfigurationToFileTool.Target(project, extensionName, project, null);
        return new ExportConfigurationToFileTool.Request(target, null,
            Path.of("C:/export/Config" + (extensionName == null ? ".cf" : ".cfe")), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String report(ExportConfigurationToFileTool.Request request, SyncReading sync)
    {
        IApplication application = mock(IApplication.class);
        when(application.getName()).thenReturn("Config app"); //$NON-NLS-1$
        InfobaseReference infobase = mock(InfobaseReference.class);
        when(infobase.getName()).thenReturn("ConfigBase"); //$NON-NLS-1$
        PublishedFile published = new PublishedFile(request.outputFile, 4096L, 1_700_000_000_000L);
        return ExportConfigurationToFileTool.renderReport(request, "App.1", application, infobase, //$NON-NLS-1$
            sync, published);
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static ExportConfigurationToFileTool.ExtensionCandidate candidate(IProject project,
        String configurationName)
    {
        return new ExportConfigurationToFileTool.ExtensionCandidate(project, configurationName);
    }

    private static void assertTarget(ExportConfigurationToFileTool.Target target, IProject configuration,
        String extensionName, IProject syncProject)
    {
        assertNull(target.error);
        assertSame(configuration, target.configurationProject);
        assertEquals(extensionName, target.extensionName);
        assertSame(syncProject, target.syncProject);
    }

    private static Map<String, String> params(String projectName, String outputFile)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", projectName); //$NON-NLS-1$
        params.put("outputFile", outputFile); //$NON-NLS-1$
        return params;
    }

    private static void assertError(String result)
    {
        assertTrue("expected an error payload, got: " + result, //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
    }
}
