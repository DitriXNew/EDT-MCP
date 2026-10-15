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
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    /** The extension block and the mapping flag, as EDT serializes a wizard-made root. */
    private static final String WIZARD_ROOT = "<mdclass:Configuration>\n" //$NON-NLS-1$
        + "  <extension xsi:type=\"mdclassExtension:ConfigurationExtension\">\n" //$NON-NLS-1$
        + "    <defaultRunMode>Checked</defaultRunMode>\n" //$NON-NLS-1$
        + "  </extension>\n" //$NON-NLS-1$
        + "  <keepMappingToExtendedConfigurationObjectsByIDs>true</keepMappingToExtendedConfigurationObjectsByIDs>\n" //$NON-NLS-1$
        + "</mdclass:Configuration>\n"; //$NON-NLS-1$

    /** A factory-made root: base-only properties, no block, no mapping flag (the #706 report). */
    private static final String FACTORY_ROOT = "<mdclass:Configuration>\n" //$NON-NLS-1$
        + "  <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
        + "  <dataLockControlMode>Managed</dataLockControlMode>\n" //$NON-NLS-1$
        + "</mdclass:Configuration>\n"; //$NON-NLS-1$

    @Test
    public void testAWizardShapedRootLacksNothing()
    {
        assertEquals(Collections.emptyList(), ExtensionRootBuilder.missingOnDisk(WIZARD_ROOT));
    }

    @Test
    public void testAFactoryShapedRootLacksBothMarks()
    {
        List<String> missing = ExtensionRootBuilder.missingOnDisk(FACTORY_ROOT);
        assertEquals(2, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("ConfigurationExtension")); //$NON-NLS-1$
        assertTrue(missing.toString(), missing.get(1).contains("keepMappingToExtendedConfigurationObjectsByIDs")); //$NON-NLS-1$
    }

    @Test
    public void testAFalseMappingFlagIsNotTheMark()
    {
        String text = WIZARD_ROOT.replace(">true</keepMapping", ">false</keepMapping"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> missing = ExtensionRootBuilder.missingOnDisk(text);
        assertEquals(1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("keepMappingToExtendedConfigurationObjectsByIDs")); //$NON-NLS-1$
    }

    @Test
    public void testAnAbsentFileIsNotConfirmed()
    {
        assertFalse(ExtensionRootBuilder.missingOnDisk(null).isEmpty());
    }

    @Test
    public void testADrainedExportWithTheMarksOnDiskConfirmsTheRootAtTheFirstRead()
    {
        AtomicInteger reads = new AtomicInteger();
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
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
        // write that failed, so this is NOT a confirmation, however long the poll is allowed to run.
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
            () -> FACTORY_ROOT, 200L, 10L);

        assertFalse(disk.confirmed);
        assertEquals(2, disk.missing.size());
        assertTrue(disk.describe(), disk.describe().contains("ConfigurationExtension")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnobservableExportIsPolledUntilTheMarksAppear()
    {
        List<String> answers = new ArrayList<>(Arrays.asList(null, FACTORY_ROOT, WIZARD_ROOT));
        AtomicInteger reads = new AtomicInteger();
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.UNOBSERVABLE,
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
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.PENDING,
            () -> null, 300L, 20L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse(disk.confirmed);
        assertEquals(DiskExportState.PENDING, disk.exportState);
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
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> !MAIN_CMI.equals(path), 300L, 20L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue("the root alone decides 'confirmed'", disk.confirmed); //$NON-NLS-1$
        assertEquals(Collections.singletonList(MAIN_CMI), disk.missingFiles);
        assertTrue("the same budget bounds the files, took " + elapsedMs + " ms", elapsedMs < 3_000L); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFilesOnDiskNeverConfirmARootThatIsNot()
    {
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
            () -> FACTORY_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> true, 100L, 10L);

        assertFalse(disk.confirmed);
        assertEquals(Collections.emptyList(), disk.missingFiles);
        assertEquals(2, disk.missing.size());
    }

    @Test
    public void testAFileCheckThatThrowsCountsTheFileAsNotSeen()
    {
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED,
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

    /** The description of a root whose last read did not complete in time. */
    private static final String NOT_READ_IN_TIME = ExtensionRootBuilder.ROOT_FILE + " could not be read in time (the read " //$NON-NLS-1$
        + "did not complete, or an earlier read of the project's files had not returned)"; //$NON-NLS-1$

    /** Project names: each look claims its own project's slot, so no look is refused for another test's claim. */
    private static final AtomicInteger PROJECTS = new AtomicInteger();

    private static String nextProject()
    {
        return "ExtensionRootBuilderTest" + PROJECTS.incrementAndGet(); //$NON-NLS-1$
    }

    /**
     * Runs each round at once, on the caller's thread, and reports it completed and its job ended: a file system that
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

    /** A look at the root alone, whose rounds a file system that answers reads at once. */
    private static ExtensionRootBuilder.RootOnDisk look(Supplier<DiskExportState> exportWait,
        ExtensionRootBuilder.RootFileReader reader, long budgetMs, long pollMs)
    {
        return look(exportWait, reader, Collections.emptyList(), path -> true, budgetMs, pollMs);
    }

    /** A look at the root and the files beside it, whose rounds a file system that answers reads at once. */
    private static ExtensionRootBuilder.RootOnDisk look(Supplier<DiskExportState> exportWait, // NOSONAR the look's own parameters, one each
        ExtensionRootBuilder.RootFileReader reader, List<String> files, Predicate<String> fileExists, long budgetMs,
        long pollMs)
    {
        return ExtensionRootBuilder.confirmOnDisk(AT_ONCE, nextProject(), exportWait, reader, files, fileExists,
            budgetMs, pollMs);
    }

    /**
     * Answers each round from a script - COMPLETED runs the round at once, any other outcome does not - then every later
     * round with {@code rest}, and records the timeout each round was given. A TIMED_OUT round keeps its job, and so its
     * project's claim, until {@link #endKept}: a read the file system has not answered. Any other round that did not run
     * ends its job at once, as a job cancelled before it started does.
     */
    private static final class ScriptedRunner implements AdoptedModuleState.BoundedRunner
    {
        private final BoundedJob.Outcome rest;
        private final List<BoundedJob.Outcome> script;
        final List<Long> timeouts = new ArrayList<>();
        private final List<Runnable> kept = new ArrayList<>();

        ScriptedRunner(BoundedJob.Outcome rest, BoundedJob.Outcome... script)
        {
            this.rest = rest;
            this.script = new ArrayList<>(Arrays.asList(script));
        }

        @Override
        public BoundedJob.Result run(String jobName, long timeoutMs, BoundedJob.IBoundedWork work, Runnable jobEnded)
        {
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

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(),
            () -> DiskExportState.DRAINED, () -> {
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

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, project,
            () -> DiskExportState.DRAINED, () -> {
                touches.incrementAndGet();
                return WIZARD_ROOT;
            }, Arrays.asList(CMI, MAIN_CMI), path -> touches.incrementAndGet() > 0, 300L, 10L);

        assertFalse(disk.confirmed);
        assertFalse(disk.readInTime);
        assertEquals(NOT_READ_IN_TIME, disk.describe());
        assertEquals("only the first round reached a job: the claim its hung read keeps refused every later one", 1, //$NON-NLS-1$
            runner.timeouts.size());
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

        ExtensionRootBuilder.RootOnDisk refused = ExtensionRootBuilder.confirmOnDisk(runner, project,
            () -> DiskExportState.DRAINED, reader, Collections.emptyList(), path -> true, 200L, 10L);

        assertFalse(refused.confirmed);
        assertEquals(NOT_READ_IN_TIME, refused.describe());
        assertTrue("no round started a job", runner.timeouts.isEmpty()); //$NON-NLS-1$
        assertEquals(0, reads.get());

        stateLineRead.set(true); // that read returned
        assertTrue("once it returned, the look reads and confirms", ExtensionRootBuilder.confirmOnDisk(runner, project, //$NON-NLS-1$
            () -> DiskExportState.DRAINED, reader, Collections.emptyList(), path -> true, 1_000L, 10L).confirmed);
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
        assertFalse(ExtensionRootBuilder.confirmOnDisk(look, project, () -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
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
            disk = ExtensionRootBuilder.confirmOnDisk(project, () -> DiskExportState.DRAINED, () -> {
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
     * An export wait that used the whole budget still leaves the root file one look (#747 review, round 3), as the look
     * always gave it: the first round is given at least {@link ExtensionRootBuilder#FIRST_LOOK_MIN_MS}, so a queue EDT did
     * not drain in time cannot alone keep a root that is on disk from being confirmed. The minimum is pinned as the
     * literal second it is, not read from the constant: a pin built from the constant would follow any change to it.
     */
    @Test
    public void testAnExportWaitThatUsedTheBudgetStillLeavesTheFileOneLook()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(), () -> {
            try
            {
                Thread.sleep(150L); // longer than the whole budget
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return DiskExportState.PENDING;
        }, () -> WIZARD_ROOT, Collections.emptyList(), path -> true, 50L, 10L);

        assertTrue("the one look after the wait confirmed the root", disk.confirmed); //$NON-NLS-1$
        assertEquals(DiskExportState.PENDING, disk.exportState);
        assertEquals("one round, given the minimum - one second", Collections.singletonList(1_000L), //$NON-NLS-1$
            runner.timeouts);
    }

    /**
     * Each round is given what is left of the budget - the first one all of it here, as the export wait took nothing -
     * and none starts with nothing left (#747 review, round 3).
     */
    @Test
    public void testEachRoundIsGivenWhatIsLeftOfTheBudgetAndNoneStartsWithNothingLeft()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.COMPLETED);
        long budget = 1_200L;

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(),
            () -> DiskExportState.DRAINED, () -> null, Collections.emptyList(), path -> true, budget, 50L);

        assertFalse(disk.confirmed);
        assertTrue(disk.readInTime);
        assertTrue(disk.describe(), disk.describe().contains("was not found on disk")); //$NON-NLS-1$
        assertTrue("the poll went on: " + runner.timeouts, runner.timeouts.size() > 2); //$NON-NLS-1$
        assertTrue("the first round got what was left - the whole budget: " + runner.timeouts, //$NON-NLS-1$
            runner.timeouts.get(0) > budget - 300L && runner.timeouts.get(0) <= budget);
        long previous = Long.MAX_VALUE;
        for (long timeout : runner.timeouts)
        {
            assertTrue("no round starts with nothing left: " + runner.timeouts, timeout > 0); //$NON-NLS-1$
            assertTrue("each round is given what is left, less each time: " + runner.timeouts, timeout <= previous); //$NON-NLS-1$
            previous = timeout;
        }
    }

    /**
     * A file a completed round saw stays seen (#747 review, round 3): a later round that did not complete in time saw
     * nothing, and must not make the look forget a file it saw - only the file no round saw is named.
     */
    @Test
    public void testAFileSeenOnceStaysSeenWhenALaterRoundIsNotReadInTime()
    {
        ScriptedRunner runner = new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START,
            BoundedJob.Outcome.COMPLETED);
        List<String> asked = new ArrayList<>();

        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(runner, nextProject(),
            () -> DiskExportState.DRAINED, () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> {
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

        ExtensionRootBuilder.RootOnDisk disk = look(() -> DiskExportState.DRAINED, () -> WIZARD_ROOT,
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
     * The four answers a root not confirmed can get tell their causes apart (#747 review, round 3): not read in time, read
     * and failed, read and not there, read and lacking the wizard's shape.
     */
    @Test
    public void testTheDescriptionOfARootNotConfirmedNamesItsCause()
    {
        assertEquals(NOT_READ_IN_TIME, ExtensionRootBuilder.confirmOnDisk(
            new ScriptedRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START), nextProject(), () -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, Collections.emptyList(), path -> true, 50L, 10L).describe());
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " could not be read (sharing violation)", //$NON-NLS-1$
            look(() -> DiskExportState.DRAINED, () -> {
                throw new IOException("sharing violation"); //$NON-NLS-1$
            }, 50L, 10L).describe());
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " was not found on disk", //$NON-NLS-1$
            look(() -> DiskExportState.DRAINED, () -> null, 50L, 10L).describe());
        assertEquals(ExtensionRootBuilder.ROOT_FILE + " does not show the ConfigurationExtension block or " //$NON-NLS-1$
            + "keepMappingToExtendedConfigurationObjectsByIDs=true yet", //$NON-NLS-1$
            look(() -> DiskExportState.DRAINED, () -> FACTORY_ROOT, 50L, 10L).describe());
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
