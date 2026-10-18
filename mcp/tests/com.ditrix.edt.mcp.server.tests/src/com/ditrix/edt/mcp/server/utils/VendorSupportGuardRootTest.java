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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard.Intent;

/**
 * Tests the vendor-support guard on the configuration ROOT address (issue #741): a change to
 * {@code Configuration} asks EDT about the configuration itself, only a MODIFY targets it (a rename,
 * which cannot address the root, never does), only an address that really names this project's root
 * does, and the refusal's way out fits a root - the shared "adopt the object in an extension" advice
 * does not.
 *
 * <p>Russian tokens are built from code points so a non-UTF-8 build cannot corrupt them.</p>
 */
public class VendorSupportGuardRootTest
{
    /** "Конфигурация" (Configuration). */
    private static final String RU_CONFIGURATION = new String(new int[] { 0x041a, 0x043e, 0x043d, 0x0444,
        0x0438, 0x0433, 0x0443, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f }, 0, 12);

    private IModelEditingSupport support;
    private Configuration config;
    private Catalog goods;
    private MetadataScope scope;

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
        config.getCatalogs().add(goods);
        scope = MetadataScope.ofConfiguration(config);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    @Test
    public void testModifyingTheRootAsksTheConfiguration()
    {
        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope, "Configuration", Intent.MODIFY); //$NON-NLS-1$
        assertNotNull("a change to the root is a change to the configuration", target); //$NON-NLS-1$
        assertSame(config, target.object);
        assertTrue(target.exact);

        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        String refusal = VendorSupportGuard.refusalForFqn(scope, "Configuration", Intent.MODIFY); //$NON-NLS-1$

        assertNotNull("a locked root must be refused, not waved through as 'not found'", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'Configuration' cannot be changed: the configuration 'Cfg' " //$NON-NLS-1$
            + "is under vendor support")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("support settings")); //$NON-NLS-1$
        assertFalse("adopting the object in an extension does not change a root: " + refusal, //$NON-NLS-1$
            refusal.contains("adopt_metadata_object")); //$NON-NLS-1$
        // The root's own way out, which the shared remedy does not carry: why an extension is no help.
        assertTrue(refusal, refusal.endsWith(" The root of a configuration on vendor support changes only " //$NON-NLS-1$
            + "once the user allows changes to it in EDT's support settings (Configuration > Support > " //$NON-NLS-1$
            + "Support settings); this server does not change support settings. A configuration extension " //$NON-NLS-1$
            + "does not help here: its root is its own, so what is set on it describes the extension, not " //$NON-NLS-1$
            + "this configuration.")); //$NON-NLS-1$
    }

    @Test
    public void testEveryFormOfTheRootAddressTargetsTheConfiguration()
    {
        for (String address : new String[] { RU_CONFIGURATION, "Configuration.Cfg", "configuration.CFG" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope, address, Intent.MODIFY);
            assertNotNull(address, target);
            assertSame(address, config, target.object);
        }
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        assertNotNull(VendorSupportGuard.refusalForFqn(scope, RU_CONFIGURATION, Intent.MODIFY));
    }

    /**
     * The guard runs before the dispatch, so it must not judge the root on the address SHAPE alone:
     * {@code Configuration.WrongName} names no root of this project, and saying the configuration 'Cfg'
     * is locked would answer about a configuration the caller never named. The dispatch refuses that
     * address on its own, before anything is written.
     */
    @Test
    public void testARootAddressNamingAnotherConfigurationIsNotJudged()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        assertNull(VendorSupportGuard.resolveTarget(scope, "Configuration.WrongName", Intent.MODIFY)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.refusalForFqn(scope, "Configuration.WrongName", Intent.MODIFY)); //$NON-NLS-1$
    }

    /** An external-objects project's configuration is the LINKED base, never this project's root. */
    @Test
    public void testAnExternalObjectsScopeNeverJudgesItsBaseAsTheRoot()
    {
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(config);
        assertNull(VendorSupportGuard.resolveTarget(external, "Configuration", Intent.MODIFY)); //$NON-NLS-1$
    }

    /** Only a MODIFY targets the root: the verdicts of a create and a delete are those of before. */
    @Test
    public void testCreateAndDeleteOfTheRootAddressAreUnchanged()
    {
        assertNull(VendorSupportGuard.resolveTarget(scope, "Configuration", Intent.DELETE)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.resolveTarget(scope, "Configuration.Cfg", Intent.DELETE)); //$NON-NLS-1$
        assertNull(VendorSupportGuard.resolveTarget(scope, "Configuration", Intent.CREATE)); //$NON-NLS-1$
    }

    /** The root's way out is its own; every other refusal keeps the shared one. */
    @Test
    public void testAnObjectRefusalKeepsTheExtensionRemedy()
    {
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);
        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.MODIFY); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("adopt_metadata_object")); //$NON-NLS-1$
    }

    /** A command interface of the configuration is judged as before: on the configuration, not exact. */
    @Test
    public void testTheConfigurationCommandInterfaceIsStillItsOwnAddress()
    {
        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope,
            "Configuration.MainSectionCommandInterface", Intent.MODIFY); //$NON-NLS-1$
        assertNotNull(target);
        assertSame(config, target.object);
        assertFalse("a command interface is not the root itself", target.exact); //$NON-NLS-1$
    }

    /**
     * rename_metadata_object cannot address the root (it answers "not found" for it), so a rename of a
     * root address is never judged: a lock on the root is not why it fails, and naming one would send
     * the caller to the support settings for a rename that still could not happen.
     */
    @Test
    public void testARenameNeverJudgesTheRoot()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        for (String address : new String[] { "Configuration", "Configuration.Cfg", RU_CONFIGURATION }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNull(address, VendorSupportGuard.resolveTarget(scope, address, Intent.RENAME));
            assertNull(address, VendorSupportGuard.refusalForFqn(scope, address, Intent.RENAME));
        }
    }

    /** Everywhere else a rename is judged exactly as a MODIFY of the same address, worded as a rename. */
    @Test
    public void testARenameIsJudgedLikeAModifyEverywhereElse()
    {
        for (String address : new String[] { "Catalog.Goods", "Configuration.MainSectionCommandInterface", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog.Missing" }) //$NON-NLS-1$
        {
            VendorSupportGuard.Target modify = VendorSupportGuard.resolveTarget(scope, address, Intent.MODIFY);
            VendorSupportGuard.Target rename = VendorSupportGuard.resolveTarget(scope, address, Intent.RENAME);
            assertEquals(address, modify == null, rename == null);
            if (modify != null)
            {
                assertSame(address, modify.object, rename.object);
                assertEquals(address, modify.exact, rename.exact);
            }
        }
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);
        String refusal = VendorSupportGuard.refusalForFqn(scope, "Catalog.Goods", Intent.RENAME); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.startsWith("'Catalog.Goods' cannot be renamed: ")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("adopt_metadata_object")); //$NON-NLS-1$
    }

    /**
     * The guard reads a root address exactly as the write path does - both ask
     * {@link ConfigurationRoot#resolve} - so the yo spelling of a root Name stored with ye, which the
     * write path accepts, is judged on the root and refused when it is locked.
     */
    @Test
    public void testAYoSpellingOfTheRootNameIsJudgedLikeTheWritePathReadsIt()
    {
        Configuration accounting = MdClassFactory.eINSTANCE.createConfiguration();
        accounting.setName(new String(new int[] { 0x0423, 0x0447, 0x0435, 0x0442 }, 0, 4)); // "Uchet", with ye
        MetadataScope accountingScope = MetadataScope.ofConfiguration(accounting);
        when(support.canEdit(accounting, EditingMode.DIRECT)).thenReturn(false);
        String yoAddress = "Configuration." + new String(new int[] { 0x0423, 0x0447, 0x0451, 0x0442 }, 0, 4); //$NON-NLS-1$

        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(accountingScope, yoAddress, Intent.MODIFY);
        assertNotNull("the write path accepts this address, so the guard must judge it", target); //$NON-NLS-1$
        assertSame(accounting, target.object);
        assertNotNull(VendorSupportGuard.refusalForFqn(accountingScope, yoAddress, Intent.MODIFY));
    }
}
