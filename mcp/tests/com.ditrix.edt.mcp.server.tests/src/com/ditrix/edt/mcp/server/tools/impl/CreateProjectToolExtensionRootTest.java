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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool.CreateStatus;
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

    /** The root these tests' create built: {@link #DATA}'s name, its empty prefix, its purpose, mode 8.5.1. */
    private static final ExtensionRootBuilder.RootIdentity ROOT = new ExtensionRootBuilder.RootIdentity("MyExt", "", //$NON-NLS-1$ //$NON-NLS-2$
        ConfigurationExtensionPurpose.CUSTOMIZATION, CompatibilityMode.VERSION8_51);

    /**
     * The marks of a wizard-made root of {@link #ROOT}, as {@link ExtensionRootBuilder#missingOnDisk} looks for them: the
     * name, the block, the mapping flag, and the mode and purpose EDT writes.
     */
    private static final String WIZARD_ROOT = "<name>MyExt</name>" //$NON-NLS-1$
        + "<extension xsi:type=\"mdclassExtension:ConfigurationExtension\"></extension>" //$NON-NLS-1$
            + "<keepMappingToExtendedConfigurationObjectsByIDs>true</keepMappingToExtendedConfigurationObjectsByIDs>" //$NON-NLS-1$
            + "<configurationExtensionCompatibilityMode>8.5.1</configurationExtensionCompatibilityMode>" //$NON-NLS-1$
            + "<configurationExtensionPurpose>Customization</configurationExtensionPurpose>"; //$NON-NLS-1$

    // These looks - confirmed(), notOnDisk(), rootSeenWithout() - and every other look here with a budget of 1s or
    // less never ask the export wait they are given: such a budget is no larger than the second the look keeps for the
    // root file, and the queue then reads as PENDING, not seen draining. A test that means the wait to run gives a
    // larger budget.
    private static RootOnDisk confirmed()
    {
        return ExtensionRootBuilder.confirmOnDisk(PROJECT, ROOT, waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
            Collections.emptyList(), path -> true, 1_000L, 10L);
    }

    private static RootOnDisk notOnDisk()
    {
        return ExtensionRootBuilder.confirmOnDisk(PROJECT, ROOT, waitMs -> DiskExportState.PENDING, () -> null,
            Collections.emptyList(), path -> true, 50L, 10L);
    }

    private static ExtensionFacts facts(CompatibilityMode mode, CompatibilityMode baseMode, boolean requested)
    {
        return new ExtensionFacts(mode, baseMode, requested, null, ROOT);
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
            "compatibilityMode is above the base.", ROOT); //$NON-NLS-1$
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
        // An ended create reports the configuration name it set (PR #747 review, round 7: only the slow answer omits it).
        assertEquals("MyExt", json.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(PROJECT, json.get("project").getAsString()); //$NON-NLS-1$
        assertEquals("English", json.get("scriptVariant").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("message").getAsString().contains("adopted from the root of")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A create that had not ended attached nothing anyone checked (PR #747 review, rounds 6 and 7): the slow answer asks
     * for verification as the slow seeded create does, and reports none of the root's values as applied - not even the
     * configuration name, which outputSchema documents as the Configuration's - only what names the project.
     */
    @Test
    public void testTheSlowAnswerReportsNoneOfTheRootsValuesAndCarriesTheNotes()
    {
        ExtensionFacts facts = new ExtensionFacts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, true,
            "The requested mode is above the base's.", ROOT); //$NON-NLS-1$

        JsonObject json = JsonParser.parseString(CreateProjectTool.buildExtensionSlowResponse(DATA, facts))
            .getAsJsonObject();

        assertEquals("verificationRequired", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("rootUnconfirmed", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        for (String key : Arrays.asList("name", "prefix", "purpose", "scriptVariant", "compatibilityMode")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertFalse("a value of a root nobody checked is not reported as applied: " + key, json.has(key)); //$NON-NLS-1$
        }
        assertEquals(PROJECT, json.get("project").getAsString()); //$NON-NLS-1$
        assertEquals("extension", json.get("projectKind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(BASE, json.get("baseProject").getAsString()); //$NON-NLS-1$
        assertEquals("8.5.1", json.get("version").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("so this answer reports none of the root's values (name, prefix, " //$NON-NLS-1$
            + "purpose, scriptVariant, compatibilityMode) as applied")); //$NON-NLS-1$
        assertTrue(message, message.contains("The requested mode is above the base's.")); //$NON-NLS-1$
        assertTrue(message, message.contains("EDT's wizard also creates a default role")); //$NON-NLS-1$
    }

    // ==================== a create job still running when the wait window closed (SLOW_EXISTS) ====================

    /** What the steps after an ended create answer in these tests. */
    private static final String CONCLUDED = "{\"concluded\":true}"; //$NON-NLS-1$

    @Test
    public void testWhileEdtsCreateMayStillRunNothingIsWrittenAndTheAnswerSaysSo()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        AtomicInteger writes = new AtomicInteger();

        String answer = CreateProjectTool.afterExtensionCreate(CreateStatus.SLOW_EXISTS, null, DATA, facts, () -> {
            writes.incrementAndGet();
            return CONCLUDED;
        });

        assertEquals("no step that writes to the project may run while EDT's create may still be running", //$NON-NLS-1$
            0, writes.get());
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertEquals("verificationRequired", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("rootUnconfirmed", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("EDT's create of it was still running when the 120s wait window ended")); //$NON-NLS-1$
        assertTrue(message, message.contains("CommandInterface.cmi and MainSectionCommandInterface.cmi")); //$NON-NLS-1$
        assertTrue(message, message.contains("were not created: this call writes nothing to a project EDT may still be creating")); //$NON-NLS-1$
    }

    @Test
    public void testTheSlowAnswerSaysWhatWasNotDoneAndHowToGetIt()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);

        String message = JsonParser.parseString(CreateProjectTool.buildExtensionSlowResponse(DATA, facts))
            .getAsJsonObject().get("message").getAsString(); //$NON-NLS-1$

        assertTrue(message, message.contains("Extension project '" + PROJECT + "' exists, but EDT's create of it was " //$NON-NLS-1$ //$NON-NLS-2$
            + "still running when the 120s wait window ended")); //$NON-NLS-1$
        assertTrue(message, message.contains("this call asked that create to stop and did not wait for it")); //$NON-NLS-1$
        assertTrue("the root handed to the create is not claimed as attached: " + message, //$NON-NLS-1$
            message.contains("was handed to that create; nothing checked that EDT attached it")); //$NON-NLS-1$
        assertTrue(message, message.contains("no MCP tool attaches them to an existing extension")); //$NON-NLS-1$
        assertTrue(message, message.contains("remove the project with delete_project and deleteContent=true - without " //$NON-NLS-1$
            + "it the project's directory stays on disk and create_project refuses it - and run create_project again")); //$NON-NLS-1$
        assertFalse(message, message.contains("stay on disk under the new create")); //$NON-NLS-1$
        // The recipe first ends the create still running (PR #747 review, round 6): a delete or a second create while it
        // runs would race it.
        String wait = "Do not remove or recreate the project while that create may still be running - a delete or a " //$NON-NLS-1$
            + "second create would race it: first make sure it has ended - restarting EDT ends it; until then EDT's " //$NON-NLS-1$
            + "Progress view lists it as 'create_project: " + PROJECT + "'. Then check list_projects before using the " //$NON-NLS-1$ //$NON-NLS-2$
            + "project;"; //$NON-NLS-1$
        assertTrue(message, message.contains(wait));
        assertTrue("the wait comes before the delete: " + message, //$NON-NLS-1$
            message.indexOf(wait) < message.indexOf("delete_project")); //$NON-NLS-1$
        assertEquals("delete_project is named once, after the wait: " + message, //$NON-NLS-1$
            message.indexOf("delete_project"), message.lastIndexOf("delete_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a create that had not ended is not said to have completed: " + message, //$NON-NLS-1$
            message.contains("completed")); //$NON-NLS-1$
        assertFalse("nothing is said to be attached when nothing was written: " + message, //$NON-NLS-1$
            message.contains("were attached")); //$NON-NLS-1$
    }

    @Test
    public void testAnEndedCreateGoesOnToTheStepsThatWrite()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        AtomicInteger writes = new AtomicInteger();

        String answer = CreateProjectTool.afterExtensionCreate(CreateStatus.OK, null, DATA, facts, () -> {
            writes.incrementAndGet();
            return CONCLUDED;
        });

        assertEquals("a create that ended goes on to its steps once", 1, writes.get()); //$NON-NLS-1$
        assertEquals(CONCLUDED, answer);
    }

    @Test
    public void testACreateThatTimedOutWithNoProjectOrWasInterruptedAnswersItsErrorAndWritesNothing()
    {
        for (CreateStatus status : Arrays.asList(CreateStatus.TIMED_OUT, CreateStatus.INTERRUPTED))
        {
            ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
            AtomicInteger writes = new AtomicInteger();
            String error = "{\"error\":\"" + status + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$

            String answer = CreateProjectTool.afterExtensionCreate(status, error, DATA, facts, () -> {
                writes.incrementAndGet();
                return CONCLUDED;
            });

            assertEquals(status.toString(), 0, writes.get());
            assertEquals(status.toString(), error, answer);
        }
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
        return ExtensionRootBuilder.confirmOnDisk(PROJECT, ROOT, waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
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
        assertFalse("no look ran, so no file is said missing", message.contains("not seen on disk")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testARefusedSaveIsSaidWithTheFilesTheLookDidNotSee()
    {
        ExtensionFacts facts = startedAndOnDisk(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.commandInterfaceSaveFailure = "the services are gone"; //$NON-NLS-1$
        facts.disk = rootSeenWithout(CMI, MAIN_CMI);

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("were attached but not queued for saving: the services are gone; " //$NON-NLS-1$
            + CMI + " and " + MAIN_CMI + " were not seen on disk after 1s.")); //$NON-NLS-1$ //$NON-NLS-2$
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

        assertTrue(message, message.contains("were attached, but " + MAIN_CMI + " was not seen on disk after 1s.")); //$NON-NLS-1$ //$NON-NLS-2$
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
        facts.disk = ExtensionRootBuilder.confirmOnDisk(PROJECT, ROOT, waitMs -> DiskExportState.PENDING, () -> null,
            ExtensionRootBuilder.COMMAND_INTERFACE_FILES, path -> false, 50L, 10L);

        String message = CreateProjectTool.extensionMessage(DATA, facts);

        assertTrue(message, message.contains("not confirmed on disk")); //$NON-NLS-1$
        assertTrue(message, message.contains(CMI + " and " + MAIN_CMI + " were not seen on disk after 1s.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the root's note already advises against racing the save: " + message, 1, //$NON-NLS-1$
            message.split("which would race that save", -1).length - 1); //$NON-NLS-1$
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
    }

    // ==================== the save and the look of a created extension (saveAndLook, ExtensionDisk) ====================

    /** A disk whose root file shows the wizard's shape, recording each save and each file the look asks about. */
    private static ExtensionDisk recordingDisk(List<List<String>> saved, boolean saveAccepted, List<String> asked,
        String notOnDisk)
    {
        return new ExtensionDisk((project, fqns) -> {
            saved.add(new ArrayList<>(fqns));
            return saveAccepted;
        }, (project, waitMs) -> DiskExportState.DRAINED, () -> WIZARD_ROOT, path -> {
            asked.add(path);
            return !path.equals(notOnDisk);
        });
    }

    /**
     * A project name reused after delete_project kept its files (PR #747 review, round 6): the old Configuration.mdo
     * shows the extension block and the mapping flag, but not this create's name. saveAndLook hands the look the root this
     * call built ({@link ExtensionFacts#root}), so the old file does not make the project ready.
     */
    @Test
    public void testARootFileAnotherCreateLeftDoesNotMakeTheProjectReady()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        String stale = WIZARD_ROOT.replace("<name>MyExt</name>", "<name>OldExt</name>"); //$NON-NLS-1$ //$NON-NLS-2$
        ExtensionDisk disk = new ExtensionDisk((project, fqns) -> true, (project, waitMs) -> DiskExportState.DRAINED,
            () -> stale, path -> true);

        CreateProjectTool.saveAndLook(mock(IProject.class), CI_FQNS, facts, disk, 300L, 10L);

        assertNotNull(facts.disk);
        assertFalse(facts.disk.confirmed);
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
        String message = CreateProjectTool.extensionMessage(DATA, facts);
        assertTrue(message, message.contains("does not show name 'MyExt' (the file shows 'OldExt')")); //$NON-NLS-1$
    }

    /**
     * A root file that shows everything but the purpose this call set (PR #747 review, round 7) does not make the project
     * ready: EDT writes a purpose other than its default, so a file without it is not this create's root.
     */
    @Test
    public void testARootFileWithoutThePurposeSetDoesNotMakeTheProjectReady()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        String noPurpose = WIZARD_ROOT
            .replace("<configurationExtensionPurpose>Customization</configurationExtensionPurpose>", ""); //$NON-NLS-1$ //$NON-NLS-2$
        ExtensionDisk disk = new ExtensionDisk((project, fqns) -> true, (project, waitMs) -> DiskExportState.DRAINED,
            () -> noPurpose, path -> true);

        CreateProjectTool.saveAndLook(mock(IProject.class), CI_FQNS, facts, disk, 300L, 10L);

        assertFalse(facts.disk.confirmed);
        assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
        String message = CreateProjectTool.extensionMessage(DATA, facts);
        assertTrue(message, message.contains("configurationExtensionPurpose 'Customization'")); //$NON-NLS-1$
    }

    @Test
    public void testTheLookIsGivenTheFilesOfTheCommandInterfacesThisCallAttached()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        List<List<String>> saved = new ArrayList<>();
        List<String> asked = new ArrayList<>();

        // A budget above the second the look keeps for the root file: the export wait is asked.
        CreateProjectTool.saveAndLook(mock(IProject.class), CI_FQNS, facts, recordingDisk(saved, true, asked, null),
            5_000L, 10L);

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

    /**
     * create_project's look gives EDT's export wait its budget less the second the look keeps for the root file (#747
     * review, round 4): the root file still gets its look within the budget. saveAndLook hands the share to the wait of
     * the project it looks at - EDT's own disk binds BuildUtils' wait as it is ({@link ExtensionDisk#of}), with no
     * timeout of its own - so this pin reaches the wiring create_project runs.
     */
    @Test
    public void testTheExportWaitOfACreatedExtensionIsGivenItsBudgetLessTheSecondKeptForTheRootFile()
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        List<Long> given = new ArrayList<>();
        List<IProject> waitedFor = new ArrayList<>();
        ExtensionDisk disk = new ExtensionDisk((project, fqns) -> true, (project, waitMs) -> {
            waitedFor.add(project);
            given.add(waitMs);
            return DiskExportState.DRAINED;
        }, () -> WIZARD_ROOT, path -> true);
        IProject looked = named(PROJECT + ".Wait" + System.nanoTime()); //$NON-NLS-1$

        CreateProjectTool.saveAndLook(looked, CI_FQNS, facts, disk, 1_400L, 10L);

        assertEquals("the wait is given the budget less one second", Collections.singletonList(400L), given); //$NON-NLS-1$
        assertEquals("the wait is the looked-at project's", Collections.singletonList(looked), waitedFor); //$NON-NLS-1$
        assertTrue(facts.disk.confirmed);
        assertEquals(DiskExportState.DRAINED, facts.disk.exportState);
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
        assertTrue(message, message.contains("were attached, but " + MAIN_CMI + " was not seen on disk")); //$NON-NLS-1$ //$NON-NLS-2$
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
        }, (project, waitMs) -> {
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
        assertEquals(DiskExportState.UNOBSERVABLE, disk.exportWait.await(project, 1_000L));
    }

    // ==================== the look's reads are bounded (#747 review, round 3) ====================

    /** A project the look reads, by name: the name is the key of the claim its reads take. */
    private static IProject named(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    /** A disk whose root file the file system does not answer until {@code answers} is counted down. */
    private static ExtensionDisk hungDisk(CountDownLatch answers, AtomicInteger rootReads)
    {
        return new ExtensionDisk((project, fqns) -> true, (project, waitMs) -> DiskExportState.DRAINED, () -> {
            rootReads.incrementAndGet();
            try
            {
                answers.await(60, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return WIZARD_ROOT;
        }, path -> true);
    }

    /**
     * A created extension's save and look, on a thread of its own, waited for no longer than {@code waitMs}: a look that
     * the file system holds fails this assertion instead of hanging the run.
     */
    private static ExtensionFacts lookWithin(IProject project, ExtensionDisk disk, long budgetMs, long waitMs)
        throws InterruptedException
    {
        ExtensionFacts facts = facts(CompatibilityMode.VERSION8_51, CompatibilityMode.VERSION8_51, false);
        facts.lifecycleStarted = true;
        Thread look = new Thread(() -> CreateProjectTool.saveAndLook(project, CI_FQNS, facts, disk, budgetMs, 10L),
            "look of " + project.getName()); //$NON-NLS-1$
        look.setDaemon(true);
        look.start();
        look.join(waitMs);
        assertFalse("the look of " + project.getName() + " is still running " + waitMs + "ms after it started; its " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "budget was " + budgetMs + "ms", look.isAlive()); //$NON-NLS-1$ //$NON-NLS-2$
        return facts;
    }

    /**
     * A root read the file system never answers does not hold create_project past the look's budget (#747 review, round
     * 3): the look ends with its budget, and the answer says the root could not be read in time - not that it is missing -
     * and stays {@code created}.
     */
    @Test
    public void testALookWhoseRootReadNeverAnswersEndsWithItsBudgetNotConfirmed() throws Exception
    {
        CountDownLatch answers = new CountDownLatch(1);
        AtomicInteger rootReads = new AtomicInteger();
        try
        {
            ExtensionFacts facts = lookWithin(named(PROJECT + ".Hung" + System.nanoTime()), hungDisk(answers, rootReads), //$NON-NLS-1$
                1_500L, 6_500L);

            assertEquals("the root was read once, in a job the look then stopped waiting for", 1, rootReads.get()); //$NON-NLS-1$
            assertNotNull("the look ran", facts.disk); //$NON-NLS-1$
            assertFalse(facts.disk.confirmed);
            assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
            String message = CreateProjectTool.extensionMessage(DATA, facts);
            assertTrue(message, message.contains("Its root was not confirmed on disk: src/Configuration/Configuration.mdo " //$NON-NLS-1$
                + "could not be read in time")); //$NON-NLS-1$
            assertFalse(message, message.contains("was not found on disk")); //$NON-NLS-1$
        }
        finally
        {
            answers.countDown();
        }
    }

    /**
     * A root read that never answers after one that completed (#747 review, round 4): the root is described by the
     * completed read - the file was not on disk - and the answer names the hung read, as that description is of a read
     * made before it, not at the end of the budget. It stays {@code created}.
     */
    @Test
    public void testARootReadThatNeverAnswersAfterACompletedOneIsNamedBesideWhatThatOneFound() throws Exception
    {
        CountDownLatch answers = new CountDownLatch(1);
        AtomicInteger rootReads = new AtomicInteger();
        try
        {
            ExtensionDisk disk = new ExtensionDisk((project, fqns) -> true,
                (project, waitMs) -> DiskExportState.DRAINED, () -> {
                    if (rootReads.incrementAndGet() == 1)
                    {
                        return null; // the first read completes: no file yet
                    }
                    try
                    {
                        answers.await(60, TimeUnit.SECONDS); // a file system that does not answer the second
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    return WIZARD_ROOT;
                }, path -> true);
            ExtensionFacts facts = lookWithin(named(PROJECT + ".RootHungLater" + System.nanoTime()), disk, 1_500L, //$NON-NLS-1$
                6_500L);

            assertEquals("the completed read, then the hung one, whose claim refused every read after it", 2, //$NON-NLS-1$
                rootReads.get());
            assertFalse(facts.disk.confirmed);
            assertEquals("created", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
            String message = CreateProjectTool.extensionMessage(DATA, facts);
            assertTrue(message, message.contains("Its root was not confirmed on disk: src/Configuration/Configuration.mdo " //$NON-NLS-1$
                + "was not found on disk; a later read of it did not complete after 2s")); //$NON-NLS-1$
            assertFalse(message, message.contains("could not be read in time")); //$NON-NLS-1$
        }
        finally
        {
            answers.countDown();
        }
    }

    /**
     * A command interface file whose check never answers does not cost the root its readiness (#747 review, round 4):
     * the root's read completed, so the answer is {@code ready}, and the message names the file that was not seen - not
     * the root as one that could not be read in time. Before, the check shared the root read's job, and its hang
     * discarded that read: the answer was {@code created}.
     */
    @Test
    public void testACommandInterfaceCheckThatNeverAnswersLeavesARootThatWasReadReady() throws Exception
    {
        CountDownLatch answers = new CountDownLatch(1);
        try
        {
            ExtensionDisk disk = new ExtensionDisk((project, fqns) -> true,
                (project, waitMs) -> DiskExportState.DRAINED, () -> WIZARD_ROOT, path -> {
                    if (MAIN_CMI.equals(path))
                    {
                        try
                        {
                            answers.await(60, TimeUnit.SECONDS); // a file system that does not answer this check
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return true;
                });
            ExtensionFacts facts = lookWithin(named(PROJECT + ".CmiHung" + System.nanoTime()), disk, 1_500L, 6_500L); //$NON-NLS-1$

            assertTrue("the root's read completed: it is confirmed", facts.disk.confirmed); //$NON-NLS-1$
            assertEquals("ready", CreateProjectTool.extensionState(facts)); //$NON-NLS-1$
            assertEquals(Collections.singletonList(MAIN_CMI), facts.disk.missingFiles);
            String message = CreateProjectTool.extensionMessage(DATA, facts);
            assertTrue(message, message.contains("were attached, but " + MAIN_CMI + " was not seen on disk after 2s.")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(message, message.contains("could not be read in time")); //$NON-NLS-1$
            assertFalse(message, message.contains("not confirmed on disk")); //$NON-NLS-1$
        }
        finally
        {
            answers.countDown();
        }
    }

    /**
     * One hung read per project (#747 review, round 3): while the look's read of a project's files hangs, a second look of
     * that project starts no read - its rounds are refused at once, and its root reads as not read in time - and a look of
     * another project is not held up. The project is told apart by its name.
     */
    @Test
    public void testWhileALookOfAProjectHangsNoOtherLookOfThatProjectStartsARead() throws Exception
    {
        String name = PROJECT + ".Held" + System.nanoTime(); //$NON-NLS-1$
        CountDownLatch answers = new CountDownLatch(1);
        try
        {
            lookWithin(named(name), hungDisk(answers, new AtomicInteger()), 1_500L, 6_500L);

            AtomicInteger reads = new AtomicInteger();
            ExtensionDisk counting = new ExtensionDisk((project, fqns) -> true,
                (project, waitMs) -> DiskExportState.DRAINED, () -> {
                    reads.incrementAndGet();
                    return WIZARD_ROOT;
                }, path -> true);
            ExtensionFacts second = lookWithin(named(name), counting, 300L, 5_300L);
            assertEquals("no read of the project's files started while its first one hangs", 0, reads.get()); //$NON-NLS-1$
            assertFalse(second.disk.confirmed);
            assertTrue(second.disk.describe(), second.disk.describe().contains("could not be read in time")); //$NON-NLS-1$

            ExtensionFacts other = lookWithin(named(name + ".Other"), counting, 1_000L, 6_000L); //$NON-NLS-1$
            assertTrue("another project's look is not held up", other.disk.confirmed); //$NON-NLS-1$
            assertEquals(1, reads.get());
        }
        finally
        {
            answers.countDown();
        }
    }
}
