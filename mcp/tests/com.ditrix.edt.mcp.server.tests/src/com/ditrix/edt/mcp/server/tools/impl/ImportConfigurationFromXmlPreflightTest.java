/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportRunner;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.ditrix.edt.mcp.server.utils.ProjectImportValidation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Issue #669 round-three regressions, executable against the unchanged round-two sources. */
public class ImportConfigurationFromXmlPreflightTest
{
    private final List<IProject> projects = new ArrayList<>();
    private final RecordingRunner runner = new RecordingRunner();
    private final Map<String, String> params = new HashMap<>();
    private Path dump;
    private String projectName;
    private Field pluginField;
    private Activator savedActivator;
    private Activator activator;
    private IV8ProjectManager manager;

    @Before
    public void setUp() throws Exception
    {
        dump = Files.createTempDirectory("import-xml-669-r3-");
        writeDump(true);
        projectName = uniqueName();
        params.put("importPath", dump.toString());
        params.put("projectName", projectName);
        manager = mock(IV8ProjectManager.class);
        activator = mock(Activator.class);
        when(activator.getV8ProjectManager()).thenReturn(manager);
        pluginField = Activator.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        savedActivator = (Activator)pluginField.get(null);
        pluginField.set(null, activator);
    }

    @After
    public void tearDown() throws Exception
    {
        try
        {
            for (IProject project : projects)
            {
                if (project.exists())
                {
                    project.delete(true, true, new NullProgressMonitor());
                }
            }
            assertTrue("remove the scratch dump", BinaryToXmlConverter.deleteQuietly(dump));
            assertNull("the request must leave no import claim",
                ImportConfigurationFromXmlTool.importClaimedAt(projectName));
        }
        finally
        {
            pluginField.set(null, savedActivator);
        }
    }

    @Test
    public void testMissingBaseV8ProjectIsRefusedBeforeImport() throws Exception
    {
        IProject base = createBase();
        assertBaseNotReady(execute(), base);
    }

    @Test
    public void testMissingBaseRuntimeVersionIsRefusedBeforeImport() throws Exception
    {
        IProject base = createBase();
        when(manager.getProject(base)).thenReturn(mock(IV8Project.class));
        assertBaseNotReady(execute(), base);
    }

    @Test
    public void testUnavailableBaseProjectManagerIsRefusedBeforeImport() throws Exception
    {
        IProject base = createBase();
        when(activator.getV8ProjectManager()).thenReturn(null);
        assertBaseNotReady(execute(), base);
    }

    @Test
    public void testFailingBaseRuntimeLookupIsRefusedBeforeImport() throws Exception
    {
        IProject base = createBase();
        when(manager.getProject(base)).thenThrow(new IllegalStateException("base is starting"));
        assertBaseNotReady(execute(), base);
    }

    @Test
    public void testExternalDumpAlsoRefusesAnUnreadableBaseRuntime() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        IProject base = createBase();
        assertBaseNotReady(execute(), base);
    }

    @Test
    public void testInvalidRequestNeverClaimsNameDuringRuntimeValidation() throws Exception
    {
        IProject base = createBase();
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean competitorClaimed = new AtomicBoolean();
        AtomicReference<Long> claimDuringValidation = new AtomicReference<>();
        ExecutorService competitor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        when(manager.getProject(base)).thenAnswer(invocation ->
        {
            claimDuringValidation.set(ImportConfigurationFromXmlTool.importClaimedAt(projectName));
            task.set(competitor.submit(() ->
            {
                boolean claimed = ImportConfigurationFromXmlTool.tryClaimImport(projectName) == null;
                competitorClaimed.set(claimed);
                attempted.countDown();
                try
                {
                    if (claimed)
                    {
                        assertTrue("release the competing claim", release.await(30, TimeUnit.SECONDS));
                    }
                }
                finally
                {
                    if (claimed)
                    {
                        ImportConfigurationFromXmlTool.releaseImport(projectName);
                    }
                }
                return null;
            }));
            assertTrue("the competing claim must run during validation", attempted.await(30, TimeUnit.SECONDS));
            return null;
        });
        try
        {
            String answer = execute();
            assertNull("validation must not temporarily own the project name", claimDuringValidation.get());
            assertTrue("another import must be able to claim the name during validation", competitorClaimed.get());
            assertNotNull("the invalid request must not release the competing import's claim",
                ImportConfigurationFromXmlTool.importClaimedAt(projectName));
            assertBaseNotReady(answer, base);
        }
        finally
        {
            release.countDown();
            try
            {
                if (task.get() != null)
                {
                    task.get().get(30, TimeUnit.SECONDS);
                }
            }
            finally
            {
                competitor.shutdownNow();
                assertTrue("the competitor must finish", competitor.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testNatureMismatchIsRefusedBeforeTakingClaim() throws Exception
    {
        writeDump(false);
        params.put("projectNature", ProjectImportValidation.NATURE_EXTENSION);
        assertRefusedWithCompetingClaim("does not match", "main configuration dump", "Omit projectNature");
    }

    @Test
    public void testUnreadableDumpIsRefusedBeforeTakingClaim() throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"), "not XML", StandardCharsets.UTF_8);
        params.put("baseProjectName", "MissingBase");
        assertRefusedWithCompetingClaim("dump kind cannot be verified", "export_configuration_to_xml");
    }

    @Test
    public void testMissingBaseIsRefusedBeforeTakingClaim()
    {
        params.put("baseProjectName", uniqueName());
        assertRefusedWithCompetingClaim("Project not found", "list_projects");
    }

    @Test
    public void testBaseRuntimeOverrideIsRefusedBeforeTakingClaim() throws Exception
    {
        createBase();
        params.put("platformVersion", "8.3.27");
        assertRefusedWithCompetingClaim("inherited from the base project", "Omit platformVersion");
    }

    @Test
    public void testRequiredParametersStillPrecedePathAndDumpValidation()
    {
        params.remove("importPath");
        params.remove("projectName");
        params.put("projectNature", "unknown.nature");
        assertRefused(execute(), "importPath is required");
    }

    @Test
    public void testMissingPathStillPrecedesDirectoryAndDumpValidation()
    {
        params.put("importPath", dump.resolve("missing").toString());
        params.put("projectNature", "unknown.nature");
        assertRefused(execute(), "importPath does not exist");
    }

    @Test
    public void testDirectoryGuardStillPrecedesProjectAndDumpValidation() throws Exception
    {
        createProject(projectName);
        params.put("importPath", dump.resolve("Configuration.xml").toString());
        params.put("projectNature", "unknown.nature");
        assertRefused(execute(), "importPath is not a directory");
    }

    @Test
    public void testExistingProjectStillPrecedesDumpAndBaseValidation() throws Exception
    {
        createProject(projectName);
        params.put("projectNature", "unknown.nature");
        params.put("baseProjectName", "MissingBase");
        assertRefused(execute(), "Project already exists in workspace", projectName, "new project name");
    }

    @Test
    public void testProjectCreatedDuringValidationIsRefusedAfterClaim() throws Exception
    {
        IProject base = createBase();
        IV8Project v8Project = mock(IV8Project.class);
        when(v8Project.getVersion()).thenReturn(new Version("8.3.27"));
        when(manager.getProject(base)).thenAnswer(invocation ->
        {
            createProject(projectName);
            return v8Project;
        });
        assertRefused(execute(), "Project already exists in workspace", projectName, "new project name");
    }

    private void assertRefusedWithCompetingClaim(String... fragments)
    {
        assertNull("the test owns a separate import claim", ImportConfigurationFromXmlTool.tryClaimImport(projectName));
        Long claim = ImportConfigurationFromXmlTool.importClaimedAt(projectName);
        try
        {
            assertRefused(execute(), fragments);
            assertEquals("validation must preserve the competing import's claim", claim,
                ImportConfigurationFromXmlTool.importClaimedAt(projectName));
        }
        finally
        {
            ImportConfigurationFromXmlTool.releaseImport(projectName);
        }
    }

    private void assertBaseNotReady(String answer, IProject base)
    {
        assertRefused(answer, base.getName(), "not ready", "runtime version cannot be read yet",
            "Retry when list_projects shows it ready");
    }

    private void assertRefused(String answer, String... fragments)
    {
        assertTrue("refusal must be error JSON: " + answer, answer.startsWith("{"));
        JsonObject error = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, error.get("success").getAsBoolean());
        for (String fragment : fragments)
        {
            assertTrue("missing '" + fragment + "' in " + answer, answer.contains(fragment));
        }
        assertFalse("validation did not mutate the workspace", error.has("mutationCommitted"));
        assertFalse("validation did not enter the import", error.has("mutationOutcomeUnknown"));
        assertEquals("refusal must precede API resolution", 0, runner.apiChecks);
        assertEquals("refusal must precede CLI import", 0, runner.calls);
    }

    private String execute()
    {
        return ImportConfigurationFromXmlTool.executeWith(params, runner, new ReadyLifecycle(), 30_000L);
    }

    private IProject createProject(String name) throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return project;
    }

    private IProject createBase() throws Exception
    {
        IProject base = createProject(uniqueName());
        IProjectDescription description = base.getDescription();
        description.setNatureIds(new String[] { ProjectImportValidation.NATURE_CONFIGURATION });
        base.setDescription(description, IResource.AVOID_NATURE_CONFIG, new NullProgressMonitor());
        params.put("baseProjectName", base.getName());
        return base;
    }

    private void writeDump(boolean extension) throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"),
            "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><Configuration><Properties>"
                + (extension ? "<ObjectBelonging>Adopted</ObjectBelonging>" : "")
                + "</Properties></Configuration></MetaDataObject>", StandardCharsets.UTF_8);
    }

    private static String uniqueName()
    {
        return "ZZImport669R3" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class RecordingRunner implements IImportRunner
    {
        int apiChecks;
        int calls;

        @Override
        public Object importApi() { apiChecks++; return this; }

        @Override
        public void runImport(Object api, Path path, String name, String version, String base) { calls++; }
    }

    private static final class ReadyLifecycle implements IImportLifecycle
    {
        @Override
        public boolean projectExists(IProject project) { return true; }
        @Override
        public void refreshLocal(IProject project, IProgressMonitor monitor) { }
        @Override
        public void permitImport(IProject project) { fail("ready import needs no recovery"); }
        @Override
        public boolean isStarted(IProject project) { return true; }
        @Override
        public void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
            IProgressMonitor monitor) { fail("ready project needs no restart"); }
        @Override
        public boolean hasDtProject(IProject project) { return true; }
    }
}
