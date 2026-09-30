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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.eclipse.emf.ecore.EObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard.Intent;

/**
 * Tests {@link VendorSupportGuard} against an in-memory configuration and a mocked
 * {@link IModelEditingSupport}: which object is asked for each operation, that it fails closed,
 * that an external-objects project is never asked, and the refusal wording. The platform's own
 * verdict on a real support file is proved by the e2e fixture {@code tests/SupportedConfiguration}.
 *
 * <p>Russian tokens are built from code points so a non-UTF-8 build cannot corrupt them.</p>
 */
public class VendorSupportGuardTest
{
    /** "Справочник" (Catalog). */
    private static final String RU_CATALOG = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447,
        0x043d, 0x0438, 0x043a);
    /** "Реквизит" (Attribute). */
    private static final String RU_ATTRIBUTE = fromCp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438,
        0x0442);
    /** "Вычисление" (a Russian object Name). */
    private static final String RU_CALC = fromCp(0x0412, 0x044b, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435,
        0x043d, 0x0438, 0x0435);

    private IModelEditingSupport support;
    private Configuration config;
    private Catalog goods;
    private CatalogAttribute weight;
    private CatalogForm itemForm;
    private CommonModule calc;
    private Subsystem sales;
    private Subsystem orders;
    private MetadataScope scope;

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);

        config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        goods.getAttributes().add(weight);
        itemForm = MdClassFactory.eINSTANCE.createCatalogForm();
        itemForm.setName("ItemForm"); //$NON-NLS-1$
        goods.getForms().add(itemForm);
        config.getCatalogs().add(goods);
        calc = MdClassFactory.eINSTANCE.createCommonModule();
        calc.setName(RU_CALC);
        config.getCommonModules().add(calc);
        sales = MdClassFactory.eINSTANCE.createSubsystem();
        sales.setName("Sales"); //$NON-NLS-1$
        orders = MdClassFactory.eINSTANCE.createSubsystem();
        orders.setName("Orders"); //$NON-NLS-1$
        sales.getSubsystems().add(orders);
        config.getSubsystems().add(sales);
        scope = MetadataScope.ofConfiguration(config);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ==================== which object is asked ====================

    @Test
    public void testCreateOfATopObjectAsksTheConfiguration()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.New", Intent.CREATE); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("'Catalog.New' cannot be created: the configuration 'Cfg' " //$NON-NLS-1$
            + "is under vendor support")); //$NON-NLS-1$
        verify(support, never()).canDelete(any(), any());
    }

    @Test
    public void testCreateOfAMemberAsksItsOwnerNotTheConfiguration()
    {
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Attribute.New", Intent.CREATE); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Catalog.Goods is under vendor support")); //$NON-NLS-1$

        // The per-object shape: an editable owner in a locked configuration takes a new member.
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(true);
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Attribute.New", Intent.CREATE)); //$NON-NLS-1$
    }

    @Test
    public void testRussianTypeAndKindTokensResolveTheSameOwner()
    {
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);
        String fqn = MetadataTypeUtils.normalizeFqn(RU_CATALOG + ".Goods." + RU_ATTRIBUTE + ".New"); //$NON-NLS-1$ //$NON-NLS-2$

        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope, fqn, Intent.CREATE);

        assertSame(goods, target.object);
        assertNotNull(VendorSupportGuard.refusalForFqn(scope, fqn, Intent.CREATE));
    }

    @Test
    public void testARussianObjectNameResolves()
    {
        String fqn = "CommonModule." + RU_CALC; //$NON-NLS-1$
        when(support.canEdit(calc, EditingMode.DIRECT)).thenReturn(false);

        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope, fqn, Intent.MODIFY);

        assertSame(calc, target.object);
        assertTrue(target.exact);
        assertTrue(VendorSupportGuard.refusalForFqn(scope, fqn, Intent.MODIFY).contains("cannot be changed")); //$NON-NLS-1$
    }

    @Test
    public void testAYoSpelledAddressIsJudgedLikeTheToolResolvesIt()
    {
        // modify/delete retry a yo-spelled Name with ye (the stored spelling); the guard must
        // judge that same object instead of finding nothing and letting the write through.
        Catalog fir = MdClassFactory.eINSTANCE.createCatalog();
        fir.setName(fromCp(0x0415, 0x043b, 0x043a, 0x0430));
        config.getCatalogs().add(fir);
        when(support.canDelete(fir, EditingMode.DIRECT)).thenReturn(false);
        String yoFqn = "Catalog." + fromCp(0x0401, 0x043b, 0x043a, 0x0430); //$NON-NLS-1$

        assertSame(fir, VendorSupportGuard.resolveTarget(scope, yoFqn, Intent.DELETE).object);
        assertNotNull(VendorSupportGuard.refusalForFqn(scope, yoFqn, Intent.DELETE));
    }

    @Test
    public void testModifyAsksTheAddressedMemberItself()
    {
        VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Attribute.Weight", Intent.MODIFY); //$NON-NLS-1$

        verify(support).canEdit(weight, EditingMode.DIRECT);
        verify(support, never()).canEdit(goods, EditingMode.DIRECT);
    }

    @Test
    public void testAFormMemberIsJudgedOnItsForm()
    {
        VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Form.ItemForm.Attribute.List", Intent.DELETE); //$NON-NLS-1$

        verify(support).canEdit(itemForm, EditingMode.DIRECT);
        verify(support, never()).canDelete(any(), any());
    }

    @Test
    public void testDeleteAsksCanDeleteOfTheTargetOnly()
    {
        when(support.canDelete(goods, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.DELETE); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("'Catalog.Goods' cannot be deleted: vendor support does not " //$NON-NLS-1$
            + "allow deleting it")); //$NON-NLS-1$
        verify(support, never()).canEdit(goods, EditingMode.DIRECT);
    }

    @Test
    public void testANestedSubsystemResolvesThroughTheSubsystemChain()
    {
        assertSame(orders, VendorSupportGuard.resolveTarget(scope, "Subsystem.Sales.Subsystem.Orders", //$NON-NLS-1$
            Intent.MODIFY).object);
        assertSame("a nested create is judged on its parent subsystem", sales, //$NON-NLS-1$
            VendorSupportGuard.resolveTarget(scope, "Subsystem.Sales.Subsystem.New", Intent.CREATE).object); //$NON-NLS-1$
    }

    @Test
    public void testACommandInterfaceIsJudgedOnItsOwner()
    {
        assertSame(config, VendorSupportGuard.resolveTarget(scope, "Configuration.CommandInterface", //$NON-NLS-1$
            Intent.MODIFY).object);
        assertSame(sales, VendorSupportGuard.resolveTarget(scope, "Subsystem.Sales.CommandInterface", //$NON-NLS-1$
            Intent.MODIFY).object);
    }

    @Test
    public void testNothingResolvableIsLeftToTheToolsNotFoundAnswer()
    {
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.Missing", Intent.MODIFY)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.Missing.Attribute.X", Intent.CREATE)); //$NON-NLS-1$
        verifyZeroInteractions(support);
    }

    @Test
    public void testIntentNoneIsNeverChecked()
    {
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.NONE)); //$NON-NLS-1$
        verifyZeroInteractions(support);
    }

    // ==================== project kinds ====================

    @Test
    public void testAnExternalObjectsProjectNeverAsksItsBaseConfiguration()
    {
        when(support.canEdit(any(), any())).thenReturn(false);
        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("Proc"); //$NON-NLS-1$
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(config, proc);

        assertNull(VendorSupportGuard.refusalForFqn(external, "ExternalDataProcessor.New", Intent.CREATE)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.refusalForFqn(external, "ExternalDataProcessor.Proc", Intent.MODIFY)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.refusalFor(proc, external, "ExternalDataProcessor.Proc", "x", "changed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(VendorSupportGuard.refusalForModule(null, external,
            "src/ExternalDataProcessors/Proc/ObjectModule.bsl", null)); //$NON-NLS-1$
        assertEquals("", VendorSupportGuard.detailsLine(proc, external)); //$NON-NLS-1$
        verifyZeroInteractions(support);
    }

    @Test
    public void testAnExtensionProjectAsksOnlyItsOwnConfiguration()
    {
        Configuration extension = MdClassFactory.eINSTANCE.createConfiguration();
        extension.setName("Ext"); //$NON-NLS-1$
        MetadataScope extensionScope = MetadataScope.ofConfiguration(extension);

        assertNull(VendorSupportGuard.refusalForFqn(extensionScope, "Catalog.New", Intent.CREATE)); //$NON-NLS-1$

        verify(support).canEdit(extension, EditingMode.DIRECT);
        verify(support, never()).canEdit(eq(config), any());
    }

    // ==================== fail closed ====================

    @Test
    public void testAMissingServiceRefuses()
    {
        VendorSupportGuard.setServiceForTests(() -> null);

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.MODIFY); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("IModelEditingSupport")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
        assertFalse(VendorSupportGuard.allowsEdit(goods));
        assertTrue(VendorSupportGuard.confirmsLock(goods, false));
    }

    @Test
    public void testAThrowingServiceRefusesAndNamesTheCause()
    {
        when(support.canEdit(goods, EditingMode.DIRECT)).thenThrow(new IllegalStateException("index broken")); //$NON-NLS-1$

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.MODIFY); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("Cannot check whether 'Catalog.Goods' may be changed")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("index broken")); //$NON-NLS-1$
        assertFalse(VendorSupportGuard.allowsEdit(goods));
        assertTrue(VendorSupportGuard.confirmsLock(goods, false));
    }

    // ==================== wording ====================

    @Test
    public void testTheRefusalNamesTheRemedyAndSaysNothingChanged()
    {
        when(support.canEdit(weight, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Attribute.Weight", Intent.MODIFY); //$NON-NLS-1$

        assertTrue(refusal, refusal.startsWith("'Catalog.Goods.Attribute.Weight' cannot be changed: it is under " //$NON-NLS-1$
            + "vendor support and its support rule does not allow changes.")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed.")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("adopt_metadata_object")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Support settings")); //$NON-NLS-1$
        assertFalse("the per-object case must not claim the configuration is locked", //$NON-NLS-1$
            refusal.contains("itself is locked")); //$NON-NLS-1$
    }

    @Test
    public void testALockedConfigurationIsNamedOnlyWhenItIsLocked()
    {
        when(support.canEdit(weight, EditingMode.DIRECT)).thenReturn(false);
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Attribute.Weight", Intent.MODIFY); //$NON-NLS-1$

        assertTrue(refusal, refusal.contains("The configuration 'Cfg' itself is locked as well.")); //$NON-NLS-1$
    }

    @Test
    public void testAnEditableTargetIsAllowed()
    {
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods.Attribute.Weight", Intent.MODIFY)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.DELETE)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Catalog.New", Intent.CREATE)); //$NON-NLS-1$
    }

    // ==================== modules ====================

    @Test
    public void testModuleOwnerFqnIsReadOffThePath()
    {
        assertEquals("CommonModule.Calc", VendorSupportGuard.moduleOwnerFqn("src/CommonModules/Calc/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Goods", VendorSupportGuard.moduleOwnerFqn("Catalogs/Goods/ObjectModule.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Goods.Form.ItemForm", //$NON-NLS-1$
            VendorSupportGuard.moduleOwnerFqn("src/Catalogs/Goods/Forms/ItemForm/Module.bsl")); //$NON-NLS-1$
        assertEquals("Catalog.Goods.Command.Print", //$NON-NLS-1$
            VendorSupportGuard.moduleOwnerFqn("src/Catalogs/Goods/Commands/Print/CommandModule.bsl")); //$NON-NLS-1$
        assertEquals("Configuration", //$NON-NLS-1$
            VendorSupportGuard.moduleOwnerFqn("src/Configuration/ManagedApplicationModule.bsl")); //$NON-NLS-1$
        assertEquals("CommonModule." + RU_CALC, //$NON-NLS-1$
            VendorSupportGuard.moduleOwnerFqn("src\\CommonModules\\" + RU_CALC + "\\Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(VendorSupportGuard.moduleOwnerFqn("src/Unknown/X/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testAModuleIsJudgedOnItsOwnerThenOnItself()
    {
        EObject module = mock(EObject.class);
        when(support.canEdit(calc, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForModule(null, scope,
            "src/CommonModules/" + RU_CALC + "/Module.bsl", module); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("cannot be written: CommonModule." + RU_CALC)); //$NON-NLS-1$

        when(support.canEdit(calc, EditingMode.DIRECT)).thenReturn(true);
        when(support.canEdit(module, EditingMode.DIRECT)).thenReturn(false);
        assertTrue(VendorSupportGuard.refusalForModule(null, scope,
            "src/CommonModules/" + RU_CALC + "/Module.bsl", module).contains("the module is under vendor support")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAModuleWhoseOwnerIsUnknownIsJudgedOnTheConfiguration()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);

        String refusal = VendorSupportGuard.refusalForModule(null, scope,
            "src/CommonModules/Absent/Module.bsl", null); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("the configuration 'Cfg' is under vendor support")); //$NON-NLS-1$
    }

    // ==================== details line ====================

    @Test
    public void testTheDetailsLineAppearsOnlyForALockedObject()
    {
        assertEquals("", VendorSupportGuard.detailsLine(goods, scope)); //$NON-NLS-1$

        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);
        when(support.canDelete(goods, EditingMode.DIRECT)).thenReturn(false);
        assertEquals("**Vendor support:** not editable, not deletable\n", //$NON-NLS-1$
            VendorSupportGuard.detailsLine(goods, scope));

        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(true);
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        assertEquals("**Vendor support:** editable, not deletable (the configuration itself is locked)\n", //$NON-NLS-1$
            VendorSupportGuard.detailsLine(goods, scope));
    }

    @Test
    public void testTheDetailsLineSaysNothingWhenTheCheckCannotRun()
    {
        when(support.canEdit(goods, EditingMode.DIRECT)).thenThrow(new IllegalStateException("x")); //$NON-NLS-1$
        assertEquals("", VendorSupportGuard.detailsLine(goods, scope)); //$NON-NLS-1$
        VendorSupportGuard.setServiceForTests(() -> null);
        assertEquals("", VendorSupportGuard.detailsLine(goods, scope)); //$NON-NLS-1$
    }

    // ==================== delete prohibitions ====================

    @Test
    public void testConfirmsLockAsksDeleteOnlyForADeletionProblem()
    {
        assertFalse(VendorSupportGuard.confirmsLock(goods, false));
        verify(support, never()).canDelete(any(), any());

        when(support.canDelete(goods, EditingMode.DIRECT)).thenReturn(false);
        assertFalse("an edit problem is not confirmed by a delete verdict", //$NON-NLS-1$
            VendorSupportGuard.confirmsLock(goods, false));
        assertTrue(VendorSupportGuard.confirmsLock(goods, true));
        assertTrue("no object to ask about cannot prove the lock away", //$NON-NLS-1$
            VendorSupportGuard.confirmsLock(null, false));
    }
}
