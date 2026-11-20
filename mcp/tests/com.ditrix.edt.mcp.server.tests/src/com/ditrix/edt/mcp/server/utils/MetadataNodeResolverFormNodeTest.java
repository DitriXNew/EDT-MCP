/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * {@link MetadataNodeResolver#formNode}: the node of a form's metadata object (issue #995) - an owned form
 * as a member of its owner's {@code forms}, a common form as the top object the generic resolver returns.
 */
public class MetadataNodeResolverFormNodeTest
{
    /** Справочник.Товары.Форма.ФормаЭлемента. */
    private static final String RU_ADDRESS = "Справочник." //$NON-NLS-1$
        + "Товары.Форма." //$NON-NLS-1$
        + "ФормаЭлемента"; //$NON-NLS-1$

    private final Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
    private final Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
    private final CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
    private final Catalog ruCatalog = MdClassFactory.eINSTANCE.createCatalog();
    private final CatalogForm ruForm = MdClassFactory.eINSTANCE.createCatalogForm();
    private final CommonForm common = MdClassFactory.eINSTANCE.createCommonForm();

    private MetadataScope scope()
    {
        catalog.setName("Goods"); //$NON-NLS-1$
        form.setName("ItemForm"); //$NON-NLS-1$
        catalog.getForms().add(form);
        config.getCatalogs().add(catalog);
        ruCatalog.setName("Товары"); //$NON-NLS-1$
        ruForm.setName("ФормаЭлемента"); //$NON-NLS-1$
        ruCatalog.getForms().add(ruForm);
        config.getCatalogs().add(ruCatalog);
        common.setName("Shared"); //$NON-NLS-1$
        config.getCommonForms().add(common);
        return MetadataScope.ofConfiguration(config);
    }

    private static String path(String fqn)
    {
        return FormElementWriter.parseFormPath(MetadataTypeUtils.normalizeFqn(fqn));
    }

    @Test
    public void testAnOwnedFormIsAMemberOfItsOwnersForms()
    {
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.formNode(scope(),
            path("Catalog.Goods.Form.ItemForm")); //$NON-NLS-1$
        assertNotNull(node);
        assertSame(form, node.object);
        assertSame(catalog, node.owner);
        assertSame(MdClassPackage.Literals.CATALOG__FORMS, node.feature);
        assertFalse(node.topLevel);
    }

    @Test
    public void testRussianTokensAndNamesResolveTheSameForm()
    {
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.formNode(scope(), path(RU_ADDRESS));
        assertNotNull(node);
        assertSame(ruForm, node.object);
        assertSame(ruCatalog, node.owner);
        assertSame(MdClassPackage.Literals.CATALOG__FORMS, node.feature);
    }

    @Test
    public void testACommonFormIsTheTopObjectTheGenericResolverReturns()
    {
        MetadataScope scope = scope();
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.formNode(scope, path("CommonForm.Shared")); //$NON-NLS-1$
        assertNotNull(node);
        assertSame(common, node.object);
        assertSame(config, node.owner);
        assertSame(MdClassPackage.Literals.CONFIGURATION__COMMON_FORMS, node.feature);
        assertTrue(node.topLevel);
    }

    @Test
    public void testAMissingFormOrANonFormPathHasNoNode()
    {
        MetadataScope scope = scope();
        assertNull(MetadataNodeResolver.formNode(scope, path("Catalog.Goods.Form.Nope"))); //$NON-NLS-1$
        assertNull(MetadataNodeResolver.formNode(scope, path("CommonForm.Nope"))); //$NON-NLS-1$
        assertNull("a top object that is no form is never a form node", //$NON-NLS-1$
            MetadataNodeResolver.formNode(scope, "Catalog.Goods")); //$NON-NLS-1$
        assertNull(MetadataNodeResolver.formNode(scope, null));
        assertNull("the generic grammar still has no form step", //$NON-NLS-1$
            MetadataNodeResolver.resolveExisting(scope, "Catalog.Goods.Form.ItemForm")); //$NON-NLS-1$
    }
}
