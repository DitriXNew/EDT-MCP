/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonObject;

/**
 * Issue #995: an owned form's FQN {@code Type.Object.Form.F} names the form's metadata object (a
 * {@code BasicForm} in its owner's {@code forms}) as well as its content root, and {@code modify_metadata}
 * routes a call by its property names - the rule {@code CommonForm.Name} already followed. Every test here
 * uses only methods that exist on master, so each is red there for the reason the issue reports: the
 * owned-form address always went to the content root, and the headless write had no node to write.
 *
 * <p>Russian tokens and names are written as Unicode escapes so a non-UTF-8 build cannot corrupt them.</p>
 */
public class ModifyMetadataOwnedFormObjectTest
{
    /** Справочник (Catalog). */
    static final String RU_CATALOG = "Справочник"; //$NON-NLS-1$
    /** Форма (Form). */
    static final String RU_FORM = "Форма"; //$NON-NLS-1$
    /** Товары (Goods), a Russian programmatic Name. */
    static final String RU_GOODS = "Товары"; //$NON-NLS-1$
    /** ФормаЭлемента (ItemForm), a Russian programmatic Name. */
    static final String RU_ITEM_FORM = "ФормаЭлемента"; //$NON-NLS-1$

    /** A configuration with an English and a Russian catalog form, a data-processor form and a common form. */
    static final class Fixture
    {
        final Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        final Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        final CatalogForm itemForm = MdClassFactory.eINSTANCE.createCatalogForm();
        final Catalog ruCatalog = MdClassFactory.eINSTANCE.createCatalog();
        final CatalogForm ruForm = MdClassFactory.eINSTANCE.createCatalogForm();
        final DataProcessor processor = MdClassFactory.eINSTANCE.createDataProcessor();
        final DataProcessorForm processorForm = MdClassFactory.eINSTANCE.createDataProcessorForm();
        final CommonForm common = MdClassFactory.eINSTANCE.createCommonForm();
        final MetadataScope scope;

        Fixture()
        {
            catalog.setName("Goods"); //$NON-NLS-1$
            itemForm.setName("ItemForm"); //$NON-NLS-1$
            catalog.getForms().add(itemForm);
            config.getCatalogs().add(catalog);
            ruCatalog.setName(RU_GOODS);
            ruForm.setName(RU_ITEM_FORM);
            ruCatalog.getForms().add(ruForm);
            config.getCatalogs().add(ruCatalog);
            processor.setName("Import"); //$NON-NLS-1$
            processorForm.setName("Main"); //$NON-NLS-1$
            processor.getForms().add(processorForm);
            config.getDataProcessors().add(processor);
            common.setName("Shared"); //$NON-NLS-1$
            config.getCommonForms().add(common);
            scope = MetadataScope.ofConfiguration(config);
        }
    }

    static JsonObject prop(String name, String value)
    {
        JsonObject o = new JsonObject();
        o.addProperty("name", name); //$NON-NLS-1$
        o.addProperty("value", value); //$NON-NLS-1$
        return o;
    }

    static List<JsonObject> props(String... names)
    {
        return Arrays.stream(names).map(n -> prop(n, "x")).toList(); //$NON-NLS-1$
    }

    static String path(String fqn)
    {
        return FormElementWriter.parseFormPath(MetadataTypeUtils.normalizeFqn(fqn));
    }

    // ==================== routing by property name ====================

    @Test
    public void testASynonymOnAnOwnedFormGoesToItsMetadataObject()
    {
        Fixture f = new Fixture();
        assertFalse("synonym is a property of the form's metadata object, not of Form.form", //$NON-NLS-1$
            ModifyMetadataTool.shouldDispatchFormRoot(path("Catalog.Goods.Form.ItemForm"), f.itemForm, //$NON-NLS-1$
                props("synonym"))); //$NON-NLS-1$
    }

    @Test
    public void testMetadataPropertiesOnARussianAddressStayOnTheMetadataObject()
    {
        Fixture f = new Fixture();
        String fqn = RU_CATALOG + "." + RU_GOODS + "." + RU_FORM + "." + RU_ITEM_FORM; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame(f.ruForm, ModifyMetadataTool.resolveFormRootForDispatch(f.scope,
            MetadataTypeUtils.normalizeFqn(fqn)));
        assertFalse(ModifyMetadataTool.shouldDispatchFormRoot(path(fqn), f.ruForm,
            props("comment", "usePurposes"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a data processor's form follows the same rule", //$NON-NLS-1$
            ModifyMetadataTool.shouldDispatchFormRoot(path("DataProcessor.Import.Form.Main"), //$NON-NLS-1$
                f.processorForm, props("synonym"))); //$NON-NLS-1$
    }

    @Test
    public void testContentRootPropertiesOfAResolvedOwnedFormStayOnTheContentRoot()
    {
        Fixture f = new Fixture();
        assertTrue(ModifyMetadataTool.shouldDispatchFormRoot(path("Catalog.Goods.Form.ItemForm"), f.itemForm, //$NON-NLS-1$
            props("title", "autoTitle"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnUnresolvedOwnedFormIsStillTakenByTheFormBranch()
    {
        // Before resolution the owned address goes to the form branch, which reports a missing form with
        // its owner; a common form waits for the mdclass resolution.
        assertTrue(ModifyMetadataTool.shouldDispatchFormRoot(path("Catalog.Goods.Form.Nope"), null, //$NON-NLS-1$
            props("synonym"))); //$NON-NLS-1$
        assertFalse(ModifyMetadataTool.shouldDispatchFormRoot(path("CommonForm.Shared"), null, //$NON-NLS-1$
            props("autoTitle"))); //$NON-NLS-1$
    }

    @Test
    public void testACommonFormKeepsItsRule()
    {
        Fixture f = new Fixture();
        assertFalse(ModifyMetadataTool.shouldDispatchFormRoot(path("CommonForm.Shared"), f.common, //$NON-NLS-1$
            props("comment"))); //$NON-NLS-1$
        assertTrue(ModifyMetadataTool.shouldDispatchFormRoot(path("CommonForm.Shared"), f.common, //$NON-NLS-1$
            props("autoTitle"))); //$NON-NLS-1$
        assertFalse("an object that is no form never goes to a content root", //$NON-NLS-1$
            ModifyMetadataTool.shouldDispatchFormRoot(path("CommonForm.Shared"), f.catalog, //$NON-NLS-1$
                Collections.emptyList()));
    }

    // ==================== the headless write node ====================

    @Test
    public void testTheWriteNodeOfAnOwnedFormIsItsMetadataObjectInTheOwnersForms()
    {
        Fixture f = new Fixture();
        MetadataNodeResolver.MetadataNode node = ModifyMetadataTool.resolveGenericWriteNode(f.scope,
            MetadataTypeUtils.normalizeFqn("Catalog.Goods.Form.ItemForm")); //$NON-NLS-1$
        assertNotNull("the headless write must find the form it writes", node); //$NON-NLS-1$
        assertSame(f.itemForm, node.object);
        assertSame(f.catalog, node.owner);
        assertSame(MdClassPackage.Literals.CATALOG__FORMS, node.feature);
        assertFalse(node.topLevel);
    }

    @Test
    public void testTheWriteNodeResolvesRussianTokensAndNames()
    {
        Fixture f = new Fixture();
        MetadataNodeResolver.MetadataNode node = ModifyMetadataTool.resolveGenericWriteNode(f.scope,
            MetadataTypeUtils.normalizeFqn(RU_CATALOG + "." + RU_GOODS + "." + RU_FORM + "." + RU_ITEM_FORM)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(node);
        assertSame(f.ruForm, node.object);
        assertSame(f.ruCatalog, node.owner);
        MetadataNodeResolver.MetadataNode processor = ModifyMetadataTool.resolveGenericWriteNode(f.scope,
            "DataProcessor.Import.Form.Main"); //$NON-NLS-1$
        assertNotNull(processor);
        assertSame(f.processorForm, processor.object);
        assertSame(MdClassPackage.Literals.DATA_PROCESSOR__FORMS, processor.feature);
    }
}
