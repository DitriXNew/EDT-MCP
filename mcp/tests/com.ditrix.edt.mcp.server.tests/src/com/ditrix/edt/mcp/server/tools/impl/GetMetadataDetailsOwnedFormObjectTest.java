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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
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

    /** The property names of the table rows up to the next section. */
    private static Set<String> rowNames(String md)
    {
        Set<String> names = new HashSet<>();
        for (String line : md.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("| ") && !line.startsWith("| Property |") && !line.startsWith("| --- |")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                names.add(line.substring(2, line.indexOf(" |", 2))); //$NON-NLS-1$
            }
        }
        return names;
    }

    /**
     * Review of #1005: the table told the caller to set with modify_metadata a property that modify_metadata
     * refuses for this very FQN. Every metadata-object property is listed exactly when the owned-form write
     * accepts it - one rule for the table and the refusal, so they cannot drift apart.
     */
    @Test
    public void testTheOwnedFormTableOffersExactlyWhatTheWriteAccepts()
    {
        String fqn = "Catalog.Goods.Form.ItemForm"; //$NON-NLS-1$
        Set<String> rows = rowNames(render(scope(), fqn, new ArrayList<>()));
        List<String> names = MetadataPropertyIntrospector.assignableNames(MdClassFactory.eINSTANCE.createCatalogForm());
        assertTrue(names.toString(), names.contains("formType")); //$NON-NLS-1$
        for (String name : names)
        {
            if ("name".equals(name)) //$NON-NLS-1$
            {
                // Refused for every object by the generic rename rule (MetadataPropertyApplier.prepare), not by
                // the owned-form rule this test pins; whether the table should list it is out of scope here.
                continue;
            }
            boolean refused = ModifyMetadataTool.formTypeRefusal(fqn,
                ModifyMetadataOwnedFormObjectTest.props(name)) != null;
            assertEquals(name + " listed iff the owned-form write accepts it: " + rows, !refused, rows.contains(name)); //$NON-NLS-1$
        }
        assertFalse(rows.toString(), rows.contains("formType")); //$NON-NLS-1$
    }

    @Test
    public void testACommonFormStillListsItsFormType()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        CommonForm common = MdClassFactory.eINSTANCE.createCommonForm();
        common.setName("Shared"); //$NON-NLS-1$
        config.getCommonForms().add(common);
        String md = render(MetadataScope.ofConfiguration(config), "CommonForm.Shared", new ArrayList<>()); //$NON-NLS-1$
        assertTrue(md, md.contains("| formType | ENUM |")); //$NON-NLS-1$
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
