/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.lang.reflect.Method;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/** Reflection keeps the tests executable (and failing assertions) when the new helper is absent on base. */
public class ReferencedTopObjectsTest
{
    public static Object invoke(String name, Class<?>[] types, Object... args) throws Exception
    {
        Method method = Class.forName("com.ditrix.edt.mcp.server.utils.ReferencedTopObjects")
            .getMethod(name, types);
        return method.invoke(null, args);
    }

    @Test public void onlySupportedReferenceCollectionsAreTopObjects() throws Exception
    {
        for (EReference ref : new EReference[] { MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__TABLES,
            MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__CUBES })
        {
            assertEquals(true, invoke("isReferencedTopCollection", new Class<?>[] { EReference.class }, ref));
        }
        for (EReference ref : new EReference[] { MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__FUNCTIONS,
            MdClassPackage.Literals.TABLE__TABLE_FIELDS, MdClassPackage.Literals.SUBSYSTEM__SUBSYSTEMS })
        {
            assertEquals(false, invoke("isReferencedTopCollection", new Class<?>[] { EReference.class }, ref));
        }
    }

    @Test public void linksBothDirectionsAndUsesTheStoredParentAddress() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        f.eds.getTables().clear();
        f.table.setParentDataSource(null);
        invoke("link", new Class<?>[] { EObject.class, EReference.class, EObject.class },
            f.eds, MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__TABLES, f.table);
        assertTrue(f.eds.getTables().contains(f.table));
        assertSame(f.eds, f.table.getParentDataSource());
        assertEquals(TABLE, invoke("attachFqn", new Class<?>[] { String.class, EClass.class, String.class },
            EDS, MdClassPackage.Literals.TABLE, "T"));
    }

    @Test public void cubeLinksBothDirections() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        f.eds.getCubes().clear();
        f.cube.setParentDataSource(null);
        invoke("link", new Class<?>[] { EObject.class, EReference.class, EObject.class },
            f.eds, MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__CUBES, f.cube);
        assertTrue(f.eds.getCubes().contains(f.cube));
        assertSame(f.eds, f.cube.getParentDataSource());
    }

    @Test public void attachPrecedesLinkAndDefaultReferences() throws Exception
    {
        var tx = mock(com._1c.g5.v8.bm.core.IBmTransaction.class);
        var factory = mock(com._1c.g5.v8.dt.core.model.IModelObjectFactory.class);
        var f = new ExternalDataSourceFixtures();
        f.eds.getTables().clear();
        var child = mock(com._1c.g5.v8.dt.metadata.mdclass.Table.class,
            withSettings().extraInterfaces(IBmObject.class, org.eclipse.emf.ecore.InternalEObject.class));
        when(child.eClass()).thenReturn(MdClassPackage.Literals.TABLE);
        invoke("attachAndLink", new Class<?>[] { com._1c.g5.v8.bm.core.IBmTransaction.class,
            EObject.class, EReference.class, com._1c.g5.v8.dt.metadata.mdclass.MdObject.class,
            String.class, com._1c.g5.v8.dt.core.model.IModelObjectFactory.class }, tx, f.eds,
            MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__TABLES, child, TABLE, factory);
        var order = inOrder(tx, child, factory);
        order.verify(tx).attachTopObject((IBmObject)child, TABLE);
        order.verify(child).eSet(MdClassPackage.Literals.TABLE__PARENT_DATA_SOURCE, f.eds);
        order.verify(factory).fillDefaultReferences(child);
        assertTrue(f.eds.getTables().contains(child));
    }

    @Test public void pathsBelongToTheReferencedTopObject() throws Exception
    {
        assertEquals("src/ExternalDataSources/Eds/Tables/T/T.mdo", path(TABLE));
        assertEquals("src/ExternalDataSources/Eds/Cubes/C/C.mdo", path(CUBE));
        assertNull(path("Catalog.X"));
        assertNull(path(FIELD));
        assertEquals("Tables", invoke("folderFor", new Class<?>[] { EClass.class }, MdClassPackage.Literals.TABLE));
    }

    @Test public void fileOwnerIsReadFromBmIdentity() throws Exception
    {
        EObject field = mock(EObject.class, withSettings().extraInterfaces(IBmObject.class));
        IBmObject table = mock(IBmObject.class);
        when(((IBmObject)field).bmGetTopObject()).thenReturn(table);
        when(table.bmGetFqn()).thenReturn(TABLE);
        assertEquals(TABLE, invoke("fileOwnerFqn", new Class<?>[] { EObject.class }, field));
        when(((IBmObject)field).bmIsTop()).thenReturn(true);
        when(((IBmObject)field).bmGetFqn()).thenReturn(TABLE);
        assertEquals(TABLE, invoke("fileOwnerFqn", new Class<?>[] { EObject.class }, field));
        when(((IBmObject)field).bmIsTop()).thenReturn(false);
        when(table.bmGetFqn()).thenReturn("Catalog.X");
        assertEquals("Catalog.X", invoke("fileOwnerFqn", new Class<?>[] { EObject.class }, field));
    }

    private static Object path(String fqn) throws Exception
    {
        return invoke("mdoPath", new Class<?>[] { String.class }, fqn);
    }
}
