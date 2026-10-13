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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.BmBatchSessionException;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState.ModuleAddress;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState.Outcome;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState.Result;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState.Verdict;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleStateTestFixtures.FakeEnvironment;

/**
 * Tests for {@link AdoptedModuleState} (issue #711): the module of an object ADOPTED into a
 * configuration extension is switched from None to Extended after its text is written, the way EDT
 * switches it when code is added to an adopted module, and the owner's {@code .mdo} is exported.
 * <p>
 * Three layers, each pinned on its own: the strict path parse ({@link AdoptedModuleState#moduleAddress}),
 * the stem-to-feature map ({@link AdoptedModuleState#moduleFeature}) and the state decision
 * ({@link AdoptedModuleState#decide}) on real metadata objects; then the orchestration
 * ({@code ensureExtended}) against a fake platform whose BM model runs every task at once, so the
 * transaction KIND of each call, the object it was made on and the order of export and wait are
 * observable. Russian names are built from code points so a non-UTF-8 build cannot corrupt them.
 */
public class AdoptedModuleStateTest
{
    /** "Вычисление" (Calculation) - a Cyrillic object Name. */
    private static final String RU_NAME = fromCp(0x0412, 0x044B, 0x0447, 0x0438, 0x0441, 0x043B, 0x0435,
        0x043D, 0x0438, 0x0435);

    private static final String DRY_SIGNAL = "src/CommonModules/DrySignal/Module.bsl"; //$NON-NLS-1$

    /** The root's ordinary-application module, which EDT does not list as extensible. */
    private static final String ORDINARY = "src/Configuration/OrdinaryApplicationModule.bsl"; //$NON-NLS-1$

    /**
     * The claim round 2 of #711 removed - that EDT leaves a module that is not Extended out of the
     * extension it exports and loads into the infobase - in every phrasing it had: the stand showed the
     * XML dump carrying such a module, and the infobase load was never tested. No text may say it again.
     */
    private static final String[] REMOVED_CLAIM = { "infobase", "left out", "leaves the module", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "exports and loads", "whatever its state", "Without Extended", "Nothing depends", "never runs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "never reaches" }; //$NON-NLS-1$

    /**
     * The claims the round-2 gate of #711 found broader than EDT's bytecode, in every phrasing they had: that
     * EDT never switches a Checked or Notify state - its listener asks for None first, but its module adopter,
     * which its UI runs to add a method to an extension, sets Extended without asking - and that EDT lists
     * every module but the ordinary-application one as extensible - 8.3.8's property-type delegate lists none,
     * and no delegate lists a recalculation's. No text may say them again.
     */
    private static final String[] OVERBROAD_CLAIM = { "like EDT,", "EDT never switches", "which EDT never", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "EDT only switches", "every other module" }; //$NON-NLS-1$ //$NON-NLS-2$

    /** What the kept-state note compares the step with: EDT's own listener, which asks for None first. */
    private static final String LIKE_THE_LISTENER = "was left as it is: like EDT's own listener, this tool only " //$NON-NLS-1$
        + "switches a module's state from None to Extended."; //$NON-NLS-1$

    /** The verified facts the switch paragraph states instead. */
    private static final String UI_RECORDS =
        "EDT's UI records this state in the .mdo when code is added to an adopted module"; //$NON-NLS-1$
    private static final String LISTENER_ONLY_MODEL = "EDT's own listener sets it only in the model, a few " //$NON-NLS-1$
        + "seconds after the file is written, and never saves the .mdo"; //$NON-NLS-1$

    /**
     * The note on the ordinary-application module: a fact of EDT's property types, compared with no other
     * module, and no export claim.
     */
    private static final String ORDINARY_FACT = "EDT does not list the ordinary-application module among the " //$NON-NLS-1$
        + "properties an extension can extend, so this does not fail the write"; //$NON-NLS-1$

    private Configuration root;
    private CommonModule drySignal;
    private CommonModule own;
    private Catalog catalog;
    private CatalogCommand fill;
    private IMdAdoptedPropertyAccess access;
    private IBmTransaction tx;
    private boolean[] inWrite;
    private FakeEnvironment env;
    private IProject project;

    @Before
    public void setUp()
    {
        root = AdoptedModuleStateTestFixtures.extensionWithAdoptedModule();
        drySignal = root.getCommonModules().get(0);
        own = MdClassFactory.eINSTANCE.createCommonModule();
        own.setName("tests_Own"); //$NON-NLS-1$
        own.setObjectBelonging(ObjectBelonging.NATIVE);
        root.getCommonModules().add(own);
        catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("X"); //$NON-NLS-1$
        catalog.setObjectBelonging(ObjectBelonging.ADOPTED);
        catalog.setExtension(MdClassExtensionFactory.eINSTANCE.createCatalogExtension());
        fill = MdClassFactory.eINSTANCE.createCatalogCommand();
        fill.setName("Fill"); //$NON-NLS-1$
        fill.setObjectBelonging(ObjectBelonging.ADOPTED);
        fill.setExtension(MdClassExtensionFactory.eINSTANCE.createBasicCommandExtension());
        catalog.getCommands().add(fill);
        root.getCatalogs().add(catalog);

        freshPlatform();
        project = AdoptedModuleStateTestFixtures.project();
    }

    /** A new property service, transaction and fake platform - for a test that runs several scenarios. */
    private void freshPlatform()
    {
        access = mock(IMdAdoptedPropertyAccess.class);
        tx = AdoptedModuleStateTestFixtures.identityTransaction();
        inWrite = new boolean[1];
        env = new FakeEnvironment();
        env.model = AdoptedModuleStateTestFixtures.model(tx, inWrite);
        env.access = access;
    }

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    /**
     * No phrasing of the removed export/infobase claim, and none of the claims broader than EDT's bytecode,
     * is in {@code text}.
     */
    private static void assertNoRemovedClaim(String what, String text)
    {
        for (String fragment : REMOVED_CLAIM)
        {
            assertFalse(what + " must claim no export or infobase consequence ('" + fragment + "'): " + text, //$NON-NLS-1$ //$NON-NLS-2$
                text.contains(fragment));
        }
        for (String fragment : OVERBROAD_CLAIM)
        {
            assertFalse(what + " must claim nothing broader than EDT's bytecode ('" + fragment + "'): " + text, //$NON-NLS-1$ //$NON-NLS-2$
                text.contains(fragment));
        }
    }

    /** The access reports {@code state} for every object and feature it is asked about. */
    private void reports(MdPropertyState state)
    {
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(state)))
            .thenReturn(Boolean.TRUE);
    }

    private Result run(String path)
    {
        return AdoptedModuleState.ensureExtended(project, MetadataScope.ofConfiguration(root), path, env);
    }

    // ==================== moduleAddress: the strict path parse ====================

    @Test
    public void testACommonModulePathAddressesItsOwnerAndItsStem()
    {
        ModuleAddress address = AdoptedModuleState.moduleAddress("CommonModules/Calc/Module.bsl"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals("CommonModule.Calc", address.ownerFqn); //$NON-NLS-1$
        assertEquals("Module", address.stem); //$NON-NLS-1$
        assertFalse(address.isConfiguration());
        assertFalse(address.isOrdinaryApplicationModule());
    }

    @Test
    public void testTheSourceFolderAndBackslashesAreAccepted()
    {
        ModuleAddress address = AdoptedModuleState.moduleAddress("src\\Catalogs\\Goods\\ObjectModule.bsl"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals("Catalog.Goods", address.ownerFqn); //$NON-NLS-1$
        assertEquals("ObjectModule", address.stem); //$NON-NLS-1$
    }

    @Test
    public void testACyrillicNameIsKeptAsTheProgrammaticName()
    {
        ModuleAddress address = AdoptedModuleState.moduleAddress("src/CommonModules/" + RU_NAME + "/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(address);
        assertEquals("CommonModule." + RU_NAME, address.ownerFqn); //$NON-NLS-1$
        assertEquals("Module", address.stem); //$NON-NLS-1$
    }

    @Test
    public void testAnObjectCommandModuleIsOwnedByTheCommand()
    {
        ModuleAddress address =
            AdoptedModuleState.moduleAddress("src/Catalogs/X/Commands/Fill/CommandModule.bsl"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals("Catalog.X.Command.Fill", address.ownerFqn); //$NON-NLS-1$
        assertEquals("CommandModule", address.stem); //$NON-NLS-1$
    }

    @Test
    public void testACommonCommandModuleIsOwnedByTheCommonCommand()
    {
        ModuleAddress address = AdoptedModuleState.moduleAddress("CommonCommands/C/CommandModule.bsl"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals("CommonCommand.C", address.ownerFqn); //$NON-NLS-1$
    }

    @Test
    public void testAConfigurationModuleIsOwnedByTheRoot()
    {
        ModuleAddress address = AdoptedModuleState.moduleAddress("src/Configuration/SessionModule.bsl"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals("Configuration", address.ownerFqn); //$NON-NLS-1$
        assertTrue(address.isConfiguration());
        assertEquals("SessionModule", address.stem); //$NON-NLS-1$
        assertFalse(address.isOrdinaryApplicationModule());
        assertTrue("known from the path alone - also without the model", //$NON-NLS-1$
            AdoptedModuleState.moduleAddress("src/Configuration/OrdinaryApplicationModule.bsl") //$NON-NLS-1$
                .isOrdinaryApplicationModule());
    }

    /**
     * The address is the ONE reading of a module path every caller shares (the vendor guard's), read
     * strictly - not a second parser: on every path it agrees with that reading.
     */
    @Test
    public void testTheAddressIsTheSharedReadingOfTheModulePathReadStrictly()
    {
        for (String path : Arrays.asList(
            "src/CommonModules/Calc/Module.bsl", //$NON-NLS-1$
            "Catalogs/Goods/ObjectModule.bsl", //$NON-NLS-1$
            "src/Catalogs/X/Commands/Fill/CommandModule.bsl", //$NON-NLS-1$
            "src/Configuration/SessionModule.bsl", //$NON-NLS-1$
            "src/Catalogs/X/Forms/ItemForm/Module.bsl", //$NON-NLS-1$
            "src/CalculationRegisters/R/Recalculations/Q/RecordSetModule.bsl", //$NON-NLS-1$
            "src/NoSuchDirectory/X/Module.bsl")) //$NON-NLS-1$
        {
            ModuleAddress address = AdoptedModuleState.moduleAddress(path);
            assertEquals(path, VendorSupportGuard.moduleOwnerFqn(path, true),
                address == null ? null : address.ownerFqn);
        }
    }

    /**
     * The reading is STRICT on purpose: a deeper path names a nested object, not the top object, and the
     * guard's lenient reading maps it to the top object - which would switch the REGISTER's module for a
     * recalculation's one. Pinned against that lenient reading directly, so an address that falls back to
     * it fails here.
     */
    @Test
    public void testARecalculationModuleIsNotMappedOntoItsRegister()
    {
        String recalculation = "src/CalculationRegisters/R/Recalculations/Q/RecordSetModule.bsl"; //$NON-NLS-1$
        assertEquals("the vendor guard's owner function does map it onto the register", //$NON-NLS-1$
            "CalculationRegister.R", VendorSupportGuard.moduleOwnerFqn(recalculation)); //$NON-NLS-1$
        assertNull("a recalculation module must address nothing, never CalculationRegister.R", //$NON-NLS-1$
            AdoptedModuleState.moduleAddress(recalculation));
    }

    @Test
    public void testShapesOutsideTheStrictParseAddressNothing()
    {
        for (String path : Arrays.asList(
            "src/Catalogs/X/Forms/ItemForm/Module.bsl", //$NON-NLS-1$
            "src/ExternalDataSources/S/Tables/T/ObjectModule.bsl", //$NON-NLS-1$
            "src/NoSuchDirectory/X/Module.bsl", //$NON-NLS-1$
            "src/Bots/X/Module.bsl", //$NON-NLS-1$
            "src/Catalogs/X/Commands/Fill/ObjectModule.bsl", //$NON-NLS-1$
            "src/Configuration/Nested/SessionModule.bsl", //$NON-NLS-1$
            "src/CommonModules/X/Module.txt", //$NON-NLS-1$
            "src/CommonModules/X/.bsl", //$NON-NLS-1$
            "src/CommonModules//Module.bsl", //$NON-NLS-1$
            "src/CommonModules/X/Module.bsl/", //$NON-NLS-1$
            "/src/CommonModules/X/Module.bsl", //$NON-NLS-1$
            "src/Configuration/X/Commands/C/CommandModule.bsl", //$NON-NLS-1$
            "src/CommonModules/X", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Module.bsl")) //$NON-NLS-1$
        {
            assertNull("'" + path + "' must address no module", AdoptedModuleState.moduleAddress(path)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertNull(AdoptedModuleState.moduleAddress(null));
    }

    // ==================== moduleFeature: EDT's stem-to-feature map, Module references only ====================

    @Test
    public void testTheStemMapsToTheOwnersModuleFeature()
    {
        assertSame(MdClassPackage.Literals.COMMON_MODULE__MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.COMMON_MODULE, "Module")); //$NON-NLS-1$
        assertSame(MdClassPackage.Literals.BASIC_DB_OBJECT__OBJECT_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CATALOG, "ObjectModule")); //$NON-NLS-1$
        assertSame(MdClassPackage.Literals.BASIC_DB_OBJECT__MANAGER_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CATALOG, "ManagerModule")); //$NON-NLS-1$
        assertSame(MdClassPackage.Literals.INFORMATION_REGISTER__RECORD_SET_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.INFORMATION_REGISTER, "RecordSetModule")); //$NON-NLS-1$
        assertSame(MdClassPackage.Literals.CONSTANT__VALUE_MANAGER_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CONSTANT, "ValueManagerModule")); //$NON-NLS-1$
        assertSame(MdClassPackage.Literals.BASIC_COMMAND__COMMAND_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CATALOG_COMMAND, "CommandModule")); //$NON-NLS-1$
        assertSame(MdClassPackage.Literals.CONFIGURATION__SESSION_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CONFIGURATION, "SessionModule")); //$NON-NLS-1$
    }

    @Test
    public void testTheStemIsMatchedIgnoringCaseAsEdtDoes()
    {
        assertSame(MdClassPackage.Literals.BASIC_DB_OBJECT__OBJECT_MODULE,
            AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CATALOG, "objectmodule")); //$NON-NLS-1$
    }

    @Test
    public void testAFormHasNoModuleFeatureOfItsOwn()
    {
        // A form's module is part of the form model; its state is <form>, set when the form is adopted.
        assertNull(AdoptedModuleState.moduleFeature(MdClassPackage.Literals.CATALOG_FORM, "Module")); //$NON-NLS-1$
        assertNull(AdoptedModuleState.moduleFeature(MdClassPackage.Literals.COMMON_FORM, "Module")); //$NON-NLS-1$
    }

    /**
     * EDT's own listener maps the stem to ANY feature of that name. Restricting the map to Module
     * references keeps a file named after an ordinary attribute ({@code comment}) or a non-module
     * reference ({@code extension}) from being treated as a module at all.
     */
    @Test
    public void testAStemNamingAFeatureThatIsNotAModuleMapsToNothing()
    {
        assertNull(AdoptedModuleState.moduleFeature(MdClassPackage.Literals.COMMON_MODULE, "Comment")); //$NON-NLS-1$
        assertNull(AdoptedModuleState.moduleFeature(MdClassPackage.Literals.COMMON_MODULE, "Extension")); //$NON-NLS-1$
        assertNull(AdoptedModuleState.moduleFeature(MdClassPackage.Literals.COMMON_MODULE, "NoSuchFeature")); //$NON-NLS-1$
        assertNull(AdoptedModuleState.moduleFeature(null, "Module")); //$NON-NLS-1$
    }

    // ==================== decide: the state decision, never a write ====================

    @Test
    public void testANativeObjectIsNotApplicableAndTheServiceIsNotAsked()
    {
        Verdict verdict = AdoptedModuleState.decide(own, MdClassPackage.Literals.COMMON_MODULE__MODULE, access);
        assertEquals(Verdict.Kind.NOT_APPLICABLE, verdict.kind);
        verifyZeroInteractions(access);
    }

    @Test
    public void testNoModuleFeatureIsNotApplicableAndTheServiceIsNotAsked()
    {
        assertEquals(Verdict.Kind.NOT_APPLICABLE, AdoptedModuleState.decide(drySignal, null, access).kind);
        verifyZeroInteractions(access);
    }

    @Test
    public void testAnAdoptedModuleInStateNoneIsToBeSwitched()
    {
        reports(MdPropertyState.NONE);
        Verdict verdict = AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, access);
        assertEquals(Verdict.Kind.SWITCH, verdict.kind);
        verify(access, never()).setPropertyState(any(), any(), any());
    }

    @Test
    public void testAnAdoptedModuleAlreadyExtendedIsReportedSo()
    {
        reports(MdPropertyState.EXTENDED);
        assertEquals(Verdict.Kind.ALREADY_EXTENDED,
            AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, access).kind);
        verify(access, never()).setPropertyState(any(), any(), any());
    }

    @Test
    public void testCheckedAndNotifyAreReportedByName()
    {
        reports(MdPropertyState.CHECKED);
        Verdict checked = AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, access);
        assertEquals(Verdict.Kind.OTHER_STATE, checked.kind);
        assertEquals("Checked", checked.detail); //$NON-NLS-1$

        IMdAdoptedPropertyAccess notifying = mock(IMdAdoptedPropertyAccess.class);
        when(notifying.hasPropertyState(any(EObject.class), any(EStructuralFeature.class),
            eq(MdPropertyState.NOTIFY))).thenReturn(Boolean.TRUE);
        Verdict notify = AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, notifying);
        assertEquals(Verdict.Kind.OTHER_STATE, notify.kind);
        assertEquals("Notify", notify.detail); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        verify(notifying, never()).setPropertyState(any(), any(), any());
    }

    @Test
    public void testAnAdoptedObjectWithoutAnExtensionBlockIsReportedSo()
    {
        // EDT's service answers "not None" for a missing block, so the block itself must be asked.
        drySignal.setExtension(null);
        assertEquals(Verdict.Kind.NO_EXTENSION_BLOCK,
            AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, access).kind);
        verify(access, never()).setPropertyState(any(), any(), any());
    }

    /** The mapper's "no extension property" refusal cannot happen for an owner the parse admits: drift. */
    @Test
    public void testTheMappersRefusalIsAFailureNotASilentSkip()
    {
        IllegalStateException drift = new IllegalStateException(
            "Cannot find appropriate extension property for the original object"); //$NON-NLS-1$
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), any())).thenThrow(drift);
        Verdict verdict = AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, access);
        assertEquals(Verdict.Kind.FAILED, verdict.kind);
        assertSame(drift, verdict.failure);
    }

    @Test
    public void testAMissingServiceFailsOnlyForAnAdoptedObject()
    {
        assertEquals(Verdict.Kind.FAILED,
            AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, null).kind);
        assertEquals(Verdict.Kind.NOT_APPLICABLE,
            AdoptedModuleState.decide(own, MdClassPackage.Literals.COMMON_MODULE__MODULE, null).kind);
    }

    /**
     * The ordinary-application module is decided like every other module: None is switched, as EDT's own
     * listener switches it; Checked is named, to be left as it is; a missing block is reported. A state
     * left as it is and a missing block fail no write, so it needs no exemption of its own here.
     */
    @Test
    public void testTheOrdinaryApplicationModuleIsDecidedLikeEveryOtherModule()
    {
        EStructuralFeature ordinary = MdClassPackage.Literals.CONFIGURATION__ORDINARY_APPLICATION_MODULE;
        reports(MdPropertyState.CHECKED);
        Verdict checked = AdoptedModuleState.decide(root, ordinary, access);
        assertEquals(Verdict.Kind.OTHER_STATE, checked.kind);
        assertEquals("Checked", checked.detail); //$NON-NLS-1$
        assertEquals(Verdict.Kind.OTHER_STATE, AdoptedModuleState.decide(root,
            MdClassPackage.Literals.CONFIGURATION__SESSION_MODULE, access).kind);

        root.setExtension(null);
        assertEquals(Verdict.Kind.NO_EXTENSION_BLOCK, AdoptedModuleState.decide(root, ordinary, access).kind);
        assertEquals(Verdict.Kind.NO_EXTENSION_BLOCK, AdoptedModuleState.decide(root,
            MdClassPackage.Literals.CONFIGURATION__SESSION_MODULE, access).kind);

        root.setExtension(MdClassExtensionFactory.eINSTANCE.createConfigurationExtension());
        IMdAdoptedPropertyAccess none = mock(IMdAdoptedPropertyAccess.class);
        when(none.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(MdPropertyState.NONE)))
            .thenReturn(Boolean.TRUE);
        assertEquals(Verdict.Kind.SWITCH, AdoptedModuleState.decide(root, ordinary, none).kind);
    }

    /**
     * The state has four values. A service that names none of them for a module whose block exists
     * contradicts itself: that is a failure to report, never a state to name in the key.
     */
    @Test
    public void testAServiceThatNamesNoneOfTheFourStatesIsAFailureNotAKeptState()
    {
        // The mock answers false to every question: not None, not Extended, not Checked, not Notify.
        Verdict verdict = AdoptedModuleState.decide(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE, access);
        assertEquals(Verdict.Kind.FAILED, verdict.kind);
        assertNull(verdict.failure);
        assertTrue(verdict.detail, verdict.detail.contains("none of the four module states")); //$NON-NLS-1$

        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failed());
        assertNull("no state to name", result.frontMatterValue()); //$NON-NLS-1$
        String message = result.failureMessage();
        assertTrue(message, message.startsWith("The module text was written to src/CommonModules/DrySignal/Module.bsl")); //$NON-NLS-1$
        assertTrue(message, message.contains("none of the four module states (None, Extended, Checked, Notify)")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        assertTrue(env.exportCalls().isEmpty());
    }

    // ==================== ensureExtended: orchestration ====================

    @Test
    public void testNotAnExtensionProjectChangesNothingAndOpensNoTransaction()
    {
        env.extension = false;
        reports(MdPropertyState.NONE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.NOT_APPLICABLE, result.outcome());
        assertNull(result.frontMatterValue());
        assertNull(result.bodySentence());
        assertFalse(result.failed());
        assertFalse("no model may be asked for a base project", env.calls.contains("model")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
        verifyZeroInteractions(access);
    }

    @Test
    public void testAPathThatIsNoModuleOfTheParseChangesNothing()
    {
        reports(MdPropertyState.NONE);
        Result result = run("src/Catalogs/X/Forms/ItemForm/Module.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.NOT_APPLICABLE, result.outcome());
        assertFalse(env.calls.contains("model")); //$NON-NLS-1$
        verifyZeroInteractions(access);
    }

    @Test
    public void testAModuleOfTheExtensionsOwnObjectIsLeftAlone()
    {
        reports(MdPropertyState.NONE);
        Result result = run("src/CommonModules/tests_Own/Module.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.NOT_APPLICABLE, result.outcome());
        assertTrue(env.exportCalls().isEmpty());
        verify(env.model, never()).execute(any());
        verifyZeroInteractions(access);
    }

    @Test
    public void testAnUnknownOwnerChangesNothing()
    {
        reports(MdPropertyState.NONE);
        assertEquals(Outcome.NOT_APPLICABLE, run("src/CommonModules/NoSuchModule/Module.bsl").outcome()); //$NON-NLS-1$
        verify(env.model, never()).execute(any());
        assertTrue(env.exportCalls().isEmpty());
    }

    @Test
    public void testStateNoneIsSwitchedToExtendedInsideAWriteTaskThenExportedAndAwaited()
    {
        reports(MdPropertyState.NONE);
        boolean[] setInWrite = new boolean[1];
        doAnswer(inv -> {
            setInWrite[0] = inWrite[0];
            return null;
        }).when(access).setPropertyState(any(), any(), any());

        Result result = run(DRY_SIGNAL);

        assertEquals(Outcome.SWITCHED, result.outcome());
        assertFalse(result.failed());
        assertEquals("switchedToExtended", result.frontMatterValue()); //$NON-NLS-1$
        verify(access, times(1)).setPropertyState(same(drySignal),
            same(MdClassPackage.Literals.COMMON_MODULE__MODULE), same(MdPropertyState.EXTENDED));
        assertTrue("the state must be set inside the WRITE task", setInWrite[0]); //$NON-NLS-1$
        // The export of the TOP object first, then the bounded wait - and its budget is the metadata
        // writes' 60s, pinned as a literal (a compile-time constant would be inlined into this test).
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:60000"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportCalls());
        String body = result.bodySentence();
        assertTrue(body, body.contains("'CommonModule.DrySignal'")); //$NON-NLS-1$
        assertTrue(body, body.contains("switched from None to Extended")); //$NON-NLS-1$
        assertTrue(body, body.contains("<module>Extended</module>")); //$NON-NLS-1$
        assertTrue(body, body.contains("src/CommonModules/DrySignal/DrySignal.mdo")); //$NON-NLS-1$
        assertTrue(body, body.contains("the .mdo was exported")); //$NON-NLS-1$
        // What the paragraph rests on: EDT's UI records the state in the .mdo, its listener only in the model.
        assertTrue(body, body.contains(UI_RECORDS));
        assertTrue(body, body.contains(LISTENER_ONLY_MODEL));
        assertNoRemovedClaim("the switch paragraph", body); //$NON-NLS-1$
    }

    /**
     * The set happens on the TRANSACTION's copy of the owner - the object the commit writes - not on the
     * global object the read resolved.
     */
    @Test
    public void testTheStateIsSetOnTheTransactionsObject()
    {
        CommonModule txCopy = EcoreUtil.copy(drySignal);
        when(tx.toTransactionObject(same(drySignal))).thenReturn(txCopy);
        reports(MdPropertyState.NONE);

        assertEquals(Outcome.SWITCHED, run(DRY_SIGNAL).outcome());

        verify(access).setPropertyState(same(txCopy), same(MdClassPackage.Literals.COMMON_MODULE__MODULE),
            same(MdPropertyState.EXTENDED));
        verify(access, never()).setPropertyState(same(drySignal), any(), any());
    }

    @Test
    public void testACommandModuleExportsItsTopObject()
    {
        reports(MdPropertyState.NONE);
        Result result = run("src/Catalogs/X/Commands/Fill/CommandModule.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.SWITCHED, result.outcome());
        verify(access).setPropertyState(same(fill), same(MdClassPackage.Literals.BASIC_COMMAND__COMMAND_MODULE),
            same(MdPropertyState.EXTENDED));
        assertEquals("a command has no .mdo of its own: its top object is exported", //$NON-NLS-1$
            Arrays.asList("forceExport:Catalog.X", "awaitExport:60000"), env.exportCalls()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.bodySentence().contains("<commandModule>Extended</commandModule>")); //$NON-NLS-1$
        assertTrue(result.bodySentence().contains("src/Catalogs/X/X.mdo")); //$NON-NLS-1$
    }

    @Test
    public void testAConfigurationModuleSwitchesTheRootAndExportsConfiguration()
    {
        reports(MdPropertyState.NONE);
        Result result = run("src/Configuration/SessionModule.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.SWITCHED, result.outcome());
        verify(access).setPropertyState(same(root), same(MdClassPackage.Literals.CONFIGURATION__SESSION_MODULE),
            same(MdPropertyState.EXTENDED));
        assertEquals(Arrays.asList("forceExport:Configuration", "awaitExport:60000"), env.exportCalls()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The heal: EDT's own listener sets the state but never saves the .mdo, so the export runs anyway. */
    @Test
    public void testAnAlreadyExtendedModuleIsNotSetButItsMdoIsExportedAgain()
    {
        reports(MdPropertyState.EXTENDED);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.ALREADY_EXTENDED, result.outcome());
        assertFalse(result.failed());
        assertEquals("alreadyExtended", result.frontMatterValue()); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        verify(env.model, never()).execute(any());
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:60000"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportCalls());
        assertNull("an already-Extended write whose export drained says nothing beyond the key", //$NON-NLS-1$
            result.bodySentence());
    }

    /** EDT's listener can set the state between our read and our write: the re-check decides. */
    @Test
    public void testARaceLostToEdtsListenerIsAlreadyExtendedAndSetsNothing()
    {
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(MdPropertyState.NONE)))
            .thenReturn(Boolean.TRUE, Boolean.FALSE);
        reports(MdPropertyState.EXTENDED);

        Result result = run(DRY_SIGNAL);

        assertEquals(Outcome.ALREADY_EXTENDED, result.outcome());
        verify(env.model, times(1)).execute(any());
        verify(access, never()).setPropertyState(any(), any(), any());
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:60000"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportCalls());
    }

    @Test
    public void testASwitchWhoseExportStaysPendingIsNotConfirmed()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertNull(result.frontMatterValue());
        String message = result.failureMessage();
        assertTrue(message, message.contains("src/CommonModules/DrySignal/Module.bsl")); //$NON-NLS-1$
        assertTrue(message, message.contains("switched from None to Extended")); //$NON-NLS-1$
        assertTrue(message, message.contains("not confirmed within 60s")); //$NON-NLS-1$
        assertTrue(message, message.contains("<module>Extended</module>")); //$NON-NLS-1$
        assertTrue(message, message.contains("Nothing was rolled back")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not commit")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
        assertTrue(message, message.contains("mode=append and an empty source")); //$NON-NLS-1$
        // The rerun rewrites the module's text as it is - but normalized, and through the syntax check.
        assertTrue(message, message.contains("rewrites the module's current text, line ends normalized")); //$NON-NLS-1$
        assertTrue(message, message.contains("add skipSyntaxCheck=true if the module was written with it")); //$NON-NLS-1$
    }

    /** A refused submission queued nothing: waiting on the empty queue would "drain" and claim an export. */
    @Test
    public void testASwitchWhoseExportCannotBeScheduledIsNotConfirmedAndNotAwaited()
    {
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertEquals(Collections.singletonList("forceExport:CommonModule.DrySignal"), env.exportCalls()); //$NON-NLS-1$
        assertTrue(result.failureMessage(), result.failureMessage().contains("could not be scheduled")); //$NON-NLS-1$
    }

    @Test
    public void testASwitchWithNoTopObjectIdentityIsNotConfirmed()
    {
        reports(MdPropertyState.NONE);
        env.topFqnOverride = ""; //$NON-NLS-1$
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue("no FQN, no export request", env.exportCalls().isEmpty()); //$NON-NLS-1$
        // With no top object named by the model, the message names the file by its role, never "null".
        String message = result.failureMessage();
        assertTrue(message, message.contains("the .mdo of its top object may still lack <module>Extended</module>")); //$NON-NLS-1$
        assertFalse(message, message.contains("null")); //$NON-NLS-1$
    }

    /**
     * The .mdo a message names is the file the export writes: the top object as the MODEL names it - the
     * identity the export is requested for - through the shared FQN-to-path resolver, never a path
     * rebuilt from the module's own path.
     */
    @Test
    public void testTheMdoTheMessagesNameIsTheTopObjectTheModelExports()
    {
        reports(MdPropertyState.NONE);
        env.topFqnOverride = "CommonModule.Elsewhere"; //$NON-NLS-1$
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        assertEquals(Arrays.asList("forceExport:CommonModule.Elsewhere", "awaitExport:60000"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportCalls());
        String body = result.bodySentence();
        assertTrue(body, body.contains("<module>Extended</module> in src/CommonModules/Elsewhere/Elsewhere.mdo")); //$NON-NLS-1$
        assertFalse(body, body.contains("DrySignal.mdo")); //$NON-NLS-1$
    }

    @Test
    public void testTheMdoIsNamedByTheModelsTopObjectFqn()
    {
        assertEquals("src/CommonModules/DrySignal/DrySignal.mdo", //$NON-NLS-1$
            AdoptedModuleState.mdoPathOf("CommonModule.DrySignal")); //$NON-NLS-1$
        assertEquals("src/Catalogs/X/X.mdo", AdoptedModuleState.mdoPathOf("Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("src/Configuration/Configuration.mdo", AdoptedModuleState.mdoPathOf("Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(AdoptedModuleState.mdoPathOf(null));
        assertNull(AdoptedModuleState.mdoPathOf("")); //$NON-NLS-1$
    }

    /**
     * The production platform names the top object the model holds above the owner, never the owner
     * itself: the command of an adopted catalog has no {@code .mdo} of its own, and an export requested
     * for a name that is not a top object is silently not scheduled. Every other test runs on the fake
     * platform, which finds the top object on its own, so this is the one pin on {@code PLATFORM}.
     */
    @Test
    public void testThePlatformNamesTheTopObjectAboveTheOwnerNotTheOwner()
    {
        IBmObject catalogTop = mock(IBmObject.class);
        when(catalogTop.bmGetFqn()).thenReturn("Catalog.X"); //$NON-NLS-1$
        IBmObject command = mock(IBmObject.class);
        when(command.bmGetFqn()).thenReturn("Catalog.X.Command.Fill"); //$NON-NLS-1$
        when(command.bmGetTopObject()).thenReturn(catalogTop);
        assertEquals("Catalog.X", AdoptedModuleState.PLATFORM.topObjectFqn(command)); //$NON-NLS-1$

        // An owner that is its own top object - a common module, the root - is named by its own FQN.
        IBmObject module = mock(IBmObject.class);
        when(module.bmGetFqn()).thenReturn("CommonModule.DrySignal"); //$NON-NLS-1$
        when(module.bmGetTopObject()).thenReturn(module);
        assertEquals("CommonModule.DrySignal", AdoptedModuleState.PLATFORM.topObjectFqn(module)); //$NON-NLS-1$

        // No top object, or no BM object at all: no identity, so no export is requested.
        IBmObject detached = mock(IBmObject.class);
        when(detached.bmGetFqn()).thenReturn("CommonModule.Detached"); //$NON-NLS-1$
        assertNull(AdoptedModuleState.PLATFORM.topObjectFqn(detached));
        assertNull(AdoptedModuleState.PLATFORM.topObjectFqn(mock(EObject.class)));
    }

    /** Bilingual: a Cyrillic Name resolves exactly, is exported under the model's FQN and named in its .mdo. */
    @Test
    public void testAnAdoptedModuleWithACyrillicNameIsSwitchedAndItsMdoNamed()
    {
        CommonModule russian = MdClassFactory.eINSTANCE.createCommonModule();
        russian.setName(RU_NAME);
        russian.setObjectBelonging(ObjectBelonging.ADOPTED);
        russian.setExtension(MdClassExtensionFactory.eINSTANCE.createCommonModuleExtension());
        root.getCommonModules().add(russian);
        reports(MdPropertyState.NONE);

        Result result = run("src/CommonModules/" + RU_NAME + "/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Outcome.SWITCHED, result.outcome());
        verify(access).setPropertyState(same(russian), same(MdClassPackage.Literals.COMMON_MODULE__MODULE),
            same(MdPropertyState.EXTENDED));
        assertEquals(Arrays.asList("forceExport:CommonModule." + RU_NAME, "awaitExport:60000"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportCalls());
        assertTrue(result.bodySentence(), result.bodySentence()
            .contains("src/CommonModules/" + RU_NAME + "/" + RU_NAME + ".mdo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAnExportThatThrowsAfterTheSwitchIsNotConfirmedAndNothingEscapes()
    {
        reports(MdPropertyState.NONE);
        env.exportFailure = new IllegalStateException("save task refused"); //$NON-NLS-1$
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        verify(access).setPropertyState(any(), any(), any());
    }

    /** UNOBSERVABLE is no evidence the export finished, so it must not be worded as if it did. */
    @Test
    public void testAnUnobservableExportIsNotWordedAsADoneOne()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        assertFalse(result.failed());
        String body = result.bodySentence();
        assertTrue(body, body.contains("could not be observed")); //$NON-NLS-1$
        assertFalse(body, body.contains("was exported")); //$NON-NLS-1$
    }

    /**
     * Only an export this call CAUSED may refuse it. In the already-Extended case nothing changed in the
     * model, and a busy or wedged export wait would otherwise turn every such write into an error.
     */
    @Test
    public void testAnAlreadyExtendedModuleWhoseExportStaysPendingIsStillASuccess()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.ALREADY_EXTENDED, result.outcome());
        assertFalse(result.failed());
        assertEquals("alreadyExtended", result.frontMatterValue()); //$NON-NLS-1$
        String body = result.bodySentence();
        assertNotNull("a warning is stated", body); //$NON-NLS-1$
        assertTrue(body, body.contains("already Extended")); //$NON-NLS-1$
        assertTrue(body, body.contains("not confirmed within 60s")); //$NON-NLS-1$
        assertTrue(body, body.contains("<module>Extended</module>")); //$NON-NLS-1$
    }

    @Test
    public void testAnAlreadyExtendedModuleWhoseExportCannotBeScheduledIsStillASuccess()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportAccepted = false;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.ALREADY_EXTENDED, result.outcome());
        assertFalse(result.failed());
        assertTrue(result.bodySentence(), result.bodySentence().contains("could not be scheduled")); //$NON-NLS-1$
        assertEquals(Collections.singletonList("forceExport:CommonModule.DrySignal"), env.exportCalls()); //$NON-NLS-1$
    }

    @Test
    public void testAnAlreadyExtendedModuleWhoseExportIsUnobservableSaysSo()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.ALREADY_EXTENDED, result.outcome());
        assertTrue(result.bodySentence(), result.bodySentence().contains("could not be observed")); //$NON-NLS-1$
    }

    /**
     * No model is a transient window that list_projects does not show (it reports the project ready),
     * so "wait for list_projects" would be wrong advice here - and the shared resolver already says
     * that. Its OWN actionable error is the message, with this step's rerun as the operation to retry
     * and "the write stands" as what is known: the canonical sentence, never a second phrasing of it.
     */
    @Test
    public void testAMissingModelIsATransientFailureWithAWaitThenRetryRecipe()
    {
        env.model = null;
        reports(MdPropertyState.NONE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failed());
        String message = result.failureMessage();
        assertTrue(message, message.startsWith("The module text was written to src/CommonModules/DrySignal/Module.bsl")); //$NON-NLS-1$
        String canonical = BmModelResolver.resolve(project, mock(IBmModelManager.class))
            .actionableError("<operation>", "<state>"); //$NON-NLS-1$ //$NON-NLS-2$
        String head = canonical.substring(0, canonical.indexOf("<state>")); //$NON-NLS-1$
        String middle = canonical.substring(canonical.indexOf("<state>") + "<state>".length(), //$NON-NLS-1$ //$NON-NLS-2$
            canonical.indexOf("<operation>")); //$NON-NLS-1$
        assertTrue("the resolver's own sentence, with the write standing and this step to retry: " + message, //$NON-NLS-1$
            message.contains(head + "The write stands: do not re-send the original edit." + middle //$NON-NLS-1$
                + "this step: call write_module_source on the same module with mode=append and an empty source")); //$NON-NLS-1$
        assertTrue(message, message.contains("BM model is not available for project 'tests'")); //$NON-NLS-1$
        assertFalse(message, message.contains("once list_projects reports")); //$NON-NLS-1$
        assertTrue(message, message.contains("line ends normalized")); //$NON-NLS-1$
        verifyZeroInteractions(access);
    }

    /**
     * Until the model is there an adopted object cannot be told from one of the extension's own, so a
     * write into an own module fails the same transient way - the guide says so; pinned here.
     */
    @Test
    public void testAnOwnModuleCannotBeToldFromAnAdoptedOneWithoutTheModel()
    {
        env.model = null;
        Result result = run("src/CommonModules/tests_Own/Module.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failed());
        assertTrue(result.failureMessage(), result.failureMessage().contains("'CommonModule.tests_Own'")); //$NON-NLS-1$
    }

    @Test
    public void testABatchSessionIsATransientFailure()
    {
        reports(MdPropertyState.NONE);
        doThrow(new BmBatchSessionException("batch session is active")).when(env.model).execute(any()); //$NON-NLS-1$
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failureMessage(), result.failureMessage().contains("batch operation")); //$NON-NLS-1$
        assertTrue(result.failureMessage(), result.failureMessage().contains("Wait a few seconds")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
    }

    @Test
    public void testAMissingServiceFailsTheWriteIntoAnAdoptedModule()
    {
        env.access = null;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failureMessage(), result.failureMessage().contains("adopted-property service")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
    }

    @Test
    public void testAMissingServiceLeavesTheExtensionsOwnModuleAlone()
    {
        env.access = null;
        assertEquals(Outcome.NOT_APPLICABLE, run("src/CommonModules/tests_Own/Module.bsl").outcome()); //$NON-NLS-1$
    }

    @Test
    public void testAPlatformFailureWhileSettingIsReportedNotThrown()
    {
        reports(MdPropertyState.NONE);
        doThrow(new IllegalArgumentException("The class 'BasicDbObjectExtension' is not a valid classifier")) //$NON-NLS-1$
            .when(access).setPropertyState(any(), any(), any());
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        String message = result.failureMessage();
        assertTrue(message, message.contains("not a valid classifier")); //$NON-NLS-1$
        assertTrue(message, message.contains("'CommonModule.DrySignal'")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
    }

    @Test
    public void testAMapperRefusalIsAFailureOfTheWrite()
    {
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), any()))
            .thenThrow(new IllegalStateException("Cannot find appropriate extension property")); //$NON-NLS-1$
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failureMessage().contains("Cannot find appropriate extension property")); //$NON-NLS-1$
        verify(env.model, never()).execute(any());
    }

    /**
     * EDT's set would CREATE a missing block - and throw for an abstract one: the step never sets here. A
     * missing block fails no write: the text is written, and the key and the note say why no state was
     * recorded.
     */
    @Test
    public void testAMissingExtensionBlockIsASuccessWithANoteAndNeitherSetNorExported()
    {
        drySignal.setExtension(null);
        reports(MdPropertyState.NONE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.NO_EXTENSION_BLOCK, result.outcome());
        assertFalse("a missing block is no failure: the text is written", result.failed()); //$NON-NLS-1$
        assertNull(result.failureMessage());
        assertEquals("noExtensionBlock", result.frontMatterValue()); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        verify(env.model, never()).execute(any());
        assertTrue(env.exportCalls().isEmpty());
        String note = result.bodySentence();
        assertNotNull(note);
        assertTrue(note, note.contains("This module belongs to 'CommonModule.DrySignal', an object adopted into " //$NON-NLS-1$
            + "this extension: there is no extension block in src/CommonModules/DrySignal/DrySignal.mdo, so the " //$NON-NLS-1$
            + "module state cannot be recorded.")); //$NON-NLS-1$
        assertTrue(note, note.contains("restore the block")); //$NON-NLS-1$
        assertTrue(note, note.contains("mode=append and an empty source")); //$NON-NLS-1$
        assertNoRemovedClaim("the missing-block note", note); //$NON-NLS-1$
    }

    /** An extension root without its block (one made outside EDT's wizard) - named as the ROOT; a success. */
    @Test
    public void testARootWithoutItsExtensionBlockNamesTheRootAndTheWizard()
    {
        root.setExtension(null);
        reports(MdPropertyState.NONE);
        Result result = run("src/Configuration/SessionModule.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.NO_EXTENSION_BLOCK, result.outcome());
        assertFalse(result.failed());
        assertNull(result.failureMessage());
        assertEquals("noExtensionBlock", result.frontMatterValue()); //$NON-NLS-1$
        String note = result.bodySentence();
        assertTrue(note, note.contains("This module belongs to the extension's root object (Configuration), " //$NON-NLS-1$
            + "adopted from the base configuration: there is no extension block in " //$NON-NLS-1$
            + "src/Configuration/Configuration.mdo, so the module state cannot be recorded.")); //$NON-NLS-1$
        assertTrue(note, note.contains("An extension root that EDT's own new-extension wizard creates carries " //$NON-NLS-1$
            + "that block.")); //$NON-NLS-1$
        assertNoRemovedClaim("the root's missing-block note", note); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
        verify(access, never()).setPropertyState(any(), any(), any());
    }

    /**
     * Checked is the author's state: left exactly as it is, named in the key and the note - no error. The
     * note compares the step with EDT's own listener, which asks for None first - not with EDT as a whole:
     * EDT's module adopter, which its UI runs to add a method to an extension, sets Extended whatever the
     * state was.
     */
    @Test
    public void testACheckedModuleIsKeptAsItIsAndNamedInTheKey()
    {
        reports(MdPropertyState.CHECKED);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.OTHER_STATE, result.outcome());
        assertFalse("a state left as it is is no failure: the text is written", result.failed()); //$NON-NLS-1$
        assertNull(result.failureMessage());
        assertEquals("keptChecked", result.frontMatterValue()); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        verify(env.model, never()).execute(any());
        assertTrue(env.exportCalls().isEmpty());
        String note = result.bodySentence();
        assertNotNull(note);
        assertTrue(note, note.contains("The module state of 'CommonModule.DrySignal' is Checked (<module>Checked" //$NON-NLS-1$
            + "</module> in src/CommonModules/DrySignal/DrySignal.mdo), not Extended, and was left as it is")); //$NON-NLS-1$
        assertTrue(note, note.contains(LIKE_THE_LISTENER));
        assertTrue(note, note.contains("set its state in EDT, in the properties of the adopted object")); //$NON-NLS-1$
        assertNoRemovedClaim("the kept-state note", note); //$NON-NLS-1$
    }

    @Test
    public void testANotifyModuleIsKeptAsItIsToo()
    {
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(MdPropertyState.NOTIFY)))
            .thenReturn(Boolean.TRUE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.OTHER_STATE, result.outcome());
        assertFalse(result.failed());
        assertEquals("keptNotify", result.frontMatterValue()); //$NON-NLS-1$
        assertTrue(result.bodySentence(), result.bodySentence().contains("is Notify (<module>Notify</module>")); //$NON-NLS-1$
        assertTrue(result.bodySentence(), result.bodySentence().contains(LIKE_THE_LISTENER));
        assertNoRemovedClaim("the Notify note", result.bodySentence()); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        assertTrue(env.exportCalls().isEmpty());
    }

    @Test
    public void testAnOrdinaryApplicationModuleWithoutABlockIsNoFailure()
    {
        root.setExtension(null);
        reports(MdPropertyState.NONE);
        Result result = run(ORDINARY);
        assertEquals(Outcome.NO_EXTENSION_BLOCK, result.outcome());
        assertFalse(result.failed());
        assertEquals("noExtensionBlock", result.frontMatterValue()); //$NON-NLS-1$
        assertTrue(result.bodySentence(), result.bodySentence().contains("the extension's root object (Configuration)")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
    }

    /**
     * The ordinary-application module is switched like the others, and a switch whose export is
     * confirmed reads like any other: the same verified facts, and no note of its own.
     */
    @Test
    public void testAConfirmedSwitchOfTheOrdinaryApplicationModuleReadsLikeAnyOther()
    {
        reports(MdPropertyState.NONE);
        Result result = run(ORDINARY);
        assertEquals(Outcome.SWITCHED, result.outcome());
        verify(access).setPropertyState(same(root),
            same(MdClassPackage.Literals.CONFIGURATION__ORDINARY_APPLICATION_MODULE), same(MdPropertyState.EXTENDED));
        String body = result.bodySentence();
        assertTrue(body, body.contains("switched from None to Extended")); //$NON-NLS-1$
        assertTrue(body, body.contains("<ordinaryApplicationModule>Extended</ordinaryApplicationModule>")); //$NON-NLS-1$
        assertTrue(body, body.contains("the .mdo was exported")); //$NON-NLS-1$
        assertTrue(body, body.contains(UI_RECORDS));
        assertFalse("a confirmed switch needs no note: " + body, body.contains(ORDINARY_FACT)); //$NON-NLS-1$
        assertNoRemovedClaim("the ordinary module's switch paragraph", body); //$NON-NLS-1$
    }

    /** An unconfirmed export of the ordinary-application module is a note on a success, never an error. */
    @Test
    public void testAnUnconfirmedExportOfTheOrdinaryApplicationModuleIsANoteNotAFailure()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        Result pending = run(ORDINARY);
        assertEquals(Outcome.SWITCHED, pending.outcome());
        assertFalse(pending.failed());
        assertNull(pending.failureMessage());
        assertEquals("switchedToExtended", pending.frontMatterValue()); //$NON-NLS-1$
        String body = pending.bodySentence();
        assertTrue(body, body.contains("not confirmed within 60s")); //$NON-NLS-1$
        assertTrue(body, body.contains(ORDINARY_FACT));
        assertNoRemovedClaim("the ordinary module's unconfirmed-export note", body); //$NON-NLS-1$

        env.exportState = BuildUtils.DiskExportState.DRAINED;
        env.exportAccepted = false;
        Result refused = run(ORDINARY);
        assertEquals(Outcome.SWITCHED, refused.outcome());
        assertFalse(refused.failed());
        assertTrue(refused.bodySentence(), refused.bodySentence().contains("could not be scheduled")); //$NON-NLS-1$

        // The contrast: the same unconfirmed export of the SESSION module refuses the write.
        env.exportAccepted = true;
        env.exportState = BuildUtils.DiskExportState.PENDING;
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, run("src/Configuration/SessionModule.bsl").outcome()); //$NON-NLS-1$
    }

    /** A step that could not run on the ordinary-application module does not fail the write: a note. */
    @Test
    public void testAFailedStepOnTheOrdinaryApplicationModuleIsANoteNotAFailure()
    {
        env.model = null;
        Result noModel = run(ORDINARY);
        assertEquals(Outcome.FAILED, noModel.outcome());
        assertFalse(noModel.failed());
        assertNull(noModel.failureMessage());
        assertNull("no state to report", noModel.frontMatterValue()); //$NON-NLS-1$
        String body = noModel.bodySentence();
        assertNotNull(body);
        assertTrue(body, body.contains("ordinary-application module of the extension's root object (Configuration)")); //$NON-NLS-1$
        assertTrue(body, body.contains("was left as it is")); //$NON-NLS-1$
        assertTrue(body, body.contains("BM model of project 'tests'")); //$NON-NLS-1$
        assertTrue(body, body.contains(ORDINARY_FACT));
        assertNoRemovedClaim("the ordinary module's failed-step note", body); //$NON-NLS-1$
        // The contrast: without the model, the SESSION module's write is refused.
        assertTrue(run("src/Configuration/SessionModule.bsl").failed()); //$NON-NLS-1$
    }

    @Test
    public void testAPlatformFailureOnTheOrdinaryApplicationModuleIsANoteNotAFailure()
    {
        reports(MdPropertyState.NONE);
        doThrow(new IllegalArgumentException("set refused")) //$NON-NLS-1$
            .when(access).setPropertyState(any(), any(), any());
        Result result = run(ORDINARY);
        assertEquals(Outcome.FAILED, result.outcome());
        assertFalse(result.failed());
        assertTrue(result.bodySentence(), result.bodySentence().contains("set refused")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
    }

    // ==================== Result: what each outcome says ====================

    /**
     * Only a failure or an unconfirmed switch fails the write. A state left as it is and a missing
     * extension block are successes now: the text is written, and the key says what was found.
     */
    @Test
    public void testOnlyAFailureOrAnUnconfirmedSwitchFailsTheWrite()
    {
        assertFalse(Result.notApplicable().failed());
        assertNull(Result.notApplicable().frontMatterValue());
        assertNull(Result.notApplicable().bodySentence());
        assertFalse(AdoptedModuleStateTestFixtures.switched().failed());
        assertFalse(AdoptedModuleStateTestFixtures.alreadyExtended(BuildUtils.DiskExportState.DRAINED).failed());
        assertFalse(AdoptedModuleStateTestFixtures.keptChecked().failed());
        assertFalse(AdoptedModuleStateTestFixtures.noExtensionBlock().failed());
        assertTrue(AdoptedModuleStateTestFixtures.exportNotConfirmed().failed());
        assertTrue(AdoptedModuleStateTestFixtures.failedForWantOfAModel().failed());
    }

    /** Each outcome a write reports has its own key value; a failure has none. */
    @Test
    public void testEveryReportedOutcomeHasItsOwnKeyValue()
    {
        assertEquals("switchedToExtended", AdoptedModuleStateTestFixtures.switched().frontMatterValue()); //$NON-NLS-1$
        assertEquals("alreadyExtended", //$NON-NLS-1$
            AdoptedModuleStateTestFixtures.alreadyExtended(BuildUtils.DiskExportState.DRAINED).frontMatterValue());
        assertEquals("keptChecked", AdoptedModuleStateTestFixtures.keptChecked().frontMatterValue()); //$NON-NLS-1$
        assertEquals("noExtensionBlock", AdoptedModuleStateTestFixtures.noExtensionBlock().frontMatterValue()); //$NON-NLS-1$
        assertNull(AdoptedModuleStateTestFixtures.exportNotConfirmed().frontMatterValue());
        assertNull(AdoptedModuleStateTestFixtures.failedForWantOfAModel().frontMatterValue());
    }

    @Test
    public void testEveryFailureMessageStartsWithTheWrittenTextAndNamesTheOwner()
    {
        for (Result failure : everyFailureOfDrySignal())
        {
            assertTrue(failure.outcome() + " must fail the write", failure.failed()); //$NON-NLS-1$
            String message = failure.failureMessage();
            assertTrue(message, message.startsWith("The module text was written to src/CommonModules/DrySignal/Module.bsl")); //$NON-NLS-1$
            assertTrue(message, message.contains("CommonModule.DrySignal")); //$NON-NLS-1$
            assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
            assertFalse("a failure must not tell the caller to repeat the edit itself: " + message, //$NON-NLS-1$
                message.contains("repeat the write")); //$NON-NLS-1$
        }
    }

    /**
     * The claim round 2 removed is gone from EVERY text the step can produce - each success paragraph and
     * each failure, for an object, the root and the ordinary-application module: the stand showed EDT's
     * XML dump carrying a module that was not Extended, and the infobase load was never tested. So are the
     * claims broader than EDT's bytecode ({@link #OVERBROAD_CLAIM}).
     */
    @Test
    public void testNoOutcomeTextClaimsAnExportOrInfobaseConsequence()
    {
        List<Result> results = new ArrayList<>(everyFailureOfDrySignal());
        for (BuildUtils.DiskExportState state : BuildUtils.DiskExportState.values())
        {
            for (MdPropertyState before : new MdPropertyState[] { MdPropertyState.NONE, MdPropertyState.EXTENDED,
                MdPropertyState.CHECKED, MdPropertyState.NOTIFY })
            {
                for (String path : Arrays.asList(DRY_SIGNAL, "src/Configuration/SessionModule.bsl", ORDINARY)) //$NON-NLS-1$
                {
                    freshPlatform();
                    reports(before);
                    env.exportState = state;
                    results.add(run(path));
                    freshPlatform();
                    reports(before);
                    env.exportAccepted = false;
                    results.add(run(path));
                }
            }
        }
        freshPlatform();
        env.model = null;
        results.add(run(ORDINARY));
        freshPlatform();
        reports(MdPropertyState.NONE);
        results.add(run("src/CommonModules/tests_Own/Module.bsl")); //$NON-NLS-1$
        for (String path : Arrays.asList(DRY_SIGNAL, "src/Configuration/SessionModule.bsl", ORDINARY)) //$NON-NLS-1$
        {
            drySignal.setExtension(null);
            root.setExtension(null);
            freshPlatform();
            reports(MdPropertyState.NONE);
            results.add(run(path));
        }

        Set<Outcome> seen = EnumSet.noneOf(Outcome.class);
        int texts = 0;
        for (Result result : results)
        {
            seen.add(result.outcome());
            for (String text : Arrays.asList(result.bodySentence(), result.failureMessage()))
            {
                if (text != null)
                {
                    texts++;
                    assertNoRemovedClaim(result.outcome() + " text", text); //$NON-NLS-1$
                }
            }
        }
        // The sweep must have reached every outcome a write can report - else it proves nothing about it.
        assertEquals(EnumSet.allOf(Outcome.class), seen);
        assertTrue("the sweep must have read texts: " + texts, texts > 40); //$NON-NLS-1$
    }

    /** One result of every way the step fails for DrySignal, each run on a fresh platform. */
    private List<Result> everyFailureOfDrySignal()
    {
        List<Result> failures = new ArrayList<>();
        failures.add(AdoptedModuleStateTestFixtures.exportNotConfirmed());
        failures.add(AdoptedModuleStateTestFixtures.failedForWantOfAModel());

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        failures.add(run(DRY_SIGNAL));

        freshPlatform();
        reports(MdPropertyState.NONE);
        doThrow(new BmBatchSessionException("batch session is active")).when(env.model).execute(any()); //$NON-NLS-1$
        failures.add(run(DRY_SIGNAL));

        freshPlatform();
        env.access = null;
        failures.add(run(DRY_SIGNAL));

        freshPlatform();
        reports(MdPropertyState.NONE);
        doThrow(new IllegalArgumentException("set refused")).when(access).setPropertyState(any(), any(), any()); //$NON-NLS-1$
        failures.add(run(DRY_SIGNAL));

        freshPlatform();
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), any()))
            .thenThrow(new IllegalStateException("Cannot find appropriate extension property")); //$NON-NLS-1$
        failures.add(run(DRY_SIGNAL));

        freshPlatform(); // the service names none of the four states
        failures.add(run(DRY_SIGNAL));
        freshPlatform();
        return failures;
    }
}
