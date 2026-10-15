/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * The vendor-support check of {@code rename_metadata_object} and the configuration ROOT (issue #741).
 * The root became addressable for {@code modify_metadata}, and the shared guard judges a change to it on
 * the configuration itself; a rename cannot address the root at all (its resolver answers "not found"
 * for {@code Configuration}), so its check must never answer with the root's lock - that would send the
 * caller to EDT's support settings for a rename that still could not happen. Everything else a rename
 * names is judged as before.
 */
public class MetadataRenameConfigurationRootTest
{
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
    public void testALockedRootIsNotTheAnswerToARenameOfTheRoot()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);
        for (String address : new String[] { "Configuration", "Configuration.Cfg" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNull("a rename cannot address the root, so its lock is not why it fails: " + address, //$NON-NLS-1$
                MetadataRenameService.vendorSupportRefusal(scope, address));
        }
    }

    @Test
    public void testALockedObjectIsStillRefusedAsARename()
    {
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);
        String refusal = MetadataRenameService.vendorSupportRefusal(scope, "Catalog.Goods"); //$NON-NLS-1$
        assertNotNull("the rename of a locked object is refused before any refactoring exists", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'Catalog.Goods' cannot be renamed: ")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
    }
}
