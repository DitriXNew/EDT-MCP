/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;

/** Issue #983: which predefined item a rename targets, and the names it refuses before EDT runs. */
public class PredefinedItemRenameTargetTest
{
    private static Catalog catalogWith(String... names)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Units"); //$NON-NLS-1$
        catalog.setHierarchical(true);
        PredefinedWriter.ItemProps folder = new PredefinedWriter.ItemProps();
        folder.isFolder = Boolean.TRUE;
        folder.isFolderSet = true;
        PredefinedWriter.create(catalog, "Folder", folder, false); //$NON-NLS-1$
        for (String name : names)
        {
            PredefinedWriter.ItemProps nested = new PredefinedWriter.ItemProps();
            nested.parentName = "Folder"; //$NON-NLS-1$
            PredefinedWriter.create(catalog, name, nested, false);
        }
        return catalog;
    }

    @Test
    public void testTheItemIsFoundInsideAFolder()
    {
        Catalog catalog = catalogWith("Piece", "Box"); //$NON-NLS-1$ //$NON-NLS-2$
        PredefinedItem piece = PredefinedWriter.findByNameExact(catalog, "Piece"); //$NON-NLS-1$

        MetadataRenameService.PredefinedTarget target =
            MetadataRenameService.predefinedTarget(catalog, "Catalog.Units", "Piece", "Unit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(target.refusal());
        assertSame(piece, target.item());
    }

    @Test
    public void testANameAnotherItemHasIsRefusedIgnoringCase()
    {
        Catalog catalog = catalogWith("Piece", "Box"); //$NON-NLS-1$ //$NON-NLS-2$

        for (String taken : new String[] { "Box", "BOX", "Folder" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            MetadataRenameService.PredefinedTarget target =
                MetadataRenameService.predefinedTarget(catalog, "Catalog.Units", "Piece", taken); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(taken, target.item());
            assertTrue(target.refusal(), target.refusal().contains("already exists on Catalog.Units")); //$NON-NLS-1$
            assertTrue(target.refusal(), target.refusal().contains("Nothing was renamed")); //$NON-NLS-1$
        }
    }

    @Test
    public void testACaseOnlyRenameOfTheSameItemIsAllowed()
    {
        Catalog catalog = catalogWith("Piece"); //$NON-NLS-1$

        MetadataRenameService.PredefinedTarget target =
            MetadataRenameService.predefinedTarget(catalog, "Catalog.Units", "Piece", "PIECE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(target.refusal());
    }

    @Test
    public void testAMissingItemAndAnUnchangedNameAreRefused()
    {
        Catalog catalog = catalogWith("Piece"); //$NON-NLS-1$

        MetadataRenameService.PredefinedTarget missing =
            MetadataRenameService.predefinedTarget(catalog, "Catalog.Units", "Nope", "Other"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(missing.refusal(), missing.refusal().startsWith("Predefined item not found: 'Nope'")); //$NON-NLS-1$

        MetadataRenameService.PredefinedTarget same =
            MetadataRenameService.predefinedTarget(catalog, "Catalog.Units", "Piece", "Piece"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(same.refusal(), same.refusal().contains("already named 'Piece'")); //$NON-NLS-1$
    }
}
