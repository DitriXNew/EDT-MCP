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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool.ExtensionDisk;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool.ExtensionFacts;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool.ExtensionResponseData;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.ditrix.edt.mcp.server.utils.ExtensionRootBuilder;
import com.ditrix.edt.mcp.server.utils.ExtensionRootBuilder.RootOnDisk;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pins what {@link CreateProjectTool} reports for a new extension (issue #706): {@code state:"ready"}
 * only once EDT reported the context started AND the root file showed the wizard's shape on disk, and a
 * {@code message} that says how the root was built, what EDT's wizard does that this tool does not, and
 * what was not seen when the answer is {@code created}.
 */
public class CreateProjectToolExtensionRootTest
{
    private static final String PROJECT = "TestConfiguration.MyExt"; //$NON-NLS-1$
    private static final String BASE = "TestConfiguration"; //$NON-NLS-1$

    private static final ExtensionResponseData DATA = new ExtensionResponseData(PROJECT, "MyExt", BASE, "", //$NON-NLS-1$ //$NON-NLS-2$
        ConfigurationExtensionPurpose.CUSTOMIZATION, ScriptVariant.ENGLISH, Version.V8_5_1, true);

    /** The marks of a wizard-made root, as {@link ExtensionRootBuilder#missingOnDisk} looks for them. */
    private static final String WIZARD_ROOT =
        "<extension xsi:type=\"mdclassExtension:ConfigurationExtension\"></extension>" //$NON-NLS-1$
            + "<keepMappingToExtendedConfigurationObjectsByIDs>true</keepMappingToExtendedConfigurationObjectsByIDs>"; //$NON-NLS-1$

    private static RootOnDisk confirmed()
    {
        return ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED, () -> WIZARD_ROOT, 1_000L, 10L);
    }

    private static RootOnDisk notOnDisk()
    {
        return ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.PENDING, () -> null, 50L, 10L);
    }

    private static ExtensionFacts facts(CompatibilityMode mode, CompatibilityMode baseMode, boolean requested)
    {
        return new ExtensionFacts(mode, baseMode, requested, null);
    }

    private static ExtensionFacts startedAndOnDisk(CompatibilityMode mode, CompatibilityMode baseMode,
        boolean requested)
    {
        ExtensionFacts facts = facts(mode, baseMode, requested);
        facts.lifecycleStarted = true;
        facts.disk = confirmed();
        return facts;
    }

    // ==================== state ====================

    @Test
    public void testReadyNeedsTheStartedContextAndTheRootOnDisk()
    {
        assertEquals("ready", CreateProjectTool.extensionState( //$NON-NLS-1$
            startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false)));
    }

    @Test
    public void testAnUnconfirmedRootIsNeverReady()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        facts.disk = notOnDisk();
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    @Test
    public void testAnUncheckedRootIsNeverReady()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        facts.disk = null;
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    @Test
    public void testAContextNotReportedStartedIsNeverReady()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = false;
        facts.disk = confirmed();
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    // ==================== message ====================

    @Test
    public void testAReadyAnswerSaysTheRootWasAdoptedFromTheBaseLikeTheWizard()
    {
        String message = CreateProjectTool.extensionMessage(DATA,
            startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false));

        assertTrue(message, message.startsWith("Extension project '" + PROJECT + "' created and bound to '" //$NON-NLS-1$ //$NON-NLS-2$
            + BASE + "'.")); //$NON-NLS-1$
        assertTrue(message, message.contains("adopted from the root of '" + BASE + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("new-extension wizard")); //$NON-NLS-1$
        assertTrue(message, message.contains("by IDs")); //$NON-NLS-1$
        assertTrue(message, message.contains("Version8_5_1 (the base configuration's)")); //$NON-NLS-1$
        assertFalse("a confirmed root carries no 'not confirmed' note", message.contains("not confirmed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("above 8.3.14 the by-ID mapping is promised as such", //$NON-NLS-1$
            message.contains("it keeps mapping adopted objects to the base by IDs")); //$NON-NLS-1$
        assertFalse("above 8.3.14 nothing is mapped by name", message.contains("by name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAtPlatform8314OrLowerTheMessageDoesNotPromiseTheByIdLink()
    {
        // EDT's copier links an adopted object by ID only when V8_3_14.isLessThan(version), whatever the root's
        // flag, and the extension's version is its base's: the flag is set, but adoptions map by name.
        ExtensionResponseData old = new ExtensionResponseData(PROJECT, "MyExt", BASE, "", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.CUSTOMIZATION, ScriptVariant.ENGLISH, Version.V8_3_14, true);

        String message = CreateProjectTool.extensionMessage(old,
            startedAndOnDisk(CompatibilityMode.VERSION8_314, CompatibilityMode.VERSION8_314, false));

        assertFalse(message, message.contains("it keeps mapping adopted objects to the base by IDs")); //$NON-NLS-1$
        assertTrue(message, message.contains("at platform version 8.3.14 EDT maps them by name")); //$NON-NLS-1$
        assertTrue(message, message.contains("by ID only above 8.3.14")); //$NON-NLS-1$
    }

    @Test
    public void testARequestedModeIsNamedWithTheBasesBesideIt()
    {
        String message = CreateProjectTool.extensionMessage(DATA,
            startedAndOnDisk(CompatibilityMode.VERSION8_327, CompatibilityMode.VERSION8_51, true));

        assertTrue(message, message.contains("Version8_3_27 (requested; the base configuration's is Version8_5_1)")); //$NON-NLS-1$
    }

    @Test
    public void testAboveTheThresholdTheMessageSaysTheWizardsDefaultRoleIsNotCreated()
    {
        String message = CreateProjectTool.extensionMessage(DATA,
            startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false));

        assertTrue(message, message.contains("EDT's wizard also creates a default role")); //$NON-NLS-1$
        assertTrue(message, message.contains("this tool does not")); //$NON-NLS-1$
        assertTrue(message, message.contains("defaultRoles")); //$NON-NLS-1$
        assertTrue(message, message.contains("add it in EDT")); //$NON-NLS-1$
    }

    @Test
    public void testAt8313OrLowerTheWizardCreatesNoDefaultRoleSoNothingIsSaid()
    {
        assertNull(CreateProjectTool.defaultRoleNote(CompatibilityMode.VERSION8_313, CompatibilityMode.VERSION8_313));
        String message = CreateProjectTool.extensionMessage(DATA,
            startedAndOnDisk(CompatibilityMode.VERSION8_313, CompatibilityMode.VERSION8_313, false));
        assertFalse(message, message.contains("default role")); //$NON-NLS-1$
    }

    @Test
    public void testTheDefaultRoleNoteNeedsTheBaseModeAndTheEffectiveModeAbove8313()
    {
        assertNotNull(CreateProjectTool.defaultRoleNote(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51));
        assertNotNull(CreateProjectTool.defaultRoleNote(CompatibilityMode.VERSION8_314, CompatibilityMode.VERSION8_327));
        assertNull("the wizard decides on the base's mode", //$NON-NLS-1$
            CreateProjectTool.defaultRoleNote(CompatibilityMode.VERSION8_313, CompatibilityMode.VERSION8_327));
        assertNull(CreateProjectTool.defaultRoleNote(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_313));
        assertNull("an unknown base mode claims nothing", //$NON-NLS-1$
            CreateProjectTool.defaultRoleNote(null, CompatibilityMode.VERSION8_51));
        assertNull(CreateProjectTool.defaultRoleNote(CompatibilityMode.VERSION8_51, null));
    }

    @Test
    public void testOverABaseAt8313OrLowerARequestedHigherModeSaysNothingOfTheWizardsDefaultRole()
    {
        // EDT's wizard decides on its root's extension mode, which is always the BASE's: over a base at 8.3.13 or
        // lower it creates no default role, whatever mode this tool was asked for.
        String message = CreateProjectTool.extensionMessage(DATA,
            startedAndOnDisk(CompatibilityMode.VERSION8_327, CompatibilityMode.VERSION8_313, true));

        assertFalse(message, message.contains("default role")); //$NON-NLS-1$
    }

    @Test
    public void testOverABaseAbove8313ARequestedHigherModeKeepsTheDefaultRoleNote()
    {
        String message = CreateProjectTool.extensionMessage(DATA,
            startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_327, true));

        assertTrue(message, message.contains("EDT's wizard also creates a default role")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnconfirmedRootSaysWhatWasNotSeenAndHowToAvoidTheRace()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        facts.disk = notOnDisk();

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("not confirmed on disk")); //$NON-NLS-1$
        assertTrue(message, message.contains("src/Configuration/Configuration.mdo")); //$NON-NLS-1$
        assertTrue(message, message.contains("PENDING")); //$NON-NLS-1$
        assertTrue(message, message.contains("clean_project")); //$NON-NLS-1$
    }

    @Test
    public void testAFailedCommandInterfaceStepIsReportedNotHidden()
    {
        ExtensionFacts facts = startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.commandInterfaceFailure = "the model is closing"; //$NON-NLS-1$

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("CommandInterface.cmi")); //$NON-NLS-1$
        assertTrue(message, message.contains("MainSectionCommandInterface.cmi")); //$NON-NLS-1$
        assertTrue(message, message.contains("were not created: the model is closing")); //$NON-NLS-1$
        assertEquals("a failed command interface step does not make the root unready", //$NON-NLS-1$
            "ready", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    @Test
    public void testACompatibilityWarningIsCarriedIntoTheMessage()
    {
        ExtensionFacts facts = new ExtensionFacts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_327, true,
            "compatibilityMode is above the base."); //$NON-NLS-1$
        facts.lifecycleStarted = true;
        facts.disk = confirmed();

        assertTrue(CreateProjectTool.extensionMessage(DATA, facts).contains("compatibilityMode is above the base.")); //$NON-NLS-1$
    }

    @Test
    public void testAContextNotReportedStartedIsSaid()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = false;

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("did not report")); //$NON-NLS-1$
        assertTrue(message, message.contains("list_projects")); //$NON-NLS-1$
        // 'Not started' also covers a project with no DT project yet and a wait that threw - no 60 s wait ran
        // then, so the answer names no duration.
        assertFalse(message, message.contains("within 60s")); //$NON-NLS-1$
    }

    // ==================== response ====================

    @Test
    public void testTheResponseEchoesTheEffectiveCompatibilityModeByItsEnumName()
    {
        ExtensionFacts facts = startedAndOnDisk(CompatibilityMode.VERSION8_327, CompatibilityMode.VERSION8_51, true);

        JsonObject json = JsonParser.parseString(
            CreateProjectTool.buildExtensionSuccessResponse(DATA, facts, Collections.emptyMap())).getAsJsonObject();

        assertEquals("Version8_3_27", json.get("compatibilityMode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ready", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(BASE, json.get("baseProject").getAsString()); //$NON-NLS-1$
        assertEquals("English", json.get("scriptVariant").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("message").getAsString().contains("adopted from the root of")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheSlowAnswerEchoesTheModeAndCarriesTheNotes()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.commandInterfaceFailure = "the extension's root was not in its model yet"; //$NON-NLS-1$

        JsonObject json = JsonParser.parseString(CreateProjectTool.buildExtensionSlowResponse(DATA, facts))
            .getAsJsonObject();

        assertEquals("created", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Version8_5_1", json.get("compatibilityMode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("creation completed past the 120s wait window")); //$NON-NLS-1$
        assertTrue(message, message.contains("were not created: the extension's root was not in its model yet")); //$NON-NLS-1$
        assertTrue(message, message.contains("EDT's wizard also creates a default role")); //$NON-NLS-1$
    }

    // ==================== the command interfaces' save: queued, and seen on disk ====================

    private static final List<String> CI_FQNS =
        Arrays.asList("Configuration.CommandInterface", "Configuration.MainSectionCommandInterface"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String CMI = "src/Configuration/CommandInterface.cmi"; //$NON-NLS-1$
    private static final String MAIN_CMI = "src/Configuration/MainSectionCommandInterface.cmi"; //$NON-NLS-1$

    /** A look that confirmed the root and saw only the command interface files not in {@code notOnDisk}. */
    private static RootOnDisk rootSeenWithout(String... notOnDisk)
    {
        List<String> missing = Arrays.asList(notOnDisk);
        return ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
            ExtensionRootBuilder.COMMAND_INTERFACE_FILES, path -> !missing.contains(path), 50L, 10L);
    }

    @Test
    public void testARefusedSaveOfTheAttachedCommandInterfacesIsRecorded()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        List<List<String>> submitted = new ArrayList<>();

        CreateProjectTool.exportWithRoot(mock(IProject.class), CI_FQNS, facts, (project, fqns) -> {
            submitted.add(new ArrayList<>(fqns));
            return false;
        });

        assertEquals("the root and both command interfaces go in one submission", //$NON-NLS-1$
            Collections.singletonList(Arrays.asList("Configuration", "Configuration.CommandInterface", //$NON-NLS-1$ //$NON-NLS-2$
                "Configuration.MainSectionCommandInterface")), submitted); //$NON-NLS-1$
        assertNotNull("a refused submission is not swallowed", facts.commandInterfaceSaveFailure); //$NON-NLS-1$
        assertTrue(facts.commandInterfaceSaveFailure, facts.commandInterfaceSaveFailure.contains("EDT accepted no save task")); //$NON-NLS-1$
    }

    @Test
    public void testASaveSubmissionThatThrowsIsRecordedWithItsReasonNeverThrown()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);

        CreateProjectTool.exportWithRoot(mock(IProject.class), CI_FQNS, facts, (project, fqns) -> {
            throw new IllegalStateException("the export services are shutting down"); //$NON-NLS-1$
        });

        assertEquals("the export services are shutting down", facts.commandInterfaceSaveFailure); //$NON-NLS-1$
    }

    @Test
    public void testAnAcceptedSaveRecordsNothing()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);

        CreateProjectTool.exportWithRoot(mock(IProject.class), CI_FQNS, facts, (project, fqns) -> true);

        assertNull(facts.commandInterfaceSaveFailure);
    }

    @Test
    public void testARefusedSaveOfTheRootAloneRecordsNothingForTheCommandInterfaces()
    {
        // No command interface was attached (a failure the answer names already): the root's own file is judged on
        // disk, so a refused submission of the root alone adds nothing here.
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        List<List<String>> submitted = new ArrayList<>();

        CreateProjectTool.exportWithRoot(mock(IProject.class), Collections.emptyList(), facts, (project, fqns) -> {
            submitted.add(new ArrayList<>(fqns));
            return false;
        });

        assertEquals(Collections.singletonList(Collections.singletonList("Configuration")), submitted); //$NON-NLS-1$
        assertNull(facts.commandInterfaceSaveFailure);
    }

    @Test
    public void testTheFilesLookedForAreTheCommandInterfacesThisCallAttached()
    {
        assertEquals(Arrays.asList(CMI, MAIN_CMI), CreateProjectTool.commandInterfaceFiles(CI_FQNS));
        assertEquals("none attached, none looked for", Collections.emptyList(), //$NON-NLS-1$
            CreateProjectTool.commandInterfaceFiles(Collections.emptyList()));
    }

    @Test
    public void testARefusedSaveIsSaidWhenTheFilesWereNotLookedFor()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = false;
        CreateProjectTool.exportWithRoot(mock(IProject.class), CI_FQNS, facts, (project, fqns) -> false);

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("CommandInterface.cmi and MainSectionCommandInterface.cmi")); //$NON-NLS-1$
        assertTrue(message, message.contains("were attached but not queued for saving: EDT accepted no save task")); //$NON-NLS-1$
        assertFalse("no look ran, so no file is said missing", message.contains("not found on disk")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testARefusedSaveIsSaidWithTheFilesTheLookDidNotSee()
    {
        ExtensionFacts facts = startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.commandInterfaceSaveFailure = "the services are gone"; //$NON-NLS-1$
        facts.disk = rootSeenWithout(CMI, MAIN_CMI);

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("were attached but not queued for saving: the services are gone; " //$NON-NLS-1$
            + CMI + " and " + MAIN_CMI + " were not found on disk after 1s.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("EDT may still be writing them")); //$NON-NLS-1$
        assertEquals("the files never change the root's state", "ready", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFilesTheLookSawOutweighARefusedSubmission()
    {
        // EDT also saves a committed change on its own: once both files are seen on disk, a refused submission of
        // this call changed nothing that matters, and the answer says nothing of it.
        ExtensionFacts facts = startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.commandInterfaceSaveFailure = "the services are gone"; //$NON-NLS-1$
        facts.disk = rootSeenWithout();

        assertNull(CreateProjectTool.commandInterfaceNote(facts));
        assertFalse(CreateProjectTool.extensionMessage(DATA, facts).contains("CommandInterface.cmi")); //$NON-NLS-1$
    }

    @Test
    public void testACommandInterfaceFileTheLookDidNotSeeIsNamedButTheRootStaysReady()
    {
        ExtensionFacts facts = startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.disk = rootSeenWithout(MAIN_CMI);

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("were attached, but " + MAIN_CMI + " was not found on disk after 1s.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("EDT may still be writing it; wait a few seconds before restarting EDT")); //$NON-NLS-1$
        assertFalse("the file that WAS seen is not named", message.contains(CMI + " ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a queued save is no refusal", message.contains("not queued for saving")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the root was seen, so the answer stays ready", "ready", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testWithTheRootUnconfirmedTooTheRaceAdviceIsGivenOnce()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        facts.disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.PENDING, () -> null,
            ExtensionRootBuilder.COMMAND_INTERFACE_FILES, path -> false, 50L, 10L);

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("not confirmed on disk")); //$NON-NLS-1$
        assertTrue(message, message.contains(CMI + " and " + MAIN_CMI + " were not found on disk after 1s.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the root's note already advises against racing the save: " + message, 1, //$NON-NLS-1$
            message.split("which would race that save", -1).length - 1); //$NON-NLS-1$
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    @Test
    public void testTheSlowAnswerCarriesARefusedSave()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        CreateProjectTool.exportWithRoot(mock(IProject.class), CI_FQNS, facts, (project, fqns) -> false);

        String message = JsonParser.parseString(CreateProjectTool.buildExtensionSlowResponse(DATA, facts))
            .getAsJsonObject().get("message").getAsString(); //$NON-NLS-1$

        assertTrue(message, message.contains("were attached but not queued for saving")); //$NON-NLS-1$
    }

    // ==================== the save and the look of a created extension (saveAndLook, ExtensionDisk) ====================

    /** A disk whose root file shows the wizard's shape, recording each save and each file the look asks about. */
    private static ExtensionDisk recordingDisk(List<List<String>> saved, boolean saveAccepted, List<String> asked,
        String notOnDisk)
    {
        return new ExtensionDisk((project, fqns) -> {
            saved.add(new ArrayList<>(fqns));
            return saveAccepted;
        }, () -> DiskExportState.DRAINED, () -> WIZARD_ROOT, path -> {
            asked.add(path);
            return !path.equals(notOnDisk);
        });
    }

    @Test
    public void testTheLookIsGivenTheFilesOfTheCommandInterfacesThisCallAttached()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        List<List<String>> saved = new ArrayList<>();
        List<String> asked = new ArrayList<>();

        CreateProjectTool.saveAndLook(mock(IProject.class), CI_FQNS, facts, recordingDisk(saved, true, asked, null),
            1_000L, 10L);

        assertEquals("the root and both command interfaces go in one save", //$NON-NLS-1$
            Collections.singletonList(Arrays.asList("Configuration", "Configuration.CommandInterface", //$NON-NLS-1$ //$NON-NLS-2$
                "Configuration.MainSectionCommandInterface")), saved); //$NON-NLS-1$
        assertEquals("the look asks for exactly the two command interface files, once each when both are there", //$NON-NLS-1$
            Arrays.asList(CMI, MAIN_CMI), asked);
        assertNotNull("the context started, so the look ran", facts.disk); //$NON-NLS-1$
        assertTrue(facts.disk.confirmed);
        assertEquals(DiskExportState.DRAINED, facts.disk.exportState);
        assertEquals(Collections.emptyList(), facts.disk.missingFiles);
        assertNull("an accepted save records nothing", facts.commandInterfaceSaveFailure); //$NON-NLS-1$
        assertEquals("ready", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    @Test
    public void testAFileTheDiskDoesNotHoldIsNamedButLeavesTheRootReady()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;

        CreateProjectTool.saveAndLook(mock(IProject.class), CI_FQNS, facts,
            recordingDisk(new ArrayList<>(), true, new ArrayList<>(), MAIN_CMI), 50L, 10L);

        assertTrue(facts.disk.confirmed);
        assertEquals(Collections.singletonList(MAIN_CMI), facts.disk.missingFiles);
        assertEquals("ready", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
        String message = CreateProjectTool.extensionMessage(DATA, facts);
        assertTrue(message, message.contains("were attached, but " + MAIN_CMI + " was not found on disk")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoFileIsLookedForWhenNoCommandInterfaceWasAttached()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        List<List<String>> saved = new ArrayList<>();
        List<String> asked = new ArrayList<>();

        CreateProjectTool.saveAndLook(mock(IProject.class), Collections.emptyList(), facts,
            recordingDisk(saved, true, asked, null), 1_000L, 10L);

        assertEquals("the root alone is saved", //$NON-NLS-1$
            Collections.singletonList(Collections.singletonList("Configuration")), saved); //$NON-NLS-1$
        assertEquals("nothing attached, nothing looked for", Collections.emptyList(), asked); //$NON-NLS-1$
        assertTrue(facts.disk.confirmed);
    }

    @Test
    public void testNothingIsLookedAtBeforeEdtReportedTheContextStartedButTheRefusedSaveIsKept()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = false;
        List<List<String>> saved = new ArrayList<>();
        ExtensionDisk disk = new ExtensionDisk((project, fqns) -> {
            saved.add(new ArrayList<>(fqns));
            return false;
        }, () -> {
            throw new AssertionError("no export wait before the context started"); //$NON-NLS-1$
        }, () -> {
            throw new AssertionError("no root read before the context started"); //$NON-NLS-1$
        }, path -> {
            throw new AssertionError("no file check before the context started"); //$NON-NLS-1$
        });

        CreateProjectTool.saveAndLook(mock(IProject.class), CI_FQNS, facts, disk, 1_000L, 10L);

        assertEquals("the save is queued all the same", 1, saved.size()); //$NON-NLS-1$
        assertNull("no look ran", facts.disk); //$NON-NLS-1$
        assertNotNull("the disk's refusal is the command interfaces' recorded failure", //$NON-NLS-1$
            facts.commandInterfaceSaveFailure);
        assertTrue(facts.commandInterfaceSaveFailure,
            facts.commandInterfaceSaveFailure.contains("EDT accepted no save task")); //$NON-NLS-1$
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    @Test
    public void testEdtsDiskOfAProjectLooksAtItsLocation() throws IOException
    {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("cpt706disk"); //$NON-NLS-1$
        try
        {
            IProject project = mock(IProject.class);
            when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path(dir.toString()));
            ExtensionDisk disk = ExtensionDisk.of(project);
            assertFalse("no command interface file there yet", disk.fileExists.test(CMI)); //$NON-NLS-1$
            assertNull("no root file there yet", disk.rootFile.read()); //$NON-NLS-1$

            java.nio.file.Path config = dir.resolve("src").resolve("Configuration"); //$NON-NLS-1$ //$NON-NLS-2$
            java.nio.file.Files.createDirectories(config);
            java.nio.file.Files.write(config.resolve("CommandInterface.cmi"), //$NON-NLS-1$
                "<cmi:CommandInterface/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            java.nio.file.Files.write(config.resolve("Configuration.mdo"), //$NON-NLS-1$
                WIZARD_ROOT.getBytes(StandardCharsets.UTF_8));

            assertTrue(disk.fileExists.test(CMI));
            assertFalse(disk.fileExists.test(MAIN_CMI));
            assertEquals(WIZARD_ROOT, disk.rootFile.read());
        }
        finally
        {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void testEdtsDiskHandsTheSaveAndTheExportWaitToEdt()
    {
        // Headless no EDT service holds a DtProject for this project: EDT's own save refuses and its export queue
        // cannot be observed. A disk that answered on its own - an accepted save, a drained queue - would show here.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(PROJECT);
        ExtensionDisk disk = ExtensionDisk.of(project);

        assertFalse("EDT accepted no save task", disk.save.submit(project, Collections.singletonList("Configuration"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DiskExportState.UNOBSERVABLE, disk.exportWait.get());
    }
}
