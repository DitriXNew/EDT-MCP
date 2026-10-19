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
import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportRunner;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Issue #669 gate regressions; also compiles against the unchanged round-one production code. */
public class ImportConfigurationFromXmlValidationTest
{
    private static final String CONFIGURATION = "com._1c.g5.v8.dt.core.V8ConfigurationNature";
    private static final String EXTENSION = "com._1c.g5.v8.dt.core.V8ExtensionNature";
    private static final String EXTERNAL = "com._1c.g5.v8.dt.core.V8ExternalObjectsNature";

    private final List<IProject> bases = new ArrayList<>();
    private final RecordingRunner runner = new RecordingRunner();
    private Path dump;
    private Map<String, String> params;
    private Field pluginField;
    private Activator savedActivator;
    private IV8ProjectManager manager;
    private IRuntimeRegistry registry;

    @Before
    public void setUp() throws Exception
    {
        dump = Files.createTempDirectory("import-xml-669-r2-");
        writeConfiguration(false);
        params = new HashMap<>();
        params.put("importPath", dump.toString());
        params.put("projectName", uniqueName());
        manager = mock(IV8ProjectManager.class);
        // Resolve the new getter by name so the same tests compile on round one for red evidence.
        Activator activator = mock(Activator.class, invocation ->
            "getRuntimeRegistry".equals(invocation.getMethod().getName()) ? registry
                : RETURNS_DEFAULTS.answer(invocation));
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
            for (IProject base : bases)
            {
                if (base.exists())
                {
                    base.delete(true, true, new NullProgressMonitor());
                }
            }
            assertTrue("remove the scratch dump", BinaryToXmlConverter.deleteQuietly(dump));
        }
        finally
        {
            pluginField.set(null, savedActivator);
        }
    }

    @Test
    public void testExtensionRefusesExplicitPlatformVersionWithBase() throws Exception
    {
        writeConfiguration(true);
        params.put("baseProjectName", createBase("8.3.27").getName());
        params.put("platformVersion", "8.3.24");
        assertRefused("platformVersion", "8.3.24", "inherited from the base project", "Omit");
    }

    @Test
    public void testExtensionRefusesDeprecatedVersionWithBase() throws Exception
    {
        writeConfiguration(true);
        params.put("baseProjectName", createBase("8.3.27").getName());
        params.put("xmlVersion", "8.3.24");
        assertRefused("platformVersion", "xmlVersion", "inherited from the base project", "Omit");
    }

    @Test
    public void testExtensionInheritsBaseRuntimeInVersionSlot() throws Exception
    {
        writeConfiguration(true);
        String base = createBase("8.3.27").getName();
        params.put("baseProjectName", " " + base + " ");
        params.put("projectNature", EXTENSION);
        assertImported("8.3.27", base);
    }

    @Test
    public void testExternalDumpWithoutOptionsReachesRunner() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        assertImported(null, null);
    }

    @Test
    public void testExternalDumpAcceptsItsNature() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        params.put("projectNature", " " + EXTERNAL + " ");
        assertImported(null, null);
    }

    @Test
    public void testExternalDumpRejectsConfigurationNatureWithDetectedId() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        params.put("projectNature", CONFIGURATION);
        assertRefused(CONFIGURATION, "external objects dump", EXTERNAL, "Omit projectNature");
    }

    @Test
    public void testExternalDumpAcceptsBaseAndInheritsItsRuntime() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        String base = createBase("8.3.27").getName();
        params.put("baseProjectName", base);
        params.put("projectNature", EXTERNAL);
        assertImported("8.3.27", base);
    }

    @Test
    public void testExternalDumpRefusesExplicitVersionWithBase() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        params.put("baseProjectName", createBase("8.3.27").getName());
        params.put("platformVersion", "8.3.24");
        assertRefused("8.3.24", "inherited from the base project", "Omit");
    }

    @Test
    public void testExternalDumpValidatesMissingBase() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        String missing = uniqueName();
        params.put("baseProjectName", missing);
        assertRefused(missing, "Project not found", "list_projects");
    }

    @Test
    public void testExternalDumpCanSpecifyRuntimeWithoutBase() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        params.put("projectNature", EXTERNAL);
        params.put("platformVersion", "8.3.27");
        assertImported("8.3.27", null);
    }

    @Test
    public void testUnknownNatureListsAllThreeAcceptedIds()
    {
        params.put("projectNature", "typo.nature");
        assertRefused("typo.nature", CONFIGURATION, EXTENSION, EXTERNAL, "Omit projectNature");
    }

    @Test
    public void testUnreadableConfigurationWithoutOptionsReachesRunner() throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"), "not XML", StandardCharsets.UTF_8);
        assertImported(null, null);
    }

    @Test
    public void testUnreadableConfigurationWithOnlyVersionReachesRunner() throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"), "not XML", StandardCharsets.UTF_8);
        params.put("platformVersion", "8.3.27");
        assertImported("8.3.27", null);
    }

    @Test
    public void testUnreadableConfigurationWithBaseNeedsKindCheck() throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"), "not XML", StandardCharsets.UTF_8);
        params.put("baseProjectName", "SomeBase");
        assertRefused("Configuration.xml", "could not be read", "dump kind cannot be verified",
            "export_configuration_to_xml");
    }

    @Test
    public void testMalformedPlatformVersionIsRefusedBeforeRunner()
    {
        params.put("platformVersion", "abc");
        assertRefused("platformVersion", "abc", "8.3.27");
    }

    @Test
    public void testIncompletePlatformVersionIsRefusedBeforeRunner()
    {
        params.put("platformVersion", "8.3");
        assertRefused("platformVersion", "8.3", "8.3.27");
    }

    @Test
    public void testMalformedDeprecatedVersionIsRefusedBeforeRunner()
    {
        params.put("xmlVersion", "abc");
        assertRefused("platformVersion", "abc", "8.3.27");
    }

    @Test
    public void testUnsupportedRuntimeIsRefusedWithSupportedVersions()
    {
        registry = mock(IRuntimeRegistry.class);
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.27"));
        when(registry.getRuntimes()).thenReturn(List.of(runtime));
        params.put("platformVersion", "8.3.24");
        assertRefused("8.3.24", "not supported by this EDT", "supported: 8.3.27", "Omit platformVersion");
        verify(registry).getRuntime(new Version("8.3.24"));
    }

    @Test
    public void testRegisteredRuntimeReachesRunner()
    {
        registry = mock(IRuntimeRegistry.class);
        when(registry.getRuntime(new Version("8.3.27"))).thenReturn(mock(IRuntime.class));
        params.put("platformVersion", "8.3.27");
        assertImported("8.3.27", null);
        verify(registry).getRuntime(new Version("8.3.27"));
    }

    @Test
    public void testUnavailableRegistryDoesNotRefuseWellFormedRuntime()
    {
        params.put("platformVersion", "8.3.27");
        assertImported("8.3.27", null);
    }

    @Test
    public void testNatureMismatchReleasesClaimWithValidBase() throws Exception
    {
        writeConfiguration(true);
        params.put("baseProjectName", createBase("8.3.27").getName());
        params.put("projectNature", CONFIGURATION);
        assertRefused(CONFIGURATION, "extension dump", EXTENSION, "Omit projectNature");
    }

    @Test
    public void testConflictingVersionAliasesReleaseClaim()
    {
        params.put("platformVersion", "8.3.27");
        params.put("xmlVersion", "8.3.24");
        assertRefused("8.3.27", "8.3.24", "conflicts", "Pass platformVersion only");
    }

    @Test
    public void testBuildNumberVersionIsRefusedRatherThanSilentlyIgnored()
    {
        params.put("platformVersion", "8.3.27.1691");
        assertRefused("platformVersion", "8.3.27.1691", "three-part runtime version", "without a build number");
    }

    @Test
    public void testBinaryImporterReusesSharedNatureConstants()
    {
        for (Field field : ImportProjectFromFileTool.class.getDeclaredFields())
        {
            assertFalse("reuse the shared nature constant: " + field.getName(),
                List.of("NATURE_CONFIGURATION", "NATURE_EXTENSION", "NATURE_EXTERNAL_OBJECTS")
                    .contains(field.getName()));
        }
    }

    @Test
    public void testBinaryImporterReusesSharedRuntimeVersionLookup()
    {
        for (java.lang.reflect.Method method : ImportProjectFromFileTool.class.getDeclaredMethods())
        {
            assertFalse("reuse ProjectImportValidation.runtimeVersionOf", "runtimeVersionOf".equals(method.getName()));
        }
    }

    @Test
    public void testDescriptionCarriesRuntimeAndExternalBaseRules()
    {
        String description = new ImportConfigurationFromXmlTool().getDescription();
        for (String clause : List.of("new project's runtime", "derived from the dump", "optional for external objects",
            "With a base, omit platformVersion and xmlVersion", "runtime is inherited"))
        {
            assertTrue("always-loaded description must carry: " + clause, description.contains(clause));
        }
    }

    private void writeConfiguration(boolean extension) throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"),
            "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><Configuration><Properties>"
                + (extension ? "<ObjectBelonging>Adopted</ObjectBelonging>" : "")
                + "</Properties></Configuration></MetaDataObject>", StandardCharsets.UTF_8);
    }

    private IProject createBase(String version) throws Exception
    {
        IProject base = ResourcesPlugin.getWorkspace().getRoot().getProject(uniqueName());
        bases.add(base);
        base.create(new NullProgressMonitor());
        base.open(new NullProgressMonitor());
        IProjectDescription description = base.getDescription();
        description.setNatureIds(new String[] { CONFIGURATION });
        base.setDescription(description, IResource.AVOID_NATURE_CONFIG, new NullProgressMonitor());
        IV8Project v8Project = mock(IV8Project.class);
        when(v8Project.getVersion()).thenReturn(new Version(version));
        when(manager.getProject(base)).thenReturn(v8Project);
        return base;
    }

    private String execute()
    {
        return ImportConfigurationFromXmlTool.executeWith(params, runner, new ReadyLifecycle(), 30_000L);
    }

    private void assertImported(String version, String base)
    {
        String answer = execute();
        assertTrue(answer, answer.contains("state: ready"));
        assertEquals(1, runner.calls);
        assertEquals("CLI version slot", version, runner.version);
        assertEquals("CLI base-project slot", base, runner.base);
        assertNull("successful import releases its claim",
            ImportConfigurationFromXmlTool.importClaimedAt(params.get("projectName")));
    }

    private void assertRefused(String... fragments)
    {
        String answer = execute();
        assertTrue("refusal must be error JSON: " + answer, answer.startsWith("{"));
        JsonObject error = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, error.get("success").getAsBoolean());
        for (String fragment : fragments)
        {
            assertTrue("missing '" + fragment + "' in " + answer, answer.contains(fragment));
        }
        assertFalse(error.has("mutationCommitted"));
        assertFalse(error.has("mutationOutcomeUnknown"));
        assertEquals("refusal must precede API resolution", 0, runner.apiChecks);
        assertEquals("refusal must precede CLI import", 0, runner.calls);
        assertNull("refusal must release its import claim",
            ImportConfigurationFromXmlTool.importClaimedAt(params.get("projectName")));
    }

    private static String uniqueName()
    {
        return "ZZImport669R2" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class RecordingRunner implements IImportRunner
    {
        int apiChecks;
        int calls;
        String version;
        String base;

        @Override
        public Object importApi() { apiChecks++; return this; }

        @Override
        public void runImport(Object api, Path path, String name, String version, String base)
        {
            calls++;
            this.version = version;
            this.base = base;
        }
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
