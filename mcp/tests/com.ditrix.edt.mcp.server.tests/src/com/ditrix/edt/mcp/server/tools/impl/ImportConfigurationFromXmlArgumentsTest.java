/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

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
import com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportRunner;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Issue #669: exercise argument validation AND the production reflective CLI call. */
public class ImportConfigurationFromXmlArgumentsTest
{
    private static final String CONFIGURATION = "com._1c.g5.v8.dt.core.V8ConfigurationNature";
    private static final String EXTENSION = "com._1c.g5.v8.dt.core.V8ExtensionNature";

    private final List<IProject> projects = new ArrayList<>();
    private Path dump;
    private Map<String, String> params;
    private RecordingRunner runner;

    @Before
    public void setUp() throws Exception
    {
        dump = Files.createTempDirectory("import-xml-669-");
        writeDump(false);
        params = new HashMap<>();
        params.put("importPath", dump.toString());
        params.put("projectName", uniqueName());
        runner = new RecordingRunner();
    }

    @After
    public void tearDown() throws Exception
    {
        for (IProject project : projects)
        {
            if (project.exists())
            {
                project.delete(true, true, new NullProgressMonitor());
            }
        }
        assertTrue("the temporary XML dump must be removed", BinaryToXmlConverter.deleteQuietly(dump));
    }

    @Test
    public void testPlatformVersionReachesVersionSlotAndNatureDoesNot() throws Exception
    {
        params.put("platformVersion", "8.3.24");
        params.put("projectNature", CONFIGURATION);
        assertImported("8.3.24", null);
    }

    @Test
    public void testDeprecatedXmlVersionReachesVersionSlotAndNeverBaseSlot() throws Exception
    {
        params.put("xmlVersion", "8.3.20");
        assertImported("8.3.20", null);
    }

    @Test
    public void testMatchingAliasesAreTrimmedAndAccepted() throws Exception
    {
        params.put("platformVersion", " 8.3.24 ");
        params.put("xmlVersion", "\t8.3.24\t");
        assertImported("8.3.24", null);
    }

    @Test
    public void testConflictingAliasesAreRefusedBeforeImport() throws Exception
    {
        params.put("platformVersion", "8.3.24");
        params.put("xmlVersion", "8.3.20");
        assertRefused("platformVersion '8.3.24'", "xmlVersion '8.3.20'", "Pass platformVersion only");
    }

    @Test
    public void testBlankCanonicalVersionFallsBackToTrimmedAlias() throws Exception
    {
        params.put("platformVersion", " \t ");
        params.put("xmlVersion", " 8.3.20 ");
        assertImported("8.3.20", null);
    }

    @Test
    public void testBlankAliasDoesNotOverrideCanonicalVersion() throws Exception
    {
        params.put("platformVersion", "8.3.24");
        params.put("xmlVersion", " \t ");
        assertImported("8.3.24", null);
    }

    @Test
    public void testWhitespaceOnlyOptionalArgumentsBecomeNull() throws Exception
    {
        for (String key : List.of("platformVersion", "xmlVersion", "projectNature", "baseProjectName"))
        {
            params.put(key, " \t ");
        }
        assertImported(null, null);
    }

    @Test
    public void testOmittedOptionsStillAutoDetect() throws Exception
    {
        assertImported(null, null);
    }

    @Test
    public void testMatchingNatureIsTrimmedButNeverPassedAsVersion() throws Exception
    {
        params.put("projectNature", " " + CONFIGURATION + " ");
        assertImported(null, null);
    }

    @Test
    public void testRussianNameAndSynonymDoNotChangeTheDetectedKind() throws Exception
    {
        String xml = Files.readString(dump.resolve("Configuration.xml"), StandardCharsets.UTF_8);
        Files.writeString(dump.resolve("Configuration.xml"), xml.replace("<md:Name>Imported</md:Name>",
            "<md:Name>\u0422\u0435\u0441\u0442</md:Name>"
                + "<md:Synonym xmlns:v8=\"http://v8.1c.ru/8.1/data/core\"><v8:item>"
                + "<v8:lang>ru</v8:lang><v8:content>\u0421\u0438\u043d\u043e\u043d\u0438\u043c</v8:content>"
                + "</v8:item></md:Synonym>"), StandardCharsets.UTF_8);
        params.put("projectNature", CONFIGURATION);
        assertImported(null, null);
    }

    @Test
    public void testExtensionRefusesExplicitVersionWithTrimmedBase() throws Exception
    {
        writeDump(true);
        String base = createBase(true, true).getName();
        params.put("baseProjectName", " " + base + " ");
        params.put("platformVersion", " 8.3.24 ");
        params.put("projectNature", EXTENSION);
        assertRefused("platformVersion", "inherited from the base project", "Omit");
    }

    @Test
    public void testMainConfigurationNatureIsRefusedForExtension() throws Exception
    {
        writeDump(true);
        params.put("projectNature", CONFIGURATION);
        assertRefused(CONFIGURATION, "extension dump", EXTENSION, "Omit projectNature");
    }

    @Test
    public void testExtensionNatureIsRefusedForMainConfiguration() throws Exception
    {
        params.put("projectNature", EXTENSION);
        assertRefused(EXTENSION, "main configuration dump", CONFIGURATION, "Omit projectNature");
    }

    @Test
    public void testUnknownNatureIsRefusedWithDetectedNatureAndFix() throws Exception
    {
        params.put("projectNature", "unknown.nature");
        assertRefused("unknown.nature", CONFIGURATION, "Omit projectNature");
    }

    @Test
    public void testMissingConfigurationXmlDetectsExternalNature() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        params.put("projectNature", CONFIGURATION);
        assertRefused(CONFIGURATION, "external objects dump", "V8ExternalObjectsNature", "Omit projectNature");
    }

    @Test
    public void testUnreadableDumpCannotSatisfyNatureCheck() throws Exception
    {
        Files.writeString(dump.resolve("Configuration.xml"), "not XML", StandardCharsets.UTF_8);
        params.put("projectNature", CONFIGURATION);
        assertRefused("Configuration.xml", "could not be read", "export_configuration_to_xml");
    }

    @Test
    public void testExternalDumpChecksRequestedBase() throws Exception
    {
        Files.delete(dump.resolve("Configuration.xml"));
        params.put("baseProjectName", "MissingBase");
        assertRefused("MissingBase", "Project not found", "list_projects");
    }

    @Test
    public void testExtensionRequiresBaseEvenWithoutNatureArgument() throws Exception
    {
        writeDump(true);
        assertRefused("baseProjectName is required", "configuration extension XML dump", "list_projects");
    }

    @Test
    public void testMainConfigurationRefusesBaseProject() throws Exception
    {
        params.put("baseProjectName", "AnyBase");
        assertRefused("baseProjectName does not apply", "configuration XML dump", "Omit baseProjectName");
    }

    @Test
    public void testExtensionRefusesMissingBaseWithDiscoveryHint() throws Exception
    {
        writeDump(true);
        String base = uniqueName();
        params.put("baseProjectName", base);
        assertRefused(base, "Project not found", "list_projects");
    }

    @Test
    public void testExtensionRefusesIllegalBaseNameWithoutLeakingAnException() throws Exception
    {
        writeDump(true);
        params.put("baseProjectName", "../bad-base");
        assertRefused("../bad-base", "Project not found", "list_projects");
    }

    @Test
    public void testExtensionRefusesClosedBase() throws Exception
    {
        writeDump(true);
        String base = createBase(false, false).getName();
        params.put("baseProjectName", base);
        assertRefused(base, "is closed", "Open it in EDT first");
    }

    @Test
    public void testExtensionRefusesAnOpenNonConfigurationBase() throws Exception
    {
        writeDump(true);
        String base = createBase(true, false).getName();
        params.put("baseProjectName", base);
        assertRefused(base, "is not a configuration project", "Pass the configuration project");
    }

    @Test
    public void testCliObjectBelongingMarkerWinsOverPurposeProperty() throws Exception
    {
        // EDT detects ObjectBelonging=Adopted, not the presence of an extension-purpose property.
        String xml = Files.readString(dump.resolve("Configuration.xml"), StandardCharsets.UTF_8);
        Files.writeString(dump.resolve("Configuration.xml"), xml.replace("</md:Properties>",
            "<md:ConfigurationExtensionPurpose>Customization</md:ConfigurationExtensionPurpose></md:Properties>"),
            StandardCharsets.UTF_8);
        params.put("projectNature", EXTENSION);
        assertRefused(EXTENSION, "main configuration dump", "Omit projectNature");
    }

    @Test
    public void testSchemaDeclaresNewParametersAndOnlyTheOriginalTwoAreRequired()
    {
        JsonObject schema = JsonParser.parseString(new ImportConfigurationFromXmlTool().getInputSchema())
            .getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");
        assertTrue(properties.has("platformVersion"));
        assertTrue(properties.has("baseProjectName"));
        assertTrue(properties.getAsJsonObject("xmlVersion").get("description").getAsString()
            .contains("Deprecated alias of platformVersion"));
        assertEquals("[\"importPath\",\"projectName\"]", schema.get("required").toString());
    }

    @Test
    public void testProtocolClausesSurviveSchemaCompaction()
    {
        ImportConfigurationFromXmlTool tool = new ImportConfigurationFromXmlTool();
        String description = tool.getDescription();
        for (String clause : List.of("platformVersion", "xmlVersion is a deprecated alias",
            "conflicting values are refused", "projectNature", "refuses mismatches",
            "For extensions, pass baseProjectName", "omit it for main configurations"))
        {
            assertTrue("always-loaded description must carry: " + clause, description.contains(clause));
        }
        JsonObject compacted = InputSchemaCompactor.compact(tool.getName(),
            JsonParser.parseString(tool.getInputSchema())).getAsJsonObject();
        assertTrue(compacted.getAsJsonObject("properties").has("platformVersion"));
        assertTrue(compacted.getAsJsonObject("properties").has("baseProjectName"));
    }

    private void writeDump(boolean extension) throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<md:MetaDataObject xmlns:md=\"http://v8.1c.ru/8.3/MDClasses\">"
            + "<md:Configuration uuid=\"1\"><md:Properties><md:Name>Imported</md:Name>"
            + (extension ? "<md:ObjectBelonging>Adopted</md:ObjectBelonging>" : "")
            + "</md:Properties></md:Configuration></md:MetaDataObject>";
        Files.writeString(dump.resolve("Configuration.xml"), xml, StandardCharsets.UTF_8);
    }

    private IProject createBase(boolean open, boolean configuration) throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(uniqueName());
        projects.add(project);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        if (configuration)
        {
            IProjectDescription description = project.getDescription();
            description.setNatureIds(new String[] { CONFIGURATION });
            // Only the descriptor is needed; running EDT's nature builder would defeat this unit seam.
            project.setDescription(description, IResource.AVOID_NATURE_CONFIG, new NullProgressMonitor());
        }
        if (!open)
        {
            project.close(new NullProgressMonitor());
        }
        return project;
    }

    private String execute()
    {
        return ImportConfigurationFromXmlTool.executeWith(params, runner, new ReadyLifecycle(), 30_000L);
    }

    private void assertImported(String version, String base)
    {
        String answer = execute();
        assertTrue("the import must finish ready: " + answer, answer.contains("state: ready"));
        assertEquals("the real reflective API call must happen once", 1, runner.api.calls);
        assertEquals(dump.toAbsolutePath().normalize(), runner.api.path);
        assertEquals(params.get("projectName"), runner.api.name);
        assertEquals("CLI version slot", version, runner.api.version);
        assertEquals("CLI base-project slot", base, runner.api.base);
    }

    private void assertRefused(String... fragments)
    {
        String answer = execute();
        assertTrue("refusals must be error JSON: " + answer, answer.startsWith("{"));
        JsonObject error = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, error.get("success").getAsBoolean());
        for (String fragment : fragments)
        {
            assertTrue("missing '" + fragment + "' in: " + answer, answer.contains(fragment));
        }
        assertFalse("validation must not claim a committed mutation", error.has("mutationCommitted"));
        assertFalse("validation must not claim an unknown mutation", error.has("mutationOutcomeUnknown"));
        assertEquals("validation must precede CLI API resolution", 0, runner.availabilityChecks);
        assertEquals("validation must precede project creation", 0, runner.api.calls);
        assertNull("validation must release the import claim",
            ImportConfigurationFromXmlTool.importClaimedAt(params.get("projectName")));
    }

    private static String uniqueName()
    {
        return "ZZImport669" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class RecordingRunner implements IImportRunner
    {
        final RecordingApi api = new RecordingApi();
        final IImportRunner production;
        int availabilityChecks;

        RecordingRunner() throws Exception
        {
            Field field = ImportConfigurationFromXmlTool.class.getDeclaredField("PLATFORM_IMPORT_RUNNER");
            field.setAccessible(true);
            production = (IImportRunner)field.get(null);
        }

        @Override
        public Object importApi()
        {
            availabilityChecks++;
            return api;
        }

        @Override
        public void runImport(Object api, Path path, String name, String version, String base) throws Exception
        {
            production.runImport(api, path, name, version, base);
        }
    }

    /** Public so the production runner can reflect on it exactly as it does on the CLI API. */
    public static final class RecordingApi
    {
        int calls;
        Path path;
        String name;
        String version;
        String base;

        public void importProject(Path path, String name, String version, String base)
        {
            calls++;
            this.path = path;
            this.name = name;
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
        public void permitImport(IProject project) { fail("a ready import needs no latch recovery"); }

        @Override
        public boolean isStarted(IProject project) { return true; }

        @Override
        public void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
            IProgressMonitor monitor) { fail("a ready project must not be restarted"); }

        @Override
        public boolean hasDtProject(IProject project) { return true; }
    }
}
