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
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.UNOBSERVABLE,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.PENDING,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
            () -> WIZARD_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> !MAIN_CMI.equals(path), 300L, 20L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue("the root alone decides 'confirmed'", disk.confirmed); //$NON-NLS-1$
        assertEquals(Collections.singletonList(MAIN_CMI), disk.missingFiles);
        assertTrue("the same budget bounds the files, took " + elapsedMs + " ms", elapsedMs < 3_000L); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFilesOnDiskNeverConfirmARootThatIsNot()
    {
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
            () -> FACTORY_ROOT, Arrays.asList(CMI, MAIN_CMI), path -> true, 100L, 10L);

        assertFalse(disk.confirmed);
        assertEquals(Collections.emptyList(), disk.missingFiles);
        assertEquals(2, disk.missing.size());
    }

    @Test
    public void testAFileCheckThatThrowsCountsTheFileAsNotSeen()
    {
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
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
        ExtensionRootBuilder.RootOnDisk disk = ExtensionRootBuilder.confirmOnDisk(() -> DiskExportState.DRAINED,
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
}
