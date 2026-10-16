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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.ContainedObject;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;

/**
 * Pins {@link ExtensionRootBuilder}: a new extension's root is the one EDT's own new-extension wizard
 * builds - the base root ADOPTED through {@link IModelObjectAdopter#adopt} plus exactly the wizard's
 * overrides - and never a factory-made configuration with base-only properties (issue #706).
 * <p>
 * The adopter is a mock returning a root shaped the way EDT's {@code ConfigurationAdopterParticipant}
 * shapes it (an extension block, the adopted default language, the base's compatibility mode and script
 * variant), so every assertion below is about what the builder does to that root - or refuses to do.
 */
public class ExtensionRootBuilderTest
{
    private static final String BASE_PROJECT = "Base"; //$NON-NLS-1$

    /** The runtime version of the base project: deliberately NOT its compatibility mode. */
    private static final Version VERSION = new Version("8.3.27"); //$NON-NLS-1$

    private IModelObjectAdopter adopter;
    private Configuration base;
    private Configuration adopted;
    private ObjectExtension block;
    private Language adoptedLanguage;
    private ContainedObject contained;

    @Before
    public void setUp()
    {
        // The look's rounds claim a per-project slot that a hung read of an earlier test may still hold.
        BuildUtils.forgetExportWaits();
        // The base: a language whose NAME differs from its CODE, so a synonym keyed by the name is caught.
        base = MdClassFactory.eINSTANCE.createConfiguration();
        base.setName("BaseConfiguration"); //$NON-NLS-1$
        Language baseLanguage = MdClassFactory.eINSTANCE.createLanguage();
        baseLanguage.setName("English"); //$NON-NLS-1$
        baseLanguage.setLanguageCode("en"); //$NON-NLS-1$
        base.getLanguages().add(baseLanguage);
        base.setDefaultLanguage(baseLanguage);
        base.setCompatibilityMode(CompatibilityMode.VERSION8_321);
        base.setScriptVariant(ScriptVariant.ENGLISH);

        // What EDT's adopter returns for that base: name copied, extension block, adopted language,
        // compatibility and script variant taken from the base, purpose Customization.
        adopted = MdClassFactory.eINSTANCE.createConfiguration();
        adopted.setName("BaseConfiguration"); //$NON-NLS-1$
        adopted.setObjectBelonging(ObjectBelonging.ADOPTED);
        block = MdClassExtensionFactory.eINSTANCE.createConfigurationExtension();
        adopted.setExtension(block);
        adoptedLanguage = MdClassFactory.eINSTANCE.createLanguage();
        adoptedLanguage.setName("English"); //$NON-NLS-1$
        adoptedLanguage.setLanguageCode("en"); //$NON-NLS-1$
        adopted.getLanguages().add(adoptedLanguage);
        adopted.setDefaultLanguage(adoptedLanguage);
        adopted.setCompatibilityMode(CompatibilityMode.VERSION8_321);
        adopted.setConfigurationExtensionCompatibilityMode(CompatibilityMode.VERSION8_321);
        adopted.setConfigurationExtensionPurpose(ConfigurationExtensionPurpose.CUSTOMIZATION);
        adopted.setScriptVariant(ScriptVariant.ENGLISH);
        contained = MdClassFactory.eINSTANCE.createContainedObject();
        adopted.getContainedObjects().add(contained);
        // A stale entry: the wizard clears the synonym before filling it.
        adopted.getSynonym().put("ru", "stale"); //$NON-NLS-1$ //$NON-NLS-2$

        adopter = mock(IModelObjectAdopter.class);
        doReturn(adopted).when(adopter).adopt(base, VERSION, null);
    }

    private static ExtensionRootBuilder.Spec spec(CompatibilityMode compatibilityMode)
    {
        return new ExtensionRootBuilder.Spec(BASE_PROJECT, "MyExt", "Ext1_", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.ADD_ON, compatibilityMode, "A comment", "My extension"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== U1, U6: the root IS the adopter's ====================

    @Test
    public void testTheRootIsTheBaseRootAdoptedOnceNeverAFactoryObject()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertNull(result.error, result.error);
        assertSame("the new root must be the object EDT's adopter returned", adopted, result.root); //$NON-NLS-1$
        verify(adopter, times(1)).adopt(base, VERSION, null);
    }

    @Test
    public void testWhatTheAdopterBuiltSurvivesUntouched()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        Configuration root = result.root;
        assertSame("the extension block (the property states) is the adopter's", block, root.getExtension()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(adoptedLanguage), new ArrayList<>(root.getLanguages()));
        assertSame("the adopted default language stays the default", adoptedLanguage, root.getDefaultLanguage()); //$NON-NLS-1$
        assertEquals("the native compatibility mode is the base's, untouched", //$NON-NLS-1$
            CompatibilityMode.VERSION8_321, root.getCompatibilityMode());
        assertEquals(ScriptVariant.ENGLISH, root.getScriptVariant());
        assertEquals(Collections.singletonList(contained), new ArrayList<>(root.getContainedObjects()));
        assertEquals(ObjectBelonging.ADOPTED, root.getObjectBelonging());
        assertEquals("the inherited script variant is reported", ScriptVariant.ENGLISH, result.scriptVariant); //$NON-NLS-1$
    }

    // ==================== U2, U3, U7: exactly the wizard's overrides ====================

    @Test
    public void testTheRootKeepsMappingToTheBaseByIds()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertTrue("EDT's wizard sets keepMappingToExtendedConfigurationObjectsByIDs on every root it adopts", //$NON-NLS-1$
            result.root.isKeepMappingToExtendedConfigurationObjectsByIDs());
    }

    @Test
    public void testNamePrefixCommentAndPurposeAreApplied()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        Configuration root = result.root;
        assertEquals("MyExt", root.getName()); //$NON-NLS-1$
        assertEquals("Ext1_", root.getNamePrefix()); //$NON-NLS-1$
        assertEquals("A comment", root.getComment()); //$NON-NLS-1$
        assertEquals("the caller's purpose overrides the adopter's Customization", //$NON-NLS-1$
            ConfigurationExtensionPurpose.ADD_ON, root.getConfigurationExtensionPurpose());
    }

    @Test
    public void testTheSynonymIsClearedAndKeyedByTheBaseLanguageCode()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertTrue(result.synonymApplied);
        // One entry, keyed by the language CODE 'en' - never by the name 'English' - and the stale
        // entry the adopter's copy carried is gone.
        assertEquals(1, result.root.getSynonym().size());
        assertEquals("My extension", result.root.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.root.getSynonym().containsKey("English")); //$NON-NLS-1$
        assertFalse(result.root.getSynonym().containsKey("ru")); //$NON-NLS-1$
    }

    @Test
    public void testAnOmittedSynonymDefaultsToTheName()
    {
        ExtensionRootBuilder.Spec noSynonym = new ExtensionRootBuilder.Spec(BASE_PROJECT, "MyExt", "", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.CUSTOMIZATION, null, null, null);

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, noSynonym);

        assertEquals("MyExt", result.root.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("no comment was asked for, so the root has none", result.root.getComment()); //$NON-NLS-1$
        assertEquals("the empty prefix is kept as given", "", result.root.getNamePrefix()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // EDT's wizard writes the comment UNCONDITIONALLY (ExtensionWizard.adopt: setComment(model.getComment())), and its
    // placeholder model's comment stays null while the comment field is untouched. EDT's copier carries only the name
    // today (MdObjectAdopterParticipant.shouldCopyAttribute), so the adopter below is one that DID carry the base's
    // comment: what these pin is the builder's override, which must never let such a comment through.

    @Test
    public void testAnOmittedCommentIsWrittenAsTheWizardsNullNotLeftAsTheAdoptedOne()
    {
        base.setComment("The base configuration's own comment"); //$NON-NLS-1$
        adopted.setComment("The base configuration's own comment"); //$NON-NLS-1$
        ExtensionRootBuilder.Spec noComment = new ExtensionRootBuilder.Spec(BASE_PROJECT, "MyExt", "Ext1_", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.CUSTOMIZATION, null, null, "My extension"); //$NON-NLS-1$

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, noComment);

        assertNull(result.error, result.error);
        assertNull("the root gets the wizard's untouched value, null - not the base's comment", //$NON-NLS-1$
            result.root.getComment());
    }

    @Test
    public void testAnEmptyCommentIsWrittenAsTheWizardsNullNotLeftAsTheAdoptedOne()
    {
        adopted.setComment("The base configuration's own comment"); //$NON-NLS-1$
        ExtensionRootBuilder.Spec emptyComment = new ExtensionRootBuilder.Spec(BASE_PROJECT, "MyExt", "Ext1_", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.CUSTOMIZATION, null, "", "My extension"); //$NON-NLS-1$ //$NON-NLS-2$

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, emptyComment);

        assertNull(result.error, result.error);
        assertNull("an empty comment is no comment: null, as for an untouched field", result.root.getComment()); //$NON-NLS-1$
    }

    @Test
    public void testARequestedCommentReplacesTheAdoptedOne()
    {
        adopted.setComment("The base configuration's own comment"); //$NON-NLS-1$

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertEquals("A comment", result.root.getComment()); //$NON-NLS-1$
    }

    @Test
    public void testWithoutAnyLanguageCodeTheSynonymIsNotAppliedButStillCleared()
    {
        base.getLanguages().clear();
        base.setDefaultLanguage(null);
        adopted.getLanguages().clear();
        adopted.setDefaultLanguage(null);

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertNull(result.error, result.error);
        assertFalse(result.synonymApplied);
        assertTrue("the stale entry is gone even when nothing could be written", //$NON-NLS-1$
            result.root.getSynonym().isEmpty());
    }

    // ==================== U4, U5: the compatibility mode ====================

    @Test
    public void testWithoutARequestTheExtensionModeIsTheBasesNotTheRuntimeVersion()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertEquals("the base's 8.3.21 stays, though the project's platform version is 8.3.27", //$NON-NLS-1$
            CompatibilityMode.VERSION8_321, result.root.getConfigurationExtensionCompatibilityMode());
        assertEquals(CompatibilityMode.VERSION8_321, result.compatibilityMode);
        assertEquals(CompatibilityMode.VERSION8_321, result.baseCompatibilityMode);
        assertNull("the base's own mode needs no warning", result.compatibilityNote); //$NON-NLS-1$
    }

    @Test
    public void testARequestedModeAboveTheBaseIsAppliedWithAWarning()
    {
        ExtensionRootBuilder.Result result =
            ExtensionRootBuilder.build(adopter, base, VERSION, spec(CompatibilityMode.VERSION8_327));

        assertNull(result.error, result.error);
        assertEquals(CompatibilityMode.VERSION8_327, result.root.getConfigurationExtensionCompatibilityMode());
        assertEquals("only the EXTENSION mode moves; the native mode stays the base's", //$NON-NLS-1$
            CompatibilityMode.VERSION8_321, result.root.getCompatibilityMode());
        assertEquals(CompatibilityMode.VERSION8_327, result.compatibilityMode);
        assertNotNull("a mode above the base's is one EDT's wizard never produces", result.compatibilityNote); //$NON-NLS-1$
        assertTrue(result.compatibilityNote, result.compatibilityNote.contains("Version8_3_27")); //$NON-NLS-1$
        assertTrue(result.compatibilityNote, result.compatibilityNote.contains("Version8_3_21")); //$NON-NLS-1$
        assertTrue(result.compatibilityNote, result.compatibilityNote.contains("above the base configuration's")); //$NON-NLS-1$
        assertFalse("crossing nothing at 8.3.13, it says nothing about default roles", //$NON-NLS-1$
            result.compatibilityNote.contains("default-roles")); //$NON-NLS-1$
    }

    @Test
    public void testARequestedModeBelowTheBaseButAbove8313IsAppliedSilently()
    {
        ExtensionRootBuilder.Result result =
            ExtensionRootBuilder.build(adopter, base, VERSION, spec(CompatibilityMode.VERSION8_320));

        assertNull(result.error, result.error);
        assertEquals(CompatibilityMode.VERSION8_320, result.root.getConfigurationExtensionCompatibilityMode());
        assertNull(result.compatibilityNote);
    }

    @Test
    public void testRequestingTheBasesOwnModeNeedsNoWarning()
    {
        ExtensionRootBuilder.Result result =
            ExtensionRootBuilder.build(adopter, base, VERSION, spec(CompatibilityMode.VERSION8_321));

        assertNull(result.error, result.error);
        assertNull(result.compatibilityNote);
    }

    @Test
    public void testAMode8313OrLowerOverABaseAbove8313IsRefusedBeforeAnythingIsAdopted()
    {
        ExtensionRootBuilder.Result result =
            ExtensionRootBuilder.build(adopter, base, VERSION, spec(CompatibilityMode.VERSION8_313));

        assertNull(result.root);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("Version8_3_13")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Version8_3_21")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("'" + BASE_PROJECT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error, result.error.contains("Nothing was created.")); //$NON-NLS-1$
        assertTrue("the refusal names the way out", result.error.contains("Omit compatibilityMode")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(adopter, never()).adopt(any(EObject.class), any(Version.class), any());
    }

    @Test
    public void testAMode8313OrLowerOverABaseAt8313OrLowerIsAccepted()
    {
        base.setCompatibilityMode(CompatibilityMode.VERSION8_312);
        adopted.setCompatibilityMode(CompatibilityMode.VERSION8_312);
        adopted.setConfigurationExtensionCompatibilityMode(CompatibilityMode.VERSION8_312);

        ExtensionRootBuilder.Result result =
            ExtensionRootBuilder.build(adopter, base, VERSION, spec(CompatibilityMode.VERSION8_310));

        assertNull(result.error, result.error);
        assertEquals(CompatibilityMode.VERSION8_310, result.root.getConfigurationExtensionCompatibilityMode());
        assertNull(result.compatibilityNote);
    }

    @Test
    public void testAModeAbove8313OverABaseAt8313OrLowerWarnsThatDefaultRolesFollowTheBase()
    {
        base.setCompatibilityMode(CompatibilityMode.VERSION8_312);
        adopted.setCompatibilityMode(CompatibilityMode.VERSION8_312);
        adopted.setConfigurationExtensionCompatibilityMode(CompatibilityMode.VERSION8_312);

        ExtensionRootBuilder.Result result =
            ExtensionRootBuilder.build(adopter, base, VERSION, spec(CompatibilityMode.VERSION8_314));

        assertNull(result.error, result.error);
        assertNotNull(result.compatibilityNote);
        assertTrue(result.compatibilityNote, result.compatibilityNote.contains("default-roles")); //$NON-NLS-1$
    }

    @Test
    public void testTheWizardsDefaultRoleThresholdIs8313Exclusive()
    {
        assertTrue(ExtensionRootBuilder.wizardCreatesDefaultRole(CompatibilityMode.VERSION8_314));
        assertTrue(ExtensionRootBuilder.wizardCreatesDefaultRole(CompatibilityMode.VERSION8_51));
        assertFalse(ExtensionRootBuilder.wizardCreatesDefaultRole(CompatibilityMode.VERSION8_313));
        assertFalse(ExtensionRootBuilder.wizardCreatesDefaultRole(CompatibilityMode.VERSION8_310));
        assertFalse(ExtensionRootBuilder.wizardCreatesDefaultRole(null));
    }

    @Test
    public void testAdoptedObjectsMapByNameOnlyAtPlatform8314OrLower()
    {
        // EDT's copier links an adopted object by ID only when V8_3_14.isLessThan(version): 8.3.15 is the first
        // version that maps by ID, and an unknown version blames nothing.
        assertTrue(ExtensionRootBuilder.versionMapsByNameOnly(Version.V8_3_13));
        assertTrue(ExtensionRootBuilder.versionMapsByNameOnly(Version.V8_3_14));
        assertFalse(ExtensionRootBuilder.versionMapsByNameOnly(Version.V8_3_15));
        assertFalse(ExtensionRootBuilder.versionMapsByNameOnly(Version.V8_5_1));
        assertFalse(ExtensionRootBuilder.versionMapsByNameOnly(null));
    }

    // ==================== U8, U9: fail closed, nothing created ====================

    @Test
    public void testAnAdopterRefusalNamesTheBaseAndSaysNothingWasCreated()
    {
        doThrow(new IllegalArgumentException("Source object Configuration cannot be adopted")) //$NON-NLS-1$
            .when(adopter).adopt(base, VERSION, null);

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertNull(result.root);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("'" + BASE_PROJECT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error, result.error.contains("cannot be adopted")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Nothing was created.")); //$NON-NLS-1$
    }

    @Test
    public void testAnAdopterReturningNothingIsARefusalToo()
    {
        doReturn(null).when(adopter).adopt(base, VERSION, null);

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertNull(result.root);
        assertTrue(result.error, result.error.contains("Nothing was created.")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingAdopterFailsClosedNamingTheBundle()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(null, base, VERSION, spec(null));

        assertNull(result.root);
        assertEquals(ExtensionRootBuilder.adopterUnavailable(BASE_PROJECT), result.error);
        assertTrue(result.error, result.error.contains("md.extension bundle")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Nothing was created.")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnloadedBaseRootFailsClosed()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, null, VERSION, spec(null));

        assertNull(result.root);
        assertTrue(result.error, result.error.contains("'" + BASE_PROJECT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error, result.error.contains("Nothing was created.")); //$NON-NLS-1$
        verify(adopter, never()).adopt(any(EObject.class), any(Version.class), any());
    }

    // ==================== the base root is read in a READ transaction ====================

    @Test
    public void testTheBaseRootIsReadInAReadTransactionNeverAWrite()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)base); //$NON-NLS-1$
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.buildFromBase(model, adopter, VERSION, spec(null));

        assertNull(result.error, result.error);
        assertSame(adopted, result.root);
        verify(model, times(1)).executeReadonlyTask(any());
        verify(model, never()).execute(any());
        verify(adopter, times(1)).adopt(base, VERSION, null);
    }

    @Test
    public void testAFailingReadOfTheBaseIsAnErrorBeforeAnythingIsCreated()
    {
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenThrow(new IllegalStateException("model is closing")); //$NON-NLS-1$

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.buildFromBase(model, adopter, VERSION, spec(null));

        assertNull(result.root);
        assertTrue(result.error, result.error.contains("'" + BASE_PROJECT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error, result.error.contains("model is closing")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Nothing was created.")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingAdopterIsRefusedBeforeTheBaseIsEvenRead()
    {
        IBmModel model = mock(IBmModel.class);

        ExtensionRootBuilder.Result result = ExtensionRootBuilder.buildFromBase(model, null, VERSION, spec(null));

        assertEquals(ExtensionRootBuilder.adopterUnavailable(BASE_PROJECT), result.error);
        verifyZeroInteractions(model);
    }

    // ==================== the root on disk ====================

    /**
     * The root these looks expect: the one {@link #spec} builds over the base of {@link #setUp} - its name, its prefix,
     * the caller's purpose and the base's mode - so {@link #WIZARD_ROOT} is this create's root.
     */
    private static final ExtensionRootBuilder.RootIdentity ROOT = new ExtensionRootBuilder.RootIdentity("MyExt", //$NON-NLS-1$
        "Ext1_", ConfigurationExtensionPurpose.ADD_ON, CompatibilityMode.VERSION8_321); //$NON-NLS-1$

    /**
     * The extension block and the mapping flag, as EDT serializes a wizard-made root, with the name, the prefix, the
     * extension compatibility mode and the purpose of {@link #ROOT} in EDT's order (as in the live root of the #747 stand).
     */
    private static final String WIZARD_ROOT = "<mdclass:Configuration>\n" //$NON-NLS-1$
        + "  <name>MyExt</name>\n" //$NON-NLS-1$
        + "  <extension xsi:type=\"mdclassExtension:ConfigurationExtension\">\n" //$NON-NLS-1$
        + "    <defaultRunMode>Checked</defaultRunMode>\n" //$NON-NLS-1$
        + "  </extension>\n" //$NON-NLS-1$
        + "  <keepMappingToExtendedConfigurationObjectsByIDs>true</keepMappingToExtendedConfigurationObjectsByIDs>\n" //$NON-NLS-1$
        + "  <namePrefix>Ext1_</namePrefix>\n" //$NON-NLS-1$
        + "  <configurationExtensionCompatibilityMode>8.3.21</configurationExtensionCompatibilityMode>\n" //$NON-NLS-1$
        + "  <configurationExtensionPurpose>AddOn</configurationExtensionPurpose>\n" //$NON-NLS-1$
        + "</mdclass:Configuration>\n"; //$NON-NLS-1$

    /**
     * A factory-made root: base-only properties, no block, no mapping flag (the #706 report) - with the name, prefix,
     * purpose and mode of {@link #ROOT}, which that root carried too (the factory path set them, and EDT writes the mode
     * anyway): only the wizard's shape is what it lacks.
     */
    private static final String FACTORY_ROOT = "<mdclass:Configuration>\n" //$NON-NLS-1$
        + "  <name>MyExt</name>\n" //$NON-NLS-1$
        + "  <namePrefix>Ext1_</namePrefix>\n" //$NON-NLS-1$
        + "  <configurationExtensionCompatibilityMode>8.3.21</configurationExtensionCompatibilityMode>\n" //$NON-NLS-1$
        + "  <configurationExtensionPurpose>AddOn</configurationExtensionPurpose>\n" //$NON-NLS-1$
        + "  <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
        + "  <dataLockControlMode>Managed</dataLockControlMode>\n" //$NON-NLS-1$
        + "</mdclass:Configuration>\n"; //$NON-NLS-1$

    @Test
    public void testAWizardShapedRootLacksNothing()
    {
        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT, ROOT));
    }

    @Test
    public void testAFactoryShapedRootLacksBothMarks()
    {
        List<String> missing = ExtensionRootBuilder.missingOnDisk(FACTORY_ROOT, ROOT);
        assertEquals(missing.toString(), 2, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("ConfigurationExtension")); //$NON-NLS-1$
        assertTrue(missing.toString(), missing.get(1).contains("keepMappingToExtendedConfigurationObjectsByIDs")); //$NON-NLS-1$
    }

    @Test
    public void testAFileWithoutANameOrAPrefixLacksThem()
    {
        List<String> missing = ExtensionRootBuilder.missingOnDisk("<mdclass:Configuration>\n</mdclass:Configuration>", //$NON-NLS-1$
            ROOT);
        assertEquals(Arrays.asList("the ConfigurationExtension block", //$NON-NLS-1$
            "keepMappingToExtendedConfigurationObjectsByIDs=true", "name 'MyExt'", "namePrefix 'Ext1_'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "configurationExtensionPurpose 'AddOn'", "configurationExtensionCompatibilityMode '8.3.21'"), missing); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAFalseMappingFlagIsNotTheMark()
    {
        String text = WIZARD_ROOT.replace(">true</keepMapping", ">false</keepMapping"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> missing = ExtensionRootBuilder.missingOnDisk(text, ROOT);
        assertEquals(1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("keepMappingToExtendedConfigurationObjectsByIDs")); //$NON-NLS-1$
    }

    @Test
    public void testAnAbsentFileIsNotConfirmed()
    {
        assertFalse(ExtensionRootBuilder.missingOnDisk(null, ROOT).isEmpty());
    }

    // ==================== the root on disk is THIS create's (PR #747 review, round 6) ====================

    /**
     * delete_project without deleteContent leaves a project's files on disk, and create_project refuses only a project
     * the workspace still holds: a new create of that project name finds the old Configuration.mdo there, the extension
     * block and the mapping flag included. It is not this create's root.
     */
    @Test
    public void testARootAnotherCreateLeftOnDiskIsNotThisCreatesRoot()
    {
        String stale = WIZARD_ROOT.replace("<name>MyExt</name>", "<name>OldExt</name>") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<namePrefix>Ext1_</namePrefix>", "<namePrefix>Old_</namePrefix>"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> missing = ExtensionRootBuilder.missingOnDisk(stale, ROOT);

        assertEquals(Arrays.asList("name 'MyExt' (the file shows 'OldExt')", //$NON-NLS-1$
            "namePrefix 'Ext1_' (the file shows 'Old_')"), missing); //$NON-NLS-1$
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED, () -> stale, 300L, 10L);
        assertFalse("a stale root with the generic marks is never confirmed", disk.confirmed); //$NON-NLS-1$
        assertTrue(disk.describe(), disk.describe().contains("does not show name 'MyExt' (the file shows 'OldExt')")); //$NON-NLS-1$
    }

    @Test
    public void testTheNameAloneDecides()
    {
        String other = WIZARD_ROOT.replace("<name>MyExt</name>", "<name>OldExt</name>"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("name 'MyExt' (the file shows 'OldExt')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(other, ROOT));
        assertEquals(Collections.singletonList("name 'MyExt'"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT.replace("  <name>MyExt</name>\n", ""), ROOT)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testThePrefixAloneDecides()
    {
        String other = WIZARD_ROOT.replace("<namePrefix>Ext1_</namePrefix>", "<namePrefix>Old_</namePrefix>"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("namePrefix 'Ext1_' (the file shows 'Old_')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(other, ROOT));
        assertEquals(Collections.singletonList("namePrefix 'Ext1_'"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT.replace("  <namePrefix>Ext1_</namePrefix>\n", ""), ROOT)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The root's name is its FIRST name element: a language's later in the file is not the root's. */
    @Test
    public void testTheRootsNameIsItsFirstNameElement()
    {
        String other = WIZARD_ROOT.replace("<name>MyExt</name>", "<name>OldExt</name>") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("</mdclass:Configuration>", //$NON-NLS-1$
                "  <languages><name>MyExt</name></languages>\n</mdclass:Configuration>"); //$NON-NLS-1$

        assertEquals(Collections.singletonList("name 'MyExt' (the file shows 'OldExt')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(other, ROOT));
    }

    @Test
    public void testAPurposeOrModeTheFileNamesMustBeTheOnesSet()
    {
        String purpose = WIZARD_ROOT.replace(">AddOn<", ">Patch<"); //$NON-NLS-1$ //$NON-NLS-2$
        String mode = WIZARD_ROOT.replace(">8.3.21</configurationExtension", ">8.5.1</configurationExtension"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("configurationExtensionPurpose 'AddOn' (the file shows 'Patch')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(purpose, ROOT));
        assertEquals(Collections.singletonList(
            "configurationExtensionCompatibilityMode '8.3.21' (the file shows '8.5.1')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(mode, ROOT));
    }

    /**
     * A file that names no purpose or no mode lacks it (PR #747 review, round 7): absence is no evidence that the file is
     * this create's root. EDT's formatters always write a CompatibilityMode-typed feature (8.5.1, its model's default,
     * too), and they write the purpose whenever it is not its model's default.
     */
    @Test
    public void testAPurposeOrModeTheFileDoesNotNameIsMissing()
    {
        String noMode = WIZARD_ROOT
            .replace("  <configurationExtensionCompatibilityMode>8.3.21</configurationExtensionCompatibilityMode>\n", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String noPurpose = WIZARD_ROOT
            .replace("  <configurationExtensionPurpose>AddOn</configurationExtensionPurpose>\n", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String neither = noMode
            .replace("  <configurationExtensionPurpose>AddOn</configurationExtensionPurpose>\n", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("configurationExtensionCompatibilityMode '8.3.21'"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(noMode, ROOT));
        assertEquals(Collections.singletonList("configurationExtensionPurpose 'AddOn'"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(noPurpose, ROOT));
        assertEquals(Arrays.asList("configurationExtensionPurpose 'AddOn'", //$NON-NLS-1$
            "configurationExtensionCompatibilityMode '8.3.21'"), ExtensionRootBuilder.missingOnDisk(neither, ROOT)); //$NON-NLS-1$
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED, () -> neither, 300L, 10L);
        assertFalse("a file without the purpose and mode set is never confirmed", disk.confirmed); //$NON-NLS-1$
    }

    /**
     * The one omission EDT's writer makes is the purpose at its model's default, Patch (MdClass.xcore declares no other
     * default, so EMF's is the first literal, and XmlFormatter writes an attribute only when it is set): a Patch root's
     * file may name no purpose, or name Patch, but not another one. The mode at its default is still written, so a file
     * without it lacks it.
     */
    @Test
    public void testOnlyThePurposesDefaultPatchMayBeAbsent()
    {
        ExtensionRootBuilder.RootIdentity patch = new ExtensionRootBuilder.RootIdentity("MyExt", "Ext1_", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.PATCH, CompatibilityMode.VERSION8_51);
        String patchRoot = WIZARD_ROOT.replace(">AddOn<", ">Patch<") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">8.3.21</configurationExtension", ">8.5.1</configurationExtension"); //$NON-NLS-1$ //$NON-NLS-2$
        String noPurpose = patchRoot
            .replace("  <configurationExtensionPurpose>Patch</configurationExtensionPurpose>\n", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(patchRoot, patch));
        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(noPurpose, patch));
        assertEquals(Collections.singletonList("configurationExtensionPurpose 'Patch' (the file shows 'AddOn')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(patchRoot.replace(">Patch<", ">AddOn<"), patch)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the mode's default is written, so its absence is not EDT's", //$NON-NLS-1$
            Collections.singletonList("configurationExtensionCompatibilityMode '8.5.1'"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(noPurpose.replace(
                "  <configurationExtensionCompatibilityMode>8.5.1</configurationExtensionCompatibilityMode>\n", ""), //$NON-NLS-1$ //$NON-NLS-2$
                patch));
    }

    @Test
    public void testAnEmptyPrefixIsMetByNoPrefixOrAnEmptyOneOnly()
    {
        ExtensionRootBuilder.RootIdentity noPrefix = new ExtensionRootBuilder.RootIdentity("MyExt", "", //$NON-NLS-1$ //$NON-NLS-2$
            ConfigurationExtensionPurpose.ADD_ON, CompatibilityMode.VERSION8_321);
        String absent = WIZARD_ROOT.replace("  <namePrefix>Ext1_</namePrefix>\n", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(absent, noPrefix));
        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(
            WIZARD_ROOT.replace("<namePrefix>Ext1_</namePrefix>", "<namePrefix></namePrefix>"), noPrefix)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(
            WIZARD_ROOT.replace("<namePrefix>Ext1_</namePrefix>", "<namePrefix/>"), noPrefix)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.singletonList("an empty namePrefix (the file shows 'Ext1_')"), //$NON-NLS-1$
            ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT, noPrefix));
    }

    @Test
    public void testWithoutTheRootThisCreateBuiltNoFileIsConfirmed()
    {
        List<String> missing = ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT, null);

        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("not known")); //$NON-NLS-1$
    }

    /** What a file must show is what the root handed to EDT's create carries - read from that root, once built. */
    @Test
    public void testTheBuiltRootsIdentityIsWhatItsFileMustShow()
    {
        ExtensionRootBuilder.Result result = ExtensionRootBuilder.build(adopter, base, VERSION, spec(null));

        assertNotNull(result.identity);
        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT, result.identity));
        assertEquals(1, ExtensionRootBuilder.missingOnDisk(
            WIZARD_ROOT.replace(">AddOn<", ">Customization<"), result.identity).size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, ExtensionRootBuilder.missingOnDisk(
            WIZARD_ROOT.replace("<name>MyExt</name>", "<name>BaseConfiguration</name>"), result.identity).size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a refusal builds no root, so it has no identity", //$NON-NLS-1$
            ExtensionRootBuilder.build(null, base, VERSION, spec(null)).identity);
    }

    @Test
    public void testADrainedExportWithTheMarksOnDiskConfirmsTheRootAtTheFirstRead()
    {
        AtomicInteger reads = new AtomicInteger();
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> {
                reads.incrementAndGet();
                return WIZARD_ROOT;
            }, 5_000L, 10L);

        assertTrue(disk.confirmed);
        assertEquals(DiskExportState.DRAINED, disk.exportState);
        assertEquals(1, reads.get());
        assertTrue(disk.missing.isEmpty());
    }

    @Test
    public void testTheFileIsTheEvidenceNotTheQueue()
    {
        // The queue drained, but the file on disk still lacks the marks: a drained queue also drains a
        // write that failed, so this is NOT a confirmation, however long the poll is allowed to run. The budget is
        // above the second the look keeps for the root file, so the export wait is asked - and answers DRAINED.
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> FACTORY_ROOT, 1_200L, 10L);

        assertFalse(disk.confirmed);
        assertEquals(DiskExportState.DRAINED, disk.exportState);
        assertEquals(2, disk.missing.size());
        assertTrue(disk.describe(), disk.describe().contains("ConfigurationExtension")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnobservableExportIsPolledUntilTheMarksAppear()
    {
        List<String> answers = new ArrayList<>(Arrays.asList(null, FACTORY_ROOT, WIZARD_ROOT));
        AtomicInteger reads = new AtomicInteger();
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.UNOBSERVABLE,
            () -> {
                reads.incrementAndGet();
                return answers.size() > 1 ? answers.remove(0) : answers.get(0);
            }, 5_000L, 10L);

        assertTrue(disk.confirmed);
        assertEquals(3, reads.get());
    }

    @Test
    public void testTheConfirmationGivesUpAtItsBudget()
    {
        long start = System.nanoTime();
        // The wait would answer UNOBSERVABLE: a budget no larger than the kept second does not ask it, and the queue
        // reads as PENDING.
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.UNOBSERVABLE,
            () -> null, 300L, 20L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse(disk.confirmed);
        assertEquals("the wait was not asked", DiskExportState.PENDING, disk.exportState); //$NON-NLS-1$
        assertTrue("the poll is bounded by its budget, took " + elapsedMs + " ms", elapsedMs < 3_000L); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(disk.describe(), disk.describe().contains("not found")); //$NON-NLS-1$
    }

    @Test
    public void testTheRootFileIsReadFromTheProjectLocation() throws IOException
    {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("erb706"); //$NON-NLS-1$
        try
        {
            IProject project = mock(IProject.class);
            when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path(dir.toString()));
            assertNull("no file yet", ExtensionRootBuilder.readRootFile(project)); //$NON-NLS-1$

            java.nio.file.Path file = dir.resolve("src").resolve("Configuration").resolve("Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, WIZARD_ROOT.getBytes(StandardCharsets.UTF_8));

            assertEquals(WIZARD_ROOT, ExtensionRootBuilder.readRootFile(project));
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
    public void testAProjectWithoutALocalLocationHasNoRootFile() throws IOException
    {
        assertNull(ExtensionRootBuilder.readRootFile(mock(IProject.class)));
    }

    @Test
    public void testAFailingReadIsReportedNotThrown()
    {
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> {
                throw new IOException("sharing violation"); //$NON-NLS-1$
            }, 100L, 10L);

        assertFalse(disk.confirmed);
        assertNotNull(disk.readFailure);
        assertTrue(disk.readFailure, disk.readFailure.contains("sharing violation")); //$NON-NLS-1$
    }

    // ==================== the command interface files, in the same look ====================

    private static final String CMI = "src/Configuration/CommandInterface.cmi"; //$NON-NLS-1$
    private static final String MAIN_CMI = "src/Configuration/MainSectionCommandInterface.cmi"; //$NON-NLS-1$

    @Test
    public void testTheCommandInterfaceFilesAreWhereEdtSavesThem()
    {
        assertEquals(Arrays.asList(CMI, MAIN_CMI), ExtensionRootBuilder.COMMAND_INTERFACE_FILES);
    }

    @Test
    public void testTheFilesBesideTheRootAreWaitedForWhileTheRootIsReadOnce()
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> {
                reads.incrementAndGet();
                return WIZARD_ROOT;
            }, Arrays.asList(CMI, MAIN_CMI), path -> checks.incrementAndGet() > 2, 5_000L, 10L);

        assertTrue(disk.confirmed);
        assertEquals("both files were seen in the end", Collections.emptyList(), disk.missingFiles); //$NON-NLS-1$
        assertEquals("a root one read confirmed is not read again", 1, reads.get()); //$NON-NLS-1$
        assertTrue("the files were looked for again after the root was confirmed: " + checks.get(), checks.get() > 2); //$NON-NLS-1$
    }

    @Test
    public void testAFileStillMissingAtTheBudgetIsNamedButTheRootStaysConfirmed()
    {
        long start = System.nanoTime();
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> !MAIN_CMI.equals(path), 300L, 20L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue("the root alone decides 'confirmed'", disk.confirmed); //$NON-NLS-1$
        assertEquals(Collections.singletonList(MAIN_CMI), disk.missingFiles);
        assertTrue("the same budget bounds the files, took " + elapsedMs + " ms", elapsedMs < 3_000L); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFilesOnDiskNeverConfirmARootThatIsNot()
    {
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> FACTORY_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> true, 100L, 10L);

        assertFalse(disk.confirmed);
        assertEquals(Collections.emptyList(), disk.missingFiles);
        assertEquals(2, disk.missing.size());
    }

    @Test
    public void testAFileCheckThatThrowsCountsTheFileAsNotSeen()
    {
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> {
                if (CMI.equals(path))
                {
                    throw new SecurityException("access denied"); //$NON-NLS-1$
                }
                return true;
            }, 100L, 10L);

        assertTrue(disk.confirmed);
        assertEquals(Collections.singletonList(CMI), disk.missingFiles);
    }

    @Test
    public void testTheRootOnlyLookAsksForNoFiles()
    {
        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, 1_000L, 10L);

        assertTrue(disk.confirmed);
        assertEquals(Collections.emptyList(), disk.missingFiles);
    }

    @Test
    public void testAProjectFileIsLookedForUnderTheProjectLocation() throws IOException
    {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("erb706cmi"); //$NON-NLS-1$
        try
        {
            IProject project = mock(IProject.class);
            when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path(dir.toString()));
            java.nio.file.Path file = dir.resolve("src").resolve("Configuration").resolve("CommandInterface.cmi"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, "<cmi:CommandInterface/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

            assertTrue(ExtensionRootBuilder.projectFileExists(project).test(CMI));
            assertFalse(ExtensionRootBuilder.projectFileExists(project).test(MAIN_CMI));
            assertFalse("a directory is not a file", //$NON-NLS-1$
                ExtensionRootBuilder.projectFileExists(project).test("src/Configuration")); //$NON-NLS-1$
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
    public void testAProjectWithoutALocalLocationHasNoFiles()
    {
        assertFalse(ExtensionRootBuilder.projectFileExists(mock(IProject.class)).test(CMI));
    }

    // ==================== every look at the file system is bounded (#747 review, round 3) ====================

    /** The description of a root no read of which completed in time. */
    private static final String NOT_READ_IN_TIME = ExtensionRootBuilder.ROOT_FILE + " could not be read in time (the read " //$NON-NLS-1$
        + "did not complete, or an earlier read of the project's files had not returned)"; //$NON-NLS-1$

    /** Project names: each look claims its own project's slot, so no look is refused for another test's claim. */
    private static final AtomicInteger PROJECTS = new AtomicInteger();

    private static String nextProject()
    {
        return "ExtensionRootBuilderTest" + PROJECTS.incrementAndGet(); //$NON-NLS-1$
    }

    /**
     * Runs each read at once, on the caller's thread, and reports it completed and its job ended: a file system that
     * answers.
     */
    private static final AdoptedModuleState.BoundedRunner AT_ONCE = (name, timeoutMs, work, jobEnded) -> {
        Throwable thrown = null;
        try
        {
            work.run(new NullProgressMonitor());
        }
        catch (Exception e) // NOSONAR captured, as BoundedJob captures it
        {
            thrown = e;
        }
        jobEnded.run();
        return new BoundedJob.Result(BoundedJob.Outcome.COMPLETED, 0L, thrown);
    };

    // A budget no larger than the second the look keeps for the root file (FIRST_LOOK_MIN_MS) never asks the export
    // wait - in these looks, or in a test that calls confirmOnDisk itself - and the queue then reads as PENDING, not
    // seen draining. A test that means the wait to run gives a larger budget (testTheFileIsTheEvidenceNotTheQueue).

    /** A look at the root alone, whose reads a file system that answers answers at once. */
    private static ExtensionRootBuilder.RootOnDisk look(LongFunction<DiskExportState> exportWait,
        ExtensionRootBuilder.RootFileReader reader, long budgetMs, long pollMs)
    {
        return look(exportWait, reader, Collections.emptyList(), path -> true, budgetMs, pollMs);
    }

    /** A look at the root and the files beside it, whose reads a file system that answers answers at once. */
    private static ExtensionRootBuilder.RootOnDisk look(LongFunction<DiskExportState> exportWait, // NOSONAR the look's own parameters, one each
        ExtensionRootBuilder.RootFileReader reader, List<String> files, Predicate<String> fileExists, long budgetMs,
        long pollMs)
    {
        return ExtensionRootBuilder.confirmOnDisk(AT_ONCE, nextProject(), ROOT, exportWait, reader, files, fileExists,
            budgetMs, pollMs);
    }

    /**
     * Answers each read from a script - COMPLETED runs the read at once, any other outcome does not - then every later
     * read with {@code rest}, and records the timeout each read was given and when it reached the runner, a
     * {@link System#nanoTime()} reading. A TIMED_OUT read keeps its job, and so its project's claim, until
     * {@link #endKept}: a read the file system has not answered. Any other read that did not run ends its job at once,
     * as a job cancelled before it started does.
     */
    private static final class ScriptedRunner implements AdoptedModuleState.BoundedRunner
    {
        private final BoundedJob.Outcome rest;
        private final List<BoundedJob.Outcome> script;
        final List<Long> timeouts = new ArrayList<>();
        final List<Long> startedAt = new ArrayList<>();
        private final List<Runnable> kept = new ArrayList<>();

        ScriptedRunner(BoundedJob.Outcome rest, BoundedJob.Outcome... script)
        {
            this.rest = rest;
            this.script = new ArrayList<>(Arrays.asList(script));
        }

        @Override
        public BoundedJob.Result run(String jobName, long timeoutMs, BoundedJob.IBoundedWork work, Runnable jobEnded)
        {
            startedAt.add(System.nanoTime());
            timeouts.add(timeoutMs);
            BoundedJob.Outcome outcome = script.isEmpty() ? rest : script.remove(0);
            if (outcome == BoundedJob.Outcome.COMPLETED)
            {
                return AT_ONCE.run(jobName, timeoutMs, work, jobEnded);
            }
            if (outcome == BoundedJob.Outcome.TIMED_OUT)
            {
                kept.add(jobEnded);
            }
            else
            {
                jobEnded.run();
            }
            return new BoundedJob.Result(outcome, timeoutMs, null);
        }

        /** The file system answers the reads it kept, long after the look stopped waiting: their jobs end. */
        void endKept()
        {
            kept.forEach(Runnable::run);
            kept.clear();
        }
    }

    /**
     * A round that did not complete in time is no evidence either way (#747 review, round 3): it confirms nothing, and the
     * poll goes on - the next round reads the root and confirms it.
     */
    @Test
    public void testARoundNotReadInTimeConfirmsNothingAndThePollGoesOn()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.TIMED_OUT_BEFORE_START);
        AtomicInteger reads = new AtomicInteger();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> {
                reads.incrementAndGet();
                return WIZARD_ROOT;
            }, Collections.emptyList(), path -> true, 5_000L, 10L);

        assertTrue("a later round confirmed the root", disk.confirmed); //$NON-NLS-1$
        assertTrue(disk.readInTime);
        assertEquals("two rounds: the one not read in time, then the one that read the root", 2, //$NON-NLS-1$
            runner.timeouts.size());
        assertEquals("only the second round ran the read", 1, reads.get()); //$NON-NLS-1$
    }

    /**
     * A read the file system never answers neither confirms the root nor says it is missing (#747 review, round 3): the
     * root is described as not read in time - not as not found, not as lacking the block - and every later round of the
     * look is refused at once by the project's claim that the hung read keeps, so no second read joins it. The files
     * beside the root were not looked at in time, so none counts as seen. Once the hung read's job ends, the claim is
     * released.
     */
    @Test
    public void testARootReadThatNeverAnswersIsNotReadInTimeAndRefusesEveryLaterRound()
    {
        String project = nextProject();
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.TIMED_OUT);
        AtomicInteger touches = new AtomicInteger();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, project, ROOT,
            waitMs -> DiskExportState.DRAINED, () -> {
                touches.incrementAndGet();
                return WIZARD_ROOT;
            }, Arrays.asList(CMI, MAIN_CMI), path -> touches.incrementAndGet() > 0, 300L, 10L);

        assertFalse(disk.confirmed);
        assertFalse(disk.readInTime);
        assertEquals(NOT_READ_IN_TIME, disk.describe());
        assertEquals("only the root's read reached a job: the claim its hung read keeps refused every read after it", //$NON-NLS-1$
            1, runner.timeouts.size());
        assertEquals("nothing was read or checked", 0, touches.get()); //$NON-NLS-1$
        assertEquals("no file was looked at in time, so none counts as seen", Arrays.asList(CMI, MAIN_CMI), //$NON-NLS-1$
            disk.missingFiles);
        assertNull("the hung read still holds the project's claim", BuildUtils.beginStateLineRead(project, 1_000L)); //$NON-NLS-1$

        runner.endKept();
        assertNotNull("released once the hung read's job ended", BuildUtils.beginStateLineRead(project, 1_000L)); //$NON-NLS-1$
    }

    /**
     * One claim per project's files, whoever reads them (#747 review, round 3): while #743's read of a module's state line
     * from a .mdo of the project is still out, the look starts no read of that project's files - every round is refused at
     * once and the root reads as not read in time - and once that read has returned, the look reads and confirms.
     */
    @Test
    public void testWhileAModuleStateReadOfTheProjectIsOutTheLookStartsNoRead()
    {
        String project = nextProject();
        AtomicBoolean stateLineRead = BuildUtils.beginStateLineRead(project, 60_000L);
        assertNotNull("a read of a module's state line is out", stateLineRead); //$NON-NLS-1$
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);
        AtomicInteger reads = new AtomicInteger();
        ExtensionRootBuilder.RootFileReader reader = () -> {
            reads.incrementAndGet();
            return WIZARD_ROOT;
        };

        ExtensionRootBuilder.RootOnDisk refused = ExtensionRootBuilder.confirmOnDisk(runner, project, ROOT,
            waitMs -> DiskExportState.DRAINED, reader, Collections.emptyList(), path -> true, 200L, 10L);

        assertFalse(refused.confirmed);
        assertEquals(NOT_READ_IN_TIME, refused.describe());
        assertTrue("no round started a job", runner.timeouts.isEmpty()); //$NON-NLS-1$
        assertEquals(0, reads.get());

        stateLineRead.set(true); // that read returned
        assertTrue("once it returned, the look reads and confirms", ExtensionRootBuilder.confirmOnDisk(runner, project, ROOT, //$NON-NLS-1$
            waitMs -> DiskExportState.DRAINED, reader, Collections.emptyList(), path -> true, 1_000L, 10L).confirmed);
        assertEquals(1, reads.get());
    }

    /**
     * The other way round (#747 review, round 3): while a round of the look is still in the file system, #743's reads of
     * the same project's module state lines start no job - they are refused at once - and another project's are not held
     * up.
     */
    @Test
    public void testAHungRoundOfTheLookRefusesTheModuleStateReadsOfItsProjectOnly()
    {
        String project = nextProject();
        ScriptedRunner look = new ScriptedRunner(BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.TIMED_OUT);
        assertFalse(ExtensionRootBuilder.confirmOnDisk(look, project, ROOT, waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
            Collections.emptyList(), path -> true, 100L, 10L).confirmed);

        ScriptedRunner stateLine = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(stateLine, project, "read", //$NON-NLS-1$
            1_000L, () -> AdoptedModuleState.LineOnDisk.PRESENT));
        assertTrue("refused at once: no job", stateLine.timeouts.isEmpty()); //$NON-NLS-1$
        assertEquals("another project's read is not held up", AdoptedModuleState.LineOnDisk.PRESENT, //$NON-NLS-1$
            AdoptedModuleState.readBounded(stateLine, nextProject(), "read", 1_000L, //$NON-NLS-1$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));

        look.endKept();
        assertEquals("once the look's read returned, the project's reads start again", //$NON-NLS-1$
            AdoptedModuleState.LineOnDisk.PRESENT, AdoptedModuleState.readBounded(stateLine, project, "read", 1_000L, //$NON-NLS-1$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));
    }

    /**
     * The production look, on EDT's job manager (#747 review, round 3): a root read the file system never answers does
     * not hold the call past the look's budget - the look ends with it, the root not confirmed and not read in time -
     * and the read's job, once the file system answers, ends and releases the project's claim.
     */
    @Test
    public void testARealReadThatNeverAnswersDoesNotHoldTheLookPastItsBudget() throws Exception
    {
        String project = nextProject();
        CountDownLatch answers = new CountDownLatch(1);
        AtomicBoolean entered = new AtomicBoolean();
        long budget = 1_500L;
        long start = System.nanoTime();
        ExtensionRootBuilder.RootOnDisk disk;
        try
        {
            disk = ExtensionRootBuilder.confirmOnDisk(project, ROOT, waitMs -> DiskExportState.DRAINED, () -> {
                entered.set(true);
                try
                {
                    answers.await(60, TimeUnit.SECONDS); // a file system that does not answer
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return WIZARD_ROOT;
            }, Collections.emptyList(), path -> true, budget, 10L);
        }
        finally
        {
            answers.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("the read ran, in a job of its own", entered.get()); //$NON-NLS-1$
        assertFalse(disk.confirmed);
        assertFalse(disk.readInTime);
        assertEquals(NOT_READ_IN_TIME, disk.describe());
        assertTrue("the look ended with its budget, not with the read: " + elapsedMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
            elapsedMs < budget + 2_000L);
        assertTrue("the read's job ended once the file system answered, and released the claim", //$NON-NLS-1$
            awaitReleased(project));
    }

    /**
     * The look keeps a second of its budget from the export wait for the root file (#747 review, round 4): the wait is
     * given the budget less that second, so a queue EDT did not drain in time cannot alone keep a root that is on disk
     * from being confirmed - the file still gets its look after a wait that used all it was given - and the look does not
     * run past its budget to give it one. The second is pinned as the literal it is, through what the wait is given, not
     * read from the constant: a pin built from the constant would follow any change to it.
     */
    @Test
    public void testTheExportWaitIsGivenTheBudgetLessTheSecondTheLookKeepsForTheRootFile()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);
        List<Long> given = new ArrayList<>();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT, waitMs -> {
            given.add(waitMs);
            sleep(waitMs); // a queue EDT does not drain: the wait takes all it is given
            return DiskExportState.PENDING;
        }, () -> WIZARD_ROOT, Collections.emptyList(), path -> true, 1_300L, 10L);

        assertEquals("the wait is given the budget less one second", Collections.singletonList(300L), given); //$NON-NLS-1$
        assertTrue("the look after the wait confirmed the root", disk.confirmed); //$NON-NLS-1$
        assertEquals(DiskExportState.PENDING, disk.exportState);
        assertEquals("one read, the root's: " + runner.timeouts, 1, runner.timeouts.size()); //$NON-NLS-1$
        assertTrue("the read got what the wait left - the second kept for it, not more: " + runner.timeouts, //$NON-NLS-1$
            runner.timeouts.get(0) > 700L && runner.timeouts.get(0) <= 1_000L);
    }

    /**
     * The whole look, the export wait included, ends within its budget (#747 review, round 4): a wait that takes all it is
     * given and BoundedJob's grace (0.5s) on top - its own job had not started by its deadline - and a read that answers
     * only at its own deadline still end with the budget, as the second the look keeps from the wait absorbs the wait's
     * grace; and the root file still gets its look. Before, the wait could take the whole budget, and the first read was
     * then given a second past it.
     */
    @Test
    public void testAWaitAndAReadThatEachTakeAllTheyAreGivenEndWithTheBudget()
    {
        long budget = 1_500L;
        long start = System.nanoTime();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(AT_THE_DEADLINE, nextProject(), ROOT,
            waitMs -> {
                sleep(waitMs + 500L); // all it is given, and BoundedJob's grace on top
                return DiskExportState.PENDING;
            }, () -> WIZARD_ROOT, Collections.emptyList(), path -> true, budget, 10L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("the root file still got its look", disk.confirmed); //$NON-NLS-1$
        assertTrue("the look ended with its budget, the wait included: " + elapsedMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
            elapsedMs < budget + 400L);
    }

    /**
     * A check of a file beside the root is given only what is left of the budget, as the root's read is (#747 review,
     * round 4): after a wait that takes all it is given and BoundedJob's grace on top, the root's read answers at once
     * and the check answers only at its own deadline - and the look still ends with its budget, the file seen. The
     * check got what the wait and the root's read left - at most 500 of the 1500 here - not the second the look keeps
     * for the root, nor a share of its own.
     */
    @Test
    public void testACheckOfAFileBesideTheRootIsGivenOnlyWhatIsLeftOfTheBudget()
    {
        long budget = 1_500L;
        List<Long> timeouts = new ArrayList<>();
        // The first read - the root's - answers at once; every later one - a check of the file - only at its deadline.
        AdoptedModuleState.BoundedRunner runner = (name, timeoutMs, work, jobEnded) -> {
            timeouts.add(timeoutMs);
            return timeouts.size() == 1 ? AT_ONCE.run(name, timeoutMs, work, jobEnded)
                : AT_THE_DEADLINE.run(name, timeoutMs, work, jobEnded);
        };
        long start = System.nanoTime();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT, waitMs -> {
            sleep(waitMs + 500L); // all it is given, and BoundedJob's grace on top
            return DiskExportState.PENDING;
        }, () -> WIZARD_ROOT, Collections.singletonList(CMI), path -> true, budget, 10L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("the root's read answered", disk.confirmed); //$NON-NLS-1$
        assertEquals("the check answered at its deadline: the file is seen", Collections.emptyList(), //$NON-NLS-1$
            disk.missingFiles);
        assertEquals("two reads, the root's and the check's: " + timeouts, 2, timeouts.size()); //$NON-NLS-1$
        assertTrue("the check got at most what the wait - 1000ms of the 1500 - left: " + timeouts, //$NON-NLS-1$
            timeouts.get(1) <= 500L);
        assertTrue("the look ended with its budget, the check included: " + elapsedMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
            elapsedMs < budget + 400L);
    }

    /**
     * No check of a file beside the root starts with nothing left of the budget (#747 review, round 4): when the
     * root's read takes all that the wait left, the check never reaches a job, the file is named as not seen, and the
     * look ends with its budget.
     */
    @Test
    public void testNoCheckOfAFileBesideTheRootStartsWithNothingLeftOfTheBudget()
    {
        long budget = 1_500L;
        List<Long> timeouts = new ArrayList<>();
        AdoptedModuleState.BoundedRunner runner = (name, timeoutMs, work, jobEnded) -> {
            timeouts.add(timeoutMs);
            return AT_THE_DEADLINE.run(name, timeoutMs, work, jobEnded);
        };
        long start = System.nanoTime();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT, waitMs -> {
            sleep(waitMs + 500L); // all it is given, and BoundedJob's grace on top
            return DiskExportState.PENDING;
        }, () -> WIZARD_ROOT, Collections.singletonList(CMI), path -> true, budget, 10L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("the root file still got its look", disk.confirmed); //$NON-NLS-1$
        assertEquals("only the root's read reached a job: the check had nothing left - " + timeouts, 1, //$NON-NLS-1$
            timeouts.size());
        assertEquals("the file no check saw is named", Collections.singletonList(CMI), disk.missingFiles); //$NON-NLS-1$
        assertTrue("the look ended with its budget: " + elapsedMs + "ms", elapsedMs < budget + 400L); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A budget no larger than the second the look keeps for the root file leaves the export wait nothing (#747 review,
     * round 4): the wait is not asked - the queue reads as not seen draining - and the look has the whole budget.
     */
    @Test
    public void testABudgetNoLargerThanTheKeptSecondLeavesTheExportWaitUnasked()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT, waitMs -> {
            throw new AssertionError("no wait with nothing to give it: " + waitMs); //$NON-NLS-1$
        }, () -> WIZARD_ROOT, Collections.emptyList(), path -> true, 1_000L, 10L);

        assertTrue(disk.confirmed);
        assertEquals("not seen draining", DiskExportState.PENDING, disk.exportState); //$NON-NLS-1$
        assertTrue("the read had the whole budget: " + runner.timeouts, //$NON-NLS-1$
            runner.timeouts.get(0) > 700L && runner.timeouts.get(0) <= 1_000L);
    }

    /**
     * Every read of every round is given what is left of the budget when it starts - the root's, while it is not
     * confirmed, and each check of a file beside it - and none starts with nothing left (#747 review, rounds 3 and 4).
     * Each read is measured against the budget's own clock, a deadline taken just before the look took its own: a read
     * given more than was left when it reached the runner - a floor, or a share of its own, in any round - fails it.
     * The first read gets the whole budget here, as the export wait took nothing.
     */
    @Test
    public void testEveryReadIsGivenWhatIsLeftOfTheBudgetAndNoneStartsWithNothingLeft()
    {
        // A first look loads the look's classes, so that only the look itself runs between the clock read below and the
        // look's own.
        look(waitMs -> DiskExportState.DRAINED, () -> null, Collections.singletonList(CMI), path -> false, 20L, 5L);
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);
        LongFunction<DiskExportState> drained = waitMs -> DiskExportState.DRAINED;
        ExtensionRootBuilder.RootFileReader absent = () -> null;
        Predicate<String> neverSeen = path -> false;
        List<String> files = Arrays.asList(CMI, MAIN_CMI);
        String project = nextProject();
        long budget = 1_200L;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget); // no later than the look's own

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, project, ROOT, drained, absent,
            files, neverSeen, budget, 50L);

        assertFalse(disk.confirmed);
        assertTrue(disk.readInTime);
        assertTrue(disk.describe(), disk.describe().contains("was not found on disk")); //$NON-NLS-1$
        assertEquals("no check saw a file", files, disk.missingFiles); //$NON-NLS-1$
        assertTrue("the poll went on, three reads a round: " + runner.timeouts, runner.timeouts.size() > 6); //$NON-NLS-1$
        assertTrue("the first read got what was left - the whole budget: " + runner.timeouts, //$NON-NLS-1$
            runner.timeouts.get(0) > budget - 300L && runner.timeouts.get(0) <= budget);
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < runner.timeouts.size(); i++)
        {
            long timeout = runner.timeouts.get(i);
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - runner.startedAt.get(i));
            assertTrue("no read starts with nothing left: " + runner.timeouts, timeout > 0); //$NON-NLS-1$
            // 50ms: the caller's thread may be descheduled between the look's clock read and the runner's.
            assertTrue("read " + i + " was given " + timeout + "ms with " + leftMs + "ms left: " + runner.timeouts, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                timeout <= leftMs + 50L);
            assertTrue("each read is given what is left, less each time: " + runner.timeouts, timeout <= previous); //$NON-NLS-1$
            previous = timeout;
        }
    }

    /**
     * A file a completed check saw stays seen (#747 review, round 3): a later check that did not complete in time saw
     * nothing, and must not make the look forget a file an earlier one saw - only the file no check saw is named. The
     * first round's three reads - the root's, then one per file - complete; every later one does not.
     */
    @Test
    public void testAFileSeenOnceStaysSeenWhenALaterRoundIsNotReadInTime()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START,
            BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.COMPLETED);
        List<String> asked = new ArrayList<>();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> {
                asked.add(path);
                return CMI.equals(path);
            }, 200L, 10L);

        assertTrue(disk.confirmed);
        assertTrue("the root's one read completed in time", disk.readInTime); //$NON-NLS-1$
        assertTrue("later rounds were tried: " + runner.timeouts, runner.timeouts.size() > 1); //$NON-NLS-1$
        assertEquals("only the first round ran its checks", Arrays.asList(CMI, MAIN_CMI), asked); //$NON-NLS-1$
        assertEquals("the file a completed round saw stays seen", Collections.singletonList(MAIN_CMI), //$NON-NLS-1$
            disk.missingFiles);
    }

    /** A file seen once is not looked for again (#747 review, round 3): each round checks only the files not seen yet. */
    @Test
    public void testARoundLooksOnlyForTheFilesNotSeenYet()
    {
        List<String> asked = new ArrayList<>();
        AtomicInteger cmiChecks = new AtomicInteger();

        ExtensionRootBuilder.RootOnDisk disk = look(waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
            Arrays.asList(CMI, MAIN_CMI), path -> {
                asked.add(path);
                return MAIN_CMI.equals(path) || cmiChecks.incrementAndGet() > 1;
            }, 5_000L, 10L);

        assertTrue(disk.confirmed);
        assertEquals(Collections.emptyList(), disk.missingFiles);
        assertEquals("the second round looked only for the file the first did not see", //$NON-NLS-1$
            Arrays.asList(CMI, MAIN_CMI, CMI), asked);
    }

    /**
     * A read that completed counts whatever a later read does (#747 review, round 4): the root's read and the check of the
     * first file beside it answer, the check of the second never does - the root stays confirmed and read in time, the
     * file the first check saw stays seen, and only the file whose check did not answer is named. The hung check keeps
     * the project's claim, so no read starts after it, and it releases the claim once its job ends.
     */
    @Test
    public void testAReadThatCompletedCountsWhateverALaterCheckDoes()
    {
        String project = nextProject();
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.COMPLETED,
            BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.TIMED_OUT);
        List<String> asked = new ArrayList<>();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, project, ROOT,
            waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> {
                asked.add(path);
                return true;
            }, 300L, 10L);

        assertTrue("the root's read answered: the root is confirmed", disk.confirmed); //$NON-NLS-1$
        assertTrue(disk.readInTime);
        assertEquals("only the file whose check did not answer is named", Collections.singletonList(MAIN_CMI), //$NON-NLS-1$
            disk.missingFiles);
        assertEquals("three reads reached a job - the root's, then one per file - and none after the hung one", 3, //$NON-NLS-1$
            runner.timeouts.size());
        assertEquals("the hung check did not get to check", Collections.singletonList(CMI), asked); //$NON-NLS-1$
        assertNull("the hung check holds the project's claim", BuildUtils.beginStateLineRead(project, 1_000L)); //$NON-NLS-1$

        runner.endKept();
        assertNotNull("released once the hung check's job ended", BuildUtils.beginStateLineRead(project, 1_000L)); //$NON-NLS-1$
    }

    /**
     * On EDT's job manager (#747 review, round 4): the root file and the first file beside it answer, the check of the
     * second never does. The look ends with its budget, the root confirmed and read in time, the first file seen, and only
     * the file that did not answer named - and the hung check's job, once the file system answers, releases the claim.
     * Before, the three shared one job: the hung check discarded the root's completed read, so the root read as not read
     * in time and create_project answered "created".
     */
    @Test
    public void testOnEdtsJobManagerACheckThatNeverAnswersCostsNoCompletedRead() throws Exception
    {
        String project = nextProject();
        CountDownLatch answers = new CountDownLatch(1);
        AtomicBoolean entered = new AtomicBoolean();
        long budget = 1_500L;
        long start = System.nanoTime();
        ExtensionRootBuilder.RootOnDisk disk;
        try
        {
            disk = ExtensionRootBuilder.confirmOnDisk(project, ROOT, waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
                Arrays.asList(CMI, MAIN_CMI), path -> {
                    if (MAIN_CMI.equals(path))
                    {
                        entered.set(true);
                        await(answers); // a file system that does not answer this check
                    }
                    return true;
                }, budget, 10L);
        }
        finally
        {
            answers.countDown();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("the check that never answers ran, in a job of its own", entered.get()); //$NON-NLS-1$
        assertTrue("the root's completed read counts", disk.confirmed); //$NON-NLS-1$
        assertTrue(disk.readInTime);
        assertEquals("only the file that did not answer is named", Collections.singletonList(MAIN_CMI), //$NON-NLS-1$
            disk.missingFiles);
        assertTrue("the look ended with its budget: " + elapsedMs + "ms", elapsedMs < budget + 2_000L); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the hung check's job ended once the file system answered, and released the claim", //$NON-NLS-1$
            awaitReleased(project));
    }

    /**
     * An interrupted look starts no further read (#747 review, round 4): the read the interrupt cut - BoundedJob keeps the
     * caller's interrupt flag - is the last one; the root reads as not read in time, no file as seen, and the flag stays
     * set for the caller.
     */
    @Test
    public void testAnInterruptedLookStartsNoFurtherRead()
    {
        List<Long> timeouts = new ArrayList<>();
        AdoptedModuleState.BoundedRunner interrupted = (name, timeoutMs, work, jobEnded) -> {
            timeouts.add(timeoutMs);
            Thread.currentThread().interrupt(); // the call is cancelled while it waits for the read
            jobEnded.run();
            return new BoundedJob.Result(BoundedJob.Outcome.INTERRUPTED, 0L, new InterruptedException());
        };

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(interrupted, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> true, 5_000L,
            10L);
        boolean flagKept = Thread.interrupted(); // clears it, too

        assertTrue("the interrupt is kept for the caller", flagKept); //$NON-NLS-1$
        assertEquals("only the read the interrupt cut started", 1, timeouts.size()); //$NON-NLS-1$
        assertFalse(disk.confirmed);
        assertFalse(disk.readInTime);
        assertEquals(Arrays.asList(CMI, MAIN_CMI), disk.missingFiles);
    }

    // ==================== the last completed read of the root decides (#747 review, round 4) ====================

    /** What a root's description adds when a read completed but a later one did not. */
    private static final String LATER_READ_NOTE = "; a later read of it did not complete"; //$NON-NLS-1$

    /**
     * A later read of the root that did not complete erases nothing an earlier, completed one found
     * (#747 review, round 4): the root's first read completed and found no file, and every later one was given up on
     * before a worker ran it, as BoundedJob gives up on a read left a sliver of the budget. The root is described by
     * what its completed read found, not as one that could not be read in time, with a note that a later read did not
     * complete: the description is of a read made before it. Before, the root's last read decided, and a later read
     * that did not complete turned the answer into "could not be read in time".
     */
    @Test
    public void testALaterRootReadThatDidNotCompleteErasesNothingTheCompletedOneFound()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START,
            BoundedJob.Outcome.COMPLETED);

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> null, Collections.emptyList(), path -> true, 500L, 10L);

        assertTrue("later reads of the root were made: " + runner.timeouts, runner.timeouts.size() > 1); //$NON-NLS-1$
        assertFalse(disk.confirmed);
        assertTrue("a read of the root completed in time", disk.readInTime); //$NON-NLS-1$
        assertFalse("its last read did not", disk.lastReadCompleted); //$NON-NLS-1$
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " was not found on disk" + LATER_READ_NOTE, disk.describe()); //$NON-NLS-1$
        assertFalse(disk.fileFound);
        assertNull(disk.readFailure);
    }

    /**
     * The same when the later read never answers (#747 review, round 4): the root's first read completed and found the
     * file without the wizard's marks; the second hangs and keeps the project's claim, so every read after it is
     * refused at once. The root is still described by its completed read, the marks the file did not show, not as one
     * that could not be read in time - and the hung read is named: the description is of a read made before it, not at
     * the end of the budget.
     */
    @Test
    public void testARootReadThatNeverAnswersAfterACompletedOneLeavesWhatThatOneFound()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.COMPLETED,
            BoundedJob.Outcome.TIMED_OUT);
        ExtensionRootBuilder.RootOnDisk disk;
        try
        {
            disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT, waitMs -> DiskExportState.DRAINED,
                () -> FACTORY_ROOT, Collections.emptyList(), path -> true, 500L, 10L);
        }
        finally
        {
            runner.endKept(); // the file system answers the hung read: its job ends, and the claim is released
        }

        assertEquals("the completed read, then the hung one, whose claim refused every read after it: " //$NON-NLS-1$
            + runner.timeouts, 2, runner.timeouts.size());
        assertFalse(disk.confirmed);
        assertTrue("a read of the root completed in time", disk.readInTime); //$NON-NLS-1$
        assertFalse("its last read did not", disk.lastReadCompleted); //$NON-NLS-1$
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " does not show the ConfigurationExtension block " //$NON-NLS-1$
            + "or keepMappingToExtendedConfigurationObjectsByIDs=true yet" + LATER_READ_NOTE, disk.describe()); //$NON-NLS-1$
        assertTrue(disk.fileFound);
        assertNull(disk.readFailure);
    }

    /**
     * A failure a completed read met is kept the same way (#747 review, round 4): the root's first read completed and
     * could not read the file, and every later one did not complete. The root is described by that failure, not as one
     * that could not be read in time, with a note that a later read did not complete.
     */
    @Test
    public void testALaterRootReadThatDidNotCompleteKeepsTheFailureTheCompletedOneMet()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START,
            BoundedJob.Outcome.COMPLETED);

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> {
                throw new IOException("sharing violation"); //$NON-NLS-1$
            }, Collections.emptyList(), path -> true, 500L, 10L);

        assertTrue("later reads of the root were made: " + runner.timeouts, runner.timeouts.size() > 1); //$NON-NLS-1$
        assertFalse(disk.confirmed);
        assertTrue("a read of the root completed in time", disk.readInTime); //$NON-NLS-1$
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " could not be read (sharing violation)" + LATER_READ_NOTE, //$NON-NLS-1$
            disk.describe());
    }

    /**
     * Only while no read of the root completed is it one that could not be read in time (#747 review, round 4): every
     * read the look made was given up on before it ran, so no read found anything, and the file itself is what none
     * showed.
     */
    @Test
    public void testWhileNoReadOfTheRootCompletedItIsNotReadInTime()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START);
        AtomicInteger reads = new AtomicInteger();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> {
                reads.incrementAndGet();
                return WIZARD_ROOT;
            }, Collections.emptyList(), path -> true, 500L, 10L);

        assertTrue("reads of the root were made: " + runner.timeouts, runner.timeouts.size() > 1); //$NON-NLS-1$
        assertEquals("none of them ran", 0, reads.get()); //$NON-NLS-1$
        assertFalse(disk.confirmed);
        assertFalse(disk.readInTime);
        assertEquals(NOT_READ_IN_TIME, disk.describe());
        assertEquals(ExtensionRootBuilder.missingOnDisk(null, ROOT), disk.missing);
        assertFalse(disk.fileFound);
        assertNull(disk.readFailure);
    }

    /**
     * A later read that completed replaces all that an earlier one found (#747 review, round 4): the root's first read
     * completed and could not read the file, the second was given up on before it ran, and every later one completed
     * and found the file without the wizard's marks. The root is described by the last completed read, and the failure
     * the first one met is gone - as is the note of the read that did not complete: the last read completed.
     */
    @Test
    public void testALaterCompletedReadOfTheRootReplacesAllThatAnEarlierOneFound()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED, BoundedJob.Outcome.COMPLETED,
            BoundedJob.Outcome.TIMED_OUT_BEFORE_START);
        AtomicInteger reads = new AtomicInteger();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED, () -> {
                if (reads.incrementAndGet() == 1)
                {
                    throw new IOException("sharing violation"); //$NON-NLS-1$
                }
                return FACTORY_ROOT;
            }, Collections.emptyList(), path -> true, 500L, 10L);

        assertTrue("a later read of the root completed: " + reads.get(), reads.get() > 1); //$NON-NLS-1$
        assertFalse(disk.confirmed);
        assertTrue(disk.readInTime);
        assertTrue("the last read of the root completed", disk.lastReadCompleted); //$NON-NLS-1$
        assertNull("the failure the first read met is gone", disk.readFailure); //$NON-NLS-1$
        assertTrue(disk.fileFound);
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " does not show the ConfigurationExtension block " //$NON-NLS-1$
            + "or keepMappingToExtendedConfigurationObjectsByIDs=true yet", disk.describe()); //$NON-NLS-1$
    }

    /**
     * The four answers a root not confirmed can get tell their causes apart (#747 review, round 3): not read in time, read
     * and failed, read and not there, read and lacking the wizard's shape.
     */
    @Test
    public void testTheDescriptionOfARootNotConfirmedNamesItsCause()
    {
        assertEquals(NOT_READ_IN_TIME, ExtensionRootBuilder.confirmOnDisk(
            new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START), nextProject(), ROOT,
            waitMs -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, Collections.emptyList(), path -> true, 50L, 10L).describe());
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " could not be read (sharing violation)", //$NON-NLS-1$
            look(waitMs -> DiskExportState.DRAINED, () -> {
                throw new IOException("sharing violation"); //$NON-NLS-1$
            }, 50L, 10L).describe());
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " was not found on disk", //$NON-NLS-1$
            look(waitMs -> DiskExportState.DRAINED, () -> null, 50L, 10L).describe());
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " does not show the ConfigurationExtension block or " //$NON-NLS-1$
            + "keepMappingToExtendedConfigurationObjectsByIDs=true yet", //$NON-NLS-1$
            look(waitMs -> DiskExportState.DRAINED, () -> FACTORY_ROOT, 50L, 10L).describe());
    }

    /**
     * Answers each read only at its deadline: waits out the timeout it was given, then runs the read and reports it
     * completed - a file system that answers, as late as the look allows.
     */
    private static final AdoptedModuleState.BoundedRunner AT_THE_DEADLINE = (name, timeoutMs, work, jobEnded) -> {
        sleep(timeoutMs);
        return AT_ONCE.run(name, timeoutMs, work, jobEnded);
    };

    /** Sleeps {@code ms}; an interrupt ends the sleep and is kept. */
    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits, within a minute, for {@code answers}: a file system that does not answer until it is counted down. */
    private static void await(CountDownLatch answers)
    {
        try
        {
            answers.await(60, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits, within ten seconds, until {@code project} holds no claim on reads of its files. */
    private static boolean awaitReleased(String project) throws InterruptedException
    {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (BuildUtils.stateLineReadHoldLeftMs(project) > 0)
        {
            if (System.nanoTime() - until >= 0)
            {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }
}
