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
import com._1c.g5.v8.dt.moxel.MoxelPackage;
import com._1c.g5.v8.dt.rights.model.RightsPackage;

/**
 * The two top-object walks: the metadata objects with a {@code .mdo} of their own, and the top
 * objects a full export re-writes - those plus the content forms, never template content or rights.
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

        assertEquals("the predicate decides; a derived index object is left out", //$NON-NLS-1$
            List.of("Catalog.Goods.Form.ItemForm.Form", "Catalog.Goods", "Role.Admin.Rights", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "CommonTemplate.Print.Template"), //$NON-NLS-1$
            exportable);
    }

    @Test
    public void testOnlyMetadataObjectsAndContentFormsAreReexportable()
    {
        List<String> exportable = MetadataTopObjects.exportableFqns(transaction(),
            MetadataTopObjects::isReexportableKind);

        assertEquals("template content and rights are never re-written: a forced template export truncates", //$NON-NLS-1$
            List.of("Catalog.Goods.Form.ItemForm.Form", "Catalog.Goods"), exportable); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheReexportableKindsAreAnyMetadataObjectAndTheContentForm()
    {
        assertTrue(MetadataTopObjects.isReexportableKind(MdClassPackage.Literals.CATALOG));
        assertTrue(MetadataTopObjects.isReexportableKind(MdClassPackage.Literals.COMMON_TEMPLATE));
        assertTrue(MetadataTopObjects.isReexportableKind(FormPackage.Literals.FORM));
        assertFalse(MetadataTopObjects.isReexportableKind(MoxelPackage.Literals.SPREADSHEET_DOCUMENT));
        assertFalse(MetadataTopObjects.isReexportableKind(RightsPackage.Literals.ROLE_DESCRIPTION));
        assertFalse(MetadataTopObjects.isReexportableKind(FormPackage.Literals.FORM_ATTRIBUTE));
        assertFalse(MetadataTopObjects.isReexportableKind(null));
        assertFalse("no exporter question is asked for a class that is out of scope", //$NON-NLS-1$
            MetadataTopObjects.isReexportable(MoxelPackage.Literals.SPREADSHEET_DOCUMENT));
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
            top("Role.Admin.Rights", RightsPackage.Literals.ROLE_DESCRIPTION), //$NON-NLS-1$
            top("CommonTemplate.Print.Template", MoxelPackage.Literals.SPREADSHEET_DOCUMENT)); //$NON-NLS-1$
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
        return FormPackage.Literals.FORM_ATTRIBUTE;
    }
}
