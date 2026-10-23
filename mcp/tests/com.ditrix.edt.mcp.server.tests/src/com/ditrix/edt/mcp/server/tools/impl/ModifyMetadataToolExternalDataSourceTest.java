/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.lang.reflect.Method;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataSource;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Table;
import com.ditrix.edt.mcp.server.utils.*;

public class ModifyMetadataToolExternalDataSourceTest
{
    @Test public void tableFetchesItsOwnBmIdAndFieldKeepsTheWalkRoot() throws Exception
    {
        var config = MdClassFactory.eINSTANCE.createConfiguration();
        var eds = mock(ExternalDataSource.class, withSettings().extraInterfaces(IBmObject.class, InternalEObject.class));
        when(eds.getName()).thenReturn("Eds");
        when(eds.eClass()).thenReturn(MdClassPackage.Literals.EXTERNAL_DATA_SOURCE);
        when(((IBmObject)eds).bmGetId()).thenReturn(11L);
        var table = mock(Table.class, withSettings().extraInterfaces(IBmObject.class, InternalEObject.class));
        when(table.getName()).thenReturn("T");
        when(table.eClass()).thenReturn(MdClassPackage.Literals.TABLE);
        when(((IBmObject)table).bmGetId()).thenReturn(42L);
        var field = MdClassFactory.eINSTANCE.createField();
        field.setName("F");
        var fields = new BasicEList<com._1c.g5.v8.dt.metadata.mdclass.Field>();
        fields.add(field);
        when(table.eGet(MdClassPackage.Literals.TABLE__TABLE_FIELDS)).thenReturn(fields);
        var tables = new BasicEList<Table>();
        tables.add(table);
        when(eds.eGet(MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__TABLES)).thenReturn(tables);
        config.getExternalDataSources().add(eds);
        var scope = MetadataScope.ofConfiguration(config);
        Object tablePlan = plan(scope, TABLE);
        assertEquals(42L, get(tablePlan, "topBmId"));
        assertNull(get(tablePlan, "memberFeature"));
        Object fieldPlan = plan(scope, FIELD);
        assertEquals(11L, get(fieldPlan, "topBmId"));
        assertSame(MdClassPackage.Literals.TABLE__TABLE_FIELDS, get(fieldPlan, "memberFeature"));
        assertEquals("F", get(fieldPlan, "memberName"));
        assertNull(get(fieldPlan, "error"));
    }

    @Test public void fieldAndFunctionUseThePlainDesignerRetype() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        assertNotNull(MetadataNodeResolver.resolveExisting(f.config, FIELD));
        assertEquals(TypeChangeDefaults.Reset.NONE, TypeChangeDefaults.resetFor(f.field));
        assertEquals(TypeChangeDefaults.Reset.NONE, TypeChangeDefaults.resetFor(f.function));
    }

    @Test public void notFoundHintNamesTheEdsShapes()
    {
        String text = ModifyMetadataTool.nodeNotFoundMessage(new ExternalDataSourceFixtures().scope(), FIELD, FIELD);
        assertTrue(text, text.contains("ExternalDataSource.<X>.Table.<T>"));
        assertTrue(text, text.contains("ExternalDataSource.<X>.Function.<F>"));
    }

    private static Object plan(MetadataScope scope, String fqn) throws Exception
    {
        var node = MetadataNodeResolver.resolveExisting(scope, fqn);
        assertNotNull(node);
        Method method = ModifyMetadataTool.class.getDeclaredMethod("resolveBmFetchPlan", MetadataScope.class,
            MetadataNodeResolver.MetadataNode.class, MdObject.class, String[].class);
        method.setAccessible(true);
        return method.invoke(null, scope, node, node.object, fqn.split("\\."));
    }

    private static Object get(Object object, String name) throws Exception
    {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
