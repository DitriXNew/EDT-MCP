/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

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

    /** The {@code .mdo} of DrySignal's top object - the module's owner itself. */
    private static final String DRY_SIGNAL_MDO = "src/CommonModules/DrySignal/DrySignal.mdo"; //$NON-NLS-1$

    /** A timeout the platform's read of a small local file finishes well within. */
    private static final long PLATFORM_READ_MS = 30_000L;

    /** The finite ceiling of a hung read in a test: far above the timeout it is given, still bounded itself. */
    private static final long HUNG_READ_CEILING_MS = 15_000L;

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
        // The platform's reads claim a per-project slot that a hung read of an earlier test may still hold.
        BuildUtils.forgetExportWaits();
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

    /**
     * Whatever the scenario, no read of the {@code .mdo} starts with nothing left of the budget, and none is given
     * more than the budget (#743 review) - checked after every test, on the platform its last scenario ran on.
     */
    @After
    public void noReadStartsWithNothingLeftOfTheBudget()
    {
        for (long timeout : env.readTimeouts)
        {
            assertTrue("a read started with nothing left of the budget: " + env.readTimeouts, timeout > 0); //$NON-NLS-1$
            assertTrue("a read was given more than the budget: " + env.readTimeouts, timeout <= 60_000L); //$NON-NLS-1$
        }
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

    // ==================== StateLine: the module's OWN state line in the owner's .mdo ====================

    private static final String MDO_ROOT_ATTRIBUTES = " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" //$NON-NLS-1$
        + " xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\"" //$NON-NLS-1$
        + " xmlns:mdclassExtension=\"http://g5.1c.ru/v8/dt/metadata/mdclass/extension\"" //$NON-NLS-1$
        + " uuid=\"94bc1aac-b04d-4431-af5f-9f59eea92471\""; //$NON-NLS-1$

    /** A .mdo the way EDT writes one: the declaration, then {@code body}. */
    private static byte[] mdo(String body)
    {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + body).getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
    }

    /** The adopted DrySignal's .mdo, its extension block holding {@code stateLines}. */
    private static byte[] drySignalMdo(String stateLines)
    {
        return mdo("<mdclass:CommonModule" + MDO_ROOT_ATTRIBUTES + ">\n  <name>DrySignal</name>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "  <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + "  <extension xsi:type=\"mdclassExtension:CommonModuleExtension\">\n" //$NON-NLS-1$
            + "    <extendedConfigurationObject>Checked</extendedConfigurationObject>\n" + stateLines //$NON-NLS-1$
            + "  </extension>\n  <server>true</server>\n</mdclass:CommonModule>\n"); //$NON-NLS-1$
    }

    /**
     * The adopted Catalog.X's .mdo: its own block holds {@code ownLines}; an adopted form, and two adopted
     * commands - {@code Other} already Extended, {@code Fill} holding {@code fillLines} - carry their own blocks.
     */
    private static byte[] catalogMdo(String ownLines, String fillLines)
    {
        return mdo("<mdclass:Catalog" + MDO_ROOT_ATTRIBUTES + ">\n  <name>X</name>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "  <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + "  <extension xsi:type=\"mdclassExtension:CatalogExtension\">\n" //$NON-NLS-1$
            + "    <extendedConfigurationObject>Checked</extendedConfigurationObject>\n" + ownLines //$NON-NLS-1$
            + "  </extension>\n" //$NON-NLS-1$
            + "  <forms uuid=\"59f829ae-28c7-401d-a12c-0f10b89e65a7\">\n    <name>ItemForm</name>\n" //$NON-NLS-1$
            + "    <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + "    <extension xsi:type=\"mdclassExtension:BasicFormExtension\">\n      <form>Extended</form>\n" //$NON-NLS-1$
            + "    </extension>\n  </forms>\n" //$NON-NLS-1$
            + "  <commands uuid=\"1b5e2bd0-8f0e-4c55-9c41-0f8c6b0c2a11\">\n    <name>Other</name>\n" //$NON-NLS-1$
            + "    <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + "    <extension xsi:type=\"mdclassExtension:BasicCommandExtension\">\n" //$NON-NLS-1$
            + "      <commandModule>Extended</commandModule>\n      <objectModule>Extended</objectModule>\n" //$NON-NLS-1$
            + "    </extension>\n  </commands>\n" //$NON-NLS-1$
            + "  <commands uuid=\"2c6f3ce1-9a1f-4d66-8d52-1a9d7c1d3b22\">\n    <name>Fill</name>\n" //$NON-NLS-1$
            + "    <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + "    <extension xsi:type=\"mdclassExtension:BasicCommandExtension\">\n" + fillLines //$NON-NLS-1$
            + "    </extension>\n  </commands>\n</mdclass:Catalog>\n"); //$NON-NLS-1$
    }

    @Test
    public void testTheStateLineOfATopObjectIsItsOwnBlocksModuleElement()
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        assertNotNull(line);
        assertEquals("extension/module", line.toString()); //$NON-NLS-1$
        assertEquals(AdoptedModuleState.LineOnDisk.PRESENT, line.in(drySignalMdo("    <module>Extended</module>\n"))); //$NON-NLS-1$
        assertEquals(AdoptedModuleState.LineOnDisk.ABSENT, line.in(drySignalMdo(""))); //$NON-NLS-1$
        assertEquals("only Extended is the line the step records", AdoptedModuleState.LineOnDisk.ABSENT, //$NON-NLS-1$
            line.in(drySignalMdo("    <module>Checked</module>\n"))); //$NON-NLS-1$
        assertEquals("whitespace around the value is EDT's formatting, not another value", //$NON-NLS-1$
            AdoptedModuleState.LineOnDisk.PRESENT, line.in(drySignalMdo("    <module>\n      Extended\n    </module>\n"))); //$NON-NLS-1$
    }

    /**
     * A command's line is in ITS block, inside the top object's .mdo, among its siblings': a sibling command that
     * already is Extended must not confirm this one.
     */
    @Test
    public void testTheStateLineOfACommandIsReadInThatCommandsOwnBlock()
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(fill, MdClassPackage.Literals.BASIC_COMMAND__COMMAND_MODULE);
        assertNotNull(line);
        assertEquals("commands[Fill]/extension/commandModule", line.toString()); //$NON-NLS-1$
        assertEquals("the sibling Other carries the line, Fill does not", AdoptedModuleState.LineOnDisk.ABSENT, //$NON-NLS-1$
            line.in(catalogMdo("", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(AdoptedModuleState.LineOnDisk.PRESENT,
            line.in(catalogMdo("", "      <commandModule>Extended</commandModule>\n"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The top object's line is in its OWN block: the same element in a nested object's block is not it. */
    @Test
    public void testATopObjectsLineIsNotTakenFromANestedObjectsBlock()
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(catalog, MdClassPackage.Literals.BASIC_DB_OBJECT__OBJECT_MODULE);
        assertEquals("extension/objectModule", line.toString()); //$NON-NLS-1$
        assertEquals("only the command Other's block holds an objectModule line", AdoptedModuleState.LineOnDisk.ABSENT, //$NON-NLS-1$
            line.in(catalogMdo("", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(AdoptedModuleState.LineOnDisk.PRESENT,
            line.in(catalogMdo("    <objectModule>Extended</objectModule>\n", ""))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheRootsLineIsInTheRootsOwnBlock()
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(root, MdClassPackage.Literals.CONFIGURATION__SESSION_MODULE);
        assertEquals("extension/sessionModule", line.toString()); //$NON-NLS-1$
        String configuration = "<mdclass:Configuration" + MDO_ROOT_ATTRIBUTES + ">\n  <name>tests</name>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "  <objectBelonging>Adopted</objectBelonging>\n" //$NON-NLS-1$
            + "  <extension xsi:type=\"mdclassExtension:ConfigurationExtension\">\n" //$NON-NLS-1$
            + "    <defaultRunMode>Checked</defaultRunMode>\n%s" //$NON-NLS-1$
            + "  </extension>\n  <languages uuid=\"b9813cc5-7b88-4b0f-8ecc-070ae4316918\">\n    <name>English</name>\n" //$NON-NLS-1$
            + "    <extension xsi:type=\"mdclassExtension:LanguageExtension\">\n" //$NON-NLS-1$
            + "      <sessionModule>Extended</sessionModule>\n    </extension>\n  </languages>\n" //$NON-NLS-1$
            + "</mdclass:Configuration>\n"; //$NON-NLS-1$
        assertEquals(AdoptedModuleState.LineOnDisk.ABSENT, line.in(mdo(String.format(configuration, "")))); //$NON-NLS-1$
        assertEquals(AdoptedModuleState.LineOnDisk.PRESENT,
            line.in(mdo(String.format(configuration, "    <sessionModule>Extended</sessionModule>\n")))); //$NON-NLS-1$
    }

    /**
     * A file that cannot be read or parsed proves nothing either way - EDT may be writing it - and the parser is the
     * hardened one: a DOCTYPE is refused, never resolved.
     */
    @Test
    public void testAnMdoThatCannotBeParsedIsUnreadableNotAbsent()
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, line.in(null));
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, line.in(new byte[0]));
        byte[] whole = drySignalMdo("    <module>Extended</module>\n"); //$NON-NLS-1$
        assertEquals("a file cut short mid-write", AdoptedModuleState.LineOnDisk.UNREADABLE, //$NON-NLS-1$
            line.in(Arrays.copyOf(whole, whole.length / 2)));
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, line.in(mdo("<!DOCTYPE x [<!ENTITY e \"Extended\">]>\n" //$NON-NLS-1$
            + "<mdclass:CommonModule" + MDO_ROOT_ATTRIBUTES + "><extension><module>&e;</module></extension>" //$NON-NLS-1$ //$NON-NLS-2$
            + "</mdclass:CommonModule>"))); //$NON-NLS-1$
    }

    @Test
    public void testNoOwnerOrNoFeatureHasNoStateLine()
    {
        assertNull(AdoptedModuleState.StateLine.of(null, MdClassPackage.Literals.COMMON_MODULE__MODULE));
        assertNull(AdoptedModuleState.StateLine.of(drySignal, null));
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

    /**
     * A base configuration: EDT's registry does not name it an extension, and its persistent nature verifiably is
     * not the extension's - the one combination that is no extension. Nothing is asked of its model.
     */
    @Test
    public void testNotAnExtensionProjectChangesNothingAndOpensNoTransaction()
    {
        env.extension = false;
        env.nature = Boolean.FALSE;
        reports(MdPropertyState.NONE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.NOT_APPLICABLE, result.outcome());
        assertNull(result.frontMatterValue());
        assertNull(result.bodySentence());
        assertFalse(result.failed());
        assertTrue("the registry's 'no' is checked against the persistent nature", env.calls.contains("nature")); //$NON-NLS-1$
        assertFalse("no model may be asked for a base project", env.calls.contains("model")); //$NON-NLS-1$
        assertTrue(env.exportCalls().isEmpty());
        verifyZeroInteractions(access);
    }

    /** The registry answers first: an extension it names needs no second look at the project's natures. */
    @Test
    public void testAnExtensionTheRegistryNamesIsNotAskedForItsNature()
    {
        reports(MdPropertyState.NONE);
        assertEquals(Outcome.SWITCHED, run(DRY_SIGNAL).outcome());
        assertFalse(env.calls.contains("nature")); //$NON-NLS-1$
    }

    /**
     * EDT's registry also says "no extension" for a project it has not registered - which is what it does while it
     * disposes and restarts a project's context, between the write and this step. The project's PERSISTENT nature
     * tells such an extension from a base configuration, and the step goes on: without the model it is the
     * transient failure that says the text is written and names the rerun - never a silent "not applicable".
     */
    @Test
    public void testAnExtensionEdtHasNotRegisteredIsToldByItsNatureAndFailsTransiently()
    {
        env.extension = false;
        env.nature = Boolean.TRUE;
        env.model = null;
        reports(MdPropertyState.NONE);

        Result result = run(DRY_SIGNAL);

        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue("the text is written, the state not checked: the write must fail", result.failed()); //$NON-NLS-1$
        assertNull(result.frontMatterValue());
        String message = result.failureMessage();
        assertTrue(message, message.startsWith("The module text was written to src/CommonModules/DrySignal/Module.bsl")); //$NON-NLS-1$
        assertTrue(message, message.contains("whether its owner 'CommonModule.DrySignal' is adopted from the base " //$NON-NLS-1$
            + "configuration - and so whether its module state is to be recorded as Extended - could not be checked")); //$NON-NLS-1$
        assertTrue(message, message.contains("BM model is not available for project 'tests'")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
        assertTrue(message, message.contains("mode=append and an empty source")); //$NON-NLS-1$
        assertEquals("the registry, then the nature, then the model", //$NON-NLS-1$
            Arrays.asList("isExtension", "nature", "model"), env.calls.subList(0, 3)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        verifyZeroInteractions(access);
    }

    /** The nature is enough to go on: with the model there, the owner's own belonging decides as for any extension. */
    @Test
    public void testAnExtensionEdtHasNotRegisteredIsSwitchedWhenItsModelIsThere()
    {
        env.extension = false;
        env.nature = Boolean.TRUE;
        reports(MdPropertyState.NONE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        verify(access).setPropertyState(same(drySignal), same(MdClassPackage.Literals.COMMON_MODULE__MODULE),
            same(MdPropertyState.EXTENDED));
    }

    /**
     * Natures that cannot be read are no proof of a base configuration: the step goes on, and the model and the
     * owner's belonging decide - here, without a model, the transient failure. A base project never gets here: its
     * nature is read, and it is not the extension's.
     */
    @Test
    public void testAProjectWhoseNaturesCannotBeReadIsNotTakenForABaseConfiguration()
    {
        env.extension = false;
        env.nature = null;
        env.model = null;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.failed());
        assertTrue(result.failureMessage(), result.failureMessage().contains("BM model is not available")); //$NON-NLS-1$
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

    /**
     * A refused submission queued nothing: waiting on the empty queue would "drain" and claim an export. The .mdo is
     * read ONCE instead - no wait, no pause, no time spent - and one without the line leaves the switch not confirmed.
     */
    @Test
    public void testASwitchWhoseExportCannotBeScheduledIsNotConfirmedAndNotAwaited()
    {
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertEquals(Collections.singletonList("forceExport:CommonModule.DrySignal"), env.exportCalls()); //$NON-NLS-1$
        assertEquals("read once, never waited for", //$NON-NLS-1$
            Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals("no time spent", 0L, env.clock); //$NON-NLS-1$
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
        assertTrue("no FQN, no file to read", env.mdoReads().isEmpty()); //$NON-NLS-1$
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

    /**
     * The platform reads the PERSISTENT nature off the project's description - the nature id EDT 2026.1 and 2026.2
     * both declare (ICoreConstants.V8_EXTENSION_NATURE, pinned as a literal) - and answers "cannot tell" when the
     * description cannot be read, never "no".
     */
    @Test
    public void testThePlatformReadsTheExtensionNatureOffTheProjectDescription() throws CoreException
    {
        assertEquals(Boolean.TRUE, AdoptedModuleState.PLATFORM.hasExtensionNature(
            natureProject("org.eclipse.xtext.ui.shared.xtextNature", "com._1c.g5.v8.dt.core.V8ExtensionNature"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, AdoptedModuleState.PLATFORM.hasExtensionNature(
            natureProject("org.eclipse.xtext.ui.shared.xtextNature", "com._1c.g5.v8.dt.core.V8ConfigurationNature"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, AdoptedModuleState.PLATFORM.hasExtensionNature(
            natureProject("com._1c.g5.v8.dt.core.V8ExternalObjectsNature"))); //$NON-NLS-1$
        IProject unreadable = mock(IProject.class);
        when(unreadable.isOpen()).thenReturn(Boolean.TRUE);
        when(unreadable.getDescription()).thenThrow(new CoreException(Status.CANCEL_STATUS));
        assertNull(AdoptedModuleState.PLATFORM.hasExtensionNature(unreadable));
    }

    /**
     * The platform reads the .mdo's BYTES off the file system - what EDT's export wrote, not the workspace's view
     * of it - and an absent file, or a project with no location for it, is unreadable, never "absent". Each read
     * runs through the bounded read; these finish well within their timeout.
     */
    @Test
    public void testThePlatformReadsTheMdoBackFromTheFileSystem() throws Exception
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        java.nio.file.Path dir = Files.createTempDirectory("adopted-module-state"); //$NON-NLS-1$
        try
        {
            java.nio.file.Path file = dir.resolve("DrySignal.mdo"); //$NON-NLS-1$
            Files.write(file, drySignalMdo("    <module>Extended</module>\n")); //$NON-NLS-1$
            IProject onDisk = projectWithFileAt(DRY_SIGNAL_MDO, file);
            assertEquals(AdoptedModuleState.LineOnDisk.PRESENT,
                AdoptedModuleState.PLATFORM.readStateLine(onDisk, DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));

            Files.write(file, drySignalMdo("")); //$NON-NLS-1$
            assertEquals(AdoptedModuleState.LineOnDisk.ABSENT,
                AdoptedModuleState.PLATFORM.readStateLine(onDisk, DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));

            Files.delete(file);
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                AdoptedModuleState.PLATFORM.readStateLine(onDisk, DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));

            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.PLATFORM.readStateLine(
                projectWithFileAt(DRY_SIGNAL_MDO, null), DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));
        }
        finally
        {
            Files.deleteIfExists(dir.resolve("DrySignal.mdo")); //$NON-NLS-1$
            Files.deleteIfExists(dir);
        }
    }

    /**
     * The platform's read of the .mdo is BOUNDED (#743 review): it runs off the caller's thread, in a BoundedJob, and
     * a file system that does not answer - a network disk, a .mdo too large for what is left - holds the call no
     * longer than the timeout it is given; a read not answered by then is UNREADABLE. The hung read here waits on a
     * latch with its own finite ceiling, so a read that lost its bound fails the elapsed-time assertion instead of
     * hanging the suite.
     */
    @Test
    public void testThePlatformReadIsBoundedAndRunsOffTheCallersThread() throws Exception
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        IProject hanging = mock(IProject.class);
        IFile mdoFile = mock(IFile.class);
        when(hanging.getFile(new Path(DRY_SIGNAL_MDO))).thenReturn(mdoFile);
        when(mdoFile.getLocation()).thenAnswer(inv -> {
            reader.set(Thread.currentThread());
            release.await(HUNG_READ_CEILING_MS, TimeUnit.MILLISECONDS); // a file system that does not answer
            return null;
        });
        long start = System.nanoTime();
        try
        {
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                AdoptedModuleState.PLATFORM.readStateLine(hanging, DRY_SIGNAL_MDO, line, 500L));
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("the read must give up at its timeout, not at the file system's ceiling (waited " + waited //$NON-NLS-1$
                + "ms)", waited < HUNG_READ_CEILING_MS / 2); //$NON-NLS-1$
            assertNotSame("the read must run off the caller's thread", Thread.currentThread(), reader.get()); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * The platform's read claims its PROJECT's slot before it starts a job (#743 review): while an earlier read of the
     * project's files has not returned, the read starts no job - the file, which carries the line, is not even
     * touched - and answers that it could not read it back. Another project's read is not held up, and once the
     * earlier read returned, the project's read runs again and releases its own claim when it returns.
     */
    @Test
    public void testThePlatformReadClaimsItsProjectsSlotBeforeItStartsAJob() throws Exception
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        java.nio.file.Path dir = Files.createTempDirectory("adopted-module-state"); //$NON-NLS-1$
        try
        {
            java.nio.file.Path file = dir.resolve("DrySignal.mdo"); //$NON-NLS-1$
            Files.write(file, drySignalMdo("    <module>Extended</module>\n")); //$NON-NLS-1$
            IProject onDisk = projectWithFileAt("SlotProject", DRY_SIGNAL_MDO, file); //$NON-NLS-1$
            AtomicBoolean earlier = BuildUtils.beginStateLineRead("SlotProject", 60_000L); //$NON-NLS-1$
            assertNotNull("an earlier read of the project is out", earlier); //$NON-NLS-1$

            assertEquals("no read starts while an earlier read of the project is out", //$NON-NLS-1$
                AdoptedModuleState.LineOnDisk.UNREADABLE,
                AdoptedModuleState.PLATFORM.readStateLine(onDisk, DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));
            verify(onDisk, never()).getFile(any(org.eclipse.core.runtime.IPath.class));

            assertEquals("another project's read is not held up", AdoptedModuleState.LineOnDisk.PRESENT, //$NON-NLS-1$
                AdoptedModuleState.PLATFORM.readStateLine(projectWithFileAt("OtherSlotProject", DRY_SIGNAL_MDO, file), //$NON-NLS-1$
                    DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));

            earlier.set(true); // the earlier read returned
            assertEquals("once it returned, the project's read runs again", AdoptedModuleState.LineOnDisk.PRESENT, //$NON-NLS-1$
                AdoptedModuleState.PLATFORM.readStateLine(onDisk, DRY_SIGNAL_MDO, line, PLATFORM_READ_MS));
            assertNotNull("and releases its own claim when it returns", //$NON-NLS-1$
                BuildUtils.beginStateLineRead("SlotProject", 60_000L)); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(dir.resolve("DrySignal.mdo")); //$NON-NLS-1$
            Files.deleteIfExists(dir);
        }
    }

    /**
     * The review's case end to end, on EDT's job manager (#743 review): a read the deadline cut off keeps its job until
     * the file system answers, and a second read of the same project meanwhile starts NO second job - it answers at
     * once that the file could not be read back - so repeated writes do not pile up one hung job each. Once the file
     * system answers, the first job returns and reopens the slot, and a later read starts a job again. The hung reads
     * wait on a latch with a finite ceiling, so a read that lost its bound fails an assertion instead of hanging the
     * suite.
     */
    @Test
    public void testAHungPlatformReadStartsNoSecondJobUntilItReturns() throws Exception
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        IProject hanging = mock(IProject.class);
        when(hanging.getName()).thenReturn("HungProject"); //$NON-NLS-1$
        IFile mdoFile = mock(IFile.class);
        when(hanging.getFile(new Path(DRY_SIGNAL_MDO))).thenReturn(mdoFile);
        when(mdoFile.getLocation()).thenAnswer(inv -> {
            entered.incrementAndGet();
            release.await(HUNG_READ_CEILING_MS, TimeUnit.MILLISECONDS); // a file system that does not answer
            return null;
        });
        try
        {
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                AdoptedModuleState.PLATFORM.readStateLine(hanging, DRY_SIGNAL_MDO, line, 2_000L));
            assertEquals("the first read's job is still in the file system", 1, entered.get()); //$NON-NLS-1$

            long start = System.nanoTime();
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                AdoptedModuleState.PLATFORM.readStateLine(hanging, DRY_SIGNAL_MDO, line, 5_000L));
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("a second read is refused at once, not after its own timeout (waited " + waited + "ms)", //$NON-NLS-1$ //$NON-NLS-2$
                waited < 2_500L);
            assertEquals("no second job reached the file system", 1, entered.get()); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
        // The file system answers: the first job returns and reopens the slot, so a later read starts a job again.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HUNG_READ_CEILING_MS);
        while (entered.get() < 2 && System.nanoTime() < until)
        {
            AdoptedModuleState.PLATFORM.readStateLine(hanging, DRY_SIGNAL_MDO, line, 2_000L);
            if (entered.get() < 2)
            {
                Thread.sleep(20);
            }
        }
        assertEquals("once the first read returned, a new read starts a job", 2, entered.get()); //$NON-NLS-1$
    }

    /** A pause the call's own thread is interrupted in ends at once, and the interrupt is kept for the caller. */
    @Test
    public void testThePlatformPauseKeepsTheInterrupt()
    {
        assertTrue(AdoptedModuleState.PLATFORM.pause(1));
        Thread.currentThread().interrupt();
        try
        {
            assertFalse(AdoptedModuleState.PLATFORM.pause(10_000));
            assertTrue("the interrupt must be restored", Thread.currentThread().isInterrupted()); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }
    }

    /** An OPEN project whose description carries exactly {@code natureIds}. */
    private static IProject natureProject(String... natureIds) throws CoreException
    {
        IProject natured = mock(IProject.class);
        when(natured.isOpen()).thenReturn(Boolean.TRUE);
        IProjectDescription description = mock(IProjectDescription.class);
        when(description.getNatureIds()).thenReturn(natureIds);
        when(natured.getDescription()).thenReturn(description);
        return natured;
    }

    /** A project whose file at {@code relativePath} lies at {@code location} on disk ({@code null}: no location). */
    private static IProject projectWithFileAt(String relativePath, java.nio.file.Path location)
    {
        return projectWithFileAt(null, relativePath, location);
    }

    /**
     * A project named {@code name} - its reads' slot - whose file at {@code relativePath} lies at {@code location} on
     * disk ({@code null}: no location).
     */
    private static IProject projectWithFileAt(String name, String relativePath, java.nio.file.Path location)
    {
        IProject owner = mock(IProject.class);
        when(owner.getName()).thenReturn(name);
        IFile mdoFile = mock(IFile.class);
        when(owner.getFile(new Path(relativePath))).thenReturn(mdoFile);
        when(mdoFile.getLocation()).thenReturn(location == null ? null : Path.fromOSString(location.toString()));
        return owner;
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

    // ==================== the export is confirmed by the FILE, never by the queue ====================

    /** The owner's .mdo as the model names it, and the state line the step looks for in it. */
    private static final String DRY_SIGNAL_READ = "readMdo:src/CommonModules/DrySignal/DrySignal.mdo#extension/module"; //$NON-NLS-1$

    /**
     * The queue's answer only says nothing is queued any more: the .mdo itself is read back, after the wait, for
     * the module's own state line - and a line that is there at once ends it, with no pause.
     */
    @Test
    public void testTheMdoIsReadBackAfterTheWaitForTheModulesOwnStateLine()
    {
        reports(MdPropertyState.NONE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:60000", DRY_SIGNAL_READ), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportAndDiskCalls());
        assertTrue(result.bodySentence(), result.bodySentence().contains("the .mdo was exported")); //$NON-NLS-1$
    }

    /**
     * EDT's export task processor catches a failed file write (a locked or read-only .mdo, a full disk) and the
     * queue DRAINS all the same - BuildUtils.waitForDiskExport's own contract. So a drained queue whose .mdo,
     * read back to the end of the budget, never carries the line is no export: after a switch the write fails
     * with the text written, and nothing says the .mdo was exported.
     */
    @Test
    public void testADrainedQueueWhoseMdoLacksTheLineIsNotConfirmed()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.DRAINED;
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);

        Result result = run(DRY_SIGNAL);

        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertNull(result.frontMatterValue());
        String message = result.failureMessage();
        assertTrue(message, message.startsWith("The module text was written to src/CommonModules/DrySignal/Module.bsl")); //$NON-NLS-1$
        assertTrue(message, message.contains("was switched from None to Extended in EDT's model, but the export of its " //$NON-NLS-1$
            + ".mdo was requested and src/CommonModules/DrySignal/DrySignal.mdo, read back, did not carry " //$NON-NLS-1$
            + "<module>Extended</module> within 60s")); //$NON-NLS-1$
        assertTrue(message, message.contains("Nothing was rolled back")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
        assertTrue(message, message.contains("mode=append and an empty source")); //$NON-NLS-1$
        assertFalse(message, message.contains("was exported")); //$NON-NLS-1$
        // The file was read until the budget ran out - ONE 60s budget, counted from before the request - and the last
        // pause reaches its end: no read starts with nothing left (#743 review), so the calls end on that pause, and
        // each read was bounded by what was left when it started.
        assertEquals(60_000L, env.clock);
        List<String> disk = env.exportAndDiskCalls();
        assertEquals(disk.toString(), "pause:500", disk.get(disk.size() - 1)); //$NON-NLS-1$
        for (String read : env.mdoReads())
        {
            assertEquals(DRY_SIGNAL_READ, read);
        }
        assertEachReadGotWhatWasLeftOf(60_000L);
        // The pause doubles from 100ms to 1s: a prompt first look, then no busier than once a second.
        assertEquals(Arrays.asList("pause:100", "pause:200", "pause:400", "pause:800", "pause:1000", "pause:1000"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            pauses().subList(0, 6));
    }

    /**
     * UNOBSERVABLE says nothing about the export, either way: the step keeps reading the .mdo, and a line that
     * appears within the budget confirms the switch.
     */
    @Test
    public void testAnUnobservableQueueIsConfirmedByTheLineAppearingWithinTheBudget()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        env.disk.addAll(Arrays.asList(AdoptedModuleState.LineOnDisk.ABSENT, AdoptedModuleState.LineOnDisk.ABSENT,
            AdoptedModuleState.LineOnDisk.PRESENT));

        Result result = run(DRY_SIGNAL);

        assertEquals(Outcome.SWITCHED, result.outcome());
        assertFalse(result.failed());
        assertEquals("switchedToExtended", result.frontMatterValue()); //$NON-NLS-1$
        assertTrue(result.bodySentence(), result.bodySentence().contains("the .mdo was exported")); //$NON-NLS-1$
        assertEquals(3, env.mdoReads().size());
        assertEquals(300L, env.clock);
    }

    /** UNOBSERVABLE and a line that never appears: the switch is not confirmed - the write fails with its text written. */
    @Test
    public void testAnUnobservableQueueWhoseLineNeverAppearsIsNotConfirmed()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);

        Result result = run(DRY_SIGNAL);

        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertTrue(result.failureMessage(), result.failureMessage().contains(
            "did not carry <module>Extended</module> within 60s")); //$NON-NLS-1$
        assertEquals(60_000L, env.clock);
    }

    /**
     * ONE budget for the queue and the file: a queue wait that spent the whole 60s leaves no time for a read, so none
     * starts (#743 review) - no read, no pause - and the export is not confirmed. The file still outranks the queue: a
     * PENDING answer whose .mdo carries the line is confirmed - a previous wait still outstanding makes the queue
     * answer PENDING at once.
     */
    @Test
    public void testTheQueueAndTheFileShareOneSixtySecondBudget()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        env.exportWaitMs = 60_000L;
        env.disk.addAll(Arrays.asList(AdoptedModuleState.LineOnDisk.ABSENT, AdoptedModuleState.LineOnDisk.PRESENT));
        Result spent = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, spent.outcome());
        assertTrue(spent.failureMessage(), spent.failureMessage().contains("was not confirmed within 60s")); //$NON-NLS-1$
        assertTrue("the wait spent the budget: no read starts", env.mdoReads().isEmpty()); //$NON-NLS-1$
        assertTrue(pauses().isEmpty());
        assertEquals(60_000L, env.clock);

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        env.disk.addAll(Arrays.asList(AdoptedModuleState.LineOnDisk.ABSENT, AdoptedModuleState.LineOnDisk.PRESENT));
        Result appeared = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, appeared.outcome());
        assertTrue(appeared.bodySentence(), appeared.bodySentence().contains("the .mdo was exported")); //$NON-NLS-1$
    }

    /** An interrupted pause ends the reading at once - the call is being cancelled - and confirms nothing. */
    @Test
    public void testAnInterruptedPollStopsAtOnceAndConfirmsNothing()
    {
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        env.interrupted = true;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertEquals("no read after the interrupted pause", 1, env.mdoReads().size()); //$NON-NLS-1$
        assertEquals(Collections.singletonList("pause:100"), pauses()); //$NON-NLS-1$
        assertEquals(100L, env.clock);
    }

    // ==================== ONE budget from BEFORE the request; no read with nothing left; every read bounded ====================

    /**
     * The budget counts the export REQUEST too (#743 review): it is taken before the request, so a request that takes
     * 20s leaves the queue's wait the 40s left, not a fresh 60s, and the read after it what the wait left. The export
     * is confirmed exactly as before - the same key and the same paragraph, word for word.
     */
    @Test
    public void testTheBudgetCountsTheExportRequestItself()
    {
        reports(MdPropertyState.NONE);
        Result instant = run(DRY_SIGNAL); // a request that takes no time leaves the queue the whole 60s, as before
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:60000"), env.exportCalls()); //$NON-NLS-1$ //$NON-NLS-2$

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportRequestMs = 20_000L;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        assertFalse(result.failed());
        assertEquals("switchedToExtended", result.frontMatterValue()); //$NON-NLS-1$
        assertEquals("the success text is unchanged", instant.bodySentence(), result.bodySentence()); //$NON-NLS-1$
        assertEquals("the queue's wait gets what the request left of the 60s", //$NON-NLS-1$
            Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:40000", DRY_SIGNAL_READ), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportAndDiskCalls());
        assertEquals("the read gets what the wait left", Collections.singletonList(40_000L), env.readTimeouts); //$NON-NLS-1$
    }

    /**
     * A queue wait that uses all it is given ends the call within the ONE budget, the request's own time included: the
     * request took 20s, the wait the 40s left, and nothing is left for a read - so none starts, and the export is not
     * confirmed within 60s (#743 review).
     */
    @Test
    public void testASlowRequestAndAWaitThatUsesTheRestEndWithinTheBudgetWithNoRead()
    {
        reports(MdPropertyState.NONE);
        env.exportRequestMs = 20_000L;
        env.exportState = BuildUtils.DiskExportState.PENDING;
        env.exportWaitMs = Long.MAX_VALUE; // the wait uses all it is given
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertTrue(result.failureMessage(), result.failureMessage().contains("was not confirmed within 60s")); //$NON-NLS-1$
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", "awaitExport:40000"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportAndDiskCalls());
        assertEquals("the whole call, the request included, within the one budget", 60_000L, env.clock); //$NON-NLS-1$
    }

    /**
     * A request that itself used the whole budget leaves nothing to wait or read with (#743 review): no wait starts - a
     * wait of no time cannot see the queue drain, and the platform's wait, once started, cannot be cancelled - and no
     * read starts, so even a healthy export whose line would be on disk is not confirmed within 60s.
     */
    @Test
    public void testARequestThatUsedTheWholeBudgetIsNeitherAwaitedNorRead()
    {
        reports(MdPropertyState.NONE);
        env.exportRequestMs = 60_000L; // a healthy queue and file otherwise: a read would find the line
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        String message = result.failureMessage();
        assertTrue(message, message.contains("was switched from None to Extended in EDT's model, but the export of its " //$NON-NLS-1$
            + ".mdo was not confirmed within 60s, so src/CommonModules/DrySignal/DrySignal.mdo may still lack " //$NON-NLS-1$
            + "<module>Extended</module>.")); //$NON-NLS-1$
        assertEquals(Collections.singletonList("forceExport:CommonModule.DrySignal"), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals(60_000L, env.clock);
    }

    /**
     * A request that left less than a second is not waited with either - the rule the metadata writes' own waits keep
     * (MIN_USEFUL_WAIT_MS): so short a wait cannot see the queue drain, while the platform's wait it starts cannot be
     * cancelled and takes the project's wait slot. The file still decides it with what is left: a .mdo that carries
     * the line confirms the switch, and one that does not leaves the export not confirmed within 60s - the queue was
     * not seen draining.
     */
    @Test
    public void testARequestThatLeftLessThanASecondIsNotAwaitedButTheFileStillDecides()
    {
        reports(MdPropertyState.NONE);
        env.exportRequestMs = 59_500L; // a healthy queue and file otherwise
        Result confirmed = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, confirmed.outcome());
        assertFalse(confirmed.failed());
        assertEquals("no wait for the queue, one read with the 500ms left", //$NON-NLS-1$
            Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(500L), env.readTimeouts);

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportRequestMs = 59_500L;
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertTrue(result.failureMessage(), result.failureMessage().contains("was not confirmed within 60s")); //$NON-NLS-1$
        assertFalse("the queue was never waited for", env.exportCalls().stream() //$NON-NLS-1$
            .anyMatch(call -> call.startsWith("awaitExport:"))); //$NON-NLS-1$
        assertEachReadGotWhatWasLeftOf(60_000L);
        assertEquals("the whole call within the one budget", 60_000L, env.clock); //$NON-NLS-1$
    }

    /**
     * A queue wait that spent the whole budget leaves no time for a read, so none starts - whatever the queue said and
     * whatever the file holds (#743 review). The export is then not confirmed, in honest words: a queue still busy is
     * "not confirmed within 60s"; a drained or unobservable one leaves a .mdo that "could not be read back ... within
     * 60s" - never "did not carry", which only a file read and found without the line may say.
     */
    @Test
    public void testAQueueWaitThatSpendsTheWholeBudgetLeavesNoReadAndConfirmsNothing()
    {
        for (BuildUtils.DiskExportState queue : BuildUtils.DiskExportState.values())
        {
            freshPlatform();
            reports(MdPropertyState.NONE);
            env.exportState = queue;
            env.exportWaitMs = Long.MAX_VALUE;
            env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT); // on disk - but no time is left to look
            Result result = run(DRY_SIGNAL);
            String what = queue.name();
            assertEquals(what, Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
            assertTrue(what + ": no read starts with nothing left", env.mdoReads().isEmpty()); //$NON-NLS-1$
            assertTrue(what, pauses().isEmpty());
            assertEquals(what, 60_000L, env.clock);
            String message = result.failureMessage();
            assertTrue(what + ": " + message, message.contains(queue == BuildUtils.DiskExportState.PENDING //$NON-NLS-1$
                ? "the export of its .mdo was not confirmed within 60s, so src/CommonModules/DrySignal/DrySignal.mdo may " //$NON-NLS-1$
                    + "still lack <module>Extended</module>" //$NON-NLS-1$
                : "the export of its .mdo was requested and src/CommonModules/DrySignal/DrySignal.mdo could not be read " //$NON-NLS-1$
                    + "back to confirm <module>Extended</module> within 60s")); //$NON-NLS-1$
            assertFalse(what + ": " + message, message.contains("did not carry")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The poll never starts a read once the budget is spent (#743 review): the last pause takes the clock to the end of
     * the budget, and a line that only a read AT that end would have seen is not seen - the export is not confirmed -
     * rather than a read starting with nothing left.
     */
    @Test
    public void testThePollStartsNoReadAtTheEndOfTheBudget()
    {
        reports(MdPropertyState.NONE);
        env.disk.addAll(Collections.nCopies(63, AdoptedModuleState.LineOnDisk.ABSENT));
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT); // what a 64th read, at the end of the budget, would see
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failureMessage(), result.failureMessage().contains(
            "did not carry <module>Extended</module> within 60s")); //$NON-NLS-1$
        assertEquals(63, env.mdoReads().size());
        assertEquals("the last read starts with time left", 59_500L, env.readStarts.get(62).longValue()); //$NON-NLS-1$
        assertEquals(60_000L, env.clock);
        assertEachReadGotWhatWasLeftOf(60_000L);
    }

    /**
     * A read that does not answer within what is left - a slow or hung file system, a large .mdo - is cut at the end of
     * the budget, as the platform's bounded read is: the call returns within the budget, and a read not answered in
     * time confirms nothing - the .mdo "could not be read back" (#743 review). Each read is given what is left when it
     * starts, never a fresh budget.
     */
    @Test
    public void testAReadThatOutlastsWhatIsLeftIsCutAtTheEndOfTheBudget()
    {
        reports(MdPropertyState.NONE);
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        env.readTimes.addAll(Arrays.asList(0L, 0L, 600_000L)); // the third read hangs for ten minutes
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        String message = result.failureMessage();
        assertTrue(message, message.contains("the export of its .mdo was requested and " //$NON-NLS-1$
            + "src/CommonModules/DrySignal/DrySignal.mdo could not be read back to confirm <module>Extended</module> " //$NON-NLS-1$
            + "within 60s")); //$NON-NLS-1$
        assertFalse(message, message.contains("did not carry")); //$NON-NLS-1$
        assertEquals("the call ends with the budget, not with the hung read", 60_000L, env.clock); //$NON-NLS-1$
        assertEquals(Arrays.asList(60_000L, 59_900L, 59_700L), env.readTimeouts);
        assertEquals(Arrays.asList("pause:100", "pause:200"), pauses()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A refused export's ONE read is bounded by what the request left of the budget as well (#743 review): a request
     * refused after 15s leaves the read 45s; one refused after the whole budget leaves no read at all - the export stays
     * not scheduled even with the line on disk, as nothing was seen; an export that threw after 15s is read within the
     * 45s left; and a read that outlasts what is left is cut at the end of the budget and confirms nothing.
     */
    @Test
    public void testARefusedExportsOneReadGetsOnlyWhatTheRequestLeft()
    {
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        env.exportRequestMs = 15_000L;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        assertEquals("refused after 15s, the line on disk", Outcome.SWITCHED, run(DRY_SIGNAL).outcome()); //$NON-NLS-1$
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(45_000L), env.readTimeouts);

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        env.exportRequestMs = 60_000L;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        Result spent = run(DRY_SIGNAL);
        assertEquals("refused after the whole budget", Outcome.EXPORT_NOT_CONFIRMED, spent.outcome()); //$NON-NLS-1$
        assertTrue(spent.failureMessage(), spent.failureMessage().contains("the export of its .mdo could not be " //$NON-NLS-1$
            + "scheduled, so src/CommonModules/DrySignal/DrySignal.mdo may still lack <module>Extended</module>.")); //$NON-NLS-1$
        assertEquals("no time left: no read", Collections.singletonList("forceExport:CommonModule.DrySignal"), //$NON-NLS-1$ //$NON-NLS-2$
            env.exportAndDiskCalls());

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportFailure = new IllegalStateException("save task refused"); //$NON-NLS-1$
        env.exportRequestMs = 15_000L;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        assertEquals("threw after 15s, the line on disk", Outcome.SWITCHED, run(DRY_SIGNAL).outcome()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(45_000L), env.readTimeouts);

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        env.readTimes.add(600_000L); // the one read hangs for ten minutes
        Result hung = run(DRY_SIGNAL);
        assertEquals("a hung read", Outcome.EXPORT_NOT_CONFIRMED, hung.outcome()); //$NON-NLS-1$
        assertTrue(hung.failureMessage(), hung.failureMessage().contains("could not be scheduled")); //$NON-NLS-1$
        assertEquals(Collections.singletonList(60_000L), env.readTimeouts);
        assertEquals("the call ends with the budget, not with the hung read", 60_000L, env.clock); //$NON-NLS-1$
    }

    /**
     * The bounded read answers only for a read that COMPLETED in time (#743 review): one the deadline caught - running,
     * or before it started - one that never ran, one that threw, one whose caller was interrupted and one that could not
     * be scheduled are UNREADABLE, whatever the read would have found; the read is given exactly the timeout it is
     * handed, and nothing left starts no read at all. An interrupt stays on the caller's thread. The runner is a fake
     * that answers a scripted outcome: no real wait.
     */
    @Test
    public void testTheBoundedReadAnswersOnlyForAReadThatCompletedInTime()
    {
        for (AdoptedModuleState.LineOnDisk found : AdoptedModuleState.LineOnDisk.values())
        {
            FakeRunner runner = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
            assertEquals(found, AdoptedModuleState.readBounded(runner, "completed", "read", 1_234L, () -> found)); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("the read's timeout is what it is handed", Collections.singletonList(1_234L), runner.timeouts); //$NON-NLS-1$
        }
        // Each case below reads a project of its own and checks that the runner was handed the read: a case whose read
        // was refused for an earlier case's claim would answer UNREADABLE for the wrong reason.
        assertEquals("a read that threw found nothing", AdoptedModuleState.LineOnDisk.UNREADABLE, //$NON-NLS-1$
            readThrough(new FakeRunner(BoundedJob.Outcome.COMPLETED, true), "threw", () -> { //$NON-NLS-1$
                throw new IllegalStateException("disk"); //$NON-NLS-1$
            }));
        assertEquals("a run that published no answer found nothing", AdoptedModuleState.LineOnDisk.UNREADABLE, //$NON-NLS-1$
            readThrough(new FakeRunner(BoundedJob.Outcome.COMPLETED, false), "unpublished", //$NON-NLS-1$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));
        for (BoundedJob.Outcome late : new BoundedJob.Outcome[] { BoundedJob.Outcome.TIMED_OUT,
            BoundedJob.Outcome.TIMED_OUT_BEFORE_START, BoundedJob.Outcome.NOT_RUN })
        {
            // Even a read that went on to find the line is no answer once the caller stopped waiting for it.
            assertEquals(late.name(), AdoptedModuleState.LineOnDisk.UNREADABLE, readThrough(
                new FakeRunner(late, true), "late-" + late, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$
        }
        // An interrupted caller, as BoundedJob reports it: the read may have run on and found the line, and the result
        // carries the InterruptedException. Then the same read with no failure on the result: only the outcome is left
        // to refuse it, so the rule "an answer only for a COMPLETED read" is pinned by itself.
        for (Throwable carried : new Throwable[] { new InterruptedException(), null })
        {
            FakeRunner interrupted = new FakeRunner(BoundedJob.Outcome.INTERRUPTED, true);
            interrupted.interruptsTheCaller = true;
            interrupted.failure = carried;
            try
            {
                assertEquals(String.valueOf(carried), AdoptedModuleState.LineOnDisk.UNREADABLE,
                    readThrough(interrupted, "interrupted-" + carried, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$
                assertTrue("the interrupt is kept for the caller", Thread.currentThread().isInterrupted()); //$NON-NLS-1$
            }
            finally
            {
                Thread.interrupted();
            }
        }
        FakeRunner unschedulable = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
        unschedulable.scheduleFailure = new IllegalStateException("job manager shut down"); //$NON-NLS-1$
        assertEquals("a read that could not be scheduled read nothing", AdoptedModuleState.LineOnDisk.UNREADABLE, //$NON-NLS-1$
            readThrough(unschedulable, "unschedulable", () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$
        for (long none : new long[] { 0L, -1L })
        {
            FakeRunner idle = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                AdoptedModuleState.readBounded(idle, "idle", "read", none, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("nothing left: no read is started", idle.timeouts.isEmpty()); //$NON-NLS-1$
        }
    }

    /**
     * One read per project is outstanding at a time while its claim holds (#743 review): a read the deadline cut off
     * keeps its job - one of EDT's worker threads - until the file system answers, so while it has not returned, a
     * second read of the same project starts NO job and answers that the file could not be read back, as a read the
     * deadline cut does. Another project's read is not held up, and once the first read's job ends - the file system
     * answers, the read returns - the project's reads start again.
     */
    @Test
    public void testOneReadPerProjectIsOutstandingAtATime() throws Exception
    {
        FakeRunner cut = new FakeRunner(BoundedJob.Outcome.TIMED_OUT, false); // the read is still in the file system
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
            readThrough(cut, "P", () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$

        FakeRunner second = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
        assertEquals("a second read while the first is out", AdoptedModuleState.LineOnDisk.UNREADABLE, //$NON-NLS-1$
            AdoptedModuleState.readBounded(second, "P", "read", 1_000L, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("no job is started for it", second.timeouts.isEmpty()); //$NON-NLS-1$

        assertEquals("another project's read is not held up", AdoptedModuleState.LineOnDisk.PRESENT, //$NON-NLS-1$
            readThrough(new FakeRunner(BoundedJob.Outcome.COMPLETED, true), "Q", //$NON-NLS-1$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));

        cut.finishKept(); // the file system answers, long after the caller stopped waiting, and the job ends
        assertEquals("once the first read returned, the next one starts", AdoptedModuleState.LineOnDisk.PRESENT, //$NON-NLS-1$
            readThrough(new FakeRunner(BoundedJob.Outcome.COMPLETED, true), "P", //$NON-NLS-1$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));
    }

    /**
     * The claim is held exactly as long as the read is out (#743 review): taken before the job is scheduled, held while
     * the read runs, and released by the job itself when the read returns - a read that threw included - so a caller
     * that has the answer finds the claim released even while the job's terminal notification is still on its way:
     * EDT marks the job done first and delivers the notification after, so a join that found the job done may return
     * a moment before it comes.
     */
    @Test
    public void testTheClaimIsHeldWhileTheReadRunsAndReleasedWhenItReturns()
    {
        boolean[] heldDuringTheRead = new boolean[1];
        FakeRunner returned = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
        returned.notificationLate = true;
        assertEquals(AdoptedModuleState.LineOnDisk.PRESENT, readThrough(returned, "P", () -> { //$NON-NLS-1$
            heldDuringTheRead[0] = BuildUtils.beginStateLineRead("P", 1_000L) == null; //$NON-NLS-1$
            return AdoptedModuleState.LineOnDisk.PRESENT;
        }));
        assertTrue("the project's slot is held while its read runs", heldDuringTheRead[0]); //$NON-NLS-1$
        assertNotNull("and released once the read returned, before the job's terminal notification", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("P", 1_000L)); //$NON-NLS-1$

        FakeRunner threw = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
        threw.notificationLate = true;
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, readThrough(threw, "Threw", () -> { //$NON-NLS-1$
            throw new IllegalStateException("disk"); //$NON-NLS-1$
        }));
        assertNotNull("a read that threw returned all the same", BuildUtils.beginStateLineRead("Threw", 1_000L)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Before any terminal notification of the job, only a read that can no longer run releases its claim at once (#743
     * review): a job our own cancel kept from starting - its notification may come a moment after the run answered -
     * and a job that could not be scheduled. A read the deadline cut while it ran, one that has not run yet (a
     * suspended job manager still holds it) and one whose caller was interrupted while it ran may still be out: they
     * keep the claim until their job ends or the claim lapses. Nothing left of the budget takes no claim at all.
     */
    @Test
    public void testOnlyAReadThatCanNoLongerRunReleasesItsClaimAtOnce()
    {
        for (BoundedJob.Outcome stillOut : new BoundedJob.Outcome[] { BoundedJob.Outcome.TIMED_OUT,
            BoundedJob.Outcome.NOT_RUN, BoundedJob.Outcome.INTERRUPTED })
        {
            String project = "out-" + stillOut; //$NON-NLS-1$
            FakeRunner runner = new FakeRunner(stillOut, false);
            runner.interruptsTheCaller = stillOut == BoundedJob.Outcome.INTERRUPTED;
            try
            {
                assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                    readThrough(runner, project, () -> AdoptedModuleState.LineOnDisk.PRESENT));
            }
            finally
            {
                Thread.interrupted();
            }
            assertNull(stillOut + " keeps the claim: the read may still be out", //$NON-NLS-1$
                BuildUtils.beginStateLineRead(project, 1_000L));
        }
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, readThrough(
            new FakeRunner(BoundedJob.Outcome.TIMED_OUT_BEFORE_START, false), "before-start", //$NON-NLS-1$
            () -> AdoptedModuleState.LineOnDisk.PRESENT));
        assertNotNull("a read our cancel kept from starting will never run: its claim is released at once", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("before-start", 1_000L)); //$NON-NLS-1$

        FakeRunner unschedulable = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
        unschedulable.scheduleFailure = new IllegalStateException("job manager shut down"); //$NON-NLS-1$
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
            readThrough(unschedulable, "unschedulable", () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$
        assertNotNull("a read that could not be scheduled started no job: its claim is released at once", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("unschedulable", 1_000L)); //$NON-NLS-1$

        for (long none : new long[] { 0L, -1L })
        {
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(
                new FakeRunner(BoundedJob.Outcome.COMPLETED, true), "none", "read", none, //$NON-NLS-1$ //$NON-NLS-2$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));
        }
        assertNotNull("nothing left of the budget takes no claim", BuildUtils.beginStateLineRead("none", 1_000L)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An unreturned claim holds for three times the step's whole budget, not three times the read's share of it (#743
     * review): a read cut with a sliver of the budget left - 1ms here - would otherwise hold its project's slot for a
     * sliver, and the next write would start another hung job at once. The hold's length is read off the slot, so a
     * basis that is too short but outlasts the test's sleep - the poll's longest pause - fails as well.
     */
    @Test
    public void testAnUnreturnedClaimHoldsForTheBudgetNotForTheReadsShare() throws Exception
    {
        FakeRunner sliver = new FakeRunner(BoundedJob.Outcome.TIMED_OUT, false);
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(sliver, "sliver", "read", //$NON-NLS-1$ //$NON-NLS-2$
            1L, () -> AdoptedModuleState.LineOnDisk.PRESENT));
        assertEquals(Collections.singletonList(1L), sliver.timeouts);
        // The hold itself, read off the slot: three times the step's whole budget. A claim sized on any shorter basis -
        // the read's share, the poll's longest pause - fails here, not only one shorter than the sleep below.
        long hold = 3 * AdoptedModuleState.EXPORT_DEADLINE_MS;
        long left = BuildUtils.stateLineReadHoldLeftMs("sliver"); //$NON-NLS-1$
        assertTrue("the claim holds for three times the step's budget: " + left + "ms of " + hold + "ms left", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            left > hold - 10_000L && left <= hold);

        Thread.sleep(25); // far past three times the read's own 1ms

        FakeRunner next = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
        assertEquals("the claim still holds", AdoptedModuleState.LineOnDisk.UNREADABLE, //$NON-NLS-1$
            AdoptedModuleState.readBounded(next, "sliver", "read", 1_000L, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and no job was started", next.timeouts.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A refused read says why in EDT's log (#743 review): while an earlier read's claim holds, every read of the
     * project is refused - the poll asks about once a second - so the refusal is logged at INFO once per claim, not
     * once per refusal. A read that takes a claim of its own logs nothing, and a refusal behind that later claim is
     * logged again.
     */
    @Test
    public void testARefusedReadIsLoggedOncePerClaim()
    {
        String project = "RefusalLoggedProject"; //$NON-NLS-1$ (no other test reads this project)
        Bundle bundle = FrameworkUtil.getBundle(AdoptedModuleState.class);
        assertNotNull("the log can only be observed from inside OSGi; without the bundle a count of its entries " //$NON-NLS-1$
            + "would see nothing at all", bundle); //$NON-NLS-1$
        ILog log = Platform.getLog(bundle);
        List<IStatus> recorded = Collections.synchronizedList(new ArrayList<>());
        ILogListener listener = (status, plugin) -> recorded.add(status);
        log.addLogListener(listener);
        try
        {
            AtomicBoolean earlier = BuildUtils.beginStateLineRead(project, 60_000L);
            assertNotNull("an earlier read of the project is out", earlier); //$NON-NLS-1$
            for (int i = 0; i < 3; i++)
            {
                FakeRunner refused = new FakeRunner(BoundedJob.Outcome.COMPLETED, true);
                assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(refused, project,
                    "read", 1_000L, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$
                assertTrue("a refused read starts no job", refused.timeouts.isEmpty()); //$NON-NLS-1$
            }
            List<IStatus> said = refusalsLoggedFor(recorded, project);
            assertEquals("three refusals behind one claim are logged once: " + said, 1, said.size()); //$NON-NLS-1$
            assertEquals("at INFO, as BuildUtils' refused waits are", IStatus.INFO, said.get(0).getSeverity()); //$NON-NLS-1$

            earlier.set(true); // the earlier read returned
            assertEquals(AdoptedModuleState.LineOnDisk.PRESENT, readThrough(new FakeRunner(BoundedJob.Outcome.COMPLETED,
                true), project, () -> AdoptedModuleState.LineOnDisk.PRESENT));
            assertEquals("a read that took a claim logs no refusal", 1, refusalsLoggedFor(recorded, project).size()); //$NON-NLS-1$

            AtomicBoolean later = BuildUtils.beginStateLineRead(project, 60_000L);
            assertNotNull("a later read of the project is out", later); //$NON-NLS-1$
            assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(
                new FakeRunner(BoundedJob.Outcome.COMPLETED, true), project, "read", 1_000L, //$NON-NLS-1$
                () -> AdoptedModuleState.LineOnDisk.PRESENT));
            assertEquals("a refusal behind the later claim is logged again", 2, //$NON-NLS-1$
                refusalsLoggedFor(recorded, project).size());
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }

    /**
     * A read whose job ends WITHOUT running it holds nothing (#743 review, round 9): EDT sends the job's terminal
     * notification also when the job is cancelled before it starts - by anything, not only by our deadline - and that
     * notification releases the claim, whatever the run answered: NOT_RUN, INTERRUPTED (our cancel ended a job that
     * was still waiting) or TIMED_OUT. Kept until it lapses, such a claim would refuse every read of the project for
     * three minutes on account of a read that no longer exists. A job that has not ended - one a suspended job manager
     * still holds - keeps the claim until its notification comes.
     */
    @Test
    public void testTheTerminalNotificationOfAJobThatEndedWithoutRunningReleasesItsClaim()
    {
        for (BoundedJob.Outcome outcome : new BoundedJob.Outcome[] { BoundedJob.Outcome.NOT_RUN,
            BoundedJob.Outcome.INTERRUPTED, BoundedJob.Outcome.TIMED_OUT })
        {
            String project = "ended-" + outcome; //$NON-NLS-1$
            FakeRunner ended = new FakeRunner(outcome, false);
            ended.endsWithoutRunning = true;
            ended.interruptsTheCaller = outcome == BoundedJob.Outcome.INTERRUPTED;
            try
            {
                assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                    readThrough(ended, project, () -> AdoptedModuleState.LineOnDisk.PRESENT));
            }
            finally
            {
                Thread.interrupted();
            }
            assertNotNull(outcome + ": a job that ended without running holds nothing", //$NON-NLS-1$
                BuildUtils.beginStateLineRead(project, 1_000L));
        }

        FakeRunner held = new FakeRunner(BoundedJob.Outcome.NOT_RUN, false); // a suspended job manager still holds it
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
            readThrough(held, "still-held", () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$
        assertNull("a job that may still run keeps the claim", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("still-held", 1_000L)); //$NON-NLS-1$
        held.keptDone.run(); // cancelled later, never having run
        assertNotNull("released when that job ends, also without having run", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("still-held", 1_000L)); //$NON-NLS-1$
    }

    /**
     * The review's case on EDT's job manager (#743 review, round 9): a read whose job is cancelled before it enters the
     * work releases its project's claim at once - EDT ends a waiting job it cancels and notifies done inside the
     * cancel - so the next read of the project may start; BoundedJob reports NOT_RUN for it. The cancel comes from the
     * scheduler, before the caller joins the job, so the order is fixed and nothing is left to timing.
     */
    @Test
    public void testAReadWhoseJobIsCancelledBeforeItRunsReleasesItsClaimAtOnce()
    {
        AtomicBoolean ran = new AtomicBoolean();
        AdoptedModuleState.BoundedRunner cancelledBeforeItRuns = (name, timeout, work, onDone) -> BoundedJob.run(name,
            timeout, work, onDone, job -> {
                job.schedule(HUNG_READ_CEILING_MS); // waiting to run...
                job.cancel(); // ...and cancelled before it does
            });
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(cancelledBeforeItRuns,
            "CancelledProject", "read", PLATFORM_READ_MS, () -> { //$NON-NLS-1$ //$NON-NLS-2$
                ran.set(true);
                return AdoptedModuleState.LineOnDisk.PRESENT;
            }));
        assertFalse("the read never ran", ran.get()); //$NON-NLS-1$
        assertEquals("its claim was released at once: a read that will never run holds nothing", 0L, //$NON-NLS-1$
            BuildUtils.stateLineReadHoldLeftMs("CancelledProject")); //$NON-NLS-1$
        assertNotNull("the next read of the project may start", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("CancelledProject", 60_000L)); //$NON-NLS-1$
    }

    /**
     * A read whose job has not run yet keeps its project's claim until that job ends (#743 review, round 9): BoundedJob
     * reports NOT_RUN also for a job a suspended job manager still holds - one that runs when the manager resumes,
     * unless something cancels it first - so NOT_RUN alone releases nothing. Here the scheduler hands the job to EDT's
     * job manager only after the caller gave up on it; once the job has run, its end releases the claim.
     */
    @Test
    public void testAReadWhoseJobHasNotRunYetKeepsItsClaimUntilTheJobRunsAndEnds() throws Exception
    {
        AtomicReference<Job> notYetRun = new AtomicReference<>();
        AdoptedModuleState.BoundedRunner heldBack = (name, timeout, work, onDone) -> BoundedJob.run(name, timeout, work,
            onDone, notYetRun::set);
        CountDownLatch read = new CountDownLatch(1);
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(heldBack, "HeldProject", //$NON-NLS-1$
            "read", PLATFORM_READ_MS, () -> { //$NON-NLS-1$
                read.countDown();
                return AdoptedModuleState.LineOnDisk.PRESENT;
            }));
        assertNotNull("the job was handed over", notYetRun.get()); //$NON-NLS-1$
        assertNull("a read whose job may still run keeps its project's claim", //$NON-NLS-1$
            BuildUtils.beginStateLineRead("HeldProject", 60_000L)); //$NON-NLS-1$

        McpJobs.schedule(notYetRun.get()); // the job manager runs it after all
        assertTrue("the job that was held back ran the read", //$NON-NLS-1$
            read.await(HUNG_READ_CEILING_MS, TimeUnit.MILLISECONDS));
        assertTrue("once that job has run and ended, its claim is released", awaitReleased("HeldProject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The release on the job's end never blocks the thread that delivers it (#743 review, round 9): EDT hands a job's
     * done notification to its listeners on whichever thread it delivers the job's events on - e.g. the job's worker,
     * or a thread that cancels the job - possibly while the caller is still waiting for the job, and a listener that
     * blocks stalls EDT's job manager - so the release must finish on its own, at once. Here the notification comes on
     * another thread while the caller is still inside the run.
     */
    @Test
    public void testTheReleaseOnTheJobsEndNeverBlocksTheThreadThatDeliversIt()
    {
        AtomicBoolean deliveredInTime = new AtomicBoolean();
        AdoptedModuleState.BoundedRunner endsWhileTheCallerWaits = (name, timeout, work, onDone) -> {
            Thread delivery = new Thread(onDone, "done notification of " + name); //$NON-NLS-1$
            delivery.setDaemon(true);
            delivery.start();
            try
            {
                delivery.join(1_000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            deliveredInTime.set(!delivery.isAlive());
            return new BoundedJob.Result(BoundedJob.Outcome.NOT_RUN, 0L, null);
        };
        assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.readBounded(endsWhileTheCallerWaits,
            "NotificationProject", "read", 1_000L, () -> AdoptedModuleState.LineOnDisk.PRESENT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the release returned on the delivering thread within a second, while the caller still waited", //$NON-NLS-1$
            deliveredInTime.get());
        assertNotNull("and released the claim", BuildUtils.beginStateLineRead("NotificationProject", 60_000L)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The production read hands the job's end on (#743 review, round 9): a platform read whose job a third party
     * cancels at the start line - EDT then ends it without running it and notifies done - never touches the file and
     * releases its project's claim once EDT has ended the job, so the next read of the project starts a job and finds
     * the line. The cancel comes from an aboutToRun listener, which EDT allows ("listeners may have canceled or put the
     * job to sleep") and which does not block; it picks the read's job by its name alone.
     */
    @Test
    public void testThePlatformReadReleasesTheClaimOfAJobCancelledBeforeItRan() throws Exception
    {
        AdoptedModuleState.StateLine line =
            AdoptedModuleState.StateLine.of(drySignal, MdClassPackage.Literals.COMMON_MODULE__MODULE);
        String mdoPath = "src/CommonModules/CancelledRead" + System.nanoTime() + "/CancelledRead.mdo"; //$NON-NLS-1$ //$NON-NLS-2$
        String jobName = "Reading back " + mdoPath; //$NON-NLS-1$
        AtomicBoolean cancelled = new AtomicBoolean();
        IJobChangeListener canceller = new JobChangeAdapter()
        {
            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                if (jobName.equals(event.getJob().getName()) && cancelled.compareAndSet(false, true))
                {
                    event.getJob().cancel();
                }
            }
        };
        java.nio.file.Path dir = Files.createTempDirectory("adopted-module-state"); //$NON-NLS-1$
        try
        {
            java.nio.file.Path file = dir.resolve("CancelledRead.mdo"); //$NON-NLS-1$
            Files.write(file, drySignalMdo("    <module>Extended</module>\n")); //$NON-NLS-1$
            IProject onDisk = projectWithFileAt("CancelledReadProject", mdoPath, file); //$NON-NLS-1$
            Job.getJobManager().addJobChangeListener(canceller);
            try
            {
                assertEquals(AdoptedModuleState.LineOnDisk.UNREADABLE,
                    AdoptedModuleState.PLATFORM.readStateLine(onDisk, mdoPath, line, PLATFORM_READ_MS));
            }
            finally
            {
                Job.getJobManager().removeJobChangeListener(canceller);
            }
            assertTrue("the read's job was cancelled at the start line", cancelled.get()); //$NON-NLS-1$
            verify(onDisk, never()).getFile(any(org.eclipse.core.runtime.IPath.class));
            assertTrue("a read whose job ended without running releases its project's claim", //$NON-NLS-1$
                awaitReleased("CancelledReadProject")); //$NON-NLS-1$
            assertEquals("so the next read of the project starts a job and finds the line", //$NON-NLS-1$
                AdoptedModuleState.LineOnDisk.PRESENT,
                AdoptedModuleState.PLATFORM.readStateLine(onDisk, mdoPath, line, PLATFORM_READ_MS));
        }
        finally
        {
            Files.deleteIfExists(dir.resolve("CancelledRead.mdo")); //$NON-NLS-1$
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Within one step (#743 review, round 9): the poll's first read goes to a job that is cancelled before it runs, on
     * EDT's job manager. That job's end releases the claim, so the poll's next read starts a job of its own and
     * confirms the line. Were the claim kept until it lapses, every later read of the project would be refused, and
     * the write would fail as "could not be read back" with a .mdo that carries the line.
     */
    @Test
    public void testAReadWhoseJobEndedWithoutRunningLeavesThePollsNextReadFreeToConfirm()
    {
        reports(MdPropertyState.NONE);
        AtomicInteger jobs = new AtomicInteger();
        AtomicInteger readsRun = new AtomicInteger();
        AdoptedModuleState.BoundedRunner firstJobCancelled = (name, timeout, work, onDone) -> BoundedJob.run(name,
            timeout, work, onDone, jobs.getAndIncrement() > 0 ? McpJobs::schedule : job -> {
                job.schedule(HUNG_READ_CEILING_MS);
                job.cancel();
            });
        env.readThrough = (target, mdoPath, line, timeoutMs) -> AdoptedModuleState.readBounded(firstJobCancelled,
            target.getName(), "Reading back " + mdoPath, timeoutMs, () -> { //$NON-NLS-1$
                readsRun.incrementAndGet();
                return AdoptedModuleState.LineOnDisk.PRESENT;
            });
        Result result = run(DRY_SIGNAL);
        assertEquals(String.valueOf(result.failureMessage()), Outcome.SWITCHED, result.outcome());
        assertEquals("two reads: the one whose job was cancelled, then the poll's next, which ran", 2, //$NON-NLS-1$
            env.mdoReads().size());
        assertEquals("only the second read ran", 1, readsRun.get()); //$NON-NLS-1$
    }

    /** Waits, within the hung read's ceiling, until {@code project} holds no state-line read claim. */
    private static boolean awaitReleased(String project) throws InterruptedException
    {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HUNG_READ_CEILING_MS);
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

    /** The recorded log entries that say a read of {@code project}'s files was not started. */
    private static List<IStatus> refusalsLoggedFor(List<IStatus> recorded, String project)
    {
        List<IStatus> ours = new ArrayList<>();
        synchronized (recorded)
        {
            for (IStatus status : recorded)
            {
                String message = status.getMessage();
                if (message != null && message.contains(project) && message.contains("has not returned yet")) //$NON-NLS-1$
                {
                    ours.add(status);
                }
            }
        }
        return ours;
    }

    /**
     * One bounded read through {@code runner}, for a project of its own, given 1000ms - and the runner was handed it: a
     * read refused for a claim held elsewhere is not the case the caller meant to test.
     */
    private static AdoptedModuleState.LineOnDisk readThrough(FakeRunner runner, String projectName,
        Supplier<AdoptedModuleState.LineOnDisk> read)
    {
        AdoptedModuleState.LineOnDisk seen = AdoptedModuleState.readBounded(runner, projectName, "read", 1_000L, read); //$NON-NLS-1$
        assertEquals("the read of " + projectName + " was handed to the runner", Collections.singletonList(1_000L), //$NON-NLS-1$ //$NON-NLS-2$
            runner.timeouts);
        return seen;
    }

    /** Each read of the .mdo started with time left, and was given exactly what was left of the budget then. */
    private void assertEachReadGotWhatWasLeftOf(long deadline)
    {
        assertEquals(env.readStarts.size(), env.readTimeouts.size());
        assertFalse("no read was made", env.readStarts.isEmpty()); //$NON-NLS-1$
        for (int i = 0; i < env.readStarts.size(); i++)
        {
            long left = deadline - env.readStarts.get(i);
            assertTrue("read " + i + " started with nothing left: " + env.readStarts, left > 0); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("read " + i + " is bounded by what was left", left, env.readTimeouts.get(i).longValue()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A bounded runner that answers a scripted outcome - running the read first, or not, as BoundedJob does when the
     * read finishes after the caller stopped waiting - and records the timeouts it is given. It hands the job's terminal
     * notification on as EDT sends it: once a job that ran the read has ended; at once for a job that ended without
     * running it ({@link #endsWithoutRunning}); and for a job that may still run, only when the test delivers it
     * ({@link #finishKept}, {@link #keptDone}).
     */
    private static final class FakeRunner implements AdoptedModuleState.BoundedRunner
    {
        private final BoundedJob.Outcome outcome;
        private final boolean runsTheRead;
        /** What scheduling throws, when set. */
        RuntimeException scheduleFailure;
        /** Whether the caller's thread is left interrupted, as BoundedJob leaves it for INTERRUPTED. */
        boolean interruptsTheCaller;
        /**
         * The failure the result carries for an outcome other than COMPLETED, as BoundedJob's INTERRUPTED result carries
         * its InterruptedException; none when not set. A COMPLETED result carries what the read threw.
         */
        Throwable failure;
        /**
         * Whether a job that does not run the read has ended all the same - cancelled before it started, by anything,
         * or removed - and so sends its terminal notification at once. When not set, such a job may still run - a
         * suspended job manager still holds it - and sends none until the test delivers it.
         */
        boolean endsWithoutRunning;
        /**
         * Whether the terminal notification of a job that ran the read is still on its way when the caller has its
         * answer, and is left for the test to deliver ({@link #keptDone}).
         */
        boolean notificationLate;
        final List<Long> timeouts = new ArrayList<>();
        /**
         * The read it was handed: one it did not run is kept, as BoundedJob's job keeps a read the deadline cut off,
         * and a test runs it later - the file system answering after the caller stopped waiting.
         */
        BoundedJob.IBoundedWork kept;
        /** The job's terminal notification it was handed: one it did not deliver, a test delivers. */
        Runnable keptDone;

        FakeRunner(BoundedJob.Outcome outcome, boolean runsTheRead)
        {
            this.outcome = outcome;
            this.runsTheRead = runsTheRead;
        }

        @Override
        public BoundedJob.Result run(String jobName, long timeoutMs, BoundedJob.IBoundedWork work, Runnable onDone)
        {
            timeouts.add(timeoutMs);
            kept = work;
            keptDone = onDone;
            if (scheduleFailure != null)
            {
                throw scheduleFailure;
            }
            Throwable thrown = null;
            if (runsTheRead)
            {
                try
                {
                    work.run(new NullProgressMonitor());
                }
                catch (Exception e) // NOSONAR captured, as BoundedJob captures it
                {
                    thrown = e;
                }
                if (!notificationLate)
                {
                    onDone.run(); // the job ended
                }
            }
            else if (endsWithoutRunning)
            {
                onDone.run(); // the job ended without running the read
            }
            if (interruptsTheCaller)
            {
                Thread.currentThread().interrupt();
            }
            return new BoundedJob.Result(outcome, timeoutMs,
                outcome == BoundedJob.Outcome.COMPLETED ? thrown : failure);
        }

        /** The file system answers long after the caller stopped waiting: the kept read runs, and its job ends. */
        void finishKept() throws Exception
        {
            kept.run(new NullProgressMonitor());
            keptDone.run();
        }
    }

    /**
     * A .mdo that cannot be read back (or parsed - EDT may be writing it) confirms nothing, and the message says
     * it could not be READ, not that the line is missing. A later read that finds the line confirms the switch.
     */
    @Test
    public void testAnMdoThatCannotBeReadBackIsNotConfirmedAndSaysSo()
    {
        reports(MdPropertyState.NONE);
        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        String message = result.failureMessage();
        assertTrue(message, message.contains("was switched from None to Extended in EDT's model, but the export of its " //$NON-NLS-1$
            + ".mdo was requested and src/CommonModules/DrySignal/DrySignal.mdo could not be read back to confirm " //$NON-NLS-1$
            + "<module>Extended</module> within 60s. Nothing was rolled back.")); //$NON-NLS-1$
        assertFalse(message, message.contains("did not carry")); //$NON-NLS-1$
        assertFalse("one 'but' per sentence: " + message, message.contains("was requested, but")); //$NON-NLS-1$ //$NON-NLS-2$

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.disk.addAll(Arrays.asList(AdoptedModuleState.LineOnDisk.UNREADABLE, AdoptedModuleState.LineOnDisk.PRESENT));
        assertEquals(Outcome.SWITCHED, run(DRY_SIGNAL).outcome());
    }

    /**
     * ONE evidence, ONE verdict: a module that already was Extended - EDT's own listener flips the model in reaction
     * to the same .bsl write, so this is a race the caller cannot see - fails the write like a switch when its .mdo,
     * read back to the end of the budget, never carries the line, whether the queue drained or could not be
     * observed. The error says the text is written, that the state is Extended in EDT's model, what the file showed
     * and how to rerun the step; nothing says the .mdo was exported. Once the line is there, the key alone says it.
     */
    @Test
    public void testAnAlreadyExtendedModuleWhoseLineNeverReachesTheMdoFailsTheWriteLikeASwitch()
    {
        for (BuildUtils.DiskExportState queue : new BuildUtils.DiskExportState[] { BuildUtils.DiskExportState.DRAINED,
            BuildUtils.DiskExportState.UNOBSERVABLE })
        {
            freshPlatform();
            reports(MdPropertyState.EXTENDED);
            env.exportState = queue;
            env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
            Result result = run(DRY_SIGNAL);
            assertEquals(queue.name(), Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
            assertTrue(queue.name(), result.failed());
            assertNull("an error carries no key", result.frontMatterValue()); //$NON-NLS-1$
            assertNull("an error carries no success paragraph", result.bodySentence()); //$NON-NLS-1$
            verify(access, never()).setPropertyState(any(), any(), any());
            String message = result.failureMessage();
            assertTrue(message, message.startsWith("The module text was written to src/CommonModules/DrySignal/Module.bsl, " //$NON-NLS-1$
                + "and the module state of 'CommonModule.DrySignal' is already Extended in EDT's model, but the export " //$NON-NLS-1$
                + "of its .mdo was requested again to match the model and src/CommonModules/DrySignal/DrySignal.mdo, " //$NON-NLS-1$
                + "read back, did not carry <module>Extended</module> within 60s; if EDT failed to write the file " //$NON-NLS-1$
                + "(a locked or read-only .mdo, a full disk), its log says so. Nothing was rolled back. The write " //$NON-NLS-1$
                + "stands: do not re-send the original edit. And do not commit the working tree in this state")); //$NON-NLS-1$
            assertTrue(message, message.contains("mode=append and an empty source")); //$NON-NLS-1$
            assertTrue(message, message.endsWith(" - that exports the .mdo again.")); //$NON-NLS-1$
            assertFalse(message, message.contains("switched from None")); //$NON-NLS-1$
            assertFalse(message, message.contains("was exported")); //$NON-NLS-1$
            assertNoRemovedClaim("the unconfirmed export of an already-Extended module", message); //$NON-NLS-1$
            // The same budget as after a switch: the file was read until it ran out.
            assertEquals(60_000L, env.clock);
        }

        freshPlatform();
        reports(MdPropertyState.EXTENDED);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        Result confirmed = run(DRY_SIGNAL);
        assertEquals(Outcome.ALREADY_EXTENDED, confirmed.outcome());
        assertFalse(confirmed.failed());
        assertNull("the line is on disk: the key alone says it", confirmed.bodySentence()); //$NON-NLS-1$
    }

    /**
     * A .mdo that cannot be read back confirms nothing for a module that already was Extended either: the error says
     * the file could not be READ - not that the line is missing, and never that it was exported.
     */
    @Test
    public void testAnAlreadyExtendedModuleWhoseMdoCannotBeReadBackFailsAndSaysSo()
    {
        reports(MdPropertyState.EXTENDED);
        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertNull(result.bodySentence());
        String message = result.failureMessage();
        assertTrue(message, message.contains("and the module state of 'CommonModule.DrySignal' is already Extended in " //$NON-NLS-1$
            + "EDT's model, but the export of its .mdo was requested again to match the model and " //$NON-NLS-1$
            + "src/CommonModules/DrySignal/DrySignal.mdo could not be read back to confirm <module>Extended</module> " //$NON-NLS-1$
            + "within 60s. Nothing was rolled back. The write stands: do not re-send the original edit.")); //$NON-NLS-1$
        assertFalse(message, message.contains("did not carry")); //$NON-NLS-1$
        assertFalse(message, message.contains("was exported")); //$NON-NLS-1$
    }

    /**
     * ONE evidence, ONE verdict - the race's three shapes. EDT's own listener flips the model in reaction to the same
     * .bsl write, so the step may find None and switch it, find Extended at once, or find None in its read and
     * Extended in its write (the listener won in between). The caller cannot tell them apart, so the same .mdo
     * evidence gives the same answer in each: a success when the file, read back, carries the line - its export
     * refused or not; a failure when it does not, cannot be read back, or its export stays pending or is refused with
     * no line in the file - and, on the ordinary-application module, a success with a note every time the line is not
     * seen. A caller that checks only the error flag never takes a failed export for a done one, whichever way the race
     * went.
     */
    @Test
    public void testTheSameMdoEvidenceGivesTheSameVerdictWhetherTheStateWasSwitchedOrFoundExtended()
    {
        for (String path : Arrays.asList(DRY_SIGNAL, "src/Configuration/SessionModule.bsl", ORDINARY)) //$NON-NLS-1$
        {
            for (String evidence : Arrays.asList("written", "missing", "unreadable", "pending", "notScheduled", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "notScheduledButOnDisk")) //$NON-NLS-1$
            {
                boolean unconfirmed = !"written".equals(evidence) && !"notScheduledButOnDisk".equals(evidence); //$NON-NLS-1$ //$NON-NLS-2$
                boolean expectFailure = unconfirmed && !ORDINARY.equals(path);
                for (String shape : Arrays.asList("switched", "foundExtended", "listenerWonBeforeTheWrite")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {
                    freshPlatform();
                    if ("switched".equals(shape)) //$NON-NLS-1$
                    {
                        reports(MdPropertyState.NONE);
                    }
                    else if ("foundExtended".equals(shape)) //$NON-NLS-1$
                    {
                        reports(MdPropertyState.EXTENDED);
                    }
                    else
                    {
                        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class),
                            eq(MdPropertyState.NONE))).thenReturn(Boolean.TRUE, Boolean.FALSE);
                        reports(MdPropertyState.EXTENDED);
                    }
                    switch (evidence)
                    {
                    case "missing": //$NON-NLS-1$
                        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
                        break;
                    case "unreadable": //$NON-NLS-1$
                        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
                        break;
                    case "pending": //$NON-NLS-1$
                        env.exportState = BuildUtils.DiskExportState.PENDING;
                        break;
                    case "notScheduled": //$NON-NLS-1$
                        env.exportAccepted = false;
                        break;
                    case "notScheduledButOnDisk": //$NON-NLS-1$
                        env.exportAccepted = false;
                        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
                        break;
                    default:
                        break;
                    }
                    String what = path + " / " + evidence + " / " + shape; //$NON-NLS-1$ //$NON-NLS-2$
                    Result result = run(path);
                    assertEquals(what, expectFailure, result.failed());
                    Outcome reached = "switched".equals(shape) ? Outcome.SWITCHED : Outcome.ALREADY_EXTENDED; //$NON-NLS-1$
                    assertEquals(what, expectFailure ? Outcome.EXPORT_NOT_CONFIRMED : reached, result.outcome());
                    verify(access, times("switched".equals(shape) ? 1 : 0)).setPropertyState(any(), any(), any()); //$NON-NLS-1$
                    if (expectFailure)
                    {
                        assertTrue(what, result.failureMessage().startsWith("The module text was written to " + path)); //$NON-NLS-1$
                        assertTrue(what, result.failureMessage().contains(" in EDT's model, but ")); //$NON-NLS-1$
                    }
                    else if (unconfirmed)
                    {
                        assertTrue(what, result.bodySentence().contains(ORDINARY_FACT));
                    }
                    else
                    {
                        // Confirmed by the file - its export scheduled or not: no error, and no note to excuse one.
                        assertNull(what, result.failureMessage());
                        String body = result.bodySentence();
                        assertTrue(what + ": " + body, body == null || !body.contains(ORDINARY_FACT)); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    /** The pauses the step made while it read the .mdo back, in order. */
    private List<String> pauses()
    {
        List<String> out = new ArrayList<>();
        for (String call : env.calls)
        {
            if (call.startsWith("pause:")) //$NON-NLS-1$
            {
                out.add(call);
            }
        }
        return out;
    }

    /**
     * A queue still busy at the end of the budget, with no line in the .mdo: not confirmed - the same failure for a
     * module that already was Extended as after a switch. Round 1 kept it a success for fear that a busy queue would
     * fail such writes; since the FILE decides, a line that lands while the queue is still busy confirms the export
     * (testTheQueueAndTheFileShareOneSixtySecondBudget), so only a .mdo without the line fails.
     */
    @Test
    public void testAnAlreadyExtendedModuleWhoseExportStaysPendingIsNotConfirmed()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertNull(result.frontMatterValue());
        assertNull(result.bodySentence());
        String message = result.failureMessage();
        assertTrue(message, message.contains("and the module state of 'CommonModule.DrySignal' is already Extended in " //$NON-NLS-1$
            + "EDT's model, but the export of its .mdo, requested again to match the model, was not confirmed within " //$NON-NLS-1$
            + "60s, so src/CommonModules/DrySignal/DrySignal.mdo may still lack <module>Extended</module>. Nothing was " //$NON-NLS-1$
            + "rolled back.")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not re-send the original edit")); //$NON-NLS-1$
        assertTrue(message, message.contains("mode=append and an empty source")); //$NON-NLS-1$
    }

    /**
     * A refused export request queued nothing, and the .mdo, read once, does not carry the line: the same failure as
     * after a switch.
     */
    @Test
    public void testAnAlreadyExtendedModuleWhoseExportCannotBeScheduledIsNotConfirmed()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportAccepted = false;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertNull(result.bodySentence());
        assertEquals("not awaited, read once", //$NON-NLS-1$
            Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        String message = result.failureMessage();
        assertTrue(message, message.contains("and the module state of 'CommonModule.DrySignal' is already Extended in " //$NON-NLS-1$
            + "EDT's model, but the export of its .mdo could not be scheduled, so " //$NON-NLS-1$
            + "src/CommonModules/DrySignal/DrySignal.mdo may still lack <module>Extended</module>. Nothing was rolled " //$NON-NLS-1$
            + "back.")); //$NON-NLS-1$
        assertFalse(message, message.contains("read back,")); //$NON-NLS-1$
    }

    /**
     * The file is the evidence, a refused export request included (#743 review): nothing was queued that could still
     * change the .mdo, so it is read ONCE, at once - never polled, never waited for - and a module that already was
     * Extended, whose .mdo already carries the line (recorded in EDT's UI, or by an earlier write), is the same success
     * as a confirmed export: the key alone, no paragraph, no error.
     */
    @Test
    public void testARefusedExportOfAnAlreadyExtendedModuleWhoseMdoCarriesTheLineIsConfirmedByOneRead()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportAccepted = false;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.ALREADY_EXTENDED, result.outcome());
        assertFalse(result.failed());
        assertNull(result.failureMessage());
        assertEquals("alreadyExtended", result.frontMatterValue()); //$NON-NLS-1$
        assertNull("confirmed by the file: the key alone says it", result.bodySentence()); //$NON-NLS-1$
        verify(access, never()).setPropertyState(any(), any(), any());
        assertEquals("one read, no wait and no pause", //$NON-NLS-1$
            Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals("no time spent", 0L, env.clock); //$NON-NLS-1$
    }

    /**
     * The same after a switch: a refused export whose .mdo, read once, carries the line answers exactly what a
     * confirmed export answers - the same key and the same paragraph, word for word.
     */
    @Test
    public void testARefusedExportAfterASwitchWhoseMdoCarriesTheLineAnswersLikeAConfirmedExport()
    {
        reports(MdPropertyState.NONE);
        Result confirmed = run(DRY_SIGNAL); // the healthy export: accepted, drained, the line on disk
        assertEquals(Outcome.SWITCHED, confirmed.outcome());

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        assertFalse(result.failed());
        assertNull(result.failureMessage());
        assertEquals("switchedToExtended", result.frontMatterValue()); //$NON-NLS-1$
        assertNotNull(result.bodySentence());
        assertEquals("the success text is the confirmed export's", confirmed.bodySentence(), result.bodySentence()); //$NON-NLS-1$
        verify(access, times(1)).setPropertyState(same(drySignal), same(MdClassPackage.Literals.COMMON_MODULE__MODULE),
            same(MdPropertyState.EXTENDED));
        assertEquals("one read, no wait and no pause", //$NON-NLS-1$
            Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals("no time spent", 0L, env.clock); //$NON-NLS-1$
    }

    /**
     * A refused export whose .mdo, read once, is not seen carrying the line - absent, unreadable, or a read that
     * throws - stays NOT confirmed, with the words a refused export has always had ("could not be scheduled"), after a
     * switch and for a module found already Extended alike: one read, never polled, never waited for, and nothing
     * escapes.
     */
    @Test
    public void testARefusedExportWhoseMdoIsNotSeenCarryingTheLineStaysNotConfirmedAfterOneRead()
    {
        for (MdPropertyState before : new MdPropertyState[] { MdPropertyState.NONE, MdPropertyState.EXTENDED })
        {
            for (String disk : Arrays.asList("absent", "unreadable", "throws")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                freshPlatform();
                reports(before);
                env.exportAccepted = false;
                if ("throws".equals(disk)) //$NON-NLS-1$
                {
                    env.readFailure = new IllegalStateException("read refused"); //$NON-NLS-1$
                }
                else
                {
                    env.disk.add("absent".equals(disk) ? AdoptedModuleState.LineOnDisk.ABSENT //$NON-NLS-1$
                        : AdoptedModuleState.LineOnDisk.UNREADABLE);
                }
                String what = before.getName() + " / " + disk; //$NON-NLS-1$
                Result result = run(DRY_SIGNAL);
                assertEquals(what, Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
                assertTrue(what, result.failed());
                assertNull(what, result.frontMatterValue());
                assertNull(what, result.bodySentence());
                String message = result.failureMessage();
                assertTrue(what + ": " + message, message.contains(" in EDT's model, but the export of its .mdo could " //$NON-NLS-1$ //$NON-NLS-2$
                    + "not be scheduled, so src/CommonModules/DrySignal/DrySignal.mdo may still lack " //$NON-NLS-1$
                    + "<module>Extended</module>. Nothing was rolled back.")); //$NON-NLS-1$
                assertFalse(what + ": " + message, message.contains("read back")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(what, Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), //$NON-NLS-1$
                    env.exportAndDiskCalls());
                assertEquals(what, 0L, env.clock);
            }
        }
    }

    /**
     * An export that throws is no export, but the file still decides it: the .mdo is read once, and one that already
     * carries the line confirms the switch as a written export does; one without it leaves it not confirmed.
     */
    @Test
    public void testAnExportThatThrowsIsStillDecidedByOneReadOfTheMdo()
    {
        reports(MdPropertyState.NONE);
        env.exportFailure = new IllegalStateException("save task refused"); //$NON-NLS-1$
        env.disk.add(AdoptedModuleState.LineOnDisk.PRESENT);
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.SWITCHED, result.outcome());
        assertFalse(result.failed());
        assertTrue(result.bodySentence(), result.bodySentence().contains("the .mdo was exported")); //$NON-NLS-1$
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
        assertEquals("no time spent", 0L, env.clock); //$NON-NLS-1$

        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportFailure = new IllegalStateException("save task refused"); //$NON-NLS-1$
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        Result missing = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, missing.outcome());
        assertTrue(missing.failureMessage(), missing.failureMessage().contains("could not be scheduled")); //$NON-NLS-1$
        assertEquals(Arrays.asList("forceExport:CommonModule.DrySignal", DRY_SIGNAL_READ), env.exportAndDiskCalls()); //$NON-NLS-1$
    }

    /**
     * An unobservable queue is no news either way: what the .mdo holds decides. A module that already was Extended,
     * whose line never appears, is not confirmed - never the round-1 "completion could not be observed".
     */
    @Test
    public void testAnAlreadyExtendedModuleWhoseQueueIsUnobservableIsDecidedByTheMdo()
    {
        reports(MdPropertyState.EXTENDED);
        env.exportState = BuildUtils.DiskExportState.UNOBSERVABLE;
        Result result = run(DRY_SIGNAL);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, result.outcome());
        assertTrue(result.failed());
        assertTrue(result.failureMessage(), result.failureMessage().contains(
            "did not carry <module>Extended</module> within 60s")); //$NON-NLS-1$
        assertFalse(result.failureMessage(), result.failureMessage().contains("could not be observed")); //$NON-NLS-1$
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

    /**
     * An extension root without its block - one an earlier create_project made from a new configuration (#706) - named as
     * the ROOT; a success. The note says which roots carry the block now - EDT's wizard's and create_project's - that a
     * root an earlier create_project made has none, and the two ways out: recreating the extension, or setting the state
     * of the root's default language in EDT, which adds the root's block; then the step's own rerun (#747 review, round
     * 3). That property, not just any of the root's: a state set on 'Extended configuration object' gets a block of
     * another class, without module states, and a Checked default run mode gets no state while the block is missing.
     */
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
        assertTrue(note, note.contains("An extension root that EDT's own new-extension wizard creates carries that " //$NON-NLS-1$
            + "block, and so does one that create_project creates, as it adopts the base's root the way that wizard " //$NON-NLS-1$
            + "does; a root made by an earlier version of create_project - a new configuration, not the base's root " //$NON-NLS-1$
            + "adopted - has none.")); //$NON-NLS-1$
        assertTrue(note, note.contains("To record the state, recreate the extension with create_project and add its " //$NON-NLS-1$
            + "objects and code again, then run this step again: call write_module_source on the same module with " //$NON-NLS-1$
            + "mode=append and an empty source")); //$NON-NLS-1$
        assertFalse("no property state set in EDT is advised: on such a root a Checked default language is a " //$NON-NLS-1$
            + "critical error that stops EDT from updating an infobase: " + note, note.contains("default language")); //$NON-NLS-1$
        assertFalse("a state of just any of the root's properties does not give it the block this step needs: " //$NON-NLS-1$
            + note, note.contains("one of the root's properties")); //$NON-NLS-1$
        assertFalse("the old note said only the wizard's roots carry the block: " + note, //$NON-NLS-1$
            note.contains("carries that block.")); //$NON-NLS-1$
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

        // A refused export whose .mdo, read once, does not carry the line: a fresh platform, so no earlier export of
        // this test has put the line there.
        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportAccepted = false;
        Result refused = run(ORDINARY);
        assertEquals(Outcome.SWITCHED, refused.outcome());
        assertFalse(refused.failed());
        assertTrue(refused.bodySentence(), refused.bodySentence().contains("could not be scheduled")); //$NON-NLS-1$

        // A drained queue whose .mdo never carries the line: the same note - never "the .mdo was exported".
        freshPlatform();
        reports(MdPropertyState.NONE);
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        Result missing = run(ORDINARY);
        assertEquals(Outcome.SWITCHED, missing.outcome());
        assertFalse(missing.failed());
        String missingBody = missing.bodySentence();
        assertTrue(missingBody, missingBody.contains("(<ordinaryApplicationModule>Extended</ordinaryApplicationModule> in " //$NON-NLS-1$
            + "src/Configuration/Configuration.mdo), but the export of the .mdo was requested and the .mdo, read back, " //$NON-NLS-1$
            + "did not carry that line within 60s.")); //$NON-NLS-1$
        assertFalse(missingBody, missingBody.contains("was exported")); //$NON-NLS-1$
        assertTrue(missingBody, missingBody.contains(ORDINARY_FACT));
        assertNoRemovedClaim("the ordinary module's not-on-disk note", missingBody); //$NON-NLS-1$

        // The contrast: the same unconfirmed export of the SESSION module refuses the write.
        freshPlatform();
        reports(MdPropertyState.NONE);
        env.exportState = BuildUtils.DiskExportState.PENDING;
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, run("src/Configuration/SessionModule.bsl").outcome()); //$NON-NLS-1$
        freshPlatform();
        reports(MdPropertyState.NONE);
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, run("src/Configuration/SessionModule.bsl").outcome()); //$NON-NLS-1$
    }

    /**
     * An ordinary-application module that already was Extended, whose export is not confirmed, is a note on a success
     * too - the paragraph says what the .mdo showed, teaches the rerun and says why the write does not fail - while
     * the SESSION module of the same root fails the write on the same evidence.
     */
    @Test
    public void testAnUnconfirmedExportOfAnAlreadyExtendedOrdinaryApplicationModuleIsANoteNotAFailure()
    {
        reports(MdPropertyState.EXTENDED);
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        Result missing = run(ORDINARY);
        assertEquals(Outcome.ALREADY_EXTENDED, missing.outcome());
        assertFalse(missing.failed());
        assertNull(missing.failureMessage());
        assertEquals("alreadyExtended", missing.frontMatterValue()); //$NON-NLS-1$
        String note = missing.bodySentence();
        assertNotNull(note);
        assertTrue(note, note.startsWith("The module state of the extension's root object (Configuration) is already " //$NON-NLS-1$
            + "Extended (<ordinaryApplicationModule>Extended</ordinaryApplicationModule> in " //$NON-NLS-1$
            + "src/Configuration/Configuration.mdo), but the export of the .mdo was requested again to match the model " //$NON-NLS-1$
            + "and the .mdo, read back, did not carry that line within 60s. Nothing was rolled back; check the line " //$NON-NLS-1$
            + "before committing")); //$NON-NLS-1$
        assertTrue(note, note.contains("mode=append and an empty source")); //$NON-NLS-1$
        assertTrue(note, note.endsWith(ORDINARY_FACT + ".")); //$NON-NLS-1$
        assertFalse(note, note.contains("was exported")); //$NON-NLS-1$
        assertNoRemovedClaim("the already-Extended ordinary module's not-on-disk note", note); //$NON-NLS-1$

        for (BuildUtils.DiskExportState queue : new BuildUtils.DiskExportState[] { BuildUtils.DiskExportState.PENDING,
            BuildUtils.DiskExportState.UNOBSERVABLE })
        {
            freshPlatform();
            reports(MdPropertyState.EXTENDED);
            env.exportState = queue;
            Result result = run(ORDINARY);
            assertEquals(queue.name(), Outcome.ALREADY_EXTENDED, result.outcome());
            assertFalse(queue.name(), result.failed());
            assertTrue(result.bodySentence(), result.bodySentence().contains(ORDINARY_FACT));
        }
        freshPlatform();
        reports(MdPropertyState.EXTENDED);
        env.exportAccepted = false;
        Result refused = run(ORDINARY);
        assertEquals(Outcome.ALREADY_EXTENDED, refused.outcome());
        assertFalse(refused.failed());
        assertTrue(refused.bodySentence(), refused.bodySentence().contains("the export of the .mdo could not be " //$NON-NLS-1$
            + "scheduled, so the .mdo on disk may still lack that line")); //$NON-NLS-1$
        assertTrue(refused.bodySentence(), refused.bodySentence().contains(ORDINARY_FACT));

        // The contrast: the same evidence on the SESSION module, already Extended, refuses the write.
        freshPlatform();
        reports(MdPropertyState.EXTENDED);
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        Result session = run("src/Configuration/SessionModule.bsl"); //$NON-NLS-1$
        assertEquals(Outcome.EXPORT_NOT_CONFIRMED, session.outcome());
        assertTrue(session.failed());
        assertTrue(session.failureMessage(), session.failureMessage().contains("the module state of the extension's " //$NON-NLS-1$
            + "root object (Configuration) is already Extended in EDT's model, but")); //$NON-NLS-1$
    }

    /**
     * An ordinary-application module whose .mdo cannot be read back: the note says the file could not be READ - never
     * that the line is missing, never that the .mdo was exported - after a switch and when the state already was
     * Extended alike, and why it does not fail the write.
     */
    @Test
    public void testAnUnreadableMdoOfTheOrdinaryApplicationModuleIsANoteThatSaysSo()
    {
        reports(MdPropertyState.NONE);
        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
        Result switched = run(ORDINARY);
        assertEquals(Outcome.SWITCHED, switched.outcome());
        assertFalse(switched.failed());
        assertNull(switched.failureMessage());
        assertEquals("switchedToExtended", switched.frontMatterValue()); //$NON-NLS-1$
        String body = switched.bodySentence();
        assertTrue(body, body.contains("its module state was switched from None to Extended " //$NON-NLS-1$
            + "(<ordinaryApplicationModule>Extended</ordinaryApplicationModule> in src/Configuration/Configuration.mdo), " //$NON-NLS-1$
            + "but the export of the .mdo was requested and the .mdo could not be read back to confirm that line within " //$NON-NLS-1$
            + "60s. ")); //$NON-NLS-1$
        assertTrue(body, body.endsWith(ORDINARY_FACT + ".")); //$NON-NLS-1$
        assertFalse(body, body.contains("did not carry")); //$NON-NLS-1$
        assertFalse(body, body.contains("was exported")); //$NON-NLS-1$
        assertNoRemovedClaim("the ordinary module's unreadable-mdo note", body); //$NON-NLS-1$

        freshPlatform();
        reports(MdPropertyState.EXTENDED);
        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
        Result already = run(ORDINARY);
        assertEquals(Outcome.ALREADY_EXTENDED, already.outcome());
        assertFalse(already.failed());
        assertNull(already.failureMessage());
        assertEquals("alreadyExtended", already.frontMatterValue()); //$NON-NLS-1$
        String note = already.bodySentence();
        assertNotNull(note);
        assertTrue(note, note.startsWith("The module state of the extension's root object (Configuration) is already " //$NON-NLS-1$
            + "Extended (<ordinaryApplicationModule>Extended</ordinaryApplicationModule> in " //$NON-NLS-1$
            + "src/Configuration/Configuration.mdo), but the export of the .mdo was requested again to match the model " //$NON-NLS-1$
            + "and the .mdo could not be read back to confirm that line within 60s. Nothing was rolled back;")); //$NON-NLS-1$
        assertTrue(note, note.endsWith(ORDINARY_FACT + ".")); //$NON-NLS-1$
        assertFalse(note, note.contains("did not carry")); //$NON-NLS-1$
        assertFalse(note, note.contains("was exported")); //$NON-NLS-1$
        assertNoRemovedClaim("the already-Extended ordinary module's unreadable-mdo note", note); //$NON-NLS-1$
    }

    /**
     * A refused export of the ordinary-application module is decided by one read of the .mdo too, and never fails the
     * write: a file that carries the line is the confirmed success - a switch says the .mdo was exported, a module
     * already Extended says nothing beyond the key, and neither needs the note - while one that does not (absent or
     * unreadable) is the note that the export could not be scheduled, never an error.
     */
    @Test
    public void testARefusedExportOfTheOrdinaryApplicationModuleIsDecidedByOneReadAndNeverFails()
    {
        for (MdPropertyState before : new MdPropertyState[] { MdPropertyState.NONE, MdPropertyState.EXTENDED })
        {
            for (AdoptedModuleState.LineOnDisk disk : AdoptedModuleState.LineOnDisk.values())
            {
                freshPlatform();
                reports(before);
                env.exportAccepted = false;
                env.disk.add(disk);
                String what = before.getName() + " / " + disk; //$NON-NLS-1$
                Result result = run(ORDINARY);
                assertEquals(what, MdPropertyState.NONE.equals(before) ? Outcome.SWITCHED : Outcome.ALREADY_EXTENDED,
                    result.outcome());
                assertFalse(what, result.failed());
                assertNull(what, result.failureMessage());
                assertEquals(what, Arrays.asList("forceExport:Configuration", //$NON-NLS-1$
                    "readMdo:src/Configuration/Configuration.mdo#extension/ordinaryApplicationModule"), //$NON-NLS-1$
                    env.exportAndDiskCalls());
                String body = result.bodySentence();
                if (disk == AdoptedModuleState.LineOnDisk.PRESENT && MdPropertyState.NONE.equals(before))
                {
                    assertTrue(what + ": " + body, body.contains(", and the .mdo was exported.")); //$NON-NLS-1$ //$NON-NLS-2$
                    assertFalse(what + ": " + body, body.contains(ORDINARY_FACT)); //$NON-NLS-1$
                }
                else if (disk == AdoptedModuleState.LineOnDisk.PRESENT)
                {
                    assertNull(what + ": the line is on disk, the key alone says it", body); //$NON-NLS-1$
                }
                else
                {
                    assertTrue(what + ": " + body, body.contains("could not be scheduled")); //$NON-NLS-1$ //$NON-NLS-2$
                    assertTrue(what + ": " + body, body.contains(ORDINARY_FACT)); //$NON-NLS-1$
                    assertFalse(what + ": " + body, body.contains("was exported")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
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
     * Only a failure or an unconfirmed export fails the write - after a switch and for a module that already was
     * Extended alike. A state left as it is and a missing extension block are successes: the text is written, and
     * the key says what was found.
     */
    @Test
    public void testOnlyAFailureOrAnUnconfirmedExportFailsTheWrite()
    {
        assertFalse(Result.notApplicable().failed());
        assertNull(Result.notApplicable().frontMatterValue());
        assertNull(Result.notApplicable().bodySentence());
        assertFalse(AdoptedModuleStateTestFixtures.switched().failed());
        assertFalse(AdoptedModuleStateTestFixtures.alreadyExtended(BuildUtils.DiskExportState.DRAINED).failed());
        assertFalse(AdoptedModuleStateTestFixtures.keptChecked().failed());
        assertFalse(AdoptedModuleStateTestFixtures.noExtensionBlock().failed());
        assertTrue(AdoptedModuleStateTestFixtures.exportNotConfirmed().failed());
        assertTrue(AdoptedModuleStateTestFixtures.switchedButNotOnDisk().failed());
        assertTrue(AdoptedModuleStateTestFixtures.alreadyExtended(BuildUtils.DiskExportState.PENDING).failed());
        assertTrue(AdoptedModuleStateTestFixtures.alreadyExtendedButNotOnDisk().failed());
        assertTrue(AdoptedModuleStateTestFixtures.failedForWantOfAModel().failed());
        // A refused export is decided by the .mdo, read once: the line there is a success, none is a failure.
        assertFalse("a refused export after a switch, the line on disk", //$NON-NLS-1$
            AdoptedModuleStateTestFixtures.switchedWithARefusedExport(true).failed());
        assertFalse("a refused export of a module already Extended, the line on disk", //$NON-NLS-1$
            AdoptedModuleStateTestFixtures.alreadyExtendedWithARefusedExport(true).failed());
        assertTrue(AdoptedModuleStateTestFixtures.switchedWithARefusedExport(false).failed());
        assertTrue(AdoptedModuleStateTestFixtures.alreadyExtendedWithARefusedExport(false).failed());
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
        assertNull(AdoptedModuleStateTestFixtures.alreadyExtendedButNotOnDisk().frontMatterValue());
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
                    // What the .mdo read back holds: the healthy default, a missing line, an unreadable file.
                    for (AdoptedModuleState.LineOnDisk disk : new AdoptedModuleState.LineOnDisk[] { null,
                        AdoptedModuleState.LineOnDisk.ABSENT, AdoptedModuleState.LineOnDisk.UNREADABLE })
                    {
                        freshPlatform();
                        reports(before);
                        env.exportState = state;
                        if (disk != null)
                        {
                            env.disk.add(disk);
                        }
                        results.add(run(path));
                    }
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
        failures.add(AdoptedModuleStateTestFixtures.switchedButNotOnDisk());
        failures.add(AdoptedModuleStateTestFixtures.alreadyExtendedButNotOnDisk());
        failures.add(AdoptedModuleStateTestFixtures.alreadyExtended(BuildUtils.DiskExportState.PENDING));

        freshPlatform(); // the queue drained, the .mdo cannot be read back
        reports(MdPropertyState.NONE);
        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
        failures.add(run(DRY_SIGNAL));

        freshPlatform(); // already Extended, the .mdo cannot be read back
        reports(MdPropertyState.EXTENDED);
        env.disk.add(AdoptedModuleState.LineOnDisk.UNREADABLE);
        failures.add(run(DRY_SIGNAL));

        freshPlatform(); // already Extended, the export cannot be scheduled
        reports(MdPropertyState.EXTENDED);
        env.exportAccepted = false;
        failures.add(run(DRY_SIGNAL));

        freshPlatform(); // an extension EDT has not registered, told by its nature, without its model
        env.extension = false;
        env.model = null;
        failures.add(run(DRY_SIGNAL));

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
