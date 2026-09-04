/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link LaunchOverrides} — the per-launch {@code /C} startup option and external
 * data processor / report of {@code launch} (issue #344).
 *
 * <p>Everything asserted here is reachable headlessly: the emptiness contract, the two refusals
 * that precede any model access, and — the one that matters most — that applying an override
 * stamps a WORKING COPY and never saves it. Resolving the external object itself needs a live
 * workspace and is covered by {@code test_launch.py}.</p>
 */
public class LaunchOverridesTest
{
    private static final String STARTUP = "xddRun Loader C:/tests; xddShutdown;"; //$NON-NLS-1$

    @Test
    public void testAbsentOverridesAreEmpty()
    {
        assertTrue(LaunchOverrides.of(null, null, null).isEmpty());
    }

    @Test
    public void testBlankOverridesAreEmpty()
    {
        // Whitespace is not an override: a client that sends "" for an untouched field must get
        // the plain launch, not a working copy stamped with an empty /C.
        assertTrue(LaunchOverrides.of("   ", "", " ").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(LaunchOverrides.blank(" ")); //$NON-NLS-1$
        assertFalse(LaunchOverrides.blank("x")); //$NON-NLS-1$
    }

    @Test
    public void testAnyOverrideIsNotEmpty()
    {
        assertFalse(LaunchOverrides.of(STARTUP, null, null).isEmpty());
        assertFalse(LaunchOverrides.of(null, "ExternalObjects", null).isEmpty()); //$NON-NLS-1$
        assertFalse(LaunchOverrides.of(null, null, "Runner").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyOverridesLaunchTheSavedConfigurationUntouched() throws Exception
    {
        // The no-override path must not even create a working copy: an ordinary launch
        // keeps launching exactly the object it launched before this feature existed.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        LaunchOverrides.Applied applied = LaunchOverrides.of(null, null, null).prepare().applyTo(config, false);
        assertNull(applied.errorJson);
        assertSame(config, applied.config);
        verify(config, never()).getWorkingCopy();
    }

    @Test
    public void testStartupOptionStampsAWorkingCopyAndNeverSavesIt() throws Exception
    {
        // The acceptance criterion of issue #344 that a review cannot check by reading: the
        // caller's saved EDT launch configuration must come out of this untouched. A working
        // copy IS an ILaunchConfiguration, so it can be launched without doSave() — and doSave()
        // is what would rewrite the user's configuration with one call's arguments.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        ILaunchConfigurationWorkingCopy workingCopy = mock(ILaunchConfigurationWorkingCopy.class);
        when(config.getWorkingCopy()).thenReturn(workingCopy);

        LaunchOverrides.Applied applied =
            LaunchOverrides.of(STARTUP, null, null).prepare().applyTo(config, false);

        assertNull(applied.errorJson);
        assertSame(workingCopy, applied.config);
        verify(workingCopy).setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, STARTUP);
        verify(workingCopy, never()).doSave();
        // The external-object attributes are NOT stamped when no object was asked for: leaving a
        // stale trio behind would make the delegate try to run something the caller never named.
        verify(workingCopy, never()).setAttribute(
            eqAttr(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME), anyString());
    }

    @Test
    public void testAttachConfigurationIsRefusedRatherThanSilentlyIgnored()
    {
        // Only the runtime-client delegate reads these attributes. Storing them on an Attach
        // config would launch happily and run nothing — the exact silent success this guards.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("Attach to 1C:Enterprise Debug Server"); //$NON-NLS-1$

        LaunchOverrides.Applied applied =
            LaunchOverrides.of(STARTUP, null, null).prepare().applyTo(config, true);

        assertNotNull("an Attach config must be refused, not stamped", applied.errorJson);
        assertNull(applied.config);
        JsonObject json = JsonParser.parseString(applied.errorJson).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        String message = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("the refusal must name the configuration kind: " + message,
            message.contains("Attach")); //$NON-NLS-1$
        assertTrue("the refusal must name the configuration: " + message,
            message.contains("Attach to 1C:Enterprise Debug Server")); //$NON-NLS-1$
    }

    @Test
    public void testTheAttachRefusalIsAskableBeforeAnythingIsLaunched()
    {
        // The by-name launch path can TERMINATE a live client session on its way to the launch
        // (restartIfRunning=true). Asking this only where the overrides are stamped meant a
        // request that was going to be refused anyway could cost somebody their session first.
        // So the refusal has to be answerable from the configuration alone.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("Attach to 1C:Enterprise Debug Server"); //$NON-NLS-1$

        LaunchOverrides.Prepared prepared =
            LaunchOverrides.of(STARTUP, null, null).prepare();
        String refusal = prepared.attachRefusalOrNull(config, true);
        assertNotNull("an Attach configuration carrying overrides must be refusable early",
            refusal);
        assertTrue(messageOf(refusal).contains("Attach")); //$NON-NLS-1$

        // ...and it says nothing about a runtime client, nor about a launch with no overrides.
        assertNull(prepared.attachRefusalOrNull(config, false));
        assertNull(LaunchOverrides.of(null, null, null).prepare().attachRefusalOrNull(config, true));
    }

    @Test
    public void testHalfAnExternalObjectAddressNamesTheMissingHalf()
    {
        // Refused by prepare() alone - no launch configuration is involved, which is the point:
        // the caller learns about the typo before anything is resolved, terminated or updated.
        String projectOnly = LaunchOverrides.of(null, "ExternalObjects", null).prepare().errorJson; //$NON-NLS-1$
        assertNotNull(projectOnly);
        assertTrue(messageOf(projectOnly).contains("externalObjectName is missing")); //$NON-NLS-1$

        String objectOnly = LaunchOverrides.of(null, null, "Runner").prepare().errorJson; //$NON-NLS-1$
        assertNotNull(objectOnly);
        assertTrue(messageOf(objectOnly).contains("externalObjectProjectName is missing")); //$NON-NLS-1$
    }

    @Test
    public void testAWellFormedRequestWithNoExternalObjectPreparesCleanly()
    {
        // A /C-only call must not be dragged through external-object resolution (which needs a
        // live workspace): prepare() has nothing to check and says so.
        assertNull(LaunchOverrides.of(STARTUP, null, null).prepare().errorJson);
        assertNull(LaunchOverrides.of(null, null, null).prepare().errorJson);
    }

    @Test
    public void testAccessorsRoundTripTheValues()
    {
        LaunchOverrides overrides = LaunchOverrides.of(STARTUP, "ExtObjects", "Runner"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(STARTUP, overrides.startupOption());
        assertEquals("ExtObjects", overrides.externalObjectProjectName()); //$NON-NLS-1$
        assertEquals("Runner", overrides.externalObjectName()); //$NON-NLS-1$
    }

    @Test
    public void testAResolvedObjectStampsAllThreeAttributesWithTheTypeEdtCompares() throws Exception
    {
        // What the launch delegate reads back. The TYPE is the one a caller is never asked for:
        // EDT spells it as externalObject.getClass().getName() - the EMF IMPLEMENTATION class, not
        // the EClass name - and re-resolves the object by string-comparing it, so producing it any
        // other way would resolve nothing and the session would run without the processor.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        ILaunchConfigurationWorkingCopy workingCopy = mock(ILaunchConfigurationWorkingCopy.class);
        when(config.getWorkingCopy()).thenReturn(workingCopy);
        MdObject object = mock(MdObject.class);
        // Stubbed, because the stamped name comes from the OBJECT, not from the request - see
        // testAQualifiedRequestStampsTheResolvedBareName for why that distinction is load-bearing.
        when(object.getName()).thenReturn("Runner"); //$NON-NLS-1$

        LaunchOverrides overrides = LaunchOverrides.of(STARTUP, "ExtObjects", "Runner"); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchOverrides.Applied applied =
            new LaunchOverrides.Prepared(overrides, object, null).applyTo(config, false);

        assertNull(applied.errorJson);
        assertSame(workingCopy, applied.config);
        verify(workingCopy).setAttribute(
            LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_PROJECT_NAME, "ExtObjects"); //$NON-NLS-1$
        verify(workingCopy).setAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME, "Runner"); //$NON-NLS-1$
        verify(workingCopy).setAttribute(
            LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_TYPE, object.getClass().getName());
        verify(workingCopy).setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, STARTUP);
        verify(workingCopy, never()).doSave();
    }

    @Test
    public void testAQualifiedRequestStampsTheResolvedBareName() throws Exception
    {
        // The address the CALLER types is not the address EDT reads back. A qualified name
        // (ExternalDataProcessor.Runner) is how a caller disambiguates a processor from a
        // same-named report - and the delegate re-resolves by comparing the attribute with
        // getName(), so stamping the qualified string verbatim matches nothing. Matching nothing
        // is not an error there: the session starts without /Execute. That would have made the
        // one documented remedy for an ambiguous name the one address guaranteed not to work.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        ILaunchConfigurationWorkingCopy workingCopy = mock(ILaunchConfigurationWorkingCopy.class);
        when(config.getWorkingCopy()).thenReturn(workingCopy);
        MdObject object = mock(MdObject.class);
        when(object.getName()).thenReturn("Runner"); //$NON-NLS-1$

        LaunchOverrides overrides =
            LaunchOverrides.of(null, "ExtObjects", "ExternalDataProcessor.Runner"); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchOverrides.Prepared prepared = new LaunchOverrides.Prepared(overrides, object, null);
        assertNull(prepared.applyTo(config, false).errorJson);

        verify(workingCopy).setAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME, "Runner"); //$NON-NLS-1$
        verify(workingCopy, never()).setAttribute(
            LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME, "ExternalDataProcessor.Runner"); //$NON-NLS-1$
        // ...and the response must name what is RUNNING, which is the resolved name too.
        assertEquals("Runner", prepared.resolvedExternalObjectName()); //$NON-NLS-1$
    }

    @Test
    public void testDumpGenerationSwitchedOffIsARefusalNotALaunch()
    {
        // THE trap this whole feature is built around. EDT's getDump returns null when the
        // project preference is off; its delegate then logs one line and starts the session with
        // no /Execute - a launch that reports success and runs nothing. So a disabled project must
        // come back as a refusal that says what to switch on.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("ExtObjects"); //$NON-NLS-1$
        IExternalObjectDumpSupport support = mock(IExternalObjectDumpSupport.class);
        when(support.isEnabled(project)).thenReturn(false);

        String refusal = LaunchOverrides.dumpRefusalOrNull(support, project);

        assertNotNull("a disabled dump must refuse, not launch", refusal);
        assertTrue("the refusal must name the project: " + refusal,
            refusal.contains("ExtObjects")); //$NON-NLS-1$
        assertTrue("the refusal must say the setting is off: " + refusal,
            refusal.contains("switched OFF")); //$NON-NLS-1$
        assertTrue("the refusal must warn that build_external_objects proves nothing here: " + refusal,
            refusal.contains("build_external_objects")); //$NON-NLS-1$
    }

    @Test
    public void testAFailingDumpValidationIsReportedWithItsReason()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("ExtObjects"); //$NON-NLS-1$
        IExternalObjectDumpSupport support = mock(IExternalObjectDumpSupport.class);
        when(support.isEnabled(project)).thenReturn(true);
        when(support.validateDumpGeneration(project)).thenReturn(
            new Status(IStatus.ERROR, "test", "no 1C platform installation")); //$NON-NLS-1$ //$NON-NLS-2$

        String refusal = LaunchOverrides.dumpRefusalOrNull(support, project);

        assertNotNull(refusal);
        assertTrue("the platform's own reason must survive into the refusal: " + refusal,
            refusal.contains("no 1C platform installation")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnavailableDumpServiceRefusesRatherThanLaunchingBlind()
    {
        // Not resolvable means the pre-check cannot be made at all. Launching anyway would be the
        // silent-success case with no way to notice it, so the honest answer is a refusal.
        String refusal = LaunchOverrides.dumpRefusalOrNull(null, mock(IProject.class));
        assertNotNull(refusal);
        assertTrue(refusal.contains("Cannot verify")); //$NON-NLS-1$
    }

    @Test
    public void testAHealthyProjectPassesTheDumpGate()
    {
        // The negative control: without this the three refusals above would also pass for a gate
        // that rejects everything.
        IProject project = mock(IProject.class);
        IExternalObjectDumpSupport support = mock(IExternalObjectDumpSupport.class);
        when(support.isEnabled(project)).thenReturn(true);
        when(support.validateDumpGeneration(project)).thenReturn(Status.OK_STATUS);
        assertNull(LaunchOverrides.dumpRefusalOrNull(support, project));
    }

    /** Mockito matcher sugar keeping the verify() above readable. */
    private static String eqAttr(String attribute)
    {
        return org.mockito.ArgumentMatchers.eq(attribute);
    }

    private static String messageOf(String errorJson)
    {
        return JsonParser.parseString(errorJson).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }
}
