/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

public class EditorNavigationTest
{
    private static Catalog catalog(Configuration configuration, String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        catalog.getSynonym().put("en", "Product list");
        configuration.getCatalogs().add(catalog);
        return catalog;
    }

    @Test
    public void englishAndRussianTypesNamesAndMembersReachUi()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog english = catalog(configuration, "Products");
        Catalog russian = catalog(configuration, "\u0422\u043e\u0432\u0430\u0440\u044b");
        CatalogAttribute price = MdClassFactory.eINSTANCE.createCatalogAttribute();
        price.setName("\u0426\u0435\u043d\u0430");
        russian.getAttributes().add(price);
        AtomicReference<MdObject> opened = new AtomicReference<>();
        EditorNavigation.Editors editors = object -> { opened.set(object); return true; };
        MetadataScope scope = MetadataScope.ofConfiguration(configuration);

        EditorNavigation.openMetadata(scope, "Catalog.Products", editors);
        assertSame(english, opened.get());
        EditorNavigation.openMetadata(scope, "Catalog.\u0422\u043e\u0432\u0430\u0440\u044b", editors);
        assertSame(russian, opened.get());
        EditorNavigation.openMetadata(scope,
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.\u0422\u043e\u0432\u0430\u0440\u044b."
                + "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442.\u0426\u0435\u043d\u0430", editors);
        assertSame("Pass the member to EDT so its native parent editor selects it", price, opened.get());
        assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openMetadata(scope, "Catalog.Product list", object -> {
                fail("A synonym must not resolve"); return false;
            }));
    }

    @Test
    public void managedFormReachesNativeEditor()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog owner = catalog(configuration, "Products");
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("Item");
        form.setFormType(FormType.MANAGED);
        owner.getForms().add(form);
        AtomicReference<MdObject> opened = new AtomicReference<>();
        EditorNavigation.openMetadata(MetadataScope.ofConfiguration(configuration), "Catalog.Products.Form.Item",
            object -> { opened.set(object); return true; });
        assertSame(form, opened.get());
    }

    @Test
    public void unsupportedFormNeverReachesUi()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog owner = catalog(configuration, "Products");
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("Item");
        form.setFormType(FormType.ORDINARY);
        owner.getForms().add(form);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openMetadata(MetadataScope.ofConfiguration(configuration),
                "Catalog.Products.Form.Item", object -> { fail("Unsupported form"); return false; }));
        assertTrue(error.getMessage().contains("Catalog.Products.Form.Item"));
        assertTrue(error.getMessage().contains("Select a managed form"));
    }

    @Test
    public void missingTargetNeverReachesUi()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openMetadata(
                MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()),
                "Catalog.Missing", object -> { fail("Missing target"); return false; }));
        assertTrue(error.getMessage().contains("Catalog.Missing"));
        assertTrue(error.getMessage().contains("get_metadata_objects"));
    }

    @Test
    public void nativeFailureIsNotSuccess()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        catalog(configuration, "Products");
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> EditorNavigation.openMetadata(MetadataScope.ofConfiguration(configuration),
                "Catalog.Products", object -> false));
        assertTrue(error.getMessage().contains("EDT could not open"));
    }

    @Test
    public void lineDefaultsAndBoundaries()
    {
        List<String> lines = List.of("// first", "// second");
        assertEquals(1, EditorNavigation.resolveLine(lines, null, null));
        assertEquals(2, EditorNavigation.resolveLine(lines, 2, null));
        assertEquals(1, EditorNavigation.resolveLine(List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> EditorNavigation.resolveLine(lines, 0, null));
        assertThrows(IllegalArgumentException.class, () -> EditorNavigation.resolveLine(lines, 3, null));
    }

    @Test
    public void englishAndRussianMethodsSelectDeclarationInsteadOfComments()
    {
        List<String> lines = List.of("// Procedure Wrong()", "// documentation", "Procedure Calculate()",
            "EndProcedure", "\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u0430 \u0420\u0430\u0441\u0447\u0435\u0442()",
            "\u041a\u043e\u043d\u0435\u0446\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b");
        assertEquals(3, EditorNavigation.resolveLine(lines, null, "calculate"));
        assertEquals(5, EditorNavigation.resolveLine(lines, null, "\u0420\u0430\u0441\u0447\u0435\u0442"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.resolveLine(lines, null, "Wrong"));
        assertTrue(error.getMessage().contains("Wrong"));
        assertTrue(error.getMessage().contains("get_module_structure"));
    }
}
