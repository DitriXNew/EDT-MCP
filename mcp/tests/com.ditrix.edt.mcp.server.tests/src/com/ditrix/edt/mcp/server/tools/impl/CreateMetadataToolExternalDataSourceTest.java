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
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;

/** Exercises the actual creation seams with detached metamodel objects (#448). */
public class CreateMetadataToolExternalDataSourceTest
{
    @Test public void genericCreateHintNamesEdsMembers() throws Exception
    {
        Method hint = CreateMetadataTool.class.getDeclaredMethod("createTargetNotFound", String.class, String.class);
        hint.setAccessible(true);
        String json = (String)hint.invoke(null, TABLE, "");
        assertTrue(json, json.contains("Table/Field/Function (ExternalDataSource)"));
    }

    @Test public void referencedCollectionsTakeTheTopAttachBranch() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        Method dispatch = CreateMetadataTool.class.getDeclaredMethod("isReferencedTopTarget",
            MetadataNodeResolver.CreateTarget.class);
        dispatch.setAccessible(true);
        for (String fqn : List.of(TABLE, CUBE, FUNCTION, FIELD))
        {
            var target = MetadataNodeResolver.resolveForCreate(f.scope(), fqn);
            assertNotNull(fqn, target);
            assertEquals(fqn, fqn.equals(TABLE) || fqn.equals(CUBE), dispatch.invoke(null, target));
        }
    }

    @Test public void unsupportedKindsNameTheActualEdsAndTableChildren() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        Method refusal = CreateMetadataTool.class.getDeclaredMethod("unsupportedChildKindRefusal",
            MetadataScope.class, String.class);
        refusal.setAccessible(true);
        String eds = (String)refusal.invoke(null, f.scope(), EDS + ".Column.X");
        assertTrue(eds, eds.contains("It accepts: Table, Cube, Function"));
        String table = (String)refusal.invoke(null, f.scope(), TABLE + ".Column.X");
        assertTrue(table, table.contains("It accepts: Command, Template, Field"));
    }

    @Test public void fieldFactoryDeclineStillProducesString10() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        f.table.getTableFields().clear();
        var target = MetadataNodeResolver.resolveForCreate(f.config, FIELD);
        assertNotNull(target);
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        IEObjectProvider provider = mock(IEObjectProvider.class);
        var stringType = McoreFactory.eINSTANCE.createType();
        doReturn(stringType).when(provider).createProxy("String");
        var child = CreateMetadataTool.createMemberChild(new CreateMetadataTool.MemberChildSpec(factory,
            target.elementType, f.table, Version.LATEST, "F", new CreateMetadataTool.Props(), null,
            target.feature, provider));
        assertSame(child, f.table.getTableFields().get(0));
        var field = (com._1c.g5.v8.dt.metadata.mdclass.Field)child;
        assertEquals(List.of(stringType), field.getType().getTypes());
        assertEquals(10, field.getType().getStringQualifiers().getLength());
        verify(factory).create(MdClassPackage.Literals.FIELD, f.table, Version.LATEST);
    }

    @Test public void fieldInitializerTypeAndFlagsAreKept() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        f.table.getTableFields().clear();
        var target = MetadataNodeResolver.resolveForCreate(f.config, FIELD);
        assertNotNull(target);
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        var initialized = MdClassFactory.eINSTANCE.createField();
        var type = McoreFactory.eINSTANCE.createTypeDescription();
        initialized.setType(type);
        initialized.setAllowNull(true);
        doReturn(initialized).when(factory).create(MdClassPackage.Literals.FIELD, f.table, Version.LATEST);
        assertSame(initialized, CreateMetadataTool.createMemberChild(new CreateMetadataTool.MemberChildSpec(factory,
            target.elementType, f.table, Version.LATEST, "F", new CreateMetadataTool.Props(), null,
            target.feature, null)));
        assertSame(type, initialized.getType());
        assertTrue(initialized.isAllowNull());
    }

    @Test public void functionUsesTheParentAwareFactoryAndMandatoryType() throws Exception
    {
        var f = new ExternalDataSourceFixtures();
        f.eds.getFunctions().clear();
        var target = MetadataNodeResolver.resolveForCreate(f.config, FUNCTION);
        assertNotNull(target);
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        IEObjectProvider provider = mock(IEObjectProvider.class);
        doReturn(McoreFactory.eINSTANCE.createType()).when(provider).createProxy("String");
        var child = CreateMetadataTool.createMemberChild(new CreateMetadataTool.MemberChildSpec(factory,
            target.elementType, f.eds, Version.LATEST, "Fn", new CreateMetadataTool.Props(), null,
            target.feature, provider));
        assertSame(child, f.eds.getFunctions().get(0));
        assertNotNull(((com._1c.g5.v8.dt.metadata.mdclass.Function)child).getType());
        verify(factory).create(MdClassPackage.Literals.FUNCTION, f.eds, Version.LATEST);
    }
}
