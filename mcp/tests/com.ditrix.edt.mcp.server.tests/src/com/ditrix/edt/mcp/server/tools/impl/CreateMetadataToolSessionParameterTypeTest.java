/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Collections;

import org.eclipse.emf.ecore.EClass;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.SessionParameter;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonParser;

/** The top-object fallback missing in #795, using the same factory seam as the write transaction. */
public class CreateMetadataToolSessionParameterTypeTest
{
    private final IModelObjectFactory factory = mock(IModelObjectFactory.class);
    private final IV8Project project = mock(IV8Project.class);
    private final IEObjectProvider typeProvider = mock(IEObjectProvider.class);
    private final TypeItem stringType = McoreFactory.eINSTANCE.createType();

    @Before
    public void setUp()
    {
        doReturn(stringType).when(typeProvider).createProxy("String"); //$NON-NLS-1$
    }

    @Test
    public void testASessionParameterTheFactoryLeftUntypedGetsTheString10Default()
    {
        SessionParameter parameter = MdClassFactory.eINSTANCE.createSessionParameter();
        doReturn(parameter).when(factory).create(MdClassPackage.Literals.SESSION_PARAMETER, project);

        MdObject created = CreateMetadataTool.newTopObject(factory, MdClassPackage.Literals.SESSION_PARAMETER,
            project, "Param", typeProvider); //$NON-NLS-1$

        assertSame(parameter, created);
        assertEquals("Param", created.getName()); //$NON-NLS-1$
        TypeDescription type = parameter.getType();
        assertNotNull("a SessionParameter must not be created without a type", type); //$NON-NLS-1$
        assertEquals("exactly one String type item", Collections.singletonList(stringType), type.getTypes()); //$NON-NLS-1$
        assertNotNull(type.getStringQualifiers());
        assertEquals(10, type.getStringQualifiers().getLength());
        assertFalse("the wizard default is a variable-length string", type.getStringQualifiers().isFixed()); //$NON-NLS-1$
        assertNull("String must not carry number qualifiers", type.getNumberQualifiers()); //$NON-NLS-1$
    }

    @Test
    public void testATopObjectWhoseInitializerSetATypeKeepsIt()
    {
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        TypeDescription initializerType = McoreFactory.eINSTANCE.createTypeDescription();
        constant.setType(initializerType);
        doReturn(constant).when(factory).create(MdClassPackage.Literals.CONSTANT, project);

        MdObject created = CreateMetadataTool.newTopObject(factory, MdClassPackage.Literals.CONSTANT,
            project, "C", typeProvider); //$NON-NLS-1$

        assertSame(constant, created);
        assertEquals("C", created.getName()); //$NON-NLS-1$
        assertSame("the factory's type must be kept", initializerType, constant.getType()); //$NON-NLS-1$
        verify(typeProvider, never()).createProxy(anyString());
    }

    @Test
    public void testATopObjectWithoutAMandatoryTypeIsNotGivenOne()
    {
        MdObject catalog = MdClassFactory.eINSTANCE.createCatalog();
        doReturn(catalog).when(factory).create(MdClassPackage.Literals.CATALOG, project);

        MdObject created = CreateMetadataTool.newTopObject(factory, MdClassPackage.Literals.CATALOG,
            project, "Goods", null); //$NON-NLS-1$

        assertSame(catalog, created);
        assertEquals("Goods", created.getName()); //$NON-NLS-1$
        assertFalse(CreateMetadataTool.carriesMandatoryType(created.eClass()));
        verify(factory).create(MdClassPackage.Literals.CATALOG, project);
        verify(factory, never()).create(any(EClass.class), any(Version.class));
    }

    @Test
    public void testANestedSubsystemKeepsTheProjectAwareFactoryPathWithoutATypeProvider()
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        doReturn(subsystem).when(factory).create(MdClassPackage.Literals.SUBSYSTEM, project);
        IEObjectProvider provider = CreateMetadataTool.typeProviderFor(MdClassPackage.Literals.SUBSYSTEM,
            Version.LATEST);
        assertNull(provider);

        MdObject created = CreateMetadataTool.newTopObject(factory, MdClassPackage.Literals.SUBSYSTEM,
            project, "Child", provider); //$NON-NLS-1$

        assertSame(subsystem, created);
        assertEquals("Child", subsystem.getName()); //$NON-NLS-1$
        assertNull("the caller attaches the detached subsystem to its parent", subsystem.getParentSubsystem()); //$NON-NLS-1$
        assertTrue(subsystem.getSubsystems().isEmpty());
        assertFalse(CreateMetadataTool.carriesMandatoryType(created.eClass()));
        verify(factory).create(MdClassPackage.Literals.SUBSYSTEM, project);
        verify(factory, never()).create(any(EClass.class), any(Version.class));
    }

    @Test
    public void testASessionParameterWithoutATypeProviderIsRefusedNotWrittenUntyped()
    {
        SessionParameter parameter = MdClassFactory.eINSTANCE.createSessionParameter();
        doReturn(parameter).when(factory).create(MdClassPackage.Literals.SESSION_PARAMETER, project);

        try
        {
            CreateMetadataTool.newTopObject(factory, MdClassPackage.Literals.SESSION_PARAMETER,
                project, "P", null); //$NON-NLS-1$
            fail("a missing platform type provider must roll back the create"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("name the object before building its mandatory type", //$NON-NLS-1$
                e.getMessage().contains("SessionParameter 'P'")); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("Platform type provider is not available")); //$NON-NLS-1$
        }
        assertNull(parameter.getType());
    }

    @Test
    public void testAFactoryThatDeclinesTheTopObjectStillReturnsNull()
    {
        assertNull(CreateMetadataTool.newTopObject(factory, MdClassPackage.Literals.SESSION_PARAMETER,
            project, "Missing", typeProvider)); //$NON-NLS-1$
        verify(typeProvider, never()).createProxy(anyString());
    }

    @Test
    public void testTheDefaultTypeSpecOfASessionParameterIsString10()
    {
        assertTrue(CreateMetadataTool.carriesMandatoryType(MdClassPackage.Literals.SESSION_PARAMETER));
        assertEquals(JsonParser.parseString("{types:[{kind:'String',length:10}]}"), //$NON-NLS-1$
            CreateMetadataTool.defaultTypeSpec(MdClassPackage.Literals.SESSION_PARAMETER));
        assertNotNull(CreateMetadataTool.typeProviderFor(MdClassPackage.Literals.SESSION_PARAMETER, Version.LATEST));
        assertNull(CreateMetadataTool.typeProviderFor(MdClassPackage.Literals.SESSION_PARAMETER, null));
        assertNull(CreateMetadataTool.typeProviderFor(MdClassPackage.Literals.CATALOG, Version.LATEST));
    }

    @Test
    public void testTheGuideStatesTheSessionParameterDefaultType()
    {
        assertTrue(new CreateMetadataTool().getGuide().contains(
            "A SessionParameter gets `String(10)` too, as EDT's New Session Parameter wizard gives it " //$NON-NLS-1$
                + "(the platform has no initializer for it); set the real type with modify_metadata.")); //$NON-NLS-1$
    }
}
