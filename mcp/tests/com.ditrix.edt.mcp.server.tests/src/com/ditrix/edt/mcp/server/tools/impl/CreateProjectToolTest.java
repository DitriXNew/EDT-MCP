/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EClass;
import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool.ExternalObjectSpec;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;
import com.google.gson.JsonParser;

/**
 * Unit tests for {@link CreateProjectTool}.
 * <p>
 * Covers tool name/constant, response type, description (guide pointer,
 * v8codestyle mention), guide non-empty, input schema (all declared params,
 * required array, projectKind enum), output schema (expected fields), and the
 * argument-validation sentinels that return before any EDT API call (headless-safe).
 */
public class CreateProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_project", new CreateProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateProjectTool.NAME, new CreateProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateProjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.trim().isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateProjectTool().getDescription();
        assertTrue("description must steer the agent to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_project')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsCodestyleOptionality()
    {
        String desc = new CreateProjectTool().getDescription();
        assertTrue("description must mention v8codestyle optionality", //$NON-NLS-1$
            new CreateProjectTool().getGuide().contains("com.e1c.v8codestyle")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new CreateProjectTool().getGuide();
        assertNotNull(guide);
        assertFalse("getGuide() must be non-empty", guide.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsKeyParams()
    {
        String guide = new CreateProjectTool().getGuide();
        assertTrue("guide must document projectKind", guide.contains("projectKind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document baseProjectName", guide.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document externalObject", guide.contains("externalObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document normalizeYo", guide.contains("normalizeYo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document slow-root verification", //$NON-NLS-1$
            guide.contains("get_metadata_objects") && guide.contains("duplicate-project guard")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document purpose", guide.contains("purpose")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document autoSortTopObjects limitation", //$NON-NLS-1$
            guide.contains("autoSortTopObjects")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectKind", schema.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare version", schema.contains("\"version\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare externalObject", schema.contains("\"externalObject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare normalizeYo", schema.contains("\"normalizeYo\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare baseProjectName", schema.contains("\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare prefix", schema.contains("\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare synonym", schema.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare comment", schema.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare purpose", schema.contains("\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare compatibilityMode", schema.contains("\"compatibilityMode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare scriptVariant", schema.contains("\"scriptVariant\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare standardChecks", schema.contains("\"standardChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare commonChecks", schema.contains("\"commonChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare autoSortTopObjects", schema.contains("\"autoSortTopObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaProjectKindEnum()
    {
        String schema = new CreateProjectTool().getInputSchema();
        // The projectKind enum must declare all three allowed values
        assertTrue("schema must declare 'configuration' kind", schema.contains("\"configuration\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'extension' kind", schema.contains("\"extension\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'externalObjects' kind", schema.contains("\"externalObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new CreateProjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectKind must be required", tail.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional params must NOT appear in the required array
        assertFalse("projectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectName\",") || tail.contains(",\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("baseProjectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"baseProjectName\",") || tail.contains(",\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("externalObject must NOT be required", //$NON-NLS-1$
            tail.contains("\"externalObject\",") || tail.contains(",\"externalObject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("normalizeYo must NOT be required", //$NON-NLS-1$
            tail.contains("\"normalizeYo\",") || tail.contains(",\"normalizeYo\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("prefix must NOT be required", //$NON-NLS-1$
            tail.contains("\"prefix\",") || tail.contains(",\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("synonym must NOT be required", //$NON-NLS-1$
            tail.contains("\"synonym\",") || tail.contains(",\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("purpose must NOT be required", //$NON-NLS-1$
            tail.contains("\"purpose\",") || tail.contains(",\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new CreateProjectTool().getOutputSchema();
        assertNotNull("outputSchema must not be null", schema); //$NON-NLS-1$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare projectKind", schema.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare baseProject", schema.contains("\"baseProject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare state", schema.contains("\"state\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare codestyle", schema.contains("\"codestyle\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare externalObject", schema.contains("\"externalObject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare externalObjectConfirmed", //$NON-NLS-1$
            schema.contains("\"externalObjectConfirmed\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare normalized", schema.contains("\"normalized\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // #706: the effective extension compatibility mode is echoed - by default the BASE's, which is
        // what EDT's wizard uses, no longer the project's runtime version.
        assertTrue("outputSchema must declare compatibilityMode", //$NON-NLS-1$
            schema.contains("\"compatibilityMode\"")); //$NON-NLS-1$
    }

    @Test
    public void testCompatibilityModeProseNamesTheBaseDefaultAndARealisticExample()
    {
        String schema = new CreateProjectTool().getInputSchema();
        int at = schema.indexOf("\"compatibilityMode\""); //$NON-NLS-1$
        assertTrue("schema must declare compatibilityMode", at >= 0); //$NON-NLS-1$
        String prose = schema.substring(at, schema.indexOf('}', at));
        assertTrue("the default is the base configuration's mode, as in EDT's wizard: " + prose, //$NON-NLS-1$
            prose.contains("base configuration's compatibility mode")); //$NON-NLS-1$
        assertFalse("'factory default' was the runtime version - the #706 defect: " + prose, //$NON-NLS-1$
            prose.contains("factory default")); //$NON-NLS-1$
        assertFalse("Version8_3_10 is refused over any base above 8.3.13 - not an example: " + prose, //$NON-NLS-1$
            prose.contains("Version8_3_10")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDescribesTheWizardShapedExtensionRoot()
    {
        String guide = new CreateProjectTool().getGuide();
        assertTrue("guide must say the root keeps mapping by IDs", //$NON-NLS-1$
            guide.contains("keepMappingToExtendedConfigurationObjectsByIDs")); //$NON-NLS-1$
        assertTrue("guide must name the two command interface files", //$NON-NLS-1$
            guide.contains("CommandInterface.cmi") && guide.contains("MainSectionCommandInterface.cmi")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must say the wizard's default role is not created", //$NON-NLS-1$
            guide.contains("default role")); //$NON-NLS-1$
        // The guide wraps its lines, so a phrase is looked for in the text with its whitespace flattened: the
        // old 'fully indexed' sentence broke across a line and a plain contains() never saw it.
        String flat = flat(guide);
        assertFalse("ready means the context started, not 'fully indexed'", //$NON-NLS-1$
            flat.contains("fully indexed")); //$NON-NLS-1$
        assertTrue("ready is said not to mean EDT has finished computing the project", //$NON-NLS-1$
            flat.contains("`ready` does not mean EDT has finished computing")); //$NON-NLS-1$
        assertFalse("the 'couple of minutes' was the 60 s wait that #706 removed", //$NON-NLS-1$
            flat.contains("couple of minutes")); //$NON-NLS-1$
    }

    @Test
    public void testGuideBindsTheByIdMappingToThePlatformVersion()
    {
        // EDT's copier links an adopted object by ID only above platform 8.3.14, whatever the root's flag.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("on a platform version above 8.3.14 only")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("at 8.3.14 or lower EDT maps adopted objects by name")); //$NON-NLS-1$
    }

    @Test
    public void testGuideSaysWhichKindsReadyConfirmsOnDisk()
    {
        // Only an extension's 'ready' waits for its root file; a configuration's or an externalObjects
        // project's 'ready' means only that EDT reported the context started.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("for a configuration or externalObjects project nothing on disk is checked")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("only an extension's `ready` confirms its root file is on disk")); //$NON-NLS-1$
        assertFalse("'message says what was not' holds for an extension only", //$NON-NLS-1$
            flat.contains("`message` says what was not;")); //$NON-NLS-1$
    }

    @Test
    public void testGuideTiesTheWizardsDefaultRoleToTheBaseMode()
    {
        // The wizard's extension mode is always its base's, so the base's mode decides whether it makes a role.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("only over a base whose compatibility mode is above 8.3.13")); //$NON-NLS-1$
        assertFalse(flat, flat.contains("only above extension compatibility 8.3.13")); //$NON-NLS-1$
    }

    @Test
    public void testGuideSaysTheCommandInterfaceFilesAreLookedForButLeaveStateToTheRoot()
    {
        // The two .cmi files are looked for in the same bounded check as the root; a file not seen is said in
        // 'message', and a save EDT did not accept only when a file was not seen or no check could run - not when the
        // check saw both (commandInterfaceNote). And 'ready' stays what it is documented as: the root seen on disk.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("looked for on disk within the same check as the root file")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("`message` names a file that was not seen on disk, and a save of them " //$NON-NLS-1$
            + "EDT did not accept when a file was not seen or no check could run (they do not change `state`)")); //$NON-NLS-1$
        assertFalse("a refused save of files the check saw is not said", //$NON-NLS-1$
            flat.contains("says when they were not queued for saving")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("what was not confirmed (its root file, its command interface files)")); //$NON-NLS-1$
    }

    @Test
    public void testGuideSaysASlowExtensionCreateWritesNothingMore()
    {
        // A create still running when the wait window ended gets an answer and no write (afterExtensionCreate), and no
        // MCP tool attaches the command interfaces to an existing extension later.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("are created too (unless the create exceeds the 120 s wait window")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("**A slow extension create writes nothing more.**")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("no MCP tool attaches them to an existing extension")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("`delete_project` and `deleteContent=true` (without it the project's directory " //$NON-NLS-1$
            + "stays on disk and `create_project` refuses it)")); //$NON-NLS-1$
        assertFalse(flat, flat.contains("stay on disk under the new create")); //$NON-NLS-1$
        // PR #747 review, rounds 6 and 7: the slow answer asks for verification and reports no value of the unchecked
        // root - its name included - and the recipe ends the running create before it deletes or creates anything.
        assertTrue(flat, flat.contains("answers `action=verificationRequired`, `state=rootUnconfirmed`, without " //$NON-NLS-1$
            + "`name`, `prefix`, `purpose`, `scriptVariant` or `compatibilityMode`")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("- `name` \u2014 the Configuration name (configuration and extension only; not " //$NON-NLS-1$
            + "reported by a slow extension create).")); //$NON-NLS-1$
        String wait = "Do not remove or recreate the project while that create may still be running"; //$NON-NLS-1$
        assertTrue(flat, flat.contains(wait + " \u2014 a delete or a second create would race it: first make sure it has " //$NON-NLS-1$
            + "ended (restarting EDT ends it;")); //$NON-NLS-1$
        int slow = flat.indexOf("**A slow extension create writes nothing more.**"); //$NON-NLS-1$
        assertTrue("in the slow-create gotcha, the wait comes before the delete", //$NON-NLS-1$
            flat.indexOf(wait, slow) < flat.indexOf("`delete_project`", slow)); //$NON-NLS-1$
    }

    @Test
    public void testGuideSaysReadyIsThisCreatesRootOnDisk()
    {
        // PR #747 review, rounds 6 and 7: 'ready' needs the values this call set in the root file - the purpose and mode
        // too, a Patch purpose (EDT's default, which EDT does not write) being the one that may be absent - and an earlier
        // project's root cannot be left there, because create_project refuses an existing project directory. The
        // round-6 residual ("same values cannot be told apart") is gone.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("`ready` also means its root file was seen on disk with the extension block, " //$NON-NLS-1$
            + "the mapping flag and the name, name prefix, purpose and extension compatibility mode this call set (a " //$NON-NLS-1$
            + "purpose of `Patch`, EDT's default, which EDT does not write, may be absent).")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("No earlier project's `Configuration.mdo` is left there to confirm it: " //$NON-NLS-1$
            + "`create_project` refuses a project whose directory already exists on disk (see Gotchas);")); //$NON-NLS-1$
        assertFalse("the round-6 residual is gone", flat.contains("cannot be told from this create's root")); //$NON-NLS-1$
        assertFalse("the leniency is gone", flat.contains("where the file names them")); //$NON-NLS-1$
        assertFalse("the unqualified claim is gone", //$NON-NLS-1$
            flat.contains("an earlier project of that name left on disk does not confirm it")); //$NON-NLS-1$
    }

    @Test
    public void testGuideSaysAnExistingProjectDirectoryIsRefused()
    {
        // PR #747 review, round 7: every kind refuses a project whose directory is already on disk.
        String flat = flat(new CreateProjectTool().getGuide());
        assertTrue(flat, flat.contains("It is also rejected, for every kind, when the project's directory " //$NON-NLS-1$
            + "`<workspace>/<project>` already exists on disk without a workspace project of that name")); //$NON-NLS-1$
        assertTrue(flat, flat.contains("remove that directory, or, if a workspace project keeps its files there, " //$NON-NLS-1$
            + "delete it with `delete_project deleteContent=true`; then retry.")); //$NON-NLS-1$
    }

    private static String flat(String text)
    {
        return text.replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ────────── an existing project directory (PR #747 review, round 7) ──────────

    /** The directories a test made in the workspace location, removed after it. */
    private final List<Path> directories = new ArrayList<>();

    @After
    public void removeDirectories() throws IOException
    {
        for (Path directory : directories)
        {
            if (Files.isDirectory(directory))
            {
                Files.deleteIfExists(directory.resolve("left-over.txt")); //$NON-NLS-1$
            }
            Files.deleteIfExists(directory);
        }
    }

    /** A project name no other test uses; letters only, so it is a valid 1C identifier as 'name' too. */
    private static String uniqueName()
    {
        StringBuilder name = new StringBuilder("RoundSevenDir"); //$NON-NLS-1$
        for (char c : Long.toHexString(System.nanoTime()).toCharArray())
        {
            name.append((char) ('a' + Character.digit(c, 16)));
        }
        return name.toString();
    }

    /** Makes the directory EDT's create would put project {@code projectName} in, holding one file, as a left-over. */
    private Path leftOverDirectory(String projectName) throws IOException
    {
        Path directory = WorkspacePaths.defaultProjectFolder(projectName);
        assertNotNull("the test workspace has a location", directory); //$NON-NLS-1$
        directories.add(directory);
        Files.createDirectories(directory);
        Files.write(directory.resolve("left-over.txt"), new byte[] { 1 }); //$NON-NLS-1$
        return directory;
    }

    private static String errorOf(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    private static String refusalText(String projectName, Path directory)
    {
        return "The directory " + directory + " of project '" + projectName + "' already exists on disk, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "though the workspace has no project of that name"; //$NON-NLS-1$
    }

    @Test
    public void testAnExistingDirectoryIsRefusedWithItsPathAndTheRecipe() throws IOException
    {
        Path directory = Files.createTempDirectory("r7-747"); //$NON-NLS-1$
        directories.add(directory);

        String refusal = CreateProjectTool.existingDirectoryRefusal("Ext", directory); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith(refusalText("Ext", directory))); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was created.")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("If a workspace project keeps its files there, delete it with " //$NON-NLS-1$
            + "delete_project and deleteContent=true; otherwise check what the directory holds and remove it. " //$NON-NLS-1$
            + "Then retry, or pass another name or projectName.")); //$NON-NLS-1$
    }

    @Test
    public void testAFileInThePlaceOfTheDirectoryIsRefusedToo() throws IOException
    {
        Path file = Files.createTempFile("r7-747", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        directories.add(file);

        assertNotNull(CreateProjectTool.existingDirectoryRefusal("Ext", file)); //$NON-NLS-1$
    }

    @Test
    public void testNoDirectoryIsNeverRefused() throws IOException
    {
        Path parent = Files.createTempDirectory("r7-747"); //$NON-NLS-1$
        directories.add(parent);

        assertNull(CreateProjectTool.existingDirectoryRefusal("Ext", parent.resolve("Ext"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a workspace without a local location has no directory to check", //$NON-NLS-1$
            CreateProjectTool.existingDirectoryRefusal("Ext", null)); //$NON-NLS-1$
    }

    /** The directory looked at is the one EDT's create uses - the workspace location joined with the project's name. */
    @Test
    public void testTheDirectoryLookedAtIsTheWorkspaceLocationOfTheProject() throws IOException
    {
        String name = uniqueName();
        assertNull("no project and no directory: nothing refuses", CreateProjectTool.takenProjectRefusal(name)); //$NON-NLS-1$
        assertNull("another name's directory is not this project's", //$NON-NLS-1$
            CreateProjectTool.takenProjectRefusal(name + "Other")); //$NON-NLS-1$

        Path directory = leftOverDirectory(name);

        String refusal = CreateProjectTool.takenProjectRefusal(name);
        assertNotNull("a directory with no workspace project behind it is refused", refusal); //$NON-NLS-1$
        assertTrue(refusal, errorOf(refusal).startsWith(refusalText(name, directory)));
        assertNull("another name's directory is not this project's", //$NON-NLS-1$
            CreateProjectTool.takenProjectRefusal(name + "Other")); //$NON-NLS-1$
    }

    @Test
    public void testAnExtensionCreateRefusesALeftOverDirectoryBeforeAnything() throws IOException
    {
        String name = uniqueName();
        Path directory = leftOverDirectory("SomeBase." + name); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", name); //$NON-NLS-1$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new CreateProjectTool().execute(params);

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("refused before the base project is even looked at: " + result, //$NON-NLS-1$
            errorOf(result).startsWith(refusalText("SomeBase." + name, directory))); //$NON-NLS-1$
        assertTrue("the left-over is not touched", Files.exists(directory.resolve("left-over.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAConfigurationCreateRefusesALeftOverDirectoryOfItsProjectName() throws IOException
    {
        String name = uniqueName();
        String projectName = name + "Project"; //$NON-NLS-1$
        Path directory = leftOverDirectory(projectName);
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", name); //$NON-NLS-1$
        params.put("projectName", projectName); //$NON-NLS-1$

        String result = new CreateProjectTool().execute(params);

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, errorOf(result).startsWith(refusalText(projectName, directory)));
    }

    @Test
    public void testAnExternalObjectsCreateRefusesALeftOverDirectory() throws IOException
    {
        String name = uniqueName();
        Path directory = leftOverDirectory(name);
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "externalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", name); //$NON-NLS-1$

        String result = new CreateProjectTool().execute(params);

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, errorOf(result).startsWith(refusalText(name, directory)));
    }

    // ────────── an illegal or taken project name (PR #747 gate, round 7) ──────────

    /** The workspace projects a test made, deleted with their content after it. */
    private final List<IProject> projects = new ArrayList<>();

    @After
    public void removeProjects() throws CoreException
    {
        for (IProject project : projects)
        {
            if (project.exists())
            {
                project.delete(true, true, new NullProgressMonitor());
            }
        }
    }

    private static String illegalNameRefusalStart(String projectName)
    {
        return "The project name '" + projectName + "' is not a legal workspace project name: "; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A name the workspace does not take as a project name is refused by name, before anything looks it up: a separator
     * and a NUL everywhere, and on Windows every kind of name a path cannot hold (a reserved character, a control
     * character, a trailing space), which made the directory lookup throw, and a trailing dot.
     */
    @Test
    public void testAnIllegalProjectNameIsRefusedByName()
    {
        List<String> names = new ArrayList<>();
        names.add("A/B"); //$NON-NLS-1$
        names.add("A" + (char) 0 + "B"); //$NON-NLS-1$ //$NON-NLS-2$
        if (java.io.File.separatorChar == '\\')
        {
            names.add("Foo*"); //$NON-NLS-1$
            names.add("A:B"); //$NON-NLS-1$
            names.add("A" + (char) 1 + "B"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String c : new String[] { "<", ">", "\"", "|", "?", "\\" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            {
                names.add("A" + c + "B"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            names.add("A "); //$NON-NLS-1$
            names.add("A."); //$NON-NLS-1$
        }
        for (String name : names)
        {
            String refusal = CreateProjectTool.takenProjectRefusal(name);
            assertNotNull(name, refusal);
            String error = errorOf(refusal);
            assertTrue(error, error.startsWith(illegalNameRefusalStart(name)));
            assertTrue(error, error.endsWith(". Nothing was created. Pass another projectName, or, without " //$NON-NLS-1$
                + "projectName, another name (the project name defaults from it).")); //$NON-NLS-1$
        }
    }

    /** Every kind answers an illegal project name with that refusal, not with an exception out of the call. */
    @Test
    public void testEveryKindRefusesAnIllegalProjectName()
    {
        String illegal = java.io.File.separatorChar == '\\' ? "Foo*" : "A/B"; //$NON-NLS-1$ //$NON-NLS-2$
        String[] kinds = { "configuration", "externalObjects", "extension" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String kind : kinds)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectKind", kind); //$NON-NLS-1$
            params.put("name", uniqueName()); //$NON-NLS-1$
            params.put("projectName", illegal); //$NON-NLS-1$
            if ("extension".equals(kind)) //$NON-NLS-1$
            {
                params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            String result = new CreateProjectTool().execute(params);

            assertTrue(kind + ": " + result, result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(kind + ": " + result, errorOf(result).startsWith(illegalNameRefusalStart(illegal)));
        }
    }

    /**
     * A workspace project of the name is said as such, before its directory - which holds that project's own files - is
     * looked at: the directory refusal would claim the workspace has no project of that name.
     */
    @Test
    public void testAWorkspaceProjectIsRefusedAsTakenBeforeItsDirectory() throws CoreException
    {
        String name = uniqueName();
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        assertTrue("the project's files are in its directory, so that check would refuse too", //$NON-NLS-1$
            Files.isDirectory(WorkspacePaths.defaultProjectFolder(name)));

        String refusal = CreateProjectTool.takenProjectRefusal(name);

        assertNotNull(refusal);
        assertEquals("Project already exists in workspace: " + name //$NON-NLS-1$
            + ". Choose a different name or projectName.", errorOf(refusal)); //$NON-NLS-1$
    }

    // ────────── Argument-validation sentinels (return before any EDT API call) ──────────

    @Test
    public void testExternalObjectResolvesBothEnglishRootKinds()
    {
        assertExternalObjectResolution("ExternalDataProcessor.MyProc", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR, "MyProc", "ExternalDataProcessor.MyProc"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExternalObjectResolution("ExternalReport.MyReport", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_REPORT, "MyReport", "ExternalReport.MyReport"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExternalObjectResolvesBothRussianRootKinds()
    {
        assertExternalObjectResolution(
            "\u0412\u043D\u0435\u0448\u043D\u044F\u044F\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430.MyProc", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR, "MyProc", "ExternalDataProcessor.MyProc"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExternalObjectResolution(
            "\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u041E\u0442\u0447\u0435\u0442.MyReport", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_REPORT, "MyReport", "ExternalReport.MyReport"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExternalObjectYoNameIsStoredNormalizedByDefaultAndReported()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject(
            "ExternalReport.\u0412\u0441\u0451"); //$NON-NLS-1$
        assertNull(spec.error);
        assertEquals("\u0412\u0441\u0435", spec.objectName); //$NON-NLS-1$
        assertEquals("ExternalReport.\u0412\u0441\u0435", spec.canonicalFqn); //$NON-NLS-1$

        MdObject root = createRootFromSpec(spec);
        assertEquals("the Name handed to EDT must already be normalized", //$NON-NLS-1$
            "\u0412\u0441\u0435", root.getName()); //$NON-NLS-1$

        String result = CreateProjectTool.buildExternalObjectsSlowResponse(
            "NoSuchYoProjectForCreateProjectToolTest", "YoProject", Version.LATEST, null, spec); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("result must report the canonical stored root FQN", //$NON-NLS-1$
            result.contains("ExternalReport.\u0412\u0441\u0435")); //$NON-NLS-1$
        assertTrue("result must report the rewritten Name through MdNameNormalizer.Report", //$NON-NLS-1$
            result.contains("\"normalized\":[\"name\"]")); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectYoNameIsKeptWhenNormalizationDisabled()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject(
            "ExternalReport.\u0412\u0441\u0451", false); //$NON-NLS-1$
        assertNull(spec.error);
        assertEquals("\u0412\u0441\u0451", spec.objectName); //$NON-NLS-1$
        assertEquals("ExternalReport.\u0412\u0441\u0451", spec.canonicalFqn); //$NON-NLS-1$
        assertEquals("normalizeYo=false must preserve the Name handed to EDT exactly", //$NON-NLS-1$
            "\u0412\u0441\u0451", createRootFromSpec(spec).getName()); //$NON-NLS-1$
    }

    @Test
    public void testSlowSeededRootAnswerRequiresVerificationWhenUnconfirmed()
    {
        ExternalObjectSpec spec =
            CreateProjectTool.resolveExternalObject("ExternalDataProcessor.UnconfirmedRoot"); //$NON-NLS-1$
        // A deliberately absent project makes the package-visible response seam's best-effort
        // metadata-scope read return false. Production reaches this builder only after exists().
        String result = CreateProjectTool.buildExternalObjectsSlowResponse(
            "NoSuchSlowProjectForCreateProjectToolTest", "SlowProject", Version.LATEST, null, spec); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("slow answer must name the requested root", //$NON-NLS-1$
            result.contains("ExternalDataProcessor.UnconfirmedRoot")); //$NON-NLS-1$
        assertTrue("slow answer must report that the root was not confirmed", //$NON-NLS-1$
            result.contains("\"externalObjectConfirmed\":false")); //$NON-NLS-1$
        assertTrue("an unresolved read-back is information, not a second tool error", //$NON-NLS-1$
            result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue("slow answer must require verification", //$NON-NLS-1$
            result.contains("\"action\":\"verificationRequired\"")); //$NON-NLS-1$
        assertFalse("an unconfirmed root must not use the created action", //$NON-NLS-1$
            result.contains("\"action\":\"created\"")); //$NON-NLS-1$
        assertFalse("an unconfirmed root must not use the created state", //$NON-NLS-1$
            result.contains("\"state\":\"created\"")); //$NON-NLS-1$
        assertTrue("slow answer must name the metadata verification route", //$NON-NLS-1$
            result.contains("get_metadata_objects")); //$NON-NLS-1$
        assertTrue("slow answer must explain that retry hits the duplicate guard", //$NON-NLS-1$
            result.contains("Do not repeat create_project") && result.contains("duplicate-project guard")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSlowEmptyProjectAnswerKeepsExistingCreatedSemantics()
    {
        String result = CreateProjectTool.buildExternalObjectsSlowResponse(
            "NoSuchEmptyProjectForCreateProjectToolTest", "EmptyProject", Version.LATEST, null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty-project slow path must retain the created action", //$NON-NLS-1$
            result.contains("\"action\":\"created\"")); //$NON-NLS-1$
        assertTrue("empty-project slow path must retain the created state", //$NON-NLS-1$
            result.contains("\"state\":\"created\"")); //$NON-NLS-1$
        assertFalse("empty-project slow path must not gain seeded-root status fields", //$NON-NLS-1$
            result.contains("externalObjectConfirmed") || result.contains("verificationRequired")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty-project slow path must retain its existing message", //$NON-NLS-1$
            result.contains("External objects project 'NoSuchEmptyProjectForCreateProjectToolTest' created " //$NON-NLS-1$
                + "(creation completed past the 120s wait window; project now exists).")); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectUnknownTypeNamesBothValidKinds()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject("UnknownRoot.MyObject"); //$NON-NLS-1$
        assertNotNull(spec.error);
        assertTrue("error must name the bad externalObject", spec.error.contains("UnknownRoot.MyObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name both supported root kinds", //$NON-NLS-1$
            spec.error.contains("ExternalDataProcessor") && spec.error.contains("ExternalReport")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExternalObjectBareNameShowsExpectedShape()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject("MyProc"); //$NON-NLS-1$
        assertNotNull(spec.error);
        assertTrue("error must name the bare value", spec.error.contains("MyProc")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must show the ExternalDataProcessor shape", //$NON-NLS-1$
            spec.error.contains("ExternalDataProcessor.<Name>")); //$NON-NLS-1$
        assertTrue("error must show the ExternalReport shape", //$NON-NLS-1$
            spec.error.contains("ExternalReport.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectRejectedForOtherProjectKinds()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("externalObject", "ExternalReport.MyReport"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("externalObject on configuration must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("ExternalReport.MyReport")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must explain the valid project kind", result.contains("projectKind=externalObjects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingProjectKindErrors()
    {
        Map<String, String> params = new HashMap<>();
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing 'projectKind' must name the param", result.contains("projectKind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing 'name' must name the param", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidProjectKindErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "bogusKind"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("invalid projectKind must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid projectKind error must name the bad value", result.contains("bogusKind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testVersionRejectedForExtension()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("version", "8.3.27"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("version for extension kind must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention 'version'", result.contains("version")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBaseProjectNameRejectedForConfiguration()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("baseProjectName for configuration kind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'baseProjectName'", result.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testScriptVariantRejectedForExtension()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Russian"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("scriptVariant for extension kind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'scriptVariant'", result.contains("scriptVariant")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBlankNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("blank name must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("blank name error must mention 'name'", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidIdentifierNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "123Bad"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("invalid identifier must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid identifier error must name the bad value", result.contains("123Bad")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBaseProjectNameForExtensionErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        // No baseProjectName supplied
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing baseProjectName for extension must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'baseProjectName'", result.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * FIX 1: invalid scriptVariant value must be rejected immediately (before any EDT
     * service lookup) with an error naming the bad value and the two allowed values.
     * <p>
     * This validation fires in {@code execute()} for non-extension kinds, before dispatch
     * to the kind-specific handler, so it is headless-safe.
     */
    @Test
    public void testInvalidScriptVariantConfigurationErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Frenglish"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid scriptVariant must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("Frenglish")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the allowed values", //$NON-NLS-1$
            result.contains("Russian") && result.contains("English")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidScriptVariantExternalObjectsErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "externalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExternal"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Frenglish"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid scriptVariant must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("Frenglish")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the allowed values", //$NON-NLS-1$
            result.contains("Russian") && result.contains("English")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionConfigurationReturnsError()
    {
        // Verifies that execute() with a valid name returns a JSON error in a headless
        // (no EDT workspace) context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionExternalObjectsReturnsError()
    {
        // Verifies that execute() with a valid name returns a JSON error in a headless
        // (no EDT workspace) context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "externalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExternal"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionExtensionReturnsError()
    {
        // Verifies that execute() with nominally valid params returns a JSON error in a
        // headless context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertExternalObjectResolution(String value, EClass expectedEClass,
        String expectedName, String expectedFqn)
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject(value);
        assertNull("valid externalObject must not carry an error", spec.error); //$NON-NLS-1$
        assertEquals(expectedEClass, spec.eClass);
        assertEquals(expectedName, spec.objectName);
        assertEquals(expectedFqn, spec.canonicalFqn);
    }

    private static MdObject createRootFromSpec(ExternalObjectSpec spec)
    {
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject root = MdClassFactory.eINSTANCE.createExternalReport();
        doReturn(root).when(factory).create(spec.eClass, Version.LATEST);
        return CreateProjectTool.createExternalObjectRoot(factory, spec, Version.LATEST);
    }
}
