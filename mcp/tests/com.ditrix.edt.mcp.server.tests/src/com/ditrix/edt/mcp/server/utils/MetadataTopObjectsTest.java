/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * The two top-object walks: the metadata objects with a {@code .mdo} of their own, and every top
 * object EDT writes a file for - which is what a full export has to cover, content forms included.
 */
public class MetadataTopObjectsTest
{
    @Test
    public void testTheMetadataWalkKeepsOnlyMetadataObjects()
    {
        IBmTransaction tx = transaction();

        assertEquals(List.of("Catalog.Goods"), MetadataTopObjects.fqns(tx)); //$NON-NLS-1$
    }

    @Test
    public void testTheExportableWalkKeepsEveryTopObjectWhoseClassHasAnExporterInBmOrder()
    {
        IBmTransaction tx = transaction();
        EClass index = indexClass();

        List<String> exportable = MetadataTopObjects.exportableFqns(tx, type -> type != index);

        assertEquals("a content form is exported too; a derived index object is not", //$NON-NLS-1$
            List.of("Catalog.Goods.Form.ItemForm.Form", "Catalog.Goods", "Rights.Role.Admin.Rights"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            exportable);
    }

    @Test
    public void testANullClassHasNoExporter()
    {
        assertFalse(MetadataTopObjects.hasExporter(null));
    }

    @Test
    public void testTheExportableWalkSkipsATopObjectWithoutAnFqn()
    {
        IBmObject nameless = top(null, FormPackage.Literals.FORM);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getTopObjectIterator()).thenReturn(List.of(nameless).iterator());

        assertTrue(MetadataTopObjects.exportableFqns(tx, type -> true).isEmpty());
    }

    private static IBmTransaction transaction()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        IBmObject catalog = mock(IBmObject.class, withSettings().extraInterfaces(Catalog.class));
        when(catalog.bmGetFqn()).thenReturn("Catalog.Goods"); //$NON-NLS-1$
        when(catalog.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        List<IBmObject> objects = List.of(
            top("Catalog.Goods.Form.ItemForm.Form", FormPackage.Literals.FORM), //$NON-NLS-1$
            catalog,
            top("CommonModule.Utils.Module.bsl.mRIdx", indexClass()), //$NON-NLS-1$
            top("Rights.Role.Admin.Rights", FormPackage.Literals.FORM_ATTRIBUTE)); //$NON-NLS-1$
        when(tx.getTopObjectIterator()).thenAnswer(invocation -> objects.iterator());
        return tx;
    }

    private static IBmObject top(String fqn, EClass eClass)
    {
        IBmObject object = mock(IBmObject.class);
        when(object.bmGetFqn()).thenReturn(fqn);
        when(object.eClass()).thenReturn(eClass);
        return object;
    }

    /** One shared stand-in for an index object's class, so identity comparison works. */
    private static EClass indexClass()
    {
        return MdClassPackage.Literals.MD_OBJECT;
    }
}
