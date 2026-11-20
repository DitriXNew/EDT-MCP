/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * Issue #995: {@code get_metadata_details} with {@code assignable:true} on an owned form lists what
 * {@code modify_metadata} now writes on that FQN - the form's metadata object first, under the plain
 * heading, with the content root's table after it. Without a BM model (this headless test) the content
 * root cannot be read and is a failure row, exactly as for a common form; the metadata object's table is
 * rendered either way. On master the owned form rendered only the content root, so here nothing at all.
 */
public class GetMetadataDetailsOwnedFormObjectTest
{
    private static String render(MetadataScope scope, String fqn, List<String[]> failures)
    {
        GetMetadataDetailsTool.RenderContext ctx =
            new GetMetadataDetailsTool.RenderContext(null, scope, null, "en", false, true, false, 0); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        VendorSupportGuard.setServiceForTests(() -> null);
        try
        {
            new GetMetadataDetailsTool().processFqn(fqn, sb, failures, ctx);
        }
        finally
        {
            VendorSupportGuard.setServiceForTests(null);
        }
        return sb.toString();
    }

    private static MetadataScope scope()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ItemForm"); //$NON-NLS-1$
        form.getSynonym().put("en", "Item form"); //$NON-NLS-1$ //$NON-NLS-2$
        form.setComment("Kept"); //$NON-NLS-1$
        catalog.getForms().add(form);
        config.getCatalogs().add(catalog);
        return MetadataScope.ofConfiguration(config);
    }

    @Test
    public void testAnOwnedFormListsItsMetadataObjectProperties()
    {
        List<String[]> failures = new ArrayList<>();
        String md = render(scope(), "Catalog.Goods.Form.ItemForm", failures); //$NON-NLS-1$

        assertTrue(md, md.startsWith("## Assignable properties: Catalog.Goods.Form.ItemForm\n")); //$NON-NLS-1$
        assertTrue(md, md.contains("| synonym | LOCALIZED_STRING |")); //$NON-NLS-1$
        assertTrue(md, md.contains("Item form")); //$NON-NLS-1$
        assertTrue(md, md.contains("| comment | STRING | Kept |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| usePurposes | MANY_ENUM |")); //$NON-NLS-1$
        assertFalse("no content-root table without a BM model: " + md, md.contains("content root assignable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only the content root, unreadable without a BM model, is a failure", 1, failures.size()); //$NON-NLS-1$
        assertTrue(failures.get(0)[1], failures.get(0)[1].contains("no editable content model")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingOwnedFormIsStillOneFailureNamingTheOwner()
    {
        List<String[]> failures = new ArrayList<>();
        String md = render(scope(), "Catalog.Goods.Form.Nope", failures); //$NON-NLS-1$
        assertEquals("", md); //$NON-NLS-1$
        assertEquals(1, failures.size());
        assertTrue(failures.get(0)[1], failures.get(0)[1].contains("Form 'Nope' not found on owner 'Catalog.Goods'")); //$NON-NLS-1$
    }
}
